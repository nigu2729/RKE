package com.randomkillentity.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 設計v14: ASM 独占層 (Java バイトコード変換の占有)。
 *
 * <p>狙い: 競合 mod が「自己アタッチ Java agent + {@code Instrumentation.retransformClasses}
 * + 自前 ASM 変換」で当方フックを剥奪/改変するのを、そもそも無効化する。
 * 具体的には共有 ASM の {@code org.objectweb.asm.ClassWriter.toByteArray()} を計装し、
 * 「第三者 mod のフレームから呼ばれた変換」に対しては、その ClassWriter が元にした
 * {@code ClassReader} の入力バイト列 (= 変換前のクラス) をそのまま返す。</p>
 *
 * <p>結果、第三者の read→write 変換は完全なパススルー (無変換) となり、当方の処理
 * (自パッケージのフレーム) だけが実際にバイトコードを書ける。これは特定の mod 名に
 * 依存しない汎用機構である。</p>
 *
 * <p><b>判定:</b> 呼び出しスタックの最初の「ASM/JDK/自ガード以外」のフレーム
 * (起点フレーム) を見て、それが許可接頭辞 (バニラ/Forge/ModLauncher/Mixin/標準
 * ライブラリ/自 mod) に属するなら通常処理、属さなければパススルーする。</p>
 *
 * <p><b>安全側の設計:</b>
 * <ul>
 *   <li>{@code ClassWriter(ClassReader,int)} で作られた writer のみを対象とする。
 *       新規クラス生成 ({@code ClassWriter(int)}) は {@code classReader} が null のため
 *       一切影響しない。</li>
 *   <li>ClassReader の入力バイト列が取得できない場合は通常処理にフォールバックする。</li>
 *   <li>例外は全て握りつぶし、常に「通常処理」を選ぶ。</li>
 *   <li>{@code -Drk.asm.monopoly=false} で完全に無効化できる。</li>
 * </ul>
 */
public final class AsmMonopolyGuard {

    // ブートストラップクラスローダにも載せられるよう、依存は JDK のみに限定する
    // (j.u.l は JDK 内。agent 固有クラスを参照しない)。これにより任意の
    // クラスローダ (ASM をロードした祖先ローダ含む) から解決可能になる。
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger("RandomKill.ASM");

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("rk.asm.monopoly", "true"));

    /** 自 mod のガードクラス (フレーム判定でスキップする)。 */
    private static final String GUARD_CLASS = "com.randomkillentity.agent.AsmMonopolyGuard";

    /** 許可するフレーム起点の接頭辞 (スラッシュ形式)。 */
    private static final String[] ALLOWED_FRAME_PREFIXES = {
            "java/", "javax/", "jdk/", "sun/", "com/sun/",
            // 自 mod (当方の変換は通す)
            "com/randomkillentity/",
            // ASM 本体
            "org/objectweb/asm/",
            // ModLauncher / SecureJarHandler / bootstrap launcher
            "cpw/mods/",
            // Mixin
            "org/spongepowered/",
            // バニラ / Forge / Mojang
            "net/minecraft/", "net/minecraftforge/", "com/mojang/",
            // 標準ライブラリ (誤爆回避)
            "org/apache/", "com/google/", "it/unimi/", "org/joml/",
            "io/netty/", "org/slf4j/", "org/lwjgl/", "org/openjdk/",
            "com/electronwill/", "kotlin/", "kotlinx/", "org/jetbrains/"
    };

    private static final StackWalker WALKER = StackWalker.getInstance();

    /** ClassWriter の「元にした ClassReader」フィールド (型で発見・遅延解決)。 */
    private static volatile Field readerField;
    private static volatile boolean readerFieldResolved;

    /** ClassReader の入力バイト列フィールド (型 + マジックで発見・遅延解決)。 */
    private static volatile Field bufferField;
    private static volatile boolean bufferFieldResolved;

    /** 診断用カウンタ。 */
    private static volatile long suppressedCount = 0;

    private AsmMonopolyGuard() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * {@code ClassWriter.toByteArray()} から呼ばれる。
     * 第三者の変換であれば、変換前のクラスバイト列を返してパススルーさせる。
     * 通常処理させる場合は {@code null} を返す。
     */
    public static byte[] bypassToByteArray(Object writer) {
        if (!ENABLED || writer == null) return null;
        try {
            if (!isForeignOrigin()) return null;
            Object reader = readReader(writer);
            if (reader == null) return null;
            byte[] original = extractClassBytes(reader);
            if (original == null) return null;
            suppressedCount++;
            if ((suppressedCount & 0x3F) == 1) {
                LOGGER.info("ASM monopoly: suppressed foreign transform #" + suppressedCount
                        + " (writer=" + writer.getClass().getName() + ")");
            }
            return original;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 呼び出しスタックの起点フレームが「許可されていない (= 第三者 mod)」か。 */
    private static boolean isForeignOrigin() {
        return WALKER.walk(frames -> frames
                // 先頭は本メソッド自身。ASM/JDK/自ガードを読み飛ばし、最初の起点を取る。
                .map(StackWalker.StackFrame::getClassName)
                .filter(cn -> !isSkippedFrame(cn))
                .findFirst()
                .map(cn -> !isAllowedFrame(cn))
                .orElse(false));
    }

    private static boolean isSkippedFrame(String dotClassName) {
        if (dotClassName == null) return true;
        if (GUARD_CLASS.equals(dotClassName)) return true;
        return dotClassName.startsWith("java.")
                || dotClassName.startsWith("javax.")
                || dotClassName.startsWith("jdk.")
                || dotClassName.startsWith("sun.")
                || dotClassName.startsWith("com.sun.")
                || dotClassName.startsWith("org.objectweb.asm.");
    }

    private static boolean isAllowedFrame(String dotClassName) {
        String n = dotClassName.replace('.', '/');
        for (String p : ALLOWED_FRAME_PREFIXES) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    private static Object readReader(Object writer) {
        try {
            if (!readerFieldResolved) {
                Class<?> c = writer.getClass();
                Field found = null;
                while (c != null && c != Object.class && found == null) {
                    for (Field candidate : c.getDeclaredFields()) {
                        if (candidate.getType().getName().equals("org.objectweb.asm.ClassReader")) {
                            found = candidate;
                            break;
                        }
                    }
                    c = c.getSuperclass();
                }
                if (found != null) {
                    found.setAccessible(true);
                    readerField = found;
                }
                readerFieldResolved = true;
            }
            Field f = readerField;
            if (f == null) return null;
            return f.get(writer);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] extractClassBytes(Object reader) {
        try {
            if (!bufferFieldResolved) {
                Class<?> c = reader.getClass();
                Field found = null;
                while (c != null && c != Object.class && found == null) {
                    for (Field candidate : c.getDeclaredFields()) {
                        if (candidate.getType() == byte[].class && !Modifier.isStatic(candidate.getModifiers())) {
                            try {
                                candidate.setAccessible(true);
                                Object v = candidate.get(reader);
                                if (v instanceof byte[] b && looksLikeClassFile(b)) {
                                    found = candidate;
                                    break;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    c = c.getSuperclass();
                }
                if (found != null) {
                    found.setAccessible(true);
                    bufferField = found;
                }
                bufferFieldResolved = true;
            }
            Field f = bufferField;
            if (f == null) return null;
            Object v = f.get(reader);
            return (v instanceof byte[] b && looksLikeClassFile(b)) ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean looksLikeClassFile(byte[] b) {
        return b != null && b.length >= 8
                && (b[0] & 0xFF) == 0xCA && (b[1] & 0xFF) == 0xFE
                && (b[2] & 0xFF) == 0xBA && (b[3] & 0xFF) == 0xBE;
    }

    public static long getSuppressedCount() {
        return suppressedCount;
    }
}

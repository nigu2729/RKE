package com.randomkillentity.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 設計v14: ASM 独占層の計装器。
 *
 * <p>共有 ASM の {@code org.objectweb.asm.ClassWriter} を探し、その
 * {@code toByteArray()[B} の先頭に {@link AsmMonopolyGuard#bypassToByteArray(Object)}
 * 呼び出しを注入する。第三者の変換では元バイト列を返してパススルーさせる。</p>
 *
 * <p><b>安全性 (重要):</b></p>
 * <ul>
 *   <li>計装は {@code retransformClasses} 経由のみで行う ({@link DefinedOnlyTransformer} は
 *       {@code classBeingRedefined != null} 以外では何もしない)。これにより ASM クラスの
 *       「初回定義」中に自前で ASM を使う再帰 (ClassCircularity/初期化競合) を避ける。</li>
 *   <li>計装する ASM クラスの classloader から {@code AsmMonopolyGuard} が解決できない場合は
 *       計装しない (未解決 {@code INVOKESTATIC} で全 ASM 変換を壊すのを防ぐ)。</li>
 *   <li>注入後の再変換でも冪等 (既に注入済みなら何もしない)。</li>
 *   <li>{@code -Drk.asm.monopoly=false} で無効化。</li>
 * </ul>
 *
 * <p>ASM が agent アタッチ時点で未ロードだった場合に備え、遅延リトライで
 * 後から現れた ClassWriter を拾う。</p>
 */
public final class AsmMonopolyInstaller {

    private static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill ASM");

    static final String WRITER = "org/objectweb/asm/ClassWriter";
    static final String WRITER_DOT = "org.objectweb.asm.ClassWriter";
    static final String GUARD = "com/randomkillentity/agent/AsmMonopolyGuard";
    static final String GUARD_DOT = "com.randomkillentity.agent.AsmMonopolyGuard";
    static final String BYPASS = "bypassToByteArray";
    static final String BYPASS_DESC = "(Ljava/lang/Object;)[B";

    /** 遅延リトライ回数と間隔 (ASM が後からロードされる環境向け)。 */
    private static final int RETRY_ATTEMPTS = 30;
    private static final long RETRY_INTERVAL_MS = 2000L;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    private static final AtomicBoolean ANY_ARMED = new AtomicBoolean(false);
    private static final java.util.Set<Class<?>> ARMED =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** bootstrap への guard 注入を一度だけ試みるためのフラグ。 */
    private static final AtomicBoolean BOOTSTRAP_ATTEMPTED = new AtomicBoolean(false);
    private static final AtomicBoolean DIAG_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean ABORT = new AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicInteger ARM_FAILURES =
            new java.util.concurrent.atomic.AtomicInteger();

    private AsmMonopolyInstaller() {
    }

    public static void install(Instrumentation inst) {
        if (!AsmMonopolyGuard.isEnabled()) {
            LOGGER.info("ASM monopoly disabled (rk.asm.monopoly=false)");
            return;
        }
        // 限定トランスフォーマは一度だけ登録 (retransform 時にのみ作用)
        if (REGISTERED.compareAndSet(false, true)) {
            try {
                inst.addTransformer(new DefinedOnlyTransformer(), true);
            } catch (Throwable t) {
                LOGGER.error("ASM monopoly: addTransformer failed", t);
                return;
            }
        }

        // 即時試行
        int armed = armLoadedClassWriters(inst);
        if (armed > 0) {
            ANY_ARMED.set(true);
        }

        // 遅延リトライ (ASM が未ロードだった場合の保険)
        Thread retry = new Thread(() -> {
            for (int i = 0; i < RETRY_ATTEMPTS; i++) {
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    if (armLoadedClassWriters(inst) > 0) {
                        ANY_ARMED.set(true);
                    } else if (ANY_ARMED.get() || ABORT.get()) {
                        // 既に武装済みで新規対象が無い / 決定的失敗で打ち切り
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "rk-asm-monopoly-retry");
        retry.setDaemon(true);
        retry.start();
    }

    /** まだ計装していない、ロード済みで計装可能な ClassWriter を計装する。新規計装数を返す。 */
    private static int armLoadedClassWriters(Instrumentation inst) {
        List<Class<?>> targets = new ArrayList<>();
        StringBuilder diag = new StringBuilder();
        int seen = 0;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (!WRITER_DOT.equals(c.getName())) continue;
            seen++;
            ClassLoader cl = c.getClassLoader();
            if (cl == null) { diag.append("[bootstrap]"); continue; }
            if (ARMED.contains(c)) continue;
            if (!inst.isModifiableClass(c)) {
                diag.append("[").append(cl.getClass().getName()).append(":notMod]");
                continue;
            }
            if (!guardResolvable(cl)) {
                // ASM をロードした祖先ローダから guard が見えない場合、
                // guard 単体を bootstrap に載せて全ローダから可視化する (一度だけ)。
                ensureBootstrapGuard(inst);
                if (!guardResolvable(cl)) {
                    diag.append("[").append(cl.getClass().getName()).append(":guardUnresolvable]");
                    continue;
                }
            }
            targets.add(c);
        }
        if (DIAG_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("ASM monopoly diag: seen=" + seen + " targets=" + targets.size()
                    + " detail=" + diag);
        }
        if (targets.isEmpty()) return 0;
        try {
            inst.retransformClasses(targets.toArray(new Class<?>[0]));
            ARMED.addAll(targets);
            LOGGER.info("ASM monopoly armed for " + targets.size() + " ClassWriter class(es) (loader="
                    + targets.get(0).getClassLoader().getClass().getName() + ")");
            return targets.size();
        } catch (Throwable t) {
            LOGGER.error("ASM monopoly retransform failed", t);
            if (ARM_FAILURES.incrementAndGet() >= 5) {
                ABORT.set(true);
            }
            return 0;
        }
    }

    /** 指定ローダから guard クラスを解決できるか。 */
    private static boolean guardResolvable(ClassLoader cl) {
        try {
            Class.forName(GUARD_DOT, false, cl);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * guard クラス単体を bootstrap クラスローダ探索に追加する (一度だけ)。
     * これにより ASM をロードした祖先ローダからも guard が解決可能になり、
     * 注入コードが NoClassDefFoundError にならない。
     */
    private static void ensureBootstrapGuard(Instrumentation inst) {
        if (!BOOTSTRAP_ATTEMPTED.compareAndSet(false, true)) return;
        try {
            java.io.InputStream in = AsmMonopolyGuard.class.getResourceAsStream(
                    "/com/randomkillentity/agent/AsmMonopolyGuard.class");
            if (in == null) {
                LOGGER.error("ASM monopoly: guard class resource not found");
                return;
            }
            java.io.File tmp = java.io.File.createTempFile("rk-asm-guard", ".jar");
            tmp.deleteOnExit();
            try (java.util.jar.JarOutputStream jos =
                         new java.util.jar.JarOutputStream(new java.io.FileOutputStream(tmp))) {
                jos.putNextEntry(new java.util.jar.JarEntry(
                        "com/randomkillentity/agent/AsmMonopolyGuard.class"));
                in.transferTo(jos);
                jos.closeEntry();
            } finally {
                try { in.close(); } catch (Throwable ignored) {
                }
            }
            inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(tmp));
            LOGGER.info("ASM monopoly: guard exposed via bootstrap classloader");
        } catch (Throwable t) {
            LOGGER.error("ASM monopoly: bootstrap guard injection failed", t);
        }
    }

    /**
     * ASM の ClassWriter.toByteArray だけを対象にする限定トランスフォーマ。
     * <b>明示的な retransform ({@code classBeingRedefined != null}) のときのみ作用する。</b>
     * 初回定義中に作用させると ASM 自身を使う再帰が起きるため。
     */
    static final class DefinedOnlyTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined == null) return null; // 初回定義では不介入 (再帰回避)
            if (className == null || classfileBuffer == null) return null;
            if (!WRITER.equals(className)) return null;
            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassNode cn = new ClassNode(Opcodes.ASM9);
                cr.accept(cn, ClassReader.EXPAND_FRAMES);
                if (!patchToByteArray(cn)) return null;
                // F_SAME 案を試すため、一時的に COMPUTE_MAXS を優先し、
                // 可能なら COMPUTE_FRAMES を試さない方が VerifyError 回避に繋がる場合もある。
                // 現状の VerifyError は COMPUTE_FRAMES が原因の可能性あり。
                ClassWriter cw = new RKTransformer.SafeClassWriter(
                        cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, null);
                cn.accept(cw);
                return cw.toByteArray();
            } catch (Throwable t) {
                LOGGER.error("ASM guard transform failed for " + className, t);
                return null;
            }
        }
    }

    /** ClassWriter.toByteArray()[B の先頭に bypass 呼び出しを注入する。 */
    private static boolean patchToByteArray(ClassNode cn) {
        if (cn.methods == null) return false;
        MethodNode target = null;
        for (MethodNode m : cn.methods) {
            if ("toByteArray".equals(m.name) && "()[B".equals(m.desc)) {
                target = m;
                break;
            }
        }
        if (target == null || target.instructions == null) return false;

        // 冪等: 既に bypass 呼び出しがあれば何もしない
        for (AbstractInsnNode insn : target.instructions.toArray()) {
            if (insn instanceof MethodInsnNode mi
                    && GUARD.equals(mi.owner)
                    && BYPASS.equals(mi.name)) {
                return false;
            }
        }

        AbstractInsnNode first = target.instructions.getFirst();
        if (first == null) return false;
        // 先頭が FrameNode の場合は一旦除去 (COMPUTE_FRAMES で再計算されるため)
        if (first instanceof org.objectweb.asm.tree.FrameNode) {
            target.instructions.remove(first);
            first = target.instructions.getFirst();
            if (first == null) return false;
        }

        LabelNode cont = new LabelNode();
        InsnList inj = new InsnList();
        inj.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inj.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, BYPASS, BYPASS_DESC, false));
        inj.add(new JumpInsnNode(Opcodes.IFNULL, cont));
        inj.add(new InsnNode(Opcodes.IRETURN));
        // F_SAME: 合流点のフレームは locals=[this], stack=[] (COMPUTE_MAXS 時の安全策)
        inj.add(new org.objectweb.asm.tree.FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        inj.add(cont);

        target.instructions.insertBefore(first, inj);
        return true;
    }
}

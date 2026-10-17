package com.randomkillentity.agent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * サーバー権威の強制破壊パケット配信 (設計v10: パケット層の汎用改変)。
 *
 * <p>構造除去 (WorldStructureDestroyer) が vanilla の tracking (ChunkMap/ServerEntity)
 * を経由せず EntityLookup を直接操作するため、サーバー側でエンティティが消えても
 * クライアントに {@code ClientboundRemoveEntitiesPacket} が届かず「ゴースト」が
 * 残る経路がある。防御mod が vanilla の tracking 更新を mixin で妨害する場合も同様。
 * 本層は除去確定時に全プレイヤーへ破壊パケットを直接配信し、クライアント側の
 * ゴーストをサーバー権威で強制消去する。</p>
 *
 * <p>Minecraft 本体 (Connection#send) への改変ではなく正規の送信 API の直接利用だが、
 * vanilla が「tracking 経由でしか送らない」パケットを tracking 状態と無関係に送る点が
 * 本体の送信経路に対する汎用介入になる。専用対策ではない: クラス名・mod 名は参照せず、
 * vanilla のパケットクラスと送信 API (SRG/dev 名フォールバック) のみに依存する。</p>
 *
 * <p>SRG 名は build/createMcpToSrg/output.tsrg で実証済み:
 * ServerPlayer.connection = f_8906_ / Level.players = m_6907_ /
 * Connection.send(Packet) = m_129512_ / ClientboundRemoveEntitiesPacket は
 * クラス名 dev/本番同一 (コンストラクタは SRG 対象外)。</p>
 */
public final class PacketDestroyBroadcaster {

    /** ServerPlayer.connection フィールド (tsrg 98057 実証) */
    private static final String[] CONNECTION_FIELDS = {"f_8906_", "connection"};
    /** Level.players() (tsrg 37091 実証) */
    private static final String[] PLAYERS_METHODS = {"m_6907_", "players"};
    /** Connection.send(Packet) (tsrg 78864 実証) */
    private static final String[] SEND_METHODS = {"m_129512_", "send"};
    /** Entity.id フィールド */
    private static final String[] ENTITY_ID_FIELDS = {"f_19848_", "id"};
    /** Entity.level フィールド */
    private static final String[] ENTITY_LEVEL_FIELDS = {"f_19853_", "level"};
    /** Level.getLevel / m_9236_ フォールバック */
    private static final String[] GET_LEVEL_METHODS = {"m_9236_", "getLevel"};

    private static final String REMOVE_PACKET_CLASS =
            "net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket";

    private static volatile Constructor<?> removePacketCtor;
    private static volatile boolean ctorResolved;

    private PacketDestroyBroadcaster() {
    }

    /**
     * エンティティの破壊パケットを、そのレベルの全プレイヤーへ強制配信する。
     * 未 tracking のプレイヤー / 既に消えたエンティティへの送信はクライアント側で
     * no-op になるため副作用はない。プレイヤー自身は対象外 (死亡画面を壊さない)。
     * 全例外を握りつぶし、キルチェーンを止めない。
     */
    public static void broadcastDestroy(Object entity) {
        if (entity == null) return;
        try {
            // プレイヤー自身の除去は vanilla の死亡処理に任せる (死亡画面維持)
            if (isPlayerLike(entity)) return;

            Integer id = readEntityId(entity);
            if (id == null) return;
            Object level = getLevel(entity);
            if (level == null) return;
            Object packet = newRemovePacket(id);
            if (packet == null) return;

            Object players = invokeNoArg(level, PLAYERS_METHODS);
            if (!(players instanceof List)) return;

            int sent = 0;
            for (Object player : (List<?>) players) {
                // players() が ServerPlayer 以外を返す実装 (防御mod 差し替え等) への防御
                if (player == null || !isPlayerLike(player)) continue;
                try {
                    Object connection = readField(player, CONNECTION_FIELDS);
                    if (connection == null) continue;
                    Method send = findMethod(connection.getClass(), SEND_METHODS, 1);
                    if (send == null) continue;
                    send.setAccessible(true);
                    send.invoke(connection, packet);
                    sent++;
                } catch (Throwable ignored) {
                }
            }
            if (sent > 0) {
                RKAgentLoggerHolder.LOGGER.debug(
                        "Broadcast destroy packet for entity {} to {} player(s)", id, sent);
            }
        } catch (Throwable ignored) {
        }
    }

    /** ClientboundRemoveEntitiesPacket(int...) を生成 (コンストラクタはキャッシュ)。 */
    private static Object newRemovePacket(int entityId) {
        try {
            Constructor<?> ctor = removePacketCtor;
            if (!ctorResolved) {
                Class<?> clazz = Class.forName(REMOVE_PACKET_CLASS, false, findContextClassLoader());
                for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                    Class<?>[] pt = c.getParameterTypes();
                    if (pt.length == 1 && pt[0] == int[].class) {
                        ctor = c;
                        break;
                    }
                    if (pt.length == 1 && pt[0] == int.class) {
                        ctor = c;
                        break;
                    }
                }
                if (ctor == null) return null;
                ctor.setAccessible(true);
                removePacketCtor = ctor;
                ctorResolved = true;
            }
            Class<?> first = removePacketCtor.getParameterTypes()[0];
            return first == int[].class
                    ? removePacketCtor.newInstance((Object) new int[]{entityId})
                    : removePacketCtor.newInstance(entityId);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer readEntityId(Object entity) {
        Object v = readField(entity, ENTITY_ID_FIELDS);
        if (v instanceof Integer) return (Integer) v;
        try {
            Method m = findMethod(entity.getClass(), new String[]{"m_19879_", "getId"}, 0);
            if (m != null) {
                Object r = m.invoke(entity);
                if (r instanceof Integer) return (Integer) r;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object getLevel(Object entity) {
        Object level = readField(entity, ENTITY_LEVEL_FIELDS);
        if (level != null) return level;
        return invokeNoArg(entity, GET_LEVEL_METHODS);
    }

    private static boolean isPlayerLike(Object o) {
        try {
            String name = o.getClass().getName();
            if (name.endsWith("Player") || name.contains("ServerPlayer") || name.contains("ClientPlayer")) {
                return true;
            }
            o.getClass().getMethod("getGameProfile");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── リフレクションヘルパー (WorldStructureDestroyer と同一方針) ──

    private static Object readField(Object target, String[] names) {
        Field f = findField(target.getClass(), names);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String[] names) {
        Method m = findMethod(target.getClass(), names, 0);
        if (m == null) return null;
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> clazz, String[] names) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (!Modifier.isStatic(f.getModifiers())) return f;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String[] names, int paramCount) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String name : names) {
                try {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                            return m;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static ClassLoader findContextClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : PacketDestroyBroadcaster.class.getClassLoader();
    }

    /** ロガー遅延解決 (RKAgentLogger は agent CL 由来。このクラスも agent CL 配下で動く) */
    private static final class RKAgentLoggerHolder {
        static final RKAgentLogger LOGGER = new RKAgentLogger("RandomKill Packet");
    }
}

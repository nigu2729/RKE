package example.bosskiller;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;
import java.util.HashSet;
import net.minecraftforge.registries.ForgeRegistries;
import com.randomkillentity.RandomKillEntity;

/**
 * 保険用: {@code BossKillerPlugin} が {@code discard()} を仕込んだ Entity が
 * チャンクロード、NBT 復元、または他 Mod のスポーン経路で再加入しようとした際に
 * 参加自体をキャンセルする。
 *
 * {@code BossKillerPlugin} 側は System properties に {@code bosskiller.killed}
 * として破棄対象クラス名（内部名、例: {@code com/example/bosskiller/MyBossEntity}）
 * を格納している。ここでそれらを取得し、対象クラスまたはそのスーパークラスが
 * 含まれる場合は {@code setCanceled(true)} と {@code discard()} を実行する。
 * このクラスは Mod エントリポイントとして登録され、Forge の EventBus に
 * 自動的に登録されるので、プラグイン側で追加のロード処理は不要。
 *
 * 注意 (2026-10-08 クラッシュ対応):
 * - クラス初期化子から例外が漏れると Mod ロード全体が失敗するため、
 *   {@link #KILLED_SET} への代入は全経路 catch する。
 * - レジストリ走査は construct 時点では Mod 登録の Entity Type が未登録のため
 *   初回 {@code EntityJoinLevelEvent} 時点 (全登録完了後) に遅延する。
 */
@Mod(RandomKillEntity.MODID)
public class BossKillerMod {

    public BossKillerMod() {
        // Register this class on the Forge event bus so that the @SubscribeEvent method is called.
        MinecraftForge.EVENT_BUS.register(this);
    }

    // ---- Dynamic coverage support -------------------------------------------------
    /** Set of internal class names to be killed. Populated from system properties or auto-cover. */
    private static final Set<String> KILLED_SET = new HashSet<>();

    /** Evaluated once at class init; registry population is deferred to the first join event. */
    private static final boolean COVER_ALL = "true".equalsIgnoreCase(System.getProperty("bosskiller.coverAll"));

    private static volatile boolean coverAllPopulated = false;

    static {
        // Legacy handling: expect a Set under the property or a comma-separated string.
        // Never let static init throw: NoSuchMethodError-like linkage failures here abort mod loading.
        try {
            if (!COVER_ALL) {
                Object prop = System.getProperties().get("bosskiller.killed");
                if (prop instanceof Set<?> set) {
                    //noinspection unchecked
                    KILLED_SET.addAll((Set<String>) set);
                    RandomKillEntity.LOGGER.info("[BossKillerMod] loaded {} entries from bosskiller.killed property", set.size());
                } else if (prop instanceof String str) {
                    for (String part : str.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) {
                            KILLED_SET.add(trimmed);
                        }
                    }
                    RandomKillEntity.LOGGER.info("[BossKillerMod] parsed {} entries from bosskiller.killed string", KILLED_SET.size());
                }
            }
        } catch (Throwable t) {
            RandomKillEntity.LOGGER.error("[BossKillerMod] failed to read bosskiller.killed property", t);
        }
    }

    /** Populates KILLED_SET with every registered entity type except players. */
    private static void populateCoverAll() {
        ForgeRegistries.ENTITY_TYPES.getValues().forEach(type -> {
            Class<? extends net.minecraft.world.entity.Entity> entityClass = type.getBaseClass();
            if (entityClass != null && !net.minecraft.world.entity.player.Player.class.isAssignableFrom(entityClass)) {
                KILLED_SET.add(entityClass.getName().replace('.', '/'));
            }
        });
        RandomKillEntity.LOGGER.info("[BossKillerMod] coverAll enabled – {} entity classes added", KILLED_SET.size());
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        try {
            if (COVER_ALL && !coverAllPopulated) {
                coverAllPopulated = true; // set first: a single failure must not retry on every join
                try {
                    populateCoverAll();
                } catch (Throwable t) {
                    RandomKillEntity.LOGGER.error("[BossKillerMod] coverAll population failed", t);
                }
            }
            if (KILLED_SET.isEmpty()) {
                return;
            }
            for (Class<?> c = event.getEntity().getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                if (KILLED_SET.contains(c.getName().replace('.', '/'))) {
                    event.setCanceled(true);
                    event.getEntity().discard();
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Insurance listener: must never throw into the Forge event bus.
        }
    }
}

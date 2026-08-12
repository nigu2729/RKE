package com.randomkillentity.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import com.randomkillentity.RandomKillEntity;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = RandomKillEntity.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class RandomKillConfig {

    public static final ForgeConfigSpec SERVER_SPEC;

    public static ForgeConfigSpec.ConfigValue<List<? extends String>> ALLOWED_AGENTS;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_ENTITIES;
    public static ForgeConfigSpec.DoubleValue KILL_PROBABILITY;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Random Kill Entity Server Configurations").push("server");

        ALLOWED_AGENTS = builder
                .comment("許可するJava Agent名リスト")
                .defineListAllowEmpty(List.of("allowed_agents"), ArrayList::new, o -> o instanceof String);

        EXCLUDED_ENTITIES = builder
                .comment("即死から除外するエンティティID (例: 'minecraft:cow')")
                .defineList(List.of("excluded_entities"), List.of(
                ), o -> o instanceof String);

        KILL_PROBABILITY = builder
                .comment("各Tickでエンティティが即死する確率 (0.0 〜 1.0)")
                .defineInRange("kill_probability", 0.0001, 0.0, 1.0);

        builder.pop();
        SERVER_SPEC = builder.build();
    }

    public static List<String> allowedAgents = new ArrayList<>();
    public static List<String> excludedEntities = new ArrayList<>();
    public static double killProbability = 0.0001;

    // ワールド読み込み時に発火
    @SubscribeEvent
    public static void onLoad(final ModConfigEvent.Loading configEvent) {
        bakeConfig();
    }

    // /fml config reload コマンド等でのリロード時に発火
    @SubscribeEvent
    public static void onReload(final ModConfigEvent.Reloading configEvent) {
        bakeConfig();
    }

    private static void bakeConfig() {
        allowedAgents = new ArrayList<>(ALLOWED_AGENTS.get());
        excludedEntities = new ArrayList<>(EXCLUDED_ENTITIES.get());
        killProbability = KILL_PROBABILITY.get();

        // プレイヤーの誤登録を防ぐため除外
        excludedEntities.remove("minecraft:player");
        excludedEntities.remove("player");

        // ⭕ Agent（別クラスローダー側）が参照できるようにシステムプロパティへリストを同期
        System.setProperty("rk.excluded_entities", String.join(",", excludedEntities));

        RandomKillEntity.LOGGER.info(
                "[RandomKillEntity] Server Config loaded/reloaded: {} allowed agents, {} excluded entities, kill probability: {}",
                allowedAgents.size(),
                excludedEntities.size(),
                killProbability
        );
    }

    public static boolean isAgentAllowed(String agentName) {
        return allowedAgents.contains(agentName);
    }

    public static boolean isEntityExcluded(String entityId) {
        if ("minecraft:player".equals(entityId) || "player".equals(entityId)) {
            return false;
        }
        return excludedEntities.contains(entityId);
    }

    public static double getKillProbability() {
        return killProbability;
    }
}
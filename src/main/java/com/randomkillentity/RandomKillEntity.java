package com.randomkillentity;

import com.mojang.logging.LogUtils;
import com.randomkillentity.config.RandomKillConfig;
import com.randomkillentity.event.GlobalKillFlag;
import com.randomkillentity.utils.RKAgentLoader;
import com.randomkillentity.utils.RKLog;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

@Mod(RandomKillEntity.MODID)
public class RandomKillEntity {

    public static final String MODID = "randomkillentity";
    // マスターゲート: OFF 時は NOPLogger に差し替え (本番ステルス)。RandomKillConfig もこのフィールドを使用する。
    public static final Logger LOGGER = RKLog.isLogEnabled() ? LogUtils.getLogger() : NOPLogger.NOP_LOGGER;

    public RandomKillEntity() {
        // ⭕ ForgeにSERVER型Configとして登録。これによりワールド毎の serverconfig/ 内に自動生成されます
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, RandomKillConfig.SERVER_SPEC, "randomkillentity-server.toml");

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // フォールバック: ITransformationService で失敗していた場合に再試行
            if (!RKAgentLoader.isLoaded()) {
                LOGGER.info("[RandomKillEntity] Retrying agent load in FMLCommonSetupEvent...");
                boolean success = RKAgentLoader.load();
                if (success) {
                    LOGGER.info("[RandomKillEntity] Agent loaded successfully");
                } else {
                    LOGGER.warn("[RandomKillEntity] Failed to load agent, kill logic may not work");
                }
            }
        });

        MinecraftForge.EVENT_BUS.register(new GlobalKillFlag());
        LOGGER.info("[RandomKillEntity] Setup complete.");
    }
}
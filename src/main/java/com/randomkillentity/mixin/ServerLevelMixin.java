package com.randomkillentity.mixin;

import com.randomkillentity.agent.RevivalBlocker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 設計v11: Mixin 版の復活ブロック (ASM の模倣)。
 *
 * <p>ASM トランスフォーマ ({@code ServerLevelTransformer} /
 * {@code LevelAddFreshEntityTransformer}) と同等の処理を Mixin で適用する。
 * 殺害直後の短時間ウィンドウに限り、同クラスの再登録
 * ({@code addFreshEntity}) をブロックし、UUID 変化型の復活を防ぐ。</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void rke$blockRevival(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (RevivalBlocker.shouldBlockRevival(entity, this)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
        }
    }
}

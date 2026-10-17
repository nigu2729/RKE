package com.randomkillentity.mixin;

import com.randomkillentity.agent.StateDestroyer;
import com.randomkillentity.agent.hook.LivingEntityMethodImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 設計v11: Mixin 版のエンティティ即死注入 (ASM の模倣)。
 *
 * <p>ASM トランスフォーマ ({@code EntityInlineKillTransformer} /
 * {@code LivingEntityTransformer}) と同等の処理を Mixin 層でも適用する。
 * ASM 経路 (Instrumentation) が無効化された環境でも、Mixin は Forge の
 * クラス変換段で必ず適用されるため冗長な突破経路となる。</p>
 *
 * <ul>
 *   <li>{@code tick} HEAD: フラグ {@code rk.should_kill} が "true" の間、
 *       全 LivingEntity を {@code setRemoved(KILLED) + kill()} で除去する。
 *       Mixin の {@code @Inject} ハンドラは対象メソッドへインライン展開されるため、
 *       呼び出し元フレームは {@code LivingEntity.tick} (バニラ) のままとなり、
 *       スタック検査を素通りする (ASM 版と同一の性質)。</li>
 *   <li>{@code setHealth} RETURN: 体力を実体フィールドへ 0 として書き戻す
 *       (不死身化・毎 tick 回復の無効化)。</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    private static final String FLAG = "rk.should_kill";

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void rke$inlineKill(CallbackInfo ci) {
        if (!"true".equals(System.getProperty(FLAG))) return;
        LivingEntity self = (LivingEntity) (Object) this;
        try {
            StateDestroyer.flipSelfDestructFlags(self);
        } catch (Throwable ignored) {
        }
        self.setRemoved(Entity.RemovalReason.KILLED);
        self.kill();
    }

    @Inject(method = "setHealth", at = @At("RETURN"), require = 0)
    private void rke$forceHealthZero(float health, CallbackInfo ci) {
        if (!"true".equals(System.getProperty(FLAG))) return;
        try {
            LivingEntityMethodImpl.INSTANCE.setHealth((Object) this, "RETURN");
        } catch (Throwable ignored) {
        }
    }
}

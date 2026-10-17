package com.randomkillentity.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.randomkillentity.agent.KillExecutor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * /rke コマンド。
 * <p>コマンドを入力した本人（実行者）のみを汎用的に即死させる。</p>
 */
public final class RkeCommand {
    private RkeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("rke")
                        .executes(RkeCommand::execute)
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Entity entity = source.getEntity();

        // プレイヤー実行時のみ有効（コンソール等からは何もしない）
        if (entity instanceof Player) {
            try {
                KillExecutor.killNow(entity);
            } catch (Throwable ignored) {
            }
            return 1;
        }

        // 実行者がエンティティでない場合は何もしない（汎用性維持・誤用防止）
        return 0;
    }
}
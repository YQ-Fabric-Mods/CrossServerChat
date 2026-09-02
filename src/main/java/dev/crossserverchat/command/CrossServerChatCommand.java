package dev.crossserverchat.command;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class CrossServerChatCommand {
	private CrossServerChatCommand() {
	}

	public static void register(ReloadHandler reloadHandler) {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("crossserverchat")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("reload")
								.executes(context -> reload(context.getSource(), reloadHandler))))
		);
	}

	private static int reload(CommandSourceStack source, ReloadHandler reloadHandler) {
		if (!reloadHandler.reload(source)) {
			source.sendFailure(Component.literal("A CrossServerChat reload is already running."));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("CrossServerChat reload started."), false);
		return Command.SINGLE_SUCCESS;
	}

	@FunctionalInterface
	public interface ReloadHandler {
		boolean reload(CommandSourceStack source);
	}
}

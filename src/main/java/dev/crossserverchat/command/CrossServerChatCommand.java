package dev.crossserverchat.command;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;

public final class CrossServerChatCommand {
	private CrossServerChatCommand() {
	}

	public static void register(ReloadHandler reloadHandler, Logger logger) {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("crossserverchat")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("reload")
								.executes(context -> reload(context.getSource(), reloadHandler, logger))))
		);
	}

	private static int reload(CommandSourceStack source, ReloadHandler reloadHandler, Logger logger) {
		try {
			if (!reloadHandler.reload(source.getServer())) {
				source.sendFailure(Component.literal(
						"CrossServerChat configuration could not be applied. The previous relay was left unchanged. Check the server log."
				));
				return 0;
			}
		} catch (IOException exception) {
			logger.error("Could not reload CrossServerChat configuration", exception);
			source.sendFailure(Component.literal("CrossServerChat configuration reload failed: " + exception.getMessage()));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("CrossServerChat configuration reloaded."), false);
		return Command.SINGLE_SUCCESS;
	}

	@FunctionalInterface
	public interface ReloadHandler {
		boolean reload(MinecraftServer server) throws IOException;
	}
}

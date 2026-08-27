package dev.crossserverchat;

import dev.crossserverchat.config.RelayConfig;
import dev.crossserverchat.net.RelayClient;
import dev.crossserverchat.net.RelayHost;
import dev.crossserverchat.net.RelayTransport;
import dev.crossserverchat.protocol.RelayMessage;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public final class CrossServerChatMod implements DedicatedServerModInitializer {
	public static final String MOD_ID = "crossserverchat";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private RelayConfig config;
	private volatile RelayTransport transport;

	@Override
	public void onInitializeServer() {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve("cross-server-chat.json");
		try {
			config = RelayConfig.load(configPath, LOGGER);
		} catch (IOException exception) {
			LOGGER.error("CrossServerChat is disabled because its configuration could not be loaded", exception);
			return;
		}

		ServerLifecycleEvents.SERVER_STARTED.register(this::startRelay);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopRelay());
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, boundChatType) ->
				publishLocalChat(sender, message.decoratedContent().getString())
		);
	}

	private void startRelay(MinecraftServer server) {
		if (config.mode() == RelayConfig.Mode.DISABLED) {
			LOGGER.info("CrossServerChat is disabled. Edit config/cross-server-chat.json and restart to enable it.");
			return;
		}

		RelayTransport newTransport = switch (config.mode()) {
			case HOST -> new RelayHost(config, LOGGER, message -> showRemoteMessage(server, message));
			case CLIENT -> new RelayClient(config, LOGGER, message -> showRemoteMessage(server, message));
			case DISABLED -> throw new IllegalStateException("Unreachable mode");
		};

		try {
			newTransport.start();
			transport = newTransport;
			LOGGER.info("CrossServerChat started in {} mode as '{}'", config.mode(), config.serverName());
		} catch (IOException exception) {
			newTransport.close();
			LOGGER.error("Could not start CrossServerChat", exception);
		}
	}

	private void publishLocalChat(ServerPlayer sender, String text) {
		RelayTransport current = transport;
		if (current == null || text == null || text.isBlank()) {
			return;
		}

		String oneLine = text.replace('\n', ' ').replace('\r', ' ');
		if (oneLine.length() > RelayMessage.MAX_TEXT_LENGTH) {
			oneLine = oneLine.substring(0, RelayMessage.MAX_TEXT_LENGTH);
		}
		current.publish(sender.getUUID(), sender.getName().getString(), oneLine);
	}

	private void showRemoteMessage(MinecraftServer server, RelayMessage message) {
		// Socket callbacks run off-thread. Minecraft state must only be touched
		// after handing the work back to the server thread.
		server.execute(() -> {
			String rendered = config.messageFormat()
					.replace("%server%", message.server())
					.replace("%player%", message.playerName())
					.replace("%message%", message.text());
			server.getPlayerList().broadcastSystemMessage(Component.literal(rendered), false);
		});
	}

	private void stopRelay() {
		RelayTransport current = transport;
		transport = null;
		if (current != null) {
			current.close();
		}
	}
}

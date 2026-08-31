package dev.crossserverchat;

import dev.crossserverchat.command.CrossServerChatCommand;
import dev.crossserverchat.config.RelayConfig;
import dev.crossserverchat.config.RelayConfigManager;
import dev.crossserverchat.net.RedisRelayTransport;
import dev.crossserverchat.net.RelayTransport;
import dev.crossserverchat.protocol.MessageType;
import dev.crossserverchat.protocol.RelayMessage;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static dev.crossserverchat.CrossServerChatConstants.MOD_ID;

public final class CrossServerChatMod implements DedicatedServerModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private RelayConfig config;
	private RelayConfigManager configManager;
	private volatile RelayTransport transport;

	@Override
	public void onInitializeServer() {
		configManager = new RelayConfigManager(FabricLoader.getInstance().getConfigDir(), LOGGER);
		try {
			config = configManager.load();
		} catch (IOException exception) {
			LOGGER.error("CrossServerChat is disabled because its configuration could not be loaded", exception);
			return;
		}

		ServerLifecycleEvents.SERVER_STARTED.register(this::startRelay);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopRelay());
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, boundChatType) ->
				publishMessage(MessageType.PLAYER_CHAT, sender, message.decoratedContent().getString())
		);
		ServerPlayConnectionEvents.JOIN.register((listener, packetSender, server) ->
				publishMessage(MessageType.PLAYER_JOIN, listener.getPlayer(), "")
		);
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) ->
				publishMessage(MessageType.PLAYER_LEAVE, listener.getPlayer(), "")
		);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayer player) {
				publishMessage(
						MessageType.PLAYER_DEATH,
						player,
						player.getCombatTracker().getDeathMessage().getString()
				);
			}
		});
		CrossServerChatCommand.register(this::reload, LOGGER);
	}

	private boolean startRelay(MinecraftServer server) {
		if (!config.enabled()) {
			LOGGER.info("CrossServerChat is disabled. Edit config/cross-server-chat.yaml and restart to enable it.");
			return true;
		}

		RelayTransport newTransport = new RedisRelayTransport(
				config,
				LOGGER,
				message -> showRemoteMessage(server, message)
		);

		try {
			newTransport.start();
			transport = newTransport;
			LOGGER.info("CrossServerChat started as '{}'", config.serverName());
			return true;
		} catch (IOException exception) {
			newTransport.close();
			LOGGER.error("Could not start CrossServerChat", exception);
			return false;
		}
	}

	private boolean reload(MinecraftServer server) throws IOException {
		RelayConfig reloaded = configManager.load();
		stopRelay();
		config = reloaded;
		return startRelay(server);
	}

	private void publishMessage(MessageType type, ServerPlayer sender, String text) {
		RelayTransport current = transport;
		if (current == null) {
			return;
		}

		String oneLine = text.replace('\n', ' ').replace('\r', ' ');
		if ((type == MessageType.PLAYER_CHAT || type == MessageType.PLAYER_DEATH) && oneLine.isBlank()) {
			return;
		}
		if (oneLine.length() > RelayMessage.MAX_TEXT_LENGTH) {
			oneLine = oneLine.substring(0, RelayMessage.MAX_TEXT_LENGTH);
		}
		current.publish(type, sender.getUUID(), sender.getName().getString(), oneLine);
	}

	private void showRemoteMessage(MinecraftServer server, RelayMessage message) {
		// Socket callbacks run off-thread. Minecraft state must only be touched
		// after handing the work back to the server thread.
		server.execute(() -> {
			RelayConfig.MessageRelay relay = config.messageRelay(message.type());
			if (!relay.enabled()) {
				return;
			}
			MinecraftServerAudiences.of(server).players().sendMessage(
					MessageFormatter.render(relay.messageFormat(), message)
			);
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

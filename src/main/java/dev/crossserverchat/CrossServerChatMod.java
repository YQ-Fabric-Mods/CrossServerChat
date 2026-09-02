package dev.crossserverchat;

import dev.crossserverchat.command.CrossServerChatCommand;
import dev.crossserverchat.config.RelayConfig;
import dev.crossserverchat.config.RelayConfigManager;
import dev.crossserverchat.net.RedisRelayManager;
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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static dev.crossserverchat.CrossServerChatConstants.MOD_ID;

public final class CrossServerChatMod implements DedicatedServerModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private RelayConfig initialConfig;
	private RelayConfigManager configManager;
	private volatile RedisRelayManager relayManager;

	@Override
	public void onInitializeServer() {
		configManager = new RelayConfigManager(FabricLoader.getInstance().getConfigDir(), LOGGER);
		try {
			initialConfig = configManager.load();
		} catch (IOException exception) {
			LOGGER.error("CrossServerChat is stopped because its configuration could not be loaded; fix it and run /crossserverchat reload", exception);
		}

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			RedisRelayManager manager = new RedisRelayManager(
					server,
					configManager,
					LOGGER,
					(relay, message) -> showRemoteMessage(server, relay, message)
			);
			relayManager = manager;
			if (initialConfig != null) {
				manager.start(initialConfig);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopRelay());
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, boundChatType) ->
				publishMessage(MessageType.PLAYER_CHAT, sender, message.decoratedContent().getString())
		);
		ServerPlayConnectionEvents.JOIN.register((listener, packetSender, server) -> {
			publishMessage(MessageType.PLAYER_JOIN, listener.getPlayer(), "");
			RedisRelayManager manager = relayManager;
			if (manager != null) {
				manager.playerJoined(listener.getPlayer());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
			publishMessage(MessageType.PLAYER_LEAVE, listener.getPlayer(), "");
			RedisRelayManager manager = relayManager;
			if (manager != null) {
				manager.playerDisconnected(listener.getPlayer());
			}
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayer player) {
				publishMessage(
						MessageType.PLAYER_DEATH,
						player,
						player.getCombatTracker().getDeathMessage().getString()
				);
			}
		});
		CrossServerChatCommand.register(this::reload);
	}

	private boolean reload(CommandSourceStack source) {
		RedisRelayManager manager = relayManager;
		if (manager == null) {
			return false;
		}
		return manager.requestReload(result -> source.getServer().execute(() -> {
			if (result.success()) {
				source.sendSuccess(() -> Component.literal(result.message()), false);
			} else {
				source.sendFailure(Component.literal(result.message()));
			}
		}));
	}

	private void publishMessage(MessageType type, ServerPlayer sender, String text) {
		RedisRelayManager current = relayManager;
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

	private void showRemoteMessage(
			MinecraftServer server,
			RelayConfig.MessageRelay relay,
			RelayMessage message
	) {
		// Socket callbacks run off-thread. Minecraft state must only be touched
		// after handing the work back to the server thread.
		server.execute(() -> {
			MinecraftServerAudiences.of(server).players().sendMessage(
					MessageFormatter.render(relay.messageFormat(), message)
			);
		});
	}

	private void stopRelay() {
		RedisRelayManager current = relayManager;
		relayManager = null;
		if (current != null) {
			current.stop();
		}
	}
}

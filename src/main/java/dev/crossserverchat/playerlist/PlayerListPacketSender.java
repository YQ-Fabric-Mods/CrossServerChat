package dev.crossserverchat.playerlist;

import com.mojang.authlib.GameProfile;
import dev.crossserverchat.mixin.PlayerInfoPacketAccessor;
import net.kyori.adventure.platform.modcommon.MinecraftServerAudiences;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PlayerListPacketSender {
	private static final EnumSet<Action> ADD_ACTIONS = EnumSet.of(
			Action.ADD_PLAYER,
			Action.UPDATE_GAME_MODE,
			Action.UPDATE_LISTED,
			Action.UPDATE_DISPLAY_NAME,
			Action.UPDATE_LATENCY,
			Action.UPDATE_LIST_ORDER,
			Action.UPDATE_HAT
	);
	private static final MiniMessage MINI_MESSAGE =
			MiniMessage.miniMessage(MiniMessage.Preset.FORMATTED_TEXT);

	private final MinecraftServer server;
	private final MinecraftServerAudiences audiences;
	private final String displayFormat;

	public PlayerListPacketSender(MinecraftServer server, String displayFormat) {
		this.server = server;
		this.audiences = MinecraftServerAudiences.of(server);
		this.displayFormat = displayFormat;
	}

	public void broadcast(RemotePlayerDirectory.Diff diff) {
		if (diff.empty()) {
			return;
		}
		server.execute(() -> {
			Set<UUID> localIds = Set.copyOf(server.getPlayerList().getPlayersByUUID().keySet());
			List<UUID> removed = diff.removed().stream()
					.filter(uuid -> !localIds.contains(uuid))
					.toList();
			if (!removed.isEmpty()) {
				server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(removed));
			}

			List<RemotePlayerView> added = diff.added().stream()
					.filter(player -> !localIds.contains(player.uuid()))
					.toList();
			if (!added.isEmpty()) {
				server.getPlayerList().broadcastAll(updatePacket(ADD_ACTIONS, added));
			}

			broadcastUpdates(diff.updated(), localIds, true, false);
			broadcastUpdates(diff.updated(), localIds, false, true);
			broadcastUpdates(diff.updated(), localIds, true, true);
		});
	}

	public void sendFull(ServerPlayer target, List<RemotePlayerView> players) {
		server.execute(() -> {
			if (server.getPlayerList().getPlayer(target.getUUID()) != target) {
				return;
			}
			Set<UUID> localIds = Set.copyOf(server.getPlayerList().getPlayersByUUID().keySet());
			List<RemotePlayerView> filtered = players.stream()
					.filter(player -> !localIds.contains(player.uuid()))
					.toList();
			if (!filtered.isEmpty()) {
				target.connection.send(updatePacket(ADD_ACTIONS, filtered));
			}
		});
	}

	public void removeAll(List<UUID> playerIds) {
		if (playerIds.isEmpty()) {
			return;
		}
		server.execute(() -> {
			Set<UUID> localIds = Set.copyOf(server.getPlayerList().getPlayersByUUID().keySet());
			List<UUID> filtered = playerIds.stream()
					.filter(uuid -> !localIds.contains(uuid))
					.toList();
			if (!filtered.isEmpty()) {
				server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(filtered));
			}
		});
	}

	private void broadcastUpdates(
			List<RemotePlayerDirectory.PlayerUpdate> updates,
			Set<UUID> localIds,
			boolean latencyChanged,
			boolean displayChanged
	) {
		List<RemotePlayerView> players = updates.stream()
				.filter(update -> update.latencyChanged() == latencyChanged
						&& update.displayChanged() == displayChanged)
				.map(RemotePlayerDirectory.PlayerUpdate::player)
				.filter(player -> !localIds.contains(player.uuid()))
				.toList();
		if (players.isEmpty()) {
			return;
		}

		EnumSet<Action> actions = EnumSet.noneOf(Action.class);
		if (latencyChanged) {
			actions.add(Action.UPDATE_LATENCY);
		}
		if (displayChanged) {
			actions.add(Action.UPDATE_DISPLAY_NAME);
		}
		server.getPlayerList().broadcastAll(updatePacket(actions, players));
	}

	private ClientboundPlayerInfoUpdatePacket updatePacket(
			EnumSet<Action> actions,
			List<RemotePlayerView> players
	) {
		List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>(players.size());
		for (RemotePlayerView player : players) {
			entries.add(new ClientboundPlayerInfoUpdatePacket.Entry(
					player.uuid(),
					new GameProfile(player.uuid(), player.name()),
					true,
					player.latency(),
					GameType.SURVIVAL,
					displayName(player),
					true,
					-1,
					null
			));
		}

		ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
				actions,
				List.<ServerPlayer>of()
		);
		((PlayerInfoPacketAccessor) (Object) packet).crossServerChat$setEntries(entries);
		return packet;
	}

	private net.minecraft.network.chat.Component displayName(RemotePlayerView player) {
		String template = displayFormat
				.replace("%server%", "<server/>")
				.replace("%player%", "<player/>");
		return audiences.asNative(MINI_MESSAGE.deserialize(
				template,
				Placeholder.unparsed("server", player.server()),
				Placeholder.unparsed("player", player.name())
		));
	}
}

package dev.crossserverchat.playerlist;

import dev.crossserverchat.protocol.SyncedPlayer;

import java.util.List;
import java.util.UUID;

public record LocalPlayer(
		UUID uuid,
		String name,
		int latency,
		boolean vanished
) {
	public SyncedPlayer toSyncedPlayer() {
		return new SyncedPlayer(uuid.toString(), name, latency);
	}

	public static List<SyncedPlayer> toVisibleSyncedPlayers(List<LocalPlayer> players) {
		// Vanish state stays local; hidden identities never enter the Redis snapshot.
		return players.stream()
				.filter(player -> !player.vanished())
				.map(LocalPlayer::toSyncedPlayer)
				.toList();
	}
}

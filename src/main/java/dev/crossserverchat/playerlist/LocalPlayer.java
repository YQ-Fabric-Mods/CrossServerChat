package dev.crossserverchat.playerlist;

import dev.crossserverchat.protocol.SyncedPlayer;

import java.util.UUID;

public record LocalPlayer(
		UUID uuid,
		String name,
		int latency
) {
	public SyncedPlayer toSyncedPlayer() {
		return new SyncedPlayer(uuid.toString(), name, latency);
	}
}

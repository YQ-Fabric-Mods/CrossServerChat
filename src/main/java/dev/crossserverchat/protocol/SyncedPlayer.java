package dev.crossserverchat.protocol;

public record SyncedPlayer(
		String uuid,
		String name,
		int latency
) {
}

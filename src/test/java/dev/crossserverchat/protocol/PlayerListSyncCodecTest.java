package dev.crossserverchat.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerListSyncCodecTest {
	private static final String SECRET = "player-list-test-secret-that-is-long-enough";

	@Test
	void roundTripsSnapshotAndNotification() throws IOException {
		String serverId = PlayerListProtocol.serverId("Survival");
		PlayerListSnapshot snapshot = PlayerListSnapshot.create(
				serverId,
				"Survival",
				List.of(new SyncedPlayer(
						"8667ba71-b85a-4004-af54-457a9734eed7",
						"Steve",
						42
				))
		);
		PlayerListSyncCodec codec = new PlayerListSyncCodec(SECRET);

		PlayerListSnapshot decodedSnapshot = codec.decodeSnapshot(codec.encodeSnapshot(snapshot));
		PlayerListNotification notification = PlayerListNotification.create(serverId);
		PlayerListNotification decodedNotification = codec.decodeNotification(
				codec.encodeNotification(notification)
		);

		assertEquals(snapshot, decodedSnapshot);
		assertEquals(notification, decodedNotification);
		assertTrue(PlayerListProtocol.validSnapshot(serverId, decodedSnapshot));
		assertTrue(PlayerListProtocol.validNotification(decodedNotification));
	}

	@Test
	void rejectsPayloadEncryptedWithAnotherSecret() throws IOException {
		PlayerListSyncCodec first = new PlayerListSyncCodec(SECRET);
		PlayerListSyncCodec second = new PlayerListSyncCodec("another-player-list-secret-that-is-long-enough");
		String payload = first.encodeNotification(PlayerListNotification.create(
				PlayerListProtocol.serverId("Survival")
		));

		assertThrows(IOException.class, () -> second.decodeNotification(payload));
	}
}

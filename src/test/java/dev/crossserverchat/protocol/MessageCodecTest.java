package dev.crossserverchat.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageCodecTest {
	private static final String SECRET = "a-random-test-secret-that-is-long-enough";
	private final MessageCodec codec = new MessageCodec(SECRET);

	@Test
	void roundTripsAnEncryptedBase64Payload() throws IOException {
		RelayMessage expected = RelayMessage.create(
				MessageType.PLAYER_CHAT, "creative", UUID.randomUUID(), "Steve", "你好，世界"
		);

		assertEquals(expected, codec.decodeBase64(codec.encodeBase64(expected)));
	}

	@Test
	void writesTheWireVersionAsTheFirstFrameByte() throws IOException {
		RelayMessage message = RelayMessage.create(
				MessageType.PLAYER_CHAT, "survival", UUID.randomUUID(), "Alex", "Redis payload"
		);

		byte[] frame = decode(codec.encodeBase64(message));

		assertEquals(5, frame[0]);
	}

	@Test
	void rejectsExtraFrameData() throws IOException {
		RelayMessage message = RelayMessage.create(
				MessageType.PLAYER_CHAT, "survival", UUID.randomUUID(), "Alex", "Redis payload"
		);
		String payload = codec.encodeBase64(message);
		byte[] bytes = decode(payload);
		byte[] withTrailingData = Arrays.copyOf(bytes, bytes.length + 1);

		assertThrows(IOException.class, () -> codec.decodeBase64(
				Base64.getEncoder().encodeToString(withTrailingData)
		));
	}

	@Test
	void usesAFreshNonceForEveryFrame() throws IOException {
		RelayMessage message = RelayMessage.create(
				MessageType.PLAYER_CHAT, "creative", UUID.randomUUID(), "Steve", "same plaintext"
		);

		assertFalse(Arrays.equals(decode(codec.encodeBase64(message)), decode(codec.encodeBase64(message))));
	}

	@Test
	void rejectsTamperedCiphertext() throws IOException {
		RelayMessage message = RelayMessage.create(
				MessageType.PLAYER_CHAT, "creative", UUID.randomUUID(), "Steve", "do not alter"
		);
		byte[] encoded = decode(codec.encodeBase64(message));
		encoded[encoded.length - 1] ^= 1;

		assertThrows(IOException.class, () -> codec.decodeBase64(Base64.getEncoder().encodeToString(encoded)));
	}

	@Test
	void rejectsTheWrongSharedSecret() throws IOException {
		RelayMessage message = RelayMessage.create(
				MessageType.PLAYER_CHAT, "creative", UUID.randomUUID(), "Steve", "secret text"
		);
		String encoded = codec.encodeBase64(message);
		MessageCodec wrongCodec = new MessageCodec("a-different-secret-that-is-also-long");

		assertThrows(IOException.class, () -> wrongCodec.decodeBase64(encoded));
	}

	@Test
	void rejectsAnOversizedFrame() {
		byte[] frame = new byte[MessageCodec.MAX_FRAME_BYTES + 1];
		assertThrows(IOException.class, () -> codec.decodeBase64(Base64.getEncoder().encodeToString(frame)));
	}

	@Test
	void rejectsTheLegacyLengthPrefixedFrame() throws IOException {
		RelayMessage message = RelayMessage.create(
				MessageType.PLAYER_CHAT, "survival", UUID.randomUUID(), "Alex", "Redis payload"
		);
		byte[] frame = decode(codec.encodeBase64(message));
		byte[] legacyFrame = ByteBuffer.allocate(Integer.BYTES + frame.length)
				.putInt(frame.length)
				.put(frame)
				.array();

		assertThrows(IOException.class, () -> codec.decodeBase64(Base64.getEncoder().encodeToString(legacyFrame)));
	}

	private static byte[] decode(String payload) {
		return Base64.getDecoder().decode(payload);
	}
}

package dev.crossserverchat.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageCodecTest {
	private static final String SECRET = "a-random-test-secret-that-is-long-enough";
	private final MessageCodec codec = new MessageCodec(SECRET);

	@Test
	void roundTripsAnEncryptedJsonFrame() throws IOException {
		RelayMessage expected = RelayMessage.create(
				MessageType.PLAYER_CHAT, "creative", UUID.randomUUID(), "Steve", "你好，世界"
		);

		byte[] encoded = encode(codec, expected);
		RelayMessage actual = codec.read(
				new DataInputStream(new ByteArrayInputStream(encoded))
		);

		assertEquals(expected, actual);
	}

	@Test
	void roundTripsAnEncryptedBase64Payload() throws IOException {
		RelayMessage expected = RelayMessage.create(
				MessageType.PLAYER_CHAT, "survival", UUID.randomUUID(), "Alex", "Redis payload"
		);

		assertEquals(expected, codec.decodeBase64(codec.encodeBase64(expected)));
	}

	@Test
	void rejectsTrailingBase64Data() throws IOException {
		RelayMessage message = RelayMessage.create(
				MessageType.PLAYER_CHAT, "survival", UUID.randomUUID(), "Alex", "Redis payload"
		);
		String payload = codec.encodeBase64(message);
		byte[] bytes = java.util.Base64.getDecoder().decode(payload);
		byte[] withTrailingData = Arrays.copyOf(bytes, bytes.length + 1);

		assertThrows(IOException.class, () -> codec.decodeBase64(
				java.util.Base64.getEncoder().encodeToString(withTrailingData)
		));
	}

	@Test
	void usesAFreshNonceForEveryFrame() throws IOException {
		RelayMessage message = RelayMessage.create(
				MessageType.PLAYER_CHAT, "creative", UUID.randomUUID(), "Steve", "same plaintext"
		);

		assertFalse(Arrays.equals(encode(codec, message), encode(codec, message)));
	}

	@Test
	void rejectsTamperedCiphertext() throws IOException {
		RelayMessage message = RelayMessage.create(
				MessageType.PLAYER_CHAT, "creative", UUID.randomUUID(), "Steve", "do not alter"
		);
		byte[] encoded = encode(codec, message);
		encoded[encoded.length - 1] ^= 1;

		assertThrows(IOException.class, () -> codec.read(
				new DataInputStream(new ByteArrayInputStream(encoded))
		));
	}

	@Test
	void rejectsTheWrongSharedSecret() throws IOException {
		RelayMessage message = RelayMessage.create(
				MessageType.PLAYER_CHAT, "creative", UUID.randomUUID(), "Steve", "secret text"
		);
		byte[] encoded = encode(codec, message);
		MessageCodec wrongCodec = new MessageCodec("a-different-secret-that-is-also-long");

		assertThrows(IOException.class, () -> wrongCodec.read(
				new DataInputStream(new ByteArrayInputStream(encoded))
		));
	}

	@Test
	void rejectsAnOversizedFrameBeforeReadingIt() {
		byte[] header = {
				(byte) 0x7f,
				(byte) 0xff,
				(byte) 0xff,
				(byte) 0xff
		};
		DataInputStream input = new DataInputStream(new ByteArrayInputStream(header));
		assertThrows(IOException.class, () -> codec.read(input));
	}

	private static byte[] encode(MessageCodec codec, RelayMessage message) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		codec.write(new DataOutputStream(bytes), message);
		return bytes.toByteArray();
	}
}

package dev.crossserverchat.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts UTF-8 JSON using AES-256-GCM. The shared secret is converted to an
 * AES key with HKDF-SHA256.
 */
public final class MessageCodec {
	static final int MAX_FRAME_BYTES = 16 * 1024;
	static final int NONCE_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;
	private static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;
	private static final byte WIRE_VERSION = 5;
	private static final byte[] HKDF_SALT =
			"crossserverchat/hkdf-salt/v5".getBytes(StandardCharsets.UTF_8);
	private static final byte[] HKDF_INFO =
			"crossserverchat/aes-256-gcm/v5".getBytes(StandardCharsets.UTF_8);
	private static final byte[] AAD =
			"crossserverchat/frame/v5".getBytes(StandardCharsets.UTF_8);
	private static final Gson GSON = new Gson();
	private static final SecureRandom RANDOM = new SecureRandom();
	private final SecretKey encryptionKey;

	public MessageCodec(String sharedSecret) {
		encryptionKey = new SecretKeySpec(hkdfSha256(sharedSecret), "AES");
	}

	public String encodeBase64(RelayMessage message) throws IOException {
		byte[] plaintext = GSON.toJson(message).getBytes(StandardCharsets.UTF_8);
		if (plaintext.length == 0
				|| plaintext.length + 1 + NONCE_BYTES + GCM_TAG_BYTES > MAX_FRAME_BYTES) {
			throw new IOException("CrossServerChat frame is too large");
		}

		byte[] nonce = new byte[NONCE_BYTES];
		RANDOM.nextBytes(nonce);
		byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, nonce, plaintext);
		byte[] frame = new byte[1 + nonce.length + ciphertext.length];
		frame[0] = WIRE_VERSION;
		System.arraycopy(nonce, 0, frame, 1, nonce.length);
		System.arraycopy(ciphertext, 0, frame, 1 + nonce.length, ciphertext.length);
		return Base64.getEncoder().encodeToString(frame);
	}

	public RelayMessage decodeBase64(String payload) throws IOException {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(payload);
		} catch (IllegalArgumentException exception) {
			throw new IOException("Invalid CrossServerChat Base64 payload", exception);
		}

		if (bytes.length < 1 + NONCE_BYTES + GCM_TAG_BYTES || bytes.length > MAX_FRAME_BYTES) {
			throw new IOException("Invalid CrossServerChat frame length: " + bytes.length);
		}
		if (bytes[0] != WIRE_VERSION) {
			throw new IOException("Unsupported encrypted CrossServerChat frame version");
		}

		byte[] nonce = Arrays.copyOfRange(bytes, 1, 1 + NONCE_BYTES);
		byte[] ciphertext = Arrays.copyOfRange(bytes, 1 + NONCE_BYTES, bytes.length);
		byte[] plaintext = crypt(Cipher.DECRYPT_MODE, nonce, ciphertext);

		try {
			return GSON.fromJson(new String(plaintext, StandardCharsets.UTF_8), RelayMessage.class);
		} catch (JsonParseException exception) {
			throw new IOException("Invalid encrypted CrossServerChat JSON", exception);
		}
	}

	private byte[] crypt(int mode, byte[] nonce, byte[] input) throws IOException {
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(mode, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
			cipher.updateAAD(AAD);
			return cipher.doFinal(input);
		} catch (AEADBadTagException exception) {
			throw new IOException("CrossServerChat frame authentication failed");
		} catch (GeneralSecurityException exception) {
			throw new IOException("Could not process encrypted CrossServerChat frame", exception);
		}
	}

	/**
	 * RFC 5869 extract-then-expand. SHA-256 already yields the 32 bytes needed
	 * for AES-256, so one expand block is sufficient.
	 */
	private static byte[] hkdfSha256(String sharedSecret) {
		try {
			Mac extract = Mac.getInstance("HmacSHA256");
			extract.init(new SecretKeySpec(HKDF_SALT, "HmacSHA256"));
			byte[] pseudorandomKey = extract.doFinal(sharedSecret.getBytes(StandardCharsets.UTF_8));

			Mac expand = Mac.getInstance("HmacSHA256");
			expand.init(new SecretKeySpec(pseudorandomKey, "HmacSHA256"));
			expand.update(HKDF_INFO);
			expand.update((byte) 1);
			return expand.doFinal();
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HKDF-SHA256 is unavailable", exception);
		}
	}
}

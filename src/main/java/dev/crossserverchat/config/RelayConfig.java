package dev.crossserverchat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * The intentionally small configuration shared by host and client modes.
 */
public final class RelayConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private String mode = "disabled";
	private String serverName = "server-1";
	private String bindAddress = "0.0.0.0";
	private String host = "127.0.0.1";
	private int port = 8192;
	private String sharedSecret = "";
	private String messageFormat = "<gray>[%server%]</gray> <%player%> %message%";
	private int connectTimeoutSeconds = 5;
	private int reconnectDelaySeconds = 5;

	public static RelayConfig load(Path path, Logger logger) throws IOException {
		if (Files.notExists(path)) {
			RelayConfig config = new RelayConfig();
			config.sharedSecret = generateSecret();
			config.save(path);
			logger.warn("Created {}. Configure it and restart the server to enable CrossServerChat.", path);
			return config;
		}

		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			RelayConfig config = GSON.fromJson(reader, RelayConfig.class);
			if (config == null) {
				throw new IOException("Configuration file is empty");
			}
			config.applyDefaults();
			config.validate();
			return config;
		} catch (RuntimeException exception) {
			throw new IOException("Could not parse " + path, exception);
		}
	}

	private void save(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, GSON.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
	}

	private void applyDefaults() {
		if (mode == null || mode.isBlank()) mode = "disabled";
		if (serverName == null || serverName.isBlank()) serverName = "server-1";
		if (bindAddress == null || bindAddress.isBlank()) bindAddress = "0.0.0.0";
		if (host == null || host.isBlank()) host = "127.0.0.1";
		if (messageFormat == null || messageFormat.isBlank()) {
			messageFormat = "<gray>[%server%]</gray> <%player%> %message%";
		}
	}

	private void validate() throws IOException {
		try {
			Mode.valueOf(mode.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new IOException("mode must be disabled, host, or client");
		}
		if (port < 1 || port > 65535) {
			throw new IOException("port must be between 1 and 65535");
		}
		if (serverName.length() > 64 || containsLineBreak(serverName)) {
			throw new IOException("serverName must be at most 64 characters and one line");
		}
		if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 60) {
			throw new IOException("connectTimeoutSeconds must be between 1 and 60");
		}
		if (reconnectDelaySeconds < 1 || reconnectDelaySeconds > 300) {
			throw new IOException("reconnectDelaySeconds must be between 1 and 300");
		}
		if (mode() != Mode.DISABLED && (sharedSecret == null || sharedSecret.length() < 32)) {
			throw new IOException("sharedSecret must contain at least 32 characters");
		}
	}

	private static boolean containsLineBreak(String value) {
		return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
	}

	private static String generateSecret() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public Mode mode() {
		try {
			return Mode.valueOf(mode.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return Mode.DISABLED;
		}
	}

	public String serverName() {
		return serverName;
	}

	public String bindAddress() {
		return bindAddress;
	}

	public String host() {
		return host;
	}

	public int port() {
		return port;
	}

	public String sharedSecret() {
		return sharedSecret;
	}

	public String messageFormat() {
		return messageFormat;
	}

	public int connectTimeoutSeconds() {
		return connectTimeoutSeconds;
	}

	public int reconnectDelaySeconds() {
		return reconnectDelaySeconds;
	}

	public enum Mode {
		DISABLED,
		HOST,
		CLIENT
	}
}

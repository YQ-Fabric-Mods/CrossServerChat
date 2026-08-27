package dev.crossserverchat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * The intentionally small configuration shared by host and client modes.
 */
public final class RelayConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));
	private static final int CURRENT_VERSION = 2;

	private int version = CURRENT_VERSION;
	private String mode = "disabled";
	private String serverName = "server-1";
	private String bindAddress = "0.0.0.0";
	private String host = "127.0.0.1";
	private int port = 8192;
	private String sharedSecret = "";
	private String messageFormat = "<gray>[%server%]</gray> <%player%> %message%";
	private int connectTimeoutSeconds = 5;
	private int reconnectDelaySeconds = 5;

	public static RelayConfig load(Path yamlPath, Path legacyJsonPath, Logger logger) throws IOException {
		if (Files.exists(yamlPath)) {
			RelayConfig config = loadYaml(yamlPath);
			if (Files.exists(legacyJsonPath)) {
				Files.delete(legacyJsonPath);
				logger.info("Removed obsolete CrossServerChat configuration {}.", legacyJsonPath);
			}
			return config;
		}

		if (Files.exists(legacyJsonPath)) {
			RelayConfig config = loadLegacyJson(legacyJsonPath);
			config.save(yamlPath);
			Files.delete(legacyJsonPath);
			logger.info("Upgraded CrossServerChat configuration from {} to {}.", legacyJsonPath, yamlPath);
			return config;
		}

		RelayConfig config = new RelayConfig();
		config.sharedSecret = generateSecret();
		config.save(yamlPath);
		logger.warn("Created {}. Configure it and restart the server to enable CrossServerChat.", yamlPath);
		return config;
	}

	public static RelayConfig load(Path yamlPath, Logger logger) throws IOException {
		if (Files.notExists(yamlPath)) {
			RelayConfig config = new RelayConfig();
			config.sharedSecret = generateSecret();
			config.save(yamlPath);
			logger.warn("Created {}. Configure it and restart the server to enable CrossServerChat.", yamlPath);
			return config;
		}
		return loadYaml(yamlPath);
	}

	private static RelayConfig loadLegacyJson(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			RelayConfig config = GSON.fromJson(reader, RelayConfig.class);
			if (config == null) {
				throw new IOException("Configuration file is empty");
			}
			config.version = CURRENT_VERSION;
			config.applyDefaults();
			config.validate();
			return config;
		} catch (RuntimeException exception) {
			throw new IOException("Could not parse " + path, exception);
		}
	}

	private static RelayConfig loadYaml(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			Object document = YAML.load(reader);
			if (!(document instanceof Map<?, ?> values)) {
				throw new IOException("Configuration must be a YAML mapping");
			}

			RelayConfig config = new RelayConfig();
			config.version = requiredInteger(values, "version");
			config.mode = string(values, "mode", config.mode);
			config.serverName = string(values, "serverName", config.serverName);
			config.bindAddress = string(values, "bindAddress", config.bindAddress);
			config.host = string(values, "host", config.host);
			config.port = integer(values, "port", config.port);
			config.sharedSecret = string(values, "sharedSecret", config.sharedSecret);
			config.messageFormat = string(values, "messageFormat", config.messageFormat);
			config.connectTimeoutSeconds = integer(values, "connectTimeoutSeconds", config.connectTimeoutSeconds);
			config.reconnectDelaySeconds = integer(values, "reconnectDelaySeconds", config.reconnectDelaySeconds);
			config.applyDefaults();
			config.validate();
			return config;
		} catch (RuntimeException exception) {
			throw new IOException("Could not parse " + path, exception);
		}
	}

	private static String string(Map<?, ?> values, String key, String defaultValue) throws IOException {
		Object value = values.get(key);
		if (value == null) return defaultValue;
		if (value instanceof String string) return string;
		throw new IOException(key + " must be a string");
	}

	private static int integer(Map<?, ?> values, String key, int defaultValue) throws IOException {
		Object value = values.get(key);
		if (value == null) return defaultValue;
		if (value instanceof Integer integer) return integer;
		throw new IOException(key + " must be an integer");
	}

	private static int requiredInteger(Map<?, ?> values, String key) throws IOException {
		if (!values.containsKey(key)) {
			throw new IOException(key + " is required");
		}
		return integer(values, key, 0);
	}

	private void save(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		String content = """
				# Mode: disabled, host, or client.
				mode: %s
				# Unique name used to identify this Minecraft server in chat messages.
				serverName: %s
				# Network address the host mode listens on.
				bindAddress: %s
				# Address of the relay host used by client mode.
				host: %s
				# TCP port used by the relay host and all clients.
				port: %d
				# Shared secret used to encrypt relay traffic. Use the same value on every server.
				sharedSecret: %s
				# MiniMessage format for remote chat. Available placeholders: %%server%%, %%player%%, %%message%%.
				messageFormat: %s
				# Maximum time in seconds a client waits while connecting to the host.
				connectTimeoutSeconds: %d
				# Delay in seconds before a disconnected client attempts to reconnect.
				reconnectDelaySeconds: %d
				
				# Do not change this number.
				version: %d
				""".formatted(
				quote(mode),
				quote(serverName),
				quote(bindAddress),
				quote(host),
				port,
				quote(sharedSecret),
				quote(messageFormat),
				connectTimeoutSeconds,
				reconnectDelaySeconds,
				version
		);
		Path temporaryPath = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
		try {
			Files.writeString(temporaryPath, content, StandardCharsets.UTF_8);
			try {
				Files.move(temporaryPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporaryPath);
		}
	}

	private static String quote(String value) {
		return GSON.toJson(value);
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
		if (version != CURRENT_VERSION) {
			throw new IOException("Unsupported configuration version " + version + "; expected " + CURRENT_VERSION);
		}
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

	public int version() {
		return version;
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

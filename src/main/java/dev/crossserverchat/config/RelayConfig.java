package dev.crossserverchat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.crossserverchat.protocol.MessageType;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The intentionally small configuration shared by host and client modes.
 */
public final class RelayConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

	private int version = RelayConfigMigrator.CURRENT_VERSION;
	private String mode = "disabled";
	private String serverName = "server-1";
	private String bindAddress = "0.0.0.0";
	private String host = "127.0.0.1";
	private int port = 8192;
	private String sharedSecret = "";
	private EnumMap<MessageType, MessageRelay> messageRelay = defaultMessageRelay();
	private int connectTimeoutSeconds = 5;
	private int reconnectDelaySeconds = 5;

	public static RelayConfig load(Path yamlPath, Path legacyJsonPath, Logger logger) throws IOException {
		if (Files.exists(yamlPath)) {
			RelayConfig config = loadYamlAndUpgrade(yamlPath, logger);
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
		return loadYamlAndUpgrade(yamlPath, logger);
	}

	private static RelayConfig loadYamlAndUpgrade(Path path, Logger logger) throws IOException {
		LoadedConfig loaded = loadYaml(path);
		if (loaded.migrated()) {
			loaded.config().save(path);
			logger.info("Upgraded CrossServerChat configuration {} from version {} to version {}.",
					path, loaded.originalVersion(), RelayConfigMigrator.CURRENT_VERSION);
		}
		return loaded.config();
	}

	private static RelayConfig loadLegacyJson(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			Map<String, Object> values = mapping(GSON.fromJson(reader, Object.class));
			values.put("version", 2);
			RelayConfigMigrator.migrate(values);
			return fromValues(values);
		} catch (RuntimeException exception) {
			throw new IOException("Could not parse " + path, exception);
		}
	}

	private static LoadedConfig loadYaml(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			Map<String, Object> values = mapping(YAML.load(reader));
			int originalVersion = requiredInteger(values, "version");
			boolean migrated = RelayConfigMigrator.migrate(values);
			return new LoadedConfig(fromValues(values), originalVersion, migrated);
		} catch (RuntimeException exception) {
			throw new IOException("Could not parse " + path, exception);
		}
	}

	private static Map<String, Object> mapping(Object document) throws IOException {
		if (!(document instanceof Map<?, ?> source)) {
			throw new IOException("Configuration must be a mapping");
		}
		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : source.entrySet()) {
			if (!(entry.getKey() instanceof String key)) {
				throw new IOException("Configuration keys must be strings");
			}
			values.put(key, entry.getValue());
		}
		return values;
	}

	private static RelayConfig fromValues(Map<String, Object> values) throws IOException {
		RelayConfig config = new RelayConfig();
		config.version = requiredInteger(values, "version");
		config.mode = string(values, "mode", config.mode);
		config.serverName = string(values, "serverName", config.serverName);
		config.bindAddress = string(values, "bindAddress", config.bindAddress);
		config.host = string(values, "host", config.host);
		config.port = integer(values, "port", config.port);
		config.sharedSecret = string(values, "sharedSecret", config.sharedSecret);
		config.messageRelay = messageRelay(values.get("message-relay"));
		config.connectTimeoutSeconds = integer(values, "connectTimeoutSeconds", config.connectTimeoutSeconds);
		config.reconnectDelaySeconds = integer(values, "reconnectDelaySeconds", config.reconnectDelaySeconds);
		config.applyDefaults();
		config.validate();
		return config;
	}

	private static EnumMap<MessageType, MessageRelay> messageRelay(Object value) throws IOException {
		if (!(value instanceof List<?> rules)) {
			throw new IOException("message-relay must be a list");
		}

		EnumMap<MessageType, MessageRelay> result = new EnumMap<>(MessageType.class);
		for (Object valueRule : rules) {
			if (!(valueRule instanceof Map<?, ?> rule)) {
				throw new IOException("Each message-relay item must be a mapping");
			}

			MessageType type = null;
			for (MessageType candidate : MessageType.values()) {
				if (rule.containsKey(candidate.configKey())) {
					if (type != null) {
						throw new IOException("Each message-relay item must contain exactly one message type");
					}
					type = candidate;
				}
			}
			if (type == null) {
				throw new IOException("Unknown message type in message-relay");
			}

			for (Object key : rule.keySet()) {
				if (!type.configKey().equals(key) && !"messageFormat".equals(key)) {
					throw new IOException("Unknown field in " + type.configKey() + " relay: " + key);
				}
			}

			boolean enabled = relayState(rule.get(type.configKey()), type);
			String format = requiredString(rule, "messageFormat");
			if (result.put(type, new MessageRelay(enabled, format)) != null) {
				throw new IOException("Duplicate message-relay item: " + type.configKey());
			}
		}

		for (MessageType type : MessageType.values()) {
			if (!result.containsKey(type)) {
				throw new IOException("Missing message-relay item: " + type.configKey());
			}
		}
		return result;
	}

	private static boolean relayState(Object value, MessageType type) throws IOException {
		if ("enabled".equals(value)) return true;
		if ("disabled".equals(value)) return false;
		throw new IOException(type.configKey() + " must be enabled or disabled");
	}

	private static String requiredString(Map<?, ?> values, String key) throws IOException {
		Object value = values.get(key);
		if (value instanceof String string) return string;
		throw new IOException(key + " is required and must be a string");
	}

	private static String string(Map<String, Object> values, String key, String defaultValue) throws IOException {
		Object value = values.get(key);
		if (value == null) return defaultValue;
		if (value instanceof String string) return string;
		throw new IOException(key + " must be a string");
	}

	private static int integer(Map<String, Object> values, String key, int defaultValue) throws IOException {
		Object value = values.get(key);
		if (value == null) return defaultValue;
		if (value instanceof Number number && number.doubleValue() == number.intValue()) {
			return number.intValue();
		}
		throw new IOException(key + " must be an integer");
	}

	private static int requiredInteger(Map<String, Object> values, String key) throws IOException {
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
				# Unique name used to identify this Minecraft server in relayed messages.
				serverName: %s
				# Network address the host mode listens on.
				bindAddress: %s
				# Address of the relay host used by client mode.
				host: %s
				# TCP port used by the relay host and all clients.
				port: %d
				# Shared secret used to encrypt relay traffic.
				# Use the same value with its host on the client servers.
				sharedSecret: %s
				# Remote message display rules on this server.
				# MiniMessage format. Available placeholders: %%server%%, %%player%%, %%message%%.
				message-relay:
				  # Displays remote player chat messages.
				  - player-chat: %s
				    messageFormat: %s
				  # Displays remote player join messages.
				  - player-join: %s
				    messageFormat: %s
				  # Displays remote player leave messages.
				  - player-leave: %s
				    messageFormat: %s
				  # Displays remote player death messages.
				  - player-death: %s
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
				state(MessageType.PLAYER_CHAT),
				quote(messageRelay.get(MessageType.PLAYER_CHAT).messageFormat()),
				state(MessageType.PLAYER_JOIN),
				quote(messageRelay.get(MessageType.PLAYER_JOIN).messageFormat()),
				state(MessageType.PLAYER_LEAVE),
				quote(messageRelay.get(MessageType.PLAYER_LEAVE).messageFormat()),
				state(MessageType.PLAYER_DEATH),
				quote(messageRelay.get(MessageType.PLAYER_DEATH).messageFormat()),
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

	private String state(MessageType type) {
		return messageRelay.get(type).enabled() ? "enabled" : "disabled";
	}

	private static String quote(String value) {
		return GSON.toJson(value);
	}

	private void applyDefaults() {
		if (mode == null || mode.isBlank()) mode = "disabled";
		if (serverName == null || serverName.isBlank()) serverName = "server-1";
		if (bindAddress == null || bindAddress.isBlank()) bindAddress = "0.0.0.0";
		if (host == null || host.isBlank()) host = "127.0.0.1";
	}

	private void validate() throws IOException {
		if (version != RelayConfigMigrator.CURRENT_VERSION) {
			throw new IOException("Unsupported configuration version " + version
					+ "; expected " + RelayConfigMigrator.CURRENT_VERSION);
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
		for (MessageType type : MessageType.values()) {
			String format = messageRelay.get(type).messageFormat();
			if (format.isBlank() || containsLineBreak(format)) {
				throw new IOException(type.configKey() + " messageFormat must be non-blank and one line");
			}
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

	private static EnumMap<MessageType, MessageRelay> defaultMessageRelay() {
		EnumMap<MessageType, MessageRelay> defaults = new EnumMap<>(MessageType.class);
		for (MessageType type : MessageType.values()) {
			defaults.put(type, new MessageRelay(true, defaultFormat(type)));
		}
		return defaults;
	}

	static String defaultFormat(MessageType type) {
		return switch (type) {
			case PLAYER_CHAT -> "<gray>[%server%]</gray> <%player%> %message%";
			case PLAYER_JOIN -> "<gray>[%server%]</gray> <yellow>%player% joined the game</yellow>";
			case PLAYER_LEAVE -> "<gray>[%server%]</gray> <yellow>%player% left the game</yellow>";
			case PLAYER_DEATH -> "<gray>[%server%]</gray> %message%";
		};
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

	public MessageRelay messageRelay(MessageType type) {
		return messageRelay.get(type);
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

	public record MessageRelay(boolean enabled, String messageFormat) {
	}

	private record LoadedConfig(RelayConfig config, int originalVersion, boolean migrated) {
	}
}

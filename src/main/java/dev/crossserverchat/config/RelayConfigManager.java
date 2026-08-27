package dev.crossserverchat.config;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

public final class RelayConfigManager {
	private final Path configPath;
	private final Path legacyConfigPath;
	private final Logger logger;

	public RelayConfigManager(Path configDirectory, Logger logger) {
		configPath = configDirectory.resolve("cross-server-chat.yaml");
		legacyConfigPath = configDirectory.resolve("cross-server-chat.json");
		this.logger = logger;
	}

	public RelayConfig load() throws IOException {
		return RelayConfig.load(configPath, legacyConfigPath, logger);
	}
}

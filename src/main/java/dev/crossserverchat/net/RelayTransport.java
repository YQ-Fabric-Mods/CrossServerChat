package dev.crossserverchat.net;

import java.io.IOException;
import java.util.UUID;

public interface RelayTransport extends AutoCloseable {
	void start() throws IOException;

	void publish(UUID playerId, String playerName, String text);

	@Override
	void close();
}

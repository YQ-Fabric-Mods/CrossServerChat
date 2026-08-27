package dev.crossserverchat.protocol;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small in-memory duplicate filter. IDs expire because the protocol rejects old
 * timestamps anyway.
 */
public final class RecentMessageCache {
	private static final long RETENTION_MILLIS = 5 * 60_000L;
	private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();
	private final AtomicInteger checks = new AtomicInteger();

	public boolean markIfNew(String id) {
		long now = System.currentTimeMillis();
		if ((checks.incrementAndGet() & 127) == 0) {
			long cutoff = now - RETENTION_MILLIS;
			seen.entrySet().removeIf(entry -> entry.getValue() < cutoff);
		}
		return seen.putIfAbsent(id, now) == null;
	}
}

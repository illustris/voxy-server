package me.cortex.voxy.server.util;

import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Kernel-style log debouncing. Groups repeated messages by their format string
 * and prints them at most once per flush interval. Suppressed occurrences are
 * reported as "[repeated N more times]" with the most recent argument values.
 *
 * Thread-safe: safe to call from multiple threads concurrently.
 */
public class DebouncedLogger {
	private static final long FLUSH_INTERVAL_MS = 1000;

	private final Logger logger;
	private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

	private static class Entry {
		volatile String lastMessage;
		volatile int count;
		volatile long windowStartMs;

		Entry(String message, long now) {
			this.lastMessage = message;
			this.count = 0;
			this.windowStartMs = now;
		}
	}

	public DebouncedLogger(Logger logger) {
		this.logger = logger;
	}

	/**
	 * Log a message with debouncing. The format string is used as the grouping
	 * key -- all calls with the same format are collapsed regardless of argument
	 * values. The most recent formatted message is shown when flushing.
	 *
	 * @param format SLF4J-style format string with {} placeholders
	 * @param args   format arguments
	 */
	public void log(String format, Object... args) {
		long now = System.currentTimeMillis();
		String formatted = formatSlf4j(format, args);

		// Flush any expired entries before processing this one
		flushExpired(now);

		Entry entry = entries.get(format);
		if (entry == null) {
			// First occurrence -- print immediately and start window
			logger.info("{}", formatted);
			entries.put(format, new Entry(formatted, now));
		} else {
			// Within an active window -- suppress and count
			entry.lastMessage = formatted;
			entry.count++;
		}
	}

	/**
	 * Flush all entries whose window has expired. Call periodically (e.g. per
	 * tick or per frame) to ensure pending counts are emitted even if no new
	 * log call arrives.
	 */
	public void flush() {
		flushExpired(System.currentTimeMillis());
	}

	private void flushExpired(long now) {
		var it = entries.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			Entry entry = e.getValue();
			if (now - entry.windowStartMs >= FLUSH_INTERVAL_MS) {
				if (entry.count > 0) {
					logger.info("{} [repeated {} more times]", entry.lastMessage, entry.count);
				}
				it.remove();
			}
		}
	}

	/**
	 * Format an SLF4J-style message (with {} placeholders) into a plain string.
	 */
	private static String formatSlf4j(String pattern, Object... args) {
		if (args == null || args.length == 0) return pattern;
		StringBuilder sb = new StringBuilder(pattern.length() + 32);
		int argIdx = 0;
		int i = 0;
		while (i < pattern.length()) {
			if (i + 1 < pattern.length() && pattern.charAt(i) == '{'
					&& pattern.charAt(i + 1) == '}' && argIdx < args.length) {
				sb.append(args[argIdx++]);
				i += 2;
			} else {
				sb.append(pattern.charAt(i));
				i++;
			}
		}
		return sb.toString();
	}
}

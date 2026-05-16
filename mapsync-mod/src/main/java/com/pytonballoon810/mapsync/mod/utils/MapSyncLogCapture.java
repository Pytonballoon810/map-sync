package com.pytonballoon810.mapsync.mod.utils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNull;

/// Ring-buffer of recent log events emitted by `com.pytonballoon810.mapsync.*`
/// loggers. Installed as a Log4j appender on the root logger at mod
/// bootstrap so both Log4j and SLF4J call paths get captured (Minecraft
/// routes SLF4J through Log4j). The buffer survives across the GUI and
/// the `/mapsync logs` command without burdening file IO.
///
/// 500-line cap is enough for a normal session's worth of MapSync activity
/// without leaking memory. Older entries drop off the front as new ones
/// land.
public final class MapSyncLogCapture {
	private static final int MAX_ENTRIES = 500;
	private static final @NotNull String LOGGER_PREFIX = "com.pytonballoon810.mapsync";
	private static final @NotNull DateTimeFormatter TIME_FORMAT =
		DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

	private static final @NotNull Deque<Entry> buffer = new ArrayDeque<>(MAX_ENTRIES);
	private static final @NotNull AtomicBoolean installed = new AtomicBoolean(false);

	private MapSyncLogCapture() {
	}

	public record Entry(
		long timestampMillis,
		@NotNull String level,
		@NotNull String loggerName,
		@NotNull String message,
		@NotNull String throwable
	) {
		public @NotNull String formatted() {
			final String prefix = TIME_FORMAT.format(Instant.ofEpochMilli(this.timestampMillis))
				+ " [" + this.level + "] " + shortLogger(this.loggerName) + ": " + this.message;
			if (this.throwable.isEmpty()) {
				return prefix;
			}
			return prefix + System.lineSeparator() + this.throwable;
		}

		private static @NotNull String shortLogger(final @NotNull String name) {
			final int dot = name.lastIndexOf('.');
			return dot < 0 ? name : name.substring(dot + 1);
		}
	}

	/// Idempotent — calling more than once is a no-op. Safe to invoke from
	/// every entrypoint (client + main) without double-registering.
	public static void install() {
		if (!installed.compareAndSet(false, true)) {
			return;
		}
		final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
		final Configuration config = ctx.getConfiguration();
		final Appender appender = new CaptureAppender();
		appender.start();
		config.addAppender(appender);
		config.getRootLogger().addAppender(appender, Level.DEBUG, null);
		ctx.updateLoggers();
	}

	public static @NotNull List<Entry> snapshot() {
		synchronized (buffer) {
			return new ArrayList<>(buffer);
		}
	}

	public static @NotNull List<Entry> tail(final int count) {
		final List<Entry> all = snapshot();
		if (count >= all.size()) {
			return all;
		}
		return new ArrayList<>(all.subList(all.size() - count, all.size()));
	}

	/// Newline-joined plain-text dump of every captured entry. Used by the
	/// GUI's Copy button and as the format the operator command echoes.
	public static @NotNull String dump() {
		final StringBuilder sb = new StringBuilder();
		for (final Entry e : snapshot()) {
			sb.append(e.formatted()).append(System.lineSeparator());
		}
		return sb.toString();
	}

	public static int size() {
		synchronized (buffer) {
			return buffer.size();
		}
	}

	private static final class CaptureAppender extends AbstractAppender {
		private CaptureAppender() {
			super("MapSyncCapture", null, null, false, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(final LogEvent event) {
			final String name = event.getLoggerName();
			if (name == null || !name.startsWith(LOGGER_PREFIX)) {
				return;
			}
			final String throwableText;
			if (event.getThrown() == null) {
				throwableText = "";
			}
			else {
				final StringWriter sw = new StringWriter();
				event.getThrown().printStackTrace(new PrintWriter(sw));
				throwableText = sw.toString();
			}
			final Entry entry = new Entry(
				event.getTimeMillis(),
				event.getLevel().toString(),
				name,
				event.getMessage().getFormattedMessage(),
				throwableText
			);
			synchronized (buffer) {
				buffer.addLast(entry);
				while (buffer.size() > MAX_ENTRIES) {
					buffer.removeFirst();
				}
			}
		}
	}
}

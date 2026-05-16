package com.pytonballoon810.mapsync.mod.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

/// Server-side log facade that unconditionally prefixes every emitted
/// message with `[mapsync] `. Implemented as a thin wrapper around a
/// real Log4j [Logger] rather than via a custom
/// [org.apache.logging.log4j.message.MessageFactory] because the
/// MessageFactory approach kept losing to Log4j's logger-registry
/// initialization races — the first caller to request a given logger
/// name wins the factory, and *something* in MC's startup classpath
/// scanning always beat our static-field initializer. This wrapper
/// owns the prefix at the call site; Log4j's internals never see it
/// as anything but a normal message.
///
/// Call-site usage stays close to plain Log4j: `logger.info("[{}] foo",
/// name)`, `logger.warn("op failed", throwable)`, etc. Trailing
/// Throwables are forwarded through the varargs path so Log4j's
/// `ParameterizedMessage` still extracts them for stack-trace
/// rendering.
///
/// The captured-log appender at
/// {@link com.pytonballoon810.mapsync.mod.utils.MapSyncLogCapture}
/// still catches every event we emit because it attaches to the root
/// logger and filters by name prefix, which is unaffected by this
/// wrapper.
public final class MsServerLog {
	private static final @NotNull String PREFIX = "[mapsync] ";

	private final @NotNull Logger delegate;

	private MsServerLog(final @NotNull Logger delegate) {
		this.delegate = delegate;
	}

	public static @NotNull MsServerLog get(final @NotNull Class<?> clazz) {
		return new MsServerLog(LogManager.getLogger(clazz));
	}

	public void info(final @NotNull String message) {
		this.delegate.info(PREFIX + message);
	}

	public void info(final @NotNull String message, final Object... args) {
		this.delegate.info(PREFIX + message, args);
	}

	public void warn(final @NotNull String message) {
		this.delegate.warn(PREFIX + message);
	}

	public void warn(final @NotNull String message, final Object... args) {
		this.delegate.warn(PREFIX + message, args);
	}

	public void warn(final @NotNull String message, final Throwable throwable) {
		this.delegate.warn(PREFIX + message, throwable);
	}

	public void error(final @NotNull String message) {
		this.delegate.error(PREFIX + message);
	}

	public void error(final @NotNull String message, final Object... args) {
		this.delegate.error(PREFIX + message, args);
	}

	public void error(final @NotNull String message, final Throwable throwable) {
		this.delegate.error(PREFIX + message, throwable);
	}

	public void debug(final @NotNull String message) {
		this.delegate.debug(PREFIX + message);
	}

	public void debug(final @NotNull String message, final Object... args) {
		this.delegate.debug(PREFIX + message, args);
	}
}

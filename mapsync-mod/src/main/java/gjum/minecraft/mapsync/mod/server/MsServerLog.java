package gjum.minecraft.mapsync.mod.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.MessageFactory;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.jetbrains.annotations.NotNull;

/// Factory for server-side Log4j loggers whose every emitted message is
/// transparently prefixed with `[mapsync] `. Spares each call site from
/// having to remember the prefix and keeps the dedicated-server console
/// scannable when MapSync logs mix with the rest of MC's startup chatter.
///
/// Server-side classes that previously held an SLF4J logger are migrated
/// to Log4j on this helper. The captured-log appender
/// ({@link gjum.minecraft.mapsync.mod.utils.MapSyncLogCapture}) catches
/// both paths because Minecraft routes SLF4J through Log4j internally, but
/// the MessageFactory hook only fires for Log4j call sites — hence the
/// migration is part of the prefix story rather than a separate cleanup.
public final class MsServerLog {
	private static final @NotNull String PREFIX = "[mapsync] ";
	private static final @NotNull Object[] EMPTY = new Object[0];

	private static final @NotNull MessageFactory FACTORY = new MessageFactory() {
		@Override
		public @NotNull Message newMessage(final Object object) {
			return new ParameterizedMessage(PREFIX + object, EMPTY);
		}

		@Override
		public @NotNull Message newMessage(final String message) {
			return new ParameterizedMessage(PREFIX + message, EMPTY);
		}

		@Override
		public @NotNull Message newMessage(final String message, final Object... params) {
			return new ParameterizedMessage(PREFIX + message, params);
		}
	};

	private MsServerLog() {
	}

	public static @NotNull Logger get(final @NotNull Class<?> clazz) {
		return LogManager.getLogger(clazz, FACTORY);
	}
}

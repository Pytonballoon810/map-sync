package gjum.minecraft.mapsync.mod.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.AbstractMessageFactory;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.MessageFactory2;
import org.apache.logging.log4j.message.ObjectMessage;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.jetbrains.annotations.NotNull;

/// Factory for server-side Log4j loggers whose every emitted message is
/// transparently prefixed with `[mapsync] `. Spares each call site from
/// having to remember the prefix and keeps the dedicated-server console
/// scannable when MapSync logs mix with the rest of MC's startup chatter.
///
/// The factory implements [MessageFactory2] (via [AbstractMessageFactory])
/// rather than the older [org.apache.logging.log4j.message.MessageFactory]
/// — Log4j auto-wraps plain [MessageFactory] implementations in a
/// [org.apache.logging.log4j.message.MessageFactory2Adapter], which then
/// trips an `ERROR` warning in Log4j's status logger every time a logger
/// is first created (the wrapped instance and the raw instance aren't
/// equal under the message-factory comparison). Implementing the v2
/// interface directly avoids the wrapping.
///
/// Server-side classes that previously held an SLF4J logger are migrated
/// to Log4j on this helper. The captured-log appender
/// ({@link gjum.minecraft.mapsync.mod.utils.MapSyncLogCapture}) catches
/// both API paths because Minecraft routes SLF4J through Log4j internally,
/// but the MessageFactory hook only fires for Log4j call sites — hence
/// the migration is part of the prefix story rather than a separate
/// cleanup.
public final class MsServerLog {
	private static final @NotNull String PREFIX = "[mapsync] ";

	private static final @NotNull MessageFactory2 FACTORY = new AbstractMessageFactory() {
		@Override
		public @NotNull Message newMessage(final CharSequence charSequence) {
			return new SimpleMessage(PREFIX + charSequence);
		}

		@Override
		public @NotNull Message newMessage(final Object object) {
			return new ObjectMessage(PREFIX + object);
		}

		@Override
		public @NotNull Message newMessage(final String message) {
			return new ParameterizedMessage(PREFIX + message);
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

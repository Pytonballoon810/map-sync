package com.pytonballoon810.mapsync.mod.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.pytonballoon810.mapsync.mod.server.MapSyncServerState;
import com.pytonballoon810.mapsync.mod.server.config.Whitelist;
import com.pytonballoon810.mapsync.mod.server.net.MapSyncWsServer;
import com.pytonballoon810.mapsync.mod.server.net.WsServerClient;
import com.pytonballoon810.mapsync.mod.server.net.auth.ServerAuthState;
import com.pytonballoon810.mapsync.mod.server.scan.WorldChunkCapture;
import com.pytonballoon810.mapsync.mod.server.scan.WorldRegionScanner;
import com.pytonballoon810.mapsync.mod.utils.MapSyncLogCapture;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.permissions.Permissions;
import org.jetbrains.annotations.NotNull;

/// Implements `/mapsync` subcommands available to operators on the dedicated
/// server. Today: status + whitelist add/remove/list/reload. List- and kick-
/// connected-clients are deferred until Phase 2E adds the websocket server.
public final class MapSyncCommand {
	private static final SimpleCommandExceptionType NO_STATE =
		new SimpleCommandExceptionType(text("MapSync state is not loaded yet", ChatFormatting.RED));
	private static final SimpleCommandExceptionType UNKNOWN_PLAYER =
		new SimpleCommandExceptionType(text("No UUID known for that name (player has not joined yet)", ChatFormatting.RED));
	private static final SimpleCommandExceptionType NO_WS_SERVER =
		new SimpleCommandExceptionType(text("MapSync websocket server is not running", ChatFormatting.RED));
	private static final SimpleCommandExceptionType UNKNOWN_CLIENT =
		new SimpleCommandExceptionType(text("No connected MapSync client with that id", ChatFormatting.RED));

	private MapSyncCommand() {
	}

	public static void register(
		final @NotNull CommandDispatcher<CommandSourceStack> dispatcher,
		@SuppressWarnings("unused") final @NotNull CommandBuildContext buildContext,
		@SuppressWarnings("unused") final @NotNull Commands.CommandSelection environment
	) {
		dispatcher.register(
			Commands.literal("mapsync")
				.requires((src) -> src.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
				.then(Commands.literal("status")
					.executes(MapSyncCommand::handleStatus))
				.then(Commands.literal("whitelist")
					.then(Commands.literal("list")
						.executes(MapSyncCommand::handleWhitelistList))
					.then(Commands.literal("add")
						.then(Commands.argument("player", StringArgumentType.word())
							.executes(MapSyncCommand::handleWhitelistAdd)))
					.then(Commands.literal("remove")
						.then(Commands.argument("player", StringArgumentType.word())
							.executes(MapSyncCommand::handleWhitelistRemove)))
					.then(Commands.literal("reload")
						.executes(MapSyncCommand::handleWhitelistReload)))
				.then(Commands.literal("clients")
					.then(Commands.literal("list")
						.executes(MapSyncCommand::handleClientsList))
					.then(Commands.literal("kick")
						.then(Commands.argument("id", LongArgumentType.longArg(1))
							.executes(MapSyncCommand::handleClientsKick))))
				.then(Commands.literal("logs")
					.executes((ctx) -> handleLogs(ctx, 20))
					.then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
						.executes((ctx) -> handleLogs(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
				.then(Commands.literal("rescan")
					.executes(MapSyncCommand::handleRescan))
				.then(Commands.literal("diagnose")
					.executes(MapSyncCommand::handleDiagnose))
		);
	}

	private static int handleDiagnose(
		final @NotNull CommandContext<CommandSourceStack> ctx
	) throws CommandSyntaxException {
		final MapSyncServerState state = requireState();
		final CommandSourceStack src = ctx.getSource();
		final StringBuilder report = new StringBuilder(8192);
		final java.time.format.DateTimeFormatter ts =
			java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
				.withZone(java.time.ZoneId.systemDefault());
		report.append("=== MapSync diagnostic report ").append(ts.format(java.time.Instant.now())).append(" ===\n\n");

		report.append("[config]\n");
		report.append("  data dir: ").append(state.dataDir()).append('\n');
		report.append("  host: ").append(state.config().host).append('\n');
		report.append("  port: ").append(state.config().port).append('\n');
		report.append("  auth: ").append(state.config().auth).append('\n');
		report.append("  whitelist: ").append(state.config().whitelist).append('\n');
		report.append("  advertisedHost: '").append(state.config().advertisedHost).append("'\n");
		report.append("  mapsync version: ").append(com.pytonballoon810.mapsync.mod.utils.MagicValues.VERSION).append('\n');
		report.append("  whitelist entries: ").append(state.whitelist().size()).append('\n');
		report.append("  uuid cache entries: ").append(state.uuidCache().size()).append("\n\n");

		final MapSyncWsServer ws = state.wsServer();
		report.append("[websocket]\n");
		if (ws == null) {
			report.append("  not started\n\n");
		}
		else {
			report.append("  listening on: ").append(ws.getAddress()).append('\n');
			report.append("  active clients: ").append(ws.activeClients().size()).append("\n\n");
		}

		final WorldChunkCapture capture = state.chunkCapture();
		report.append("[chunk capture]\n");
		report.append("  captured: ").append(capture.capturedCount()).append('\n');
		report.append("  queue: ").append(capture.queueSize()).append(" / ").append(capture.queueCapacity()).append('\n');
		report.append("  dropped: ").append(capture.droppedCount()).append('\n');
		report.append("  failed: ").append(capture.failedCount()).append("\n\n");

		final WorldRegionScanner scanner = state.regionScanner();
		report.append("[region scan]\n");
		if (scanner == null) {
			report.append("  not initialised\n\n");
		}
		else {
			report.append("  status: ").append(scanner.status()).append("\n\n");
		}

		report.append("[connected clients]\n");
		if (ws == null || ws.activeClients().isEmpty()) {
			report.append("  (none)\n\n");
		}
		else {
			for (final WsServerClient c : ws.activeClients()) {
				report.append("  #").append(c.id).append(' ').append(c.auth.logName()).append('\n');
				report.append("    auth state: ").append(c.auth.getClass().getSimpleName()).append('\n');
				if (c.auth instanceof final ServerAuthState.Welcomed w) {
					report.append("    uuid: ").append(w.uuid()).append(" authed=").append(w.authed()).append('\n');
				}
				report.append("    dim: ").append(c.dimension != null ? c.dimension : "—").append('\n');
				report.append("    via: ").append(c.gameAddress != null ? c.gameAddress : "—").append('\n');
				report.append("    packets: in=").append(c.packetsReceived.get())
					.append(" out=").append(c.packetsSent.get()).append('\n');
				report.append("    chunk tiles: in=").append(c.chunkTilesReceived.get())
					.append(" out=").append(c.chunkTilesSentToClient.get()).append('\n');
				report.append("    catchup batches fulfilled: ").append(c.catchupBatchesFulfilled.get()).append('\n');
			}
			report.append('\n');
		}

		report.append("[recent log capture (last 50 entries)]\n");
		final List<MapSyncLogCapture.Entry> tail = MapSyncLogCapture.tail(50);
		if (tail.isEmpty()) {
			report.append("  (none)\n");
		}
		else {
			for (final MapSyncLogCapture.Entry e : tail) {
				for (final String line : e.formatted().split("\\r?\\n")) {
					report.append("  ").append(line).append('\n');
				}
			}
		}

		final String filename = "diagnose-"
			+ java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
				.withZone(java.time.ZoneId.systemDefault())
				.format(java.time.Instant.now())
			+ ".txt";
		final java.nio.file.Path outPath = state.dataDir().resolve(filename);
		try {
			java.nio.file.Files.createDirectories(outPath.getParent());
			java.nio.file.Files.writeString(outPath, report.toString());
		}
		catch (final Exception e) {
			src.sendFailure(text("Failed to write diagnostic file: " + e.getMessage(), ChatFormatting.RED));
			return 0;
		}

		src.sendSuccess(() -> header("MapSync diagnose"), false);
		src.sendSuccess(() -> kv("report saved", outPath.toString()), false);
		src.sendSuccess(() -> kv("captured chunks", Long.toString(capture.capturedCount())), false);
		src.sendSuccess(() -> kv("scan", scanner == null ? "n/a" : scanner.status().toString()), false);
		src.sendSuccess(() -> kv("active clients", Integer.toString(ws == null ? 0 : ws.activeClients().size())), false);
		src.sendSuccess(() -> muted("Paste the .txt file contents to share the full report."), false);
		return 1;
	}

	private static int handleRescan(
		final @NotNull CommandContext<CommandSourceStack> ctx
	) throws CommandSyntaxException {
		final MapSyncServerState state = requireState();
		final CommandSourceStack src = ctx.getSource();
		final int removed;
		try {
			removed = state.restartRegionScan(src.getServer());
		}
		catch (final Exception e) {
			src.sendFailure(text("Failed to restart region scan: " + e.getMessage(), ChatFormatting.RED));
			return 0;
		}
		src.sendSuccess(() -> text("Region scan restarted ", ChatFormatting.GREEN)
			.append(text("(cleared " + removed + " marker(s)). Watch /mapsync status for progress.",
				ChatFormatting.GRAY)), true);
		return 1;
	}

	private static @NotNull MapSyncWsServer requireWsServer() throws CommandSyntaxException {
		final MapSyncServerState state = requireState();
		final MapSyncWsServer ws = state.wsServer();
		if (ws == null) {
			throw NO_WS_SERVER.create();
		}
		return ws;
	}

	private static @NotNull MapSyncServerState requireState() throws CommandSyntaxException {
		final MapSyncServerState state = MapSyncServerState.current();
		if (state == null) {
			throw NO_STATE.create();
		}
		return state;
	}

	private static @NotNull UUID resolvePlayer(
		final @NotNull MapSyncServerState state,
		final @NotNull String input
	) throws CommandSyntaxException {
		try {
			return UUID.fromString(input);
		}
		catch (final IllegalArgumentException ignored) {
		}
		final UUID cached = state.uuidCache().lookup(input);
		if (cached == null) {
			throw UNKNOWN_PLAYER.create();
		}
		return cached;
	}

	private static int handleStatus(
		final @NotNull CommandContext<CommandSourceStack> ctx
	) throws CommandSyntaxException {
		final MapSyncServerState state = requireState();
		final CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> header("MapSync status"), false);
		src.sendSuccess(() -> kv("data dir", state.dataDir().toString()), false);
		src.sendSuccess(() -> kv("port", Integer.toString(state.config().port)), false);
		src.sendSuccess(() -> kv("auth", state.config().auth).append(
			text("  ", ChatFormatting.DARK_GRAY)
		).append(kv("whitelist", state.config().whitelist)), false);
		src.sendSuccess(() -> kv("whitelist entries", Integer.toString(state.whitelist().size())), false);
		src.sendSuccess(() -> kv("uuid cache entries", Integer.toString(state.uuidCache().size())), false);
		final MapSyncWsServer ws = state.wsServer();
		if (ws != null) {
			final int clientCount = ws.activeClients().size();
			src.sendSuccess(() -> text("websocket: ", ChatFormatting.AQUA)
				.append(text("listening on " + ws.getAddress(), ChatFormatting.GREEN))
				.append(text("  (" + clientCount + " connected)", ChatFormatting.GRAY)), false);
		}
		else {
			src.sendSuccess(() -> text("websocket: ", ChatFormatting.AQUA)
				.append(text("not started", ChatFormatting.YELLOW)), false);
		}

		final WorldChunkCapture capture = state.chunkCapture();
		src.sendSuccess(() -> text("chunk capture: ", ChatFormatting.AQUA)
			.append(text(capture.capturedCount() + " stored", ChatFormatting.WHITE))
			.append(text("  (queue=" + capture.queueSize()
				+ ", dropped=" + capture.droppedCount()
				+ ", failed=" + capture.failedCount() + ")", ChatFormatting.GRAY)), false);

		final WorldRegionScanner scanner = state.regionScanner();
		if (scanner != null) {
			final WorldRegionScanner.Status scanStatus = scanner.status();
			final MutableComponent scanLine = text("region scan: ", ChatFormatting.AQUA);
			switch (scanStatus) {
				case final WorldRegionScanner.Status.Idle $ ->
					scanLine.append(text("not yet started", ChatFormatting.GRAY));
				case final WorldRegionScanner.Status.Scanning s ->
					scanLine.append(text(s.chunksRequested() + " requested, in " + s.dimension(), ChatFormatting.YELLOW));
				case final WorldRegionScanner.Status.Done d ->
					scanLine.append(text("done (" + d.chunksRequested() + " requested)", ChatFormatting.GREEN));
				case final WorldRegionScanner.Status.Failed f ->
					scanLine.append(text("failed: " + f.reason(), ChatFormatting.RED));
			}
			src.sendSuccess(() -> scanLine, false);
		}
		return 1;
	}

	private static int handleClientsList(
		final @NotNull CommandContext<CommandSourceStack> ctx
	) throws CommandSyntaxException {
		final MapSyncWsServer ws = requireWsServer();
		final List<WsServerClient> clients = ws.activeClients().stream()
			.sorted(Comparator.comparingLong((WsServerClient c) -> c.id))
			.toList();
		final CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> header("MapSync clients")
			.append(text(" (" + clients.size() + ")", ChatFormatting.GRAY)), false);
		if (clients.isEmpty()) {
			src.sendSuccess(() -> text("  (none connected)", ChatFormatting.DARK_GRAY), false);
			return 0;
		}
		for (final WsServerClient client : clients) {
			final String authLabel;
			final ChatFormatting authColor;
			switch (client.auth) {
				case final ServerAuthState.Welcomed welcomed -> {
					authLabel = welcomed.name() + (welcomed.authed() ? " (authed)" : " (offline)");
					authColor = welcomed.authed() ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
				}
				case final ServerAuthState.AwaitingIdentityResponse $ -> {
					authLabel = "awaiting identity";
					authColor = ChatFormatting.YELLOW;
				}
				default -> {
					authLabel = "pre-handshake";
					authColor = ChatFormatting.GRAY;
				}
			}
			final String dim = client.dimension != null ? client.dimension.toString() : "—";
			final String addr = client.gameAddress != null ? client.gameAddress : "—";
			src.sendSuccess(() -> text("  #" + client.id + " ", ChatFormatting.DARK_GRAY)
				.append(text(authLabel, authColor))
				.append(text("  dim=", ChatFormatting.DARK_GRAY))
				.append(text(dim, ChatFormatting.WHITE))
				.append(text("  via=", ChatFormatting.DARK_GRAY))
				.append(text(addr, ChatFormatting.WHITE)), false);
			src.sendSuccess(() -> text("     packets ", ChatFormatting.DARK_GRAY)
				.append(text("in=" + client.packetsReceived.get()
					+ " out=" + client.packetsSent.get()
					+ "  chunks in=" + client.chunkTilesReceived.get()
					+ " out=" + client.chunkTilesSentToClient.get()
					+ "  catchup-batches=" + client.catchupBatchesFulfilled.get(),
					ChatFormatting.GRAY)), false);
		}
		return clients.size();
	}

	private static int handleLogs(
		final @NotNull CommandContext<CommandSourceStack> ctx,
		final int count
	) {
		final List<MapSyncLogCapture.Entry> entries = MapSyncLogCapture.tail(count);
		final CommandSourceStack src = ctx.getSource();
		if (entries.isEmpty()) {
			src.sendSuccess(() -> text("No MapSync log entries captured yet", ChatFormatting.YELLOW), false);
			return 0;
		}
		src.sendSuccess(() -> header("MapSync log")
			.append(text(" (last " + entries.size() + " of " + MapSyncLogCapture.size() + ")", ChatFormatting.GRAY)), false);
		for (final MapSyncLogCapture.Entry entry : entries) {
			final ChatFormatting color = switch (entry.level()) {
				case "ERROR", "FATAL" -> ChatFormatting.RED;
				case "WARN" -> ChatFormatting.YELLOW;
				case "DEBUG", "TRACE" -> ChatFormatting.DARK_GRAY;
				default -> ChatFormatting.WHITE;
			};
			final String line = entry.formatted();
			// Chat lines have a soft length cap — split overlong entries by
			// natural newlines (stack traces). Each fragment renders as its
			// own chat row so the operator can still read the whole thing.
			for (final String fragment : line.split("\\r?\\n")) {
				if (fragment.isEmpty()) continue;
				src.sendSuccess(() -> text(fragment, color), false);
			}
		}
		src.sendSuccess(() -> muted("(use /mapsync logs <1-200> to fetch a different size)"), false);
		return entries.size();
	}

	private static int handleClientsKick(
		final @NotNull CommandContext<CommandSourceStack> ctx
	) throws CommandSyntaxException {
		final MapSyncWsServer ws = requireWsServer();
		final long targetId = LongArgumentType.getLong(ctx, "id");
		final WsServerClient target = ws.activeClients().stream()
			.filter((c) -> c.id == targetId)
			.findFirst()
			.orElseThrow(UNKNOWN_CLIENT::create);
		target.kick("operator-issued /mapsync clients kick");
		ctx.getSource().sendSuccess(() -> text("Kicked client #" + targetId, ChatFormatting.GREEN), true);
		return 1;
	}

	private static int handleWhitelistList(
		final @NotNull CommandContext<CommandSourceStack> ctx
	) throws CommandSyntaxException {
		final MapSyncServerState state = requireState();
		final Map<UUID, String> names = state.uuidCache().namesByUuid();
		final List<UUID> entries = state.whitelist().sortedSnapshot(
			(uuid) -> names.getOrDefault(uuid, uuid.toString())
		);
		final CommandSourceStack src = ctx.getSource();
		src.sendSuccess(() -> header("MapSync whitelist")
			.append(text(" (" + entries.size() + ")", ChatFormatting.GRAY)), false);
		if (entries.isEmpty()) {
			src.sendSuccess(() -> text("  (empty — only ops + MC-whitelist sync in)", ChatFormatting.DARK_GRAY), false);
		}
		else {
			for (final UUID uuid : entries) {
				final String name = names.get(uuid);
				final MutableComponent line = text("  • ", ChatFormatting.DARK_GRAY);
				if (name != null) {
					line.append(text(name, ChatFormatting.WHITE))
						.append(text("  " + uuid, ChatFormatting.DARK_GRAY));
				}
				else {
					line.append(text(uuid.toString(), ChatFormatting.GRAY))
						.append(text("  (no cached name)", ChatFormatting.DARK_GRAY));
				}
				src.sendSuccess(() -> line, false);
			}
		}
		final Path file = state.dataDir().resolve("whitelist.json");
		src.sendSuccess(() -> muted("source: " + file), false);
		return entries.size();
	}

	private static int handleWhitelistAdd(
		final @NotNull CommandContext<CommandSourceStack> ctx
	) throws CommandSyntaxException {
		final MapSyncServerState state = requireState();
		final String input = StringArgumentType.getString(ctx, "player");
		final UUID uuid = resolvePlayer(state, input);
		final CommandSourceStack src = ctx.getSource();
		final boolean added = state.whitelist().add(uuid);
		if (!added) {
			src.sendSuccess(() -> text("Already whitelisted: ", ChatFormatting.YELLOW)
				.append(text(uuid.toString(), ChatFormatting.WHITE)), false);
			return 0;
		}
		try {
			state.whitelist().save(state.dataDir().resolve("whitelist.json"));
		}
		catch (final Exception e) {
			src.sendFailure(text("Whitelist saved in memory but failed to persist: " + e.getMessage(), ChatFormatting.RED));
			return 0;
		}
		final String name = state.uuidCache().namesByUuid().get(uuid);
		src.sendSuccess(() -> text("Whitelisted ", ChatFormatting.GREEN)
			.append(text(name != null ? name : uuid.toString(), ChatFormatting.WHITE))
			.append(name != null ? text("  " + uuid, ChatFormatting.DARK_GRAY) : Component.empty()), true);
		return 1;
	}

	private static int handleWhitelistRemove(
		final @NotNull CommandContext<CommandSourceStack> ctx
	) throws CommandSyntaxException {
		final MapSyncServerState state = requireState();
		final String input = StringArgumentType.getString(ctx, "player");
		final UUID uuid = resolvePlayer(state, input);
		final CommandSourceStack src = ctx.getSource();
		final boolean removed = state.whitelist().remove(uuid);
		if (!removed) {
			src.sendSuccess(() -> text("Was not on the whitelist: ", ChatFormatting.YELLOW)
				.append(text(uuid.toString(), ChatFormatting.WHITE)), false);
			return 0;
		}
		try {
			state.whitelist().save(state.dataDir().resolve("whitelist.json"));
		}
		catch (final Exception e) {
			src.sendFailure(text("Whitelist updated in memory but failed to persist: " + e.getMessage(), ChatFormatting.RED));
			return 0;
		}
		final String name = state.uuidCache().namesByUuid().get(uuid);
		src.sendSuccess(() -> text("Removed from whitelist: ", ChatFormatting.GREEN)
			.append(text(name != null ? name : uuid.toString(), ChatFormatting.WHITE))
			.append(name != null ? text("  " + uuid, ChatFormatting.DARK_GRAY) : Component.empty()), true);
		return 1;
	}

	private static int handleWhitelistReload(
		final @NotNull CommandContext<CommandSourceStack> ctx
	) throws CommandSyntaxException {
		final MapSyncServerState state = requireState();
		final CommandSourceStack src = ctx.getSource();
		try {
			final Path file = state.dataDir().resolve("whitelist.json");
			final Whitelist reloaded = Whitelist.loadOrCreate(file);
			state.whitelist().replaceAll(reloaded.snapshot());
			state.importMinecraftAllowlist(src.getServer());
			final int size = state.whitelist().size();
			src.sendSuccess(() -> text("Reloaded MapSync whitelist ", ChatFormatting.GREEN)
				.append(text("(" + size + " entries)", ChatFormatting.GRAY)), true);
			return size;
		}
		catch (final Exception e) {
			src.sendFailure(text("Reload failed: " + e.getMessage(), ChatFormatting.RED));
			return 0;
		}
	}

	// ============================================================
	// Formatting helpers
	// ============================================================

	private static @NotNull MutableComponent text(
		final @NotNull String value,
		final @NotNull ChatFormatting color
	) {
		return Component.literal(value).withStyle(color);
	}

	private static @NotNull MutableComponent header(
		final @NotNull String label
	) {
		return text("[MapSync] ", ChatFormatting.GOLD).append(text(label, ChatFormatting.YELLOW));
	}

	private static @NotNull MutableComponent kv(
		final @NotNull String key,
		final @NotNull String value
	) {
		return text(key + ": ", ChatFormatting.AQUA).append(text(value, ChatFormatting.WHITE));
	}

	private static @NotNull MutableComponent kv(
		final @NotNull String key,
		final boolean value
	) {
		return text(key + ": ", ChatFormatting.AQUA)
			.append(text(Boolean.toString(value), value ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	private static @NotNull MutableComponent muted(
		final @NotNull String value
	) {
		return text(value, ChatFormatting.DARK_GRAY);
	}
}

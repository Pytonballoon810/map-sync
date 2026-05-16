package com.pytonballoon810.mapsync.mod.server;

import com.mojang.authlib.GameProfile;
import com.pytonballoon810.mapsync.mod.server.config.MapSyncConfig;
import com.pytonballoon810.mapsync.mod.server.config.UuidCache;
import com.pytonballoon810.mapsync.mod.server.config.Whitelist;
import com.pytonballoon810.mapsync.mod.server.db.MapSyncDatabase;
import com.pytonballoon810.mapsync.mod.server.net.MapSyncWsServer;
import com.pytonballoon810.mapsync.mod.server.net.ProtocolHandler;
import com.pytonballoon810.mapsync.mod.server.scan.WorldChunkCapture;
import com.pytonballoon810.mapsync.mod.server.scan.WorldRegionScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.world.level.storage.LevelResource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Lifecycle-bound holder for the bundled server's live state: config,
/// whitelist, UUID cache, and database connection. One instance per
/// running Minecraft server; opened on `SERVER_STARTING` and closed on
/// `SERVER_STOPPING` by {@link MapSyncServerMod}. The future websocket
/// server (Phase 2E) reads from {@link #current()} to share the same
/// instances.
///
/// Data lives in `<world>/mapsync/` so a server with multiple worlds — or
/// a server that swaps worlds — gets a fresh sync database per world.
public final class MapSyncServerState implements AutoCloseable {
	private static final MsServerLog logger = MsServerLog.get(MapSyncServerState.class);
	private static volatile @Nullable MapSyncServerState instance;

	private final @NotNull Path dataDir;
	private final @NotNull MapSyncConfig config;
	private final @NotNull Whitelist whitelist;
	private final @NotNull UuidCache uuidCache;
	private final @NotNull MapSyncDatabase database;
	private final @NotNull ProtocolHandler protocolHandler;
	private final @NotNull WorldChunkCapture chunkCapture;
	private volatile @Nullable MapSyncWsServer wsServer;
	private volatile @Nullable WorldRegionScanner regionScanner;

	private MapSyncServerState(
		final @NotNull Path dataDir,
		final @NotNull MapSyncConfig config,
		final @NotNull Whitelist whitelist,
		final @NotNull UuidCache uuidCache,
		final @NotNull MapSyncDatabase database
	) {
		this.dataDir = dataDir;
		this.config = config;
		this.whitelist = whitelist;
		this.uuidCache = uuidCache;
		this.database = database;
		this.protocolHandler = new ProtocolHandler(this);
		this.chunkCapture = new WorldChunkCapture(this);
	}

	public static @NotNull MapSyncServerState open(
		final @NotNull MinecraftServer server
	) throws Exception {
		final Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("mapsync");
		Files.createDirectories(dataDir);
		final MapSyncConfig config = MapSyncConfig.loadOrCreate(dataDir.resolve("config.json"));
		final Whitelist whitelist = Whitelist.loadOrCreate(dataDir.resolve("whitelist.json"));
		final UuidCache uuidCache = UuidCache.loadOrCreate(dataDir.resolve("uuid_cache.json"));
		final MapSyncDatabase database = MapSyncDatabase.openFile(dataDir.resolve("db.sqlite"));
		return new MapSyncServerState(dataDir, config, whitelist, uuidCache, database);
	}

	@Override
	public void close() throws Exception {
		final WorldRegionScanner scanner = this.regionScanner;
		if (scanner != null) {
			// Daemon thread; just signal — it'll exit when the JVM does.
			this.regionScanner = null;
		}
		this.chunkCapture.close();
		final MapSyncWsServer running = this.wsServer;
		if (running != null) {
			try {
				running.stop(2000);
				logger.info("MapSync websocket server stopped");
			}
			catch (final Exception e) {
				logger.warn("Failed to stop MapSync websocket server cleanly", e);
			}
			this.wsServer = null;
		}
		this.protocolHandler.shutdown();
		this.database.close();
	}

	/// Constructs and starts the websocket server. Called from
	/// SERVER_STARTED so the player list and whitelist are already populated
	/// — the first authenticating client must be checked against a complete
	/// whitelist or it gets rejected for no reason.
	public void startWebsocket() {
		if (this.wsServer != null) {
			throw new IllegalStateException("websocket server already started");
		}
		final MapSyncWsServer ws = new MapSyncWsServer(this, this.protocolHandler);
		this.protocolHandler.install(ws);
		ws.start();
		this.wsServer = ws;
	}

	public @Nullable MapSyncWsServer wsServer() {
		return this.wsServer;
	}

	public @NotNull ProtocolHandler protocolHandler() {
		return this.protocolHandler;
	}

	public @NotNull WorldChunkCapture chunkCapture() {
		return this.chunkCapture;
	}

	public @Nullable WorldRegionScanner regionScanner() {
		return this.regionScanner;
	}

	/// Arms the CHUNK_LOAD capture hook and (on first run per dimension)
	/// kicks off the background region-file scanner. Called from
	/// SERVER_STARTED — by then the server has all dimensions constructed
	/// and the chunk pipeline is alive enough to accept getChunk calls.
	public void startWorldCapture(final @NotNull MinecraftServer server) {
		this.chunkCapture.start();
		final WorldRegionScanner scanner = new WorldRegionScanner(server, this);
		this.regionScanner = scanner;
		scanner.start();
	}

	public @NotNull Path dataDir() {
		return this.dataDir;
	}

	public @NotNull MapSyncConfig config() {
		return this.config;
	}

	public @NotNull Whitelist whitelist() {
		return this.whitelist;
	}

	public @NotNull UuidCache uuidCache() {
		return this.uuidCache;
	}

	public @NotNull MapSyncDatabase database() {
		return this.database;
	}

	/// Folds the Minecraft server's whitelist and ops list into MapSync's
	/// whitelist so operators don't manage two parallel allowlists. Called
	/// at SERVER_STARTING and again on every player join to pick up live
	/// `/whitelist add` and `/op` changes without a restart. New additions
	/// trigger a save; if nothing changed, no IO happens.
	public void importMinecraftAllowlist(
		final @NotNull MinecraftServer server
	) throws Exception {
		final PlayerList players = server.getPlayerList();
		int added = 0;
		for (final UserWhiteListEntry entry : players.getWhiteList().getEntries()) {
			final NameAndId user = entry.getUser();
			if (user != null && this.whitelist.add(user.id())) {
				added++;
				logger.info("Auto-whitelisted MC-whitelisted player {} ({})", user.name(), user.id());
			}
		}
		for (final ServerOpListEntry entry : players.getOps().getEntries()) {
			final NameAndId user = entry.getUser();
			if (user != null && this.whitelist.add(user.id())) {
				added++;
				logger.info("Auto-whitelisted operator {} ({})", user.name(), user.id());
			}
		}
		if (added > 0) {
			this.whitelist.save(this.dataDir.resolve("whitelist.json"));
		}
	}

	/// Records an IGN→UUID mapping learned from a player joining the
	/// Minecraft server. Lets operators later run `/mapsync whitelist add
	/// <name>` for someone who's already played here even if MapSync's
	/// websocket has never seen them.
	public void cachePlayerProfile(
		final @NotNull GameProfile profile
	) throws Exception {
		final UUID before = this.uuidCache.lookup(profile.name());
		if (profile.id().equals(before)) {
			return;
		}
		this.uuidCache.put(profile.name(), profile.id());
		this.uuidCache.save(this.dataDir.resolve("uuid_cache.json"));
	}

	public static @Nullable MapSyncServerState current() {
		return instance;
	}

	static void install(final @NotNull MapSyncServerState state) {
		instance = state;
	}

	static void uninstall() {
		instance = null;
	}
}

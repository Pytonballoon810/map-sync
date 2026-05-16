package com.pytonballoon810.mapsync.mod.integrations.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.pytonballoon810.mapsync.mod.config.gui.SyncConnectionsGui;
import com.pytonballoon810.mapsync.mod.sync.GameContext;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.NotNull;

/**
 * Adds support for https://github.com/TerraformersMC/ModMenu (Fabric only)
 */
public class ModMenuIntegration implements ModMenuApi {
	public @NotNull ConfigScreenFactory<Screen> getModConfigScreenFactory() {
		return (previousScreen) -> GameContext.get()
			.map((gameContext) -> new SyncConnectionsGui(previousScreen, gameContext))
			.orElse(null);
	}
}

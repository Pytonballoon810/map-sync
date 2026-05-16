package com.pytonballoon810.mapsync.mod.config.gui;

import com.pytonballoon810.mapsync.mod.utils.MapSyncLogCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/// Read-only-ish log viewer. Populates a MultiLineEditBox with whatever
/// {@link MapSyncLogCapture#dump()} returns at open time. The buffer
/// keeps growing in the background even while this screen is open, so a
/// Refresh button re-pulls the latest snapshot, and a Copy button drops
/// the visible text into the system clipboard for pasting into a bug
/// report.
///
/// The widget itself is technically editable — there's no `setEditable`
/// on MultiLineEditBox in 26.1.2 — but any local edits are throwaway,
/// they never write back to the capture buffer, and Refresh discards
/// them in one click.
public final class MapSyncLogScreen extends Screen {
	private final @Nullable Screen parentScreen;
	private @Nullable MultiLineEditBox logView;

	public MapSyncLogScreen(
		final @Nullable Screen parentScreen
	) {
		super(Component.literal("MapSync Log"));
		this.parentScreen = parentScreen;
	}

	@Override
	protected void init() {
		final int margin = 20;
		final int buttonRowHeight = 30;
		final int width = this.width - margin * 2;
		final int viewerY = 30;
		final int viewerHeight = this.height - viewerY - margin - buttonRowHeight;

		this.logView = MultiLineEditBox.builder()
			.setX(margin)
			.setY(viewerY)
			.setPlaceholder(Component.literal("(no MapSync log entries yet)"))
			.setShowBackground(true)
			.setShowDecorations(true)
			.build(this.font, width, viewerHeight, Component.literal("MapSync log"));
		this.logView.setCharacterLimit(Integer.MAX_VALUE);
		this.logView.setLineLimit(Integer.MAX_VALUE);
		this.logView.setValue(MapSyncLogCapture.dump());
		this.addRenderableWidget(this.logView);

		final int buttonY = this.height - margin - 20;
		final int buttonWidth = 100;
		final int buttonGap = 8;
		final int totalButtonsWidth = buttonWidth * 3 + buttonGap * 2;
		int buttonX = this.width / 2 - totalButtonsWidth / 2;

		this.addRenderableWidget(
			Button.builder(
					Component.literal("Refresh"),
					(button) -> {
						if (this.logView != null) {
							this.logView.setValue(MapSyncLogCapture.dump());
						}
					}
				)
				.pos(buttonX, buttonY)
				.width(buttonWidth)
				.build()
		);
		buttonX += buttonWidth + buttonGap;

		this.addRenderableWidget(
			Button.builder(
					Component.literal("Copy to clipboard"),
					(button) -> Minecraft.getInstance()
						.keyboardHandler
						.setClipboard(MapSyncLogCapture.dump())
				)
				.pos(buttonX, buttonY)
				.width(buttonWidth)
				.build()
		);
		buttonX += buttonWidth + buttonGap;

		this.addRenderableWidget(
			Button.builder(CommonComponents.GUI_BACK, (button) -> this.onClose())
				.pos(buttonX, buttonY)
				.width(buttonWidth)
				.build()
		);
	}

	@Override
	public void extractRenderState(
		final @NotNull GuiGraphicsExtractor guiGraphics,
		final int mouseX,
		final int mouseY,
		final float partialTick
	) {
		super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFF_FF_FF_FF);
		final int captured = MapSyncLogCapture.size();
		guiGraphics.centeredText(
			this.font,
			Component.literal("(" + captured + " entries captured)"),
			this.width / 2,
			this.height - 12,
			0x88_88_88_88
		);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(this.parentScreen);
	}
}

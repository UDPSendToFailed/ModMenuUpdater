package com.udpsendtofailed.modmenu.updater.mixin;

import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.config.ModMenuConfig;
import com.terraformersmc.modmenu.gui.ModsScreen;
import com.terraformersmc.modmenu.gui.widget.DescriptionListWidget;
import com.terraformersmc.modmenu.gui.widget.entries.ModListEntry;
import com.udpsendtofailed.modmenu.updater.ModUpdaterService;
import com.udpsendtofailed.modmenu.updater.api.ModExtension;
import com.udpsendtofailed.modmenu.updater.api.UpdateInfoExtension;
import com.terraformersmc.modmenu.util.mod.Mod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModsScreen.class)
public abstract class MixinModsScreen extends Screen {
    @Shadow private ModListEntry selected;
    @Shadow private int paneWidth;
    @Shadow private int rightPaneX;
    @Shadow private EditBox searchBox;
    @Shadow private SpriteIconButton filtersButton;
    @Shadow private int searchBoxX; 
    @Shadow private int filtersX; 
    @Shadow private int searchRowWidth;
    @Shadow private DescriptionListWidget descriptionListWidget;
    @Shadow private SpriteIconButton configureButton;

    @Unique private static final Component UPDATE_ALL_TEXT = Component.translatable("modmenu.update.button.updateAll");
    @Unique private static final Component UPDATE_TEXT = Component.translatable("modmenu.update.button.update");
    @Unique private static final Component UPDATING_TEXT = Component.translatable("modmenu.update.state.updating");
    @Unique private static final Component UPDATED_TEXT = Component.translatable("modmenu.update.state.updated");

    @Unique private ModUpdaterService updater;
    @Unique private AbstractWidget updateButton;
    @Unique private AbstractWidget updateAllButton;
    @Unique private int cachedIssuesRightAlign;

    protected MixinModsScreen(Component title) { super(title); }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void initService(Screen previous, CallbackInfo ci) {
        this.updater = new ModUpdaterService();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void recalculateLayoutExact(CallbackInfo ci) {
        boolean updateChecksEnabled = ModMenuConfig.UPDATE_CHECKER.getValue();

        // --- 1. Manage Buttons ---
        
        // Update All Button
        if (updateChecksEnabled) {
            if (this.updateAllButton == null) {
                this.updateAllButton = Button.builder(UPDATE_ALL_TEXT, b -> updater.performUpdateAll())
                    .size(0, 20)
                    .build();
                this.updateAllButton.setTooltip(Tooltip.create(Component.translatable("modmenu.update.tooltip.all")));
            }
            // Always add it to the screen if enabled
            this.addRenderableWidget(this.updateAllButton);
        } else {
            // If disabled, clear the reference so it's recreated fresh if re-enabled
            this.updateAllButton = null;
        }

        // Update Button (Single)
        if (updateChecksEnabled) {
            if (this.updateButton == null) {
                this.updateButton = Button.builder(UPDATE_TEXT, b -> {
                    if (selected != null) {
                        updater.performUpdate(selected.getMod());
                        this.descriptionListWidget.updateSelectedMod(selected.getMod());
                    }
                })
                .size(0, 20)
                .build();
                this.updateButton.visible = false;
                this.updateButton.setTooltip(Tooltip.create(Component.translatable("modmenu.update.tooltip.single")));
            }
            // Always add it to the screen if enabled
            this.addRenderableWidget(this.updateButton);
        } else {
            this.updateButton = null;
        }

        // --- 2. PRECISE LAYOUT CALCULATION ---
        int updateAllButtonWidth = updateChecksEnabled
                ? Math.max(
                        this.font.width(UPDATE_ALL_TEXT),
                        this.font.width(Component.translatable("modmenu.update.button.updatingProgress", 999, 999))
                  ) + 20
                : 0;
        int updateAllGap = updateChecksEnabled ? 2 : 0;
        int filtersButtonSize = (ModMenuConfig.CONFIG_MODE.getValue() ? 0 : 22);

        int searchWidthMax = this.paneWidth - 32 - filtersButtonSize - updateAllButtonWidth - updateAllGap;
        int searchBoxWidth = ModMenuConfig.CONFIG_MODE.getValue() ? Math.min(200, searchWidthMax) : searchWidthMax;
        int totalRowWidth = searchBoxWidth + filtersButtonSize + updateAllGap + updateAllButtonWidth;
        
        this.searchBoxX = this.paneWidth / 2 - totalRowWidth / 2;
        this.searchRowWidth = this.searchBoxX + totalRowWidth + 22;

        if (this.searchBox != null) {
            this.searchBox.setX(this.searchBoxX);
            this.searchBox.setWidth(searchBoxWidth);
        }

        int currentX = this.searchBoxX + searchBoxWidth + 2;

        if (!ModMenuConfig.CONFIG_MODE.getValue() && this.filtersButton != null) {
            this.filtersButton.setX(currentX);
            this.filtersX = currentX; 
            currentX += 20 + updateAllGap;
        }

        if (this.updateAllButton != null && updateChecksEnabled) {
            this.updateAllButton.setX(currentX);
            this.updateAllButton.setY(22);
            this.updateAllButton.setWidth(updateAllButtonWidth);
        }

        if (this.updateButton != null && updateChecksEnabled) {
            int urlButtonWidths = this.paneWidth / 2 - 2;
            int cappedButtonWidth = Math.min(urlButtonWidths, 200);
            int issuesButtonX = this.rightPaneX + urlButtonWidths + 4 + (urlButtonWidths / 2) - (cappedButtonWidth / 2);
            this.cachedIssuesRightAlign = issuesButtonX + cappedButtonWidth;
            int updateButtonWidth = Math.max(
                    this.font.width(UPDATING_TEXT),
                    Math.max(this.font.width(UPDATED_TEXT), this.font.width(UPDATE_TEXT))
            ) + 10;
            
            this.updateButton.setWidth(updateButtonWidth);
            this.updateButton.setY(48);
            positionUpdateButton();
            
            // Re-apply configure button pos
            if (this.configureButton != null) {
                this.configureButton.setX(this.cachedIssuesRightAlign - 20);
            }
        }
    }

    @Unique
    private void positionUpdateButton() {
        boolean configVisible = this.configureButton != null && this.configureButton.visible;
        if (configVisible) {
            this.updateButton.setX(this.cachedIssuesRightAlign - 20 - this.updateButton.getWidth() - 2);
        } else {
            this.updateButton.setX(this.cachedIssuesRightAlign - this.updateButton.getWidth());
        }
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void updateButtonState(CallbackInfo ci) {
        if (updateAllButton != null) {
            if (ModUpdaterService.isBatchActive()) {
                updateAllButton.active = false;
                updateAllButton.setMessage(Component.translatable(
                        "modmenu.update.button.updatingProgress",
                        ModUpdaterService.getBatchCompleted(),
                        ModUpdaterService.getBatchTotal()));
            } else {
                if (!updateAllButton.getMessage().equals(UPDATE_ALL_TEXT)) {
                    updateAllButton.setMessage(UPDATE_ALL_TEXT);
                }
                boolean anyUpdates = ModMenu.MODS.values().stream().anyMatch(m ->
                    m instanceof ModExtension ext
                    && m.hasUpdate()
                    && !ext.isUpdateDownloaded()
                    && !ext.isDownloadingUpdate()
                    && m.getUpdateInfo() instanceof UpdateInfoExtension uie
                    && uie.getDownloadUrl() != null
                );
                updateAllButton.active = anyUpdates;
            }
        }

        if (selected != null) {
            Mod mod = selected.getMod();

            if (!(mod instanceof ModExtension ext)) {
                if (updateButton != null) updateButton.visible = false;
                return;
            }

            boolean hasUrl =
                mod.getUpdateInfo() instanceof UpdateInfoExtension uie
                && uie.getDownloadUrl() != null;
            
            if (updateButton != null) {
                updateButton.visible = hasUrl || ext.isUpdateDownloaded();
                positionUpdateButton();

                if (ext.isDownloadingUpdate()) {
                    updateButton.setMessage(UPDATING_TEXT);
                    updateButton.active = false;
                } else if (ext.isUpdateDownloaded()) {
                    if (updateButton.active || !updateButton.getMessage().equals(UPDATED_TEXT)) {
                        updateButton.setMessage(UPDATED_TEXT);
                        updateButton.active = false;
                        this.descriptionListWidget.updateSelectedMod(mod);
                    }
                } else {
                    updateButton.setMessage(UPDATE_TEXT);
                    updateButton.active = mod.hasUpdate();
                }
            }
        } else {
            if (updateButton != null) updateButton.visible = false;
        }
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void renderBatchProgress(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (this.updateAllButton != null && this.updateAllButton.visible && ModUpdaterService.isBatchActive()) {
            int total = ModUpdaterService.getBatchTotal();
            int done = ModUpdaterService.getBatchCompleted();
            if (total > 0) {
                int x = this.updateAllButton.getX() + 2;
                int y = this.updateAllButton.getY() + this.updateAllButton.getHeight() - 3;
                int barWidth = this.updateAllButton.getWidth() - 4;
                int fillWidth = Math.round(barWidth * Math.min(1.0f, (float) done / total));
                graphics.fill(x, y, x + barWidth, y + 2, 0x80000000);
                if (fillWidth > 0) {
                    graphics.fill(x, y, x + fillWidth, y + 2, 0xFF55FF55);
                }
            }
        }
    }
}
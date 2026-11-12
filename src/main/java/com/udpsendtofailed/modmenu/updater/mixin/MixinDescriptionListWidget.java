package com.udpsendtofailed.modmenu.updater.mixin;

import com.terraformersmc.modmenu.config.option.BooleanConfigOption;
import com.terraformersmc.modmenu.gui.widget.DescriptionListWidget;
import com.terraformersmc.modmenu.util.mod.Mod;
import com.udpsendtofailed.modmenu.updater.ModMenuUpdater;
import com.udpsendtofailed.modmenu.updater.api.DescriptionEntryExtension;
import com.udpsendtofailed.modmenu.updater.api.ModExtension;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.Constructor;

@Mixin(DescriptionListWidget.class)
public abstract class MixinDescriptionListWidget {
    private static volatile Constructor<?> entryConstructor;

    @Shadow private Mod selectedMod;
    @Shadow private Font textRenderer;
    @Shadow public abstract int getRowWidth();

    @Redirect(
        method = "rebuildUI", 
        at = @At(
            value = "INVOKE", 
            target = "Lcom/terraformersmc/modmenu/config/option/BooleanConfigOption;getValue()Z",
            ordinal = 0
        )
    )
    private boolean interceptUpdateCheck(BooleanConfigOption instance) {
        boolean originalValue = instance.getValue();
        if (!originalValue) return false;

        if (selectedMod instanceof ModExtension ext && ext.isUpdateDownloaded()) {
            int width = getRowWidth() - 5;

            addDescriptionEntry(FormattedCharSequence.EMPTY, false);

            for (FormattedCharSequence line : textRenderer.split(
                    Component.translatable("modmenu.update.state.widget.updated").withStyle(ChatFormatting.GREEN), width)) {
                addDescriptionEntry(line, true);
            }

            for (FormattedCharSequence line : textRenderer.split(
                    Component.translatable("modmenu.update.state.widget.restartRequired").withStyle(ChatFormatting.GRAY), width)) {
                addDescriptionEntry(line, false);
            }

            return false;
        }
        return originalValue;
    }

    private static Constructor<?> getEntryConstructor() throws ReflectiveOperationException {
        Constructor<?> ctor = entryConstructor;
        if (ctor == null) {
            Class<?> entryClass = Class.forName(
                    "com.terraformersmc.modmenu.gui.widget.DescriptionListWidget$DescriptionEntry");
            ctor = entryClass.getDeclaredConstructor(DescriptionListWidget.class, FormattedCharSequence.class);
            ctor.setAccessible(true);
            entryConstructor = ctor;
        }
        return ctor;
    }

    private void addDescriptionEntry(FormattedCharSequence text, boolean isBadge) {
        try {
            DescriptionListWidget self = (DescriptionListWidget) (Object) this;
            var entry = (AbstractSelectionList.Entry<?>) getEntryConstructor().newInstance(self, text);

            if (isBadge && entry instanceof DescriptionEntryExtension ext) {
                ext.setUpdateBadge(true);
            }

            ((DescriptionListWidgetAccessor) self).invokeAddEntry(entry);
        } catch (Exception e) {
            ModMenuUpdater.LOGGER.error("Failed to add description entry", e);
        }
    }
}
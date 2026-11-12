package com.udpsendtofailed.modmenu.updater;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModMenuUpdater implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Mod Menu Updater");

    @Override
    public void onInitializeClient() {
        CleanupManager.performCleanup();
    }
}
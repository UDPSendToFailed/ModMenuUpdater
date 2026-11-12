package com.udpsendtofailed.modmenu.updater.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.terraformersmc.modmenu.util.UpdateCheckerUtil;
import com.udpsendtofailed.modmenu.updater.ModMenuUpdater;
import com.udpsendtofailed.modmenu.updater.UpdateDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(UpdateCheckerUtil.class)
public class MixinUpdateCheckerUtil {
    @Redirect(
        method = "getUpdatedVersions", 
        at = @At(
            value = "INVOKE", 
            target = "Lcom/google/gson/JsonParser;parseString(Ljava/lang/String;)Lcom/google/gson/JsonElement;"
        )
    )
    private static JsonElement captureDownloadInfo(String jsonBody) {
        JsonElement result = JsonParser.parseString(jsonBody);
        
        try {
            JsonObject responseObject = result.getAsJsonObject();
            responseObject.asMap().forEach((lookupHash, versionJson) -> {
                try {
                    JsonObject versionObj = versionJson.getAsJsonObject();
                    String versionId = versionObj.get("id").getAsString();
                    
                    var primaryFileOpt = versionObj.get("files").getAsJsonArray().asList().stream()
                        .filter(f -> f.getAsJsonObject().get("primary").getAsBoolean())
                        .findFirst();

                    if (primaryFileOpt.isPresent()) {
                        JsonObject file = primaryFileOpt.get().getAsJsonObject();
                        String url = file.get("url").getAsString();
                        String filename = file.get("filename").getAsString();
                        String hash = null;
                        if (file.has("hashes") && file.get("hashes").isJsonObject()) {
                            JsonObject hashes = file.getAsJsonObject("hashes");
                            if (hashes.has("sha512") && hashes.get("sha512").isJsonPrimitive()) {
                                hash = hashes.get("sha512").getAsString();
                            }
                        }

                        UpdateDataStorage.DOWNLOAD_CACHE.put(versionId, new UpdateDataStorage.DownloadData(url, filename, hash));
                    }
                } catch (Exception e) {
                    ModMenuUpdater.LOGGER.debug("Failed to parse download info for version entry", e);
                }
            });
        } catch (Exception e) {
            ModMenuUpdater.LOGGER.debug("Failed to parse Modrinth update response for download info", e);
        }

        return result;
    }
}
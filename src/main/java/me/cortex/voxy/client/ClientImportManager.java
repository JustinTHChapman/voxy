package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.importers.IDataImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ClientImportManager extends ImportManager {
    // TODO: re-implement boss-bar progress overlay; BossHealthOverlay.events is private in 1.21.1
    //       (needs either an Accessor mixin or an AccessTransformer entry).
    protected class ClientImportTask extends ImportTask {
        protected ClientImportTask(IDataImporter importer) {
            super(importer);
        }

        @Override
        protected void onCompleted(int total) {
            super.onCompleted(total);
            long delta = Math.max(System.currentTimeMillis() - this.startTime, 1);
            String msg = "Voxy world import finished in " + (delta/1000) + " seconds, averaging " + (int)(total/(delta/1000f)) + " chunks per second";
            Minecraft.getInstance().execute(()->
                    Minecraft.getInstance().gui.getChat().addMessage(Component.literal(msg))
            );
            Logger.info(msg);
        }
    }

    @Override
    protected synchronized ImportTask createImportTask(IDataImporter importer) {
        return new ClientImportTask(importer);
    }
}

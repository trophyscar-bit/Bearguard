package dev.frostguard.app;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.engine.service.ProdInstanceGuard;

public final class ApplicationTitle {
    private ApplicationTitle() {
    }

    public static String current() {
        if (ProdInstanceGuard.isSeparateInstance()) {
            return "Bearguard New UI · v" + RuntimeVersion.current();
        }
        return format(WorkspacePaths.current().channel(), RuntimeVersion.current(),
                RuntimeInstanceIdentity.current());
    }

    static String format(RuntimeChannel channel, String version, String instanceLabel) {
        String title = String.format("%s · %s", channel.productName(), instanceLabel);
        return version == null || version.isBlank() ? title : title + " · v" + version;
    }
}

package dev.modernframes.common.network;

import dev.modernframes.ModernFramesConfig;
import dev.modernframes.common.data.FrameData;
import dev.modernframes.common.util.FrameSources;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side policy validation for frame sources. Runs on the logical server; the client
 * also calls it (with its local config copy) to give early feedback in the screen.
 */
public final class FramePolicy {

    private FramePolicy() {
    }

    /**
     * @return {@code null} when the data is acceptable, otherwise a translation key describing why not.
     */
    @Nullable
    public static String validate(FrameData data) {
        String source = data.source().trim();
        if (source.isEmpty()) {
            return null; // clearing a frame is always allowed
        }
        if (source.length() > FrameData.MAX_SOURCE_LENGTH) {
            return "modernframes.error.too_long";
        }

        if (data.kind() == FrameData.SourceKind.URL) {
            if (!FrameSources.isRemote(source)) {
                return "modernframes.error.bad_url";
            }
            if (!ModernFramesConfig.Common.ALLOW_REMOTE_URLS.get()) {
                return "modernframes.error.remote_disabled";
            }
            return null;
        }

        // Local file source
        if (FrameSources.resolveAbsolute(source).isPresent()) {
            if (!ModernFramesConfig.Common.ALLOW_ABSOLUTE_PATHS.get()) {
                return "modernframes.error.absolute_disabled";
            }
            return null;
        }
        if (!ModernFramesConfig.Common.ALLOW_LOCAL_FILES.get()) {
            return "modernframes.error.local_disabled";
        }
        if (FrameSources.resolveSandbox(source).isEmpty()) {
            return "modernframes.error.bad_path";
        }
        return null;
    }
}

package dev.modernframes;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration split in two specs:
 * <ul>
 *   <li>{@link Common} — server policy, enforced on the logical server.</li>
 *   <li>{@link Client} — client-side performance and appearance.</li>
 * </ul>
 */
public final class ModernFramesConfig {

    private ModernFramesConfig() {
    }

    public enum EditPolicy {
        /** Any player may configure a frame. */
        EVERYONE,
        /** Only creative-mode players (or operators) may configure a frame. */
        CREATIVE_ONLY,
        /** Only the player that configured the frame last (or operators) may change it. */
        OWNER
    }

    public static final class Common {
        public static final ModConfigSpec SPEC;
        public static final ModConfigSpec.BooleanValue ALLOW_REMOTE_URLS;
        public static final ModConfigSpec.BooleanValue ALLOW_LOCAL_FILES;
        public static final ModConfigSpec.BooleanValue ALLOW_ABSOLUTE_PATHS;
        public static final ModConfigSpec.IntValue MAX_DOWNLOAD_KB;
        public static final ModConfigSpec.IntValue DOWNLOAD_TIMEOUT_SECONDS;
        public static final ModConfigSpec.EnumValue<EditPolicy> EDIT_POLICY;
        public static final ModConfigSpec.IntValue RESIZE_MAX_SIZE;
        public static final ModConfigSpec.IntValue RESIZE_MAX_AREA;

        static {
            ModConfigSpec.Builder b = new ModConfigSpec.Builder();

            b.comment("Policy for remote image sources (http/https URLs, image APIs)").push("remote");
            ALLOW_REMOTE_URLS = b
                    .comment("Allow frames to load images from http/https URLs")
                    .define("allowRemoteUrls", true);
            MAX_DOWNLOAD_KB = b
                    .comment("Maximum size of a single downloaded image, in kilobytes")
                    .defineInRange("maxDownloadKb", 8192, 16, 262144);
            DOWNLOAD_TIMEOUT_SECONDS = b
                    .comment("HTTP connect/read timeout in seconds")
                    .defineInRange("downloadTimeoutSeconds", 15, 2, 120);
            b.pop();

            b.comment("Policy for local file sources").push("local");
            ALLOW_LOCAL_FILES = b
                    .comment("Allow frames to load files from the server-side sandbox folder (config/modernframes/images)")
                    .define("allowLocalFiles", true);
            ALLOW_ABSOLUTE_PATHS = b
                    .comment("Allow frames to reference absolute paths on each client's own machine.",
                            "SECURITY: only enable this on private worlds/servers you fully trust.")
                    .define("allowAbsolutePaths", false);
            b.pop();

            b.comment("Who is allowed to configure a frame").push("permissions");
            EDIT_POLICY = b
                    .comment("EVERYONE: anybody. CREATIVE_ONLY: creative players and operators.",
                            "OWNER: the last player that configured the frame and operators.")
                    .defineEnum("editPolicy", EditPolicy.OWNER);
            b.pop();

            b.comment("Limits for multi-block frame resizing").push("resize");
            RESIZE_MAX_SIZE = b
                    .comment("Maximum width and height of a frame, in blocks")
                    .defineInRange("maxSize", 8, 1, 64);
            RESIZE_MAX_AREA = b
                    .comment("Maximum total area of a frame, in blocks")
                    .defineInRange("maxArea", 64, 1, 4096);
            b.pop();

            SPEC = b.build();
        }

        private Common() {
        }
    }

    public static final class Client {
        public static final ModConfigSpec SPEC;
        public static final ModConfigSpec.IntValue MAX_CACHED_IMAGES;
        public static final ModConfigSpec.IntValue MAX_TEXTURE_SIZE;
        public static final ModConfigSpec.IntValue GIF_MAX_FRAMES;
        public static final ModConfigSpec.IntValue GIF_MAX_SIZE;
        public static final ModConfigSpec.BooleanValue SMOOTH_FILTERING;
        public static final ModConfigSpec.BooleanValue DISK_CACHE;

        static {
            ModConfigSpec.Builder b = new ModConfigSpec.Builder();

            b.comment("Image pipeline settings").push("images");
            MAX_CACHED_IMAGES = b
                    .comment("How many images (textures) to keep in memory at once; least recently used are evicted")
                    .defineInRange("maxCachedImages", 64, 8, 1024);
            MAX_TEXTURE_SIZE = b
                    .comment("Static images are downscaled so neither side exceeds this many pixels")
                    .defineInRange("maxTextureSize", 1024, 64, 4096);
            SMOOTH_FILTERING = b
                    .comment("true: smooth (bilinear) filtering. false: crisp nearest-neighbor (good for pixel art)")
                    .define("smoothFiltering", true);
            DISK_CACHE = b
                    .comment("Cache downloaded images on disk (config/modernframes/cache)")
                    .define("diskCache", true);
            b.pop();

            b.comment("Animated GIF settings").push("gifs");
            GIF_MAX_FRAMES = b
                    .comment("Maximum number of animation frames kept per GIF")
                    .defineInRange("maxFrames", 128, 1, 1024);
            GIF_MAX_SIZE = b
                    .comment("GIFs are downscaled so neither side exceeds this many pixels")
                    .defineInRange("maxSize", 512, 64, 2048);
            b.pop();

            SPEC = b.build();
        }

        private Client() {
        }
    }
}

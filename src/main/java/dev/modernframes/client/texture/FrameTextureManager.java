package dev.modernframes.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import dev.modernframes.ModernFrames;
import dev.modernframes.ModernFramesConfig;
import dev.modernframes.common.data.FrameData;
import dev.modernframes.common.util.FrameSources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side texture cache: one dynamic texture per image source, shared by every frame
 * using it, with LRU eviction and GIF animation ticking.
 */
public final class FrameTextureManager {

    public enum Status {
        LOADING,
        READY,
        FAILED
    }

    public static final class Entry {
        private Status status = Status.LOADING;
        private String error = "";
        private DynamicTexture texture;
        private ResourceLocation textureLocation;
        private List<NativeImage> frames = List.of();
        private int[] delaysMs = new int[0];
        private int currentFrame;
        private long frameStartedAtMs;
        private int width;
        private int height;
        private long lastUsedTick;

        public Status status() {
            return this.status;
        }

        public String error() {
            return this.error;
        }

        public ResourceLocation textureLocation() {
            return this.textureLocation;
        }

        public int width() {
            return this.width;
        }

        public int height() {
            return this.height;
        }
    }

    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static final LinkedHashMap<String, Entry> LRU = new LinkedHashMap<>();
    private static long currentTick;

    private FrameTextureManager() {
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    /** Returns (and lazily starts loading) the entry for the given frame source. */
    public static Entry get(FrameData data) {
        return get(data, false);
    }

    /**
     * @param retryFailed when {@code true} and the cached entry previously failed, the load is retried.
     */
    public static Entry get(FrameData data, boolean retryFailed) {
        if (data == null || !data.isConfigured()) {
            return null;
        }
        String key = keyFor(data);
        Entry entry = ENTRIES.get(key);
        if (entry == null) {
            entry = new Entry();
            ENTRIES.put(key, entry);
            LRU.put(key, entry);
            startLoad(key, entry, data);
        } else if (retryFailed && entry.status == Status.FAILED) {
            entry.status = Status.LOADING;
            entry.error = "";
            startLoad(key, entry, data);
        }
        entry.lastUsedTick = currentTick;
        // Re-insert so the linked map keeps LRU order (oldest first).
        LRU.remove(key);
        LRU.put(key, entry);
        return entry;
    }

    private static String keyFor(FrameData data) {
        return data.kind().name() + "|" + data.source().trim();
    }

    private static void startLoad(String key, Entry entry, FrameData data) {
        FrameImageLoader.request(data, result -> onLoaded(key, entry, result));
    }

    private static void onLoaded(String key, Entry entry, FrameImageLoader.Result result) {
        // Client thread (render thread).
        if (ENTRIES.get(key) != entry) {
            closeImages(result.image());
            return;
        }
        if (!result.ok()) {
            entry.status = Status.FAILED;
            entry.error = result.error();
            return;
        }

        FrameImageLoader.LoadedImage loaded = result.image();
        DynamicTexture texture;
        try {
            texture = new DynamicTexture(loaded.frames().get(0));
        } catch (Exception e) {
            ModernFrames.LOGGER.warn("Could not create dynamic texture", e);
            closeImages(loaded);
            entry.status = Status.FAILED;
            entry.error = "modernframes.error.load_failed";
            return;
        }

        ResourceLocation location = ModernFrames.id("dynamic/" + FrameSources.sha1(key));
        Minecraft.getInstance().getTextureManager().register(location, texture);
        texture.setFilter(ModernFramesConfig.Client.SMOOTH_FILTERING.get(), false);

        entry.texture = texture;
        entry.textureLocation = location;
        entry.frames = loaded.frames();
        entry.delaysMs = loaded.delaysMs();
        entry.width = loaded.width();
        entry.height = loaded.height();
        entry.currentFrame = 0;
        entry.frameStartedAtMs = System.currentTimeMillis();
        entry.status = Status.READY;
    }

    private static void closeImages(FrameImageLoader.LoadedImage image) {
        if (image != null) {
            for (NativeImage frame : image.frames()) {
                frame.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // Ticking (GIF animation) and eviction
    // ------------------------------------------------------------------

    public static void tick() {
        currentTick++;
        for (Entry entry : ENTRIES.values()) {
            advanceAnimation(entry);
        }
        evictIfNeeded();
    }

    private static void advanceAnimation(Entry entry) {
        if (entry.status != Status.READY || entry.frames.size() <= 1) {
            return;
        }
        long now = System.currentTimeMillis();
        int delay = entry.currentFrame < entry.delaysMs.length ? entry.delaysMs[entry.currentFrame] : 100;
        if (now - entry.frameStartedAtMs < Math.max(20, delay)) {
            return;
        }
        entry.frameStartedAtMs = now;
        int next = (entry.currentFrame + 1) % entry.frames.size();
        try {
            entry.texture.bind();
            entry.frames.get(next).upload(0, 0, 0, false);
            entry.currentFrame = next;
        } catch (Exception e) {
            ModernFrames.LOGGER.warn("Failed to upload animation frame", e);
            entry.frames = List.of();
        }
    }

    private static void evictIfNeeded() {
        int max = ModernFramesConfig.Client.MAX_CACHED_IMAGES.get();
        if (ENTRIES.size() <= max) {
            return;
        }
        Iterator<Map.Entry<String, Entry>> iterator = LRU.entrySet().iterator();
        while (iterator.hasNext() && ENTRIES.size() > max) {
            Map.Entry<String, Entry> candidate = iterator.next();
            Entry entry = candidate.getValue();
            if (currentTick - entry.lastUsedTick < 40) {
                break; // recently used; stop evicting
            }
            iterator.remove();
            ENTRIES.remove(candidate.getKey());
            close(entry);
        }
    }

    private static void close(Entry entry) {
        if (entry.textureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(entry.textureLocation);
        }
        for (NativeImage frame : entry.frames) {
            frame.close();
        }
        entry.texture = null;
        entry.textureLocation = null;
        entry.frames = List.of();
    }

    /** Drops every cached texture (on disconnect). */
    public static void clearAll() {
        for (Entry entry : ENTRIES.values()) {
            close(entry);
        }
        ENTRIES.clear();
        LRU.clear();
    }
}

package dev.modernframes.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import dev.modernframes.ModernFrames;
import dev.modernframes.ModernFramesConfig;
import dev.modernframes.common.data.FrameData;
import dev.modernframes.common.util.FrameSources;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.spi.IIORegistry;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Downloads and decodes images off-thread. Runs on the client; callbacks are executed on the
 * render thread through {@link Minecraft#execute}.
 *
 * <p>Supports PNG/JPG/BMP single frames, animated GIFs (composited with disposal methods
 * honoured), WebP (lossy and lossless, through the bundled TwelveMonkeys decoder) and a few
 * legacy formats through the game's own decoder (TGA, PSD, PNM, HDR).</p>
 */
public final class FrameImageLoader {

    public record LoadedImage(int width, int height, List<NativeImage> frames, int[] delaysMs) {
        public boolean animated() {
            return this.frames.size() > 1;
        }
    }

    public record Result(LoadedImage image, String error) {
        public boolean ok() {
            return this.image != null;
        }

        static Result ok(LoadedImage image) {
            return new Result(image, "");
        }

        static Result error(String message) {
            return new Result(null, message);
        }
    }

    private FrameImageLoader() {
    }

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ModernFrames-IO");
        thread.setDaemon(true);
        return thread;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Registers the bundled TwelveMonkeys WebP reader with ImageIO. Doing it explicitly avoids
     * relying on service-loader discovery inside the game's class loader.
     */
    public static void registerCodecs() {
        try {
            IIORegistry.getDefaultInstance().registerServiceProvider(new WebPImageReaderSpi());
        } catch (Throwable t) {
            ModernFrames.LOGGER.warn("Could not register the WebP image reader", t);
        }
    }

    public static void request(FrameData data, Consumer<Result> callback) {
        IO.submit(() -> {
            Result result = loadSafely(data);
            Minecraft.getInstance().execute(() -> callback.accept(result));
        });
    }

    private static Result loadSafely(FrameData data) {
        try {
            return load(data);
        } catch (Throwable t) {
            ModernFrames.LOGGER.warn("Failed to load frame image '{}'", data.source(), t);
            return Result.error(messageFor(t));
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private static Result load(FrameData data) throws Exception {
        Path file;
        boolean temporary = false;
        if (data.kind() == FrameData.SourceKind.URL) {
            Download download = download(data.source());
            file = download.path();
            temporary = download.temporary();
        } else {
            file = resolveLocal(data.source()).orElse(null);
            if (file == null) {
                return Result.error("modernframes.error.not_found");
            }
        }

        try {
            if (!Files.isRegularFile(file)) {
                return Result.error("modernframes.error.not_found");
            }
            return decode(file);
        } finally {
            if (temporary) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static Optional<Path> resolveLocal(String source) {
        Optional<Path> absolute = FrameSources.resolveAbsolute(source);
        if (absolute.isPresent()) {
            return absolute;
        }
        return FrameSources.resolveSandbox(source);
    }

    private record Download(Path path, boolean temporary) {
    }

    private static Download download(String url) throws Exception {
        FrameSources.ensureDir(FrameSources.cacheDir());
        Path cached = FrameSources.cacheDir().resolve(FrameSources.sha1(url) + ".img");
        boolean diskCache = ModernFramesConfig.Client.DISK_CACHE.get();

        if (diskCache && Files.isRegularFile(cached) && Files.size(cached) > 0L) {
            return new Download(cached, false);
        }

        long maxBytes = ModernFramesConfig.Common.MAX_DOWNLOAD_KB.get() * 1024L;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(ModernFramesConfig.Common.DOWNLOAD_TIMEOUT_SECONDS.get()))
                .header("User-Agent", "ModernFrames/0.1 (Minecraft NeoForge mod)")
                .header("Accept", "image/*")
                .GET()
                .build();

        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new HttpStatusException(response.statusCode());
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declared > maxBytes) {
            throw new ImageTooLargeException();
        }

        Path temp = Files.createTempFile(FrameSources.cacheDir(), "download", ".tmp");
        long total = 0L;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new ImageTooLargeException();
                }
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        if (diskCache) {
            Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new Download(cached, false);
        }
        return new Download(temp, true);
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    private static Result decode(Path file) throws IOException {
        IOException imageIoFailure = null;
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            if (input != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(input, false, false);
                        int count;
                        try {
                            count = reader.getNumImages(true);
                        } catch (IOException e) {
                            count = 1;
                        }
                        // Only GIFs are composited frame by frame; other multi-image formats
                        // (an animated WebP, for example) are shown as their first frame.
                        if (count > 1 && "gif".equalsIgnoreCase(reader.getFormatName())) {
                            return decodeAnimation(reader, count);
                        }
                        BufferedImage image = reader.read(0);
                        if (image != null) {
                            LoadedImage loaded = toLoadedImage(List.of(image), new int[]{0},
                                    ModernFramesConfig.Client.MAX_TEXTURE_SIZE.get());
                            return Result.ok(loaded);
                        }
                    } catch (IOException e) {
                        imageIoFailure = e;
                    } finally {
                        reader.dispose();
                    }
                }
            }
        }

        // Formats without an ImageIO reader (TGA, PSD, PNM, HDR...) go through the game's own
        // decoder (stb_image), which covers a few more legacy formats.
        Result viaStb = decodeWithStb(file);
        if (viaStb.ok()) {
            return viaStb;
        }
        if (imageIoFailure != null) {
            throw imageIoFailure;
        }
        return Result.error("modernframes.error.unsupported");
    }

    private static Result decodeWithStb(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage nativeImage = NativeImage.read(in);
            try {
                LoadedImage loaded = toLoadedImage(List.of(toBufferedImage(nativeImage)), new int[]{0},
                        ModernFramesConfig.Client.MAX_TEXTURE_SIZE.get());
                return Result.ok(loaded);
            } finally {
                nativeImage.close();
            }
        } catch (Throwable t) {
            return Result.error("modernframes.error.unsupported");
        }
    }

    /** Converts a decoded {@link NativeImage} (ABGR) to the ARGB image the pipeline works with. */
    private static BufferedImage toBufferedImage(NativeImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int abgr = source.getPixelRGBA(x, y);
                int a = (abgr >>> 24) & 0xFF;
                int b = (abgr >>> 16) & 0xFF;
                int g = (abgr >>> 8) & 0xFF;
                int r = abgr & 0xFF;
                row[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    // ------------------------------------------------------------------
    // Animated GIFs
    // ------------------------------------------------------------------

    private static Result decodeAnimation(ImageReader reader, int count) throws IOException {
        int maxFrames = ModernFramesConfig.Client.GIF_MAX_FRAMES.get();
        int step = Math.max(1, (int) Math.ceil(count / (double) maxFrames));

        int canvasWidth = reader.getWidth(0);
        int canvasHeight = reader.getHeight(0);
        try {
            IIOMetadata stream = reader.getStreamMetadata();
            if (stream != null) {
                IIOMetadataNode root = (IIOMetadataNode) stream.getAsTree(stream.getNativeMetadataFormatName());
                IIOMetadataNode screen = firstChild(root, "LogicalScreenDescriptor");
                if (screen != null) {
                    canvasWidth = parseInt(screen.getAttribute("logicalScreenWidth"), canvasWidth);
                    canvasHeight = parseInt(screen.getAttribute("logicalScreenHeight"), canvasHeight);
                }
            }
        } catch (Exception ignored) {
        }
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            canvasWidth = Math.max(1, canvasWidth);
            canvasHeight = Math.max(1, canvasHeight);
        }

        BufferedImage working = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = working.createGraphics();

        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();

        for (int i = 0; i < count; i += step) {
            BufferedImage raw;
            IIOMetadata metadata = null;
            try {
                raw = reader.read(i);
                metadata = reader.getImageMetadata(i);
            } catch (IOException e) {
                break;
            }
            if (raw == null) {
                break;
            }

            GifFrameMeta meta = GifFrameMeta.parse(metadata);

            BufferedImage restore = null;
            if (meta.restoreToPrevious) {
                restore = copyOf(working);
            }

            graphics.drawImage(raw, meta.x, meta.y, null);
            frames.add(copyOf(working));
            delays.add(meta.delayMs);

            if (meta.restoreToBackground) {
                graphics.setComposite(java.awt.AlphaComposite.Clear);
                graphics.fillRect(meta.x, meta.y, raw.getWidth(), raw.getHeight());
                graphics.setComposite(java.awt.AlphaComposite.SrcOver);
            } else if (restore != null) {
                working = restore;
                graphics.dispose();
                graphics = working.createGraphics();
            }
        }

        graphics.dispose();

        if (frames.isEmpty()) {
            return Result.error("modernframes.error.unsupported");
        }
        int[] delaysArray = new int[delays.size()];
        for (int i = 0; i < delaysArray.length; i++) {
            delaysArray[i] = delays.get(i);
        }
        LoadedImage loaded = toLoadedImage(frames, delaysArray, ModernFramesConfig.Client.GIF_MAX_SIZE.get());
        return Result.ok(loaded);
    }

    private static final class GifFrameMeta {
        int x;
        int y;
        int delayMs = 100;
        boolean restoreToBackground;
        boolean restoreToPrevious;

        static GifFrameMeta parse(IIOMetadata metadata) {
            GifFrameMeta meta = new GifFrameMeta();
            if (metadata == null) {
                return meta;
            }
            try {
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(metadata.getNativeMetadataFormatName());
                IIOMetadataNode control = firstChild(root, "GraphicControlExtension");
                if (control != null) {
                    meta.delayMs = clampDelay(parseInt(control.getAttribute("delayTime"), 10) * 10);
                    String disposal = control.getAttribute("disposalMethod");
                    meta.restoreToBackground = "restoreToBackgroundColor".equals(disposal);
                    meta.restoreToPrevious = "restoreToPrevious".equals(disposal);
                }
                IIOMetadataNode descriptor = firstChild(root, "ImageDescriptor");
                if (descriptor != null) {
                    meta.x = parseInt(descriptor.getAttribute("imageLeftPosition"), 0);
                    meta.y = parseInt(descriptor.getAttribute("imageTopPosition"), 0);
                }
            } catch (Exception ignored) {
            }
            return meta;
        }

        private static int clampDelay(int ms) {
            return Math.max(20, Math.min(ms, 10_000));
        }
    }

    // ------------------------------------------------------------------
    // Image conversion
    // ------------------------------------------------------------------

    private static LoadedImage toLoadedImage(List<BufferedImage> images, int[] delays, int maxSide) {
        BufferedImage first = images.get(0);
        int width = first.getWidth();
        int height = first.getHeight();
        double scale = 1.0D;
        int largest = Math.max(width, height);
        if (largest > maxSide) {
            scale = maxSide / (double) largest;
        }
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        List<NativeImage> natives = new ArrayList<>(images.size());
        for (BufferedImage image : images) {
            natives.add(toNativeImage(image, targetWidth, targetHeight));
        }
        return new LoadedImage(targetWidth, targetHeight, natives, delays);
    }

    private static NativeImage toNativeImage(BufferedImage source, int width, int height) {
        BufferedImage scaled = source;
        if (source.getWidth() != width || source.getHeight() != height) {
            scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
            graphics.dispose();
        }

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            scaled.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int argb = row[x];
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                // NativeImage expects ABGR order.
                nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return nativeImage;
    }

    private static BufferedImage copyOf(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static IIOMetadataNode firstChild(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        return null;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String messageFor(Throwable t) {
        if (t instanceof ImageTooLargeException) {
            return "modernframes.error.too_large";
        }
        if (t instanceof HttpStatusException status) {
            return "HTTP " + status.code;
        }
        return "modernframes.error.load_failed";
    }

    private static final class ImageTooLargeException extends IOException {
    }

    private static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }
}

package dev.modernframes.common.util;

import dev.modernframes.ModernFrames;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Helpers for resolving and validating frame sources both on the logical server
 * (policy checks) and on the client (actual file/URL loading).
 */
public final class FrameSources {

    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "tga", "psd");

    private FrameSources() {
    }

    /** Folder where players drop local images: {@code config/modernframes/images}. */
    public static Path sandboxDir() {
        return FMLPaths.CONFIGDIR.get().resolve("modernframes").resolve("images");
    }

    /** Folder where downloaded images are cached: {@code config/modernframes/cache}. */
    public static Path cacheDir() {
        return FMLPaths.CONFIGDIR.get().resolve("modernframes").resolve("cache");
    }

    public static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            ModernFrames.LOGGER.warn("Could not create directory {}", dir, e);
        }
    }

    public static boolean isRemote(String source) {
        String s = source.toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /**
     * Resolves a sandbox-relative name ({@code cat.png}, {@code memes/dog.gif}) and rejects
     * anything that escapes {@code config/modernframes/images}.
     */
    public static Optional<Path> resolveSandbox(String name) {
        String trimmed = name.trim().replace('\\', '/');
        if (trimmed.isEmpty() || trimmed.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        Path base = sandboxDir().toAbsolutePath().normalize();
        Path resolved = base.resolve(trimmed).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    /** Resolves a user-typed absolute path; only accepts paths that are actually absolute. */
    public static Optional<Path> resolveAbsolute(String raw) {
        try {
            Path p = Path.of(raw.trim());
            if (!p.isAbsolute()) {
                return Optional.empty();
            }
            return Optional.of(p.normalize());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isSupportedFileName(String name) {
        return SUPPORTED_EXTENSIONS.contains(extensionOf(name));
    }

    /** Stable cache key for a URL or source string. */
    public static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }
}

package dev.modernframes.common.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/**
 * Immutable per-frame configuration: what to display and how.
 *
 * <p>The {@code source} string is server-side data. For {@link SourceKind#URL} it is an
 * http/https image URL; for {@link SourceKind#FILE} it is either a name relative to the
 * sandbox folder ({@code config/modernframes/images}) or an absolute path when the server
 * policy allows it. Every player resolves the source on their own machine.</p>
 *
 * <p>{@code sizeW}/{@code sizeH} describe how many blocks the frame occupies. The gameplay
 * limits live in the common config and are enforced server-side by {@code FrameResizer};
 * the hard caps here are only a sanity valve for NBT/network data.</p>
 *
 * <p>{@code border} and {@code backing} control the drawn frame itself: a 1 px border ring
 * around the rectangle and a dark panel behind the picture (which hides the wall and its
 * block seams when the picture is translucent).</p>
 */
public record FrameData(
        String source,
        SourceKind kind,
        float offsetU,
        float offsetV,
        float zoom,
        float rotation,
        int alpha,
        int brightness,
        boolean flipX,
        boolean flipY,
        boolean renderBothSides,
        boolean lit,
        int renderDistance,
        int sizeW,
        int sizeH,
        boolean autoSize,
        boolean backing,
        boolean border
) {

    public enum SourceKind {
        /** Remote http/https URL, typically an image API endpoint. */
        URL,
        /** Local file: sandbox-relative name, or absolute path when allowed. */
        FILE
    }

    public static final int MAX_SOURCE_LENGTH = 2048;
    /** Hard cap for {@code sizeW}/{@code sizeH}; the config limit is usually much smaller. */
    public static final int MAX_SIZE = 64;

    public static final FrameData EMPTY = new FrameData(
            "", SourceKind.URL,
            0.0F, 0.0F, 1.0F, 0.0F,
            255, 255,
            false, false, false, true, 48,
            1, 1, true,
            true, true);

    public boolean isConfigured() {
        return !this.source.isBlank();
    }

    /** Returns a copy with every numeric field clamped to a sane range. */
    public FrameData clamped() {
        return new FrameData(
                this.source.length() > MAX_SOURCE_LENGTH
                        ? this.source.substring(0, MAX_SOURCE_LENGTH)
                        : this.source,
                this.kind,
                Mth.clamp(this.offsetU, -2.0F, 2.0F),
                Mth.clamp(this.offsetV, -2.0F, 2.0F),
                Mth.clamp(this.zoom, 0.125F, 8.0F),
                Mth.clamp(this.rotation, 0.0F, 360.0F),
                Mth.clamp(this.alpha, 0, 255),
                Mth.clamp(this.brightness, 0, 255),
                this.flipX,
                this.flipY,
                this.renderBothSides,
                this.lit,
                Mth.clamp(this.renderDistance, 4, 256),
                Mth.clamp(this.sizeW, 1, MAX_SIZE),
                Mth.clamp(this.sizeH, 1, MAX_SIZE),
                this.autoSize,
                this.backing,
                this.border);
    }

    public FrameData withSource(String newSource, SourceKind newKind) {
        return new FrameData(newSource, newKind,
                this.offsetU, this.offsetV, this.zoom, this.rotation,
                this.alpha, this.brightness,
                this.flipX, this.flipY, this.renderBothSides, this.lit, this.renderDistance,
                this.sizeW, this.sizeH, this.autoSize,
                this.backing, this.border);
    }

    public FrameData withSize(int newWidth, int newHeight) {
        return new FrameData(this.source, this.kind,
                this.offsetU, this.offsetV, this.zoom, this.rotation,
                this.alpha, this.brightness,
                this.flipX, this.flipY, this.renderBothSides, this.lit, this.renderDistance,
                Mth.clamp(newWidth, 1, MAX_SIZE), Mth.clamp(newHeight, 1, MAX_SIZE),
                this.autoSize,
                this.backing, this.border);
    }

    // ------------------------------------------------------------------
    // NBT persistence (block entity save/load and update tags)
    // ------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Source", this.source);
        tag.putString("Kind", this.kind.name());
        tag.putFloat("OffsetU", this.offsetU);
        tag.putFloat("OffsetV", this.offsetV);
        tag.putFloat("Zoom", this.zoom);
        tag.putFloat("Rotation", this.rotation);
        tag.putInt("Alpha", this.alpha);
        tag.putInt("Brightness", this.brightness);
        tag.putBoolean("FlipX", this.flipX);
        tag.putBoolean("FlipY", this.flipY);
        tag.putBoolean("BothSides", this.renderBothSides);
        tag.putBoolean("Lit", this.lit);
        tag.putInt("RenderDistance", this.renderDistance);
        tag.putInt("SizeW", this.sizeW);
        tag.putInt("SizeH", this.sizeH);
        tag.putBoolean("AutoSize", this.autoSize);
        tag.putBoolean("Backing", this.backing);
        tag.putBoolean("Border", this.border);
        return tag;
    }

    public static FrameData load(CompoundTag tag) {
        if (tag.isEmpty()) {
            return EMPTY;
        }
        SourceKind kind;
        try {
            kind = SourceKind.valueOf(tag.getString("Kind"));
        } catch (IllegalArgumentException e) {
            kind = SourceKind.URL;
        }
        return new FrameData(
                tag.getString("Source"),
                kind,
                tag.getFloat("OffsetU"),
                tag.getFloat("OffsetV"),
                tag.contains("Zoom") ? tag.getFloat("Zoom") : 1.0F,
                tag.getFloat("Rotation"),
                tag.contains("Alpha") ? tag.getInt("Alpha") : 255,
                tag.contains("Brightness") ? tag.getInt("Brightness") : 255,
                tag.getBoolean("FlipX"),
                tag.getBoolean("FlipY"),
                tag.getBoolean("BothSides"),
                tag.contains("Lit") ? tag.getBoolean("Lit") : true,
                tag.contains("RenderDistance") ? tag.getInt("RenderDistance") : 48,
                tag.contains("SizeW") ? tag.getInt("SizeW") : 1,
                tag.contains("SizeH") ? tag.getInt("SizeH") : 1,
                !tag.contains("AutoSize") || tag.getBoolean("AutoSize"),
                !tag.contains("Backing") || tag.getBoolean("Backing"),
                !tag.contains("Border") || tag.getBoolean("Border")).clamped();
    }

    // ------------------------------------------------------------------
    // Network codec (client -> server edits)
    // ------------------------------------------------------------------

    public static final StreamCodec<FriendlyByteBuf, FrameData> STREAM_CODEC = StreamCodec.of(
            (buf, data) -> {
                buf.writeUtf(data.source, MAX_SOURCE_LENGTH);
                buf.writeEnum(data.kind);
                buf.writeFloat(data.offsetU);
                buf.writeFloat(data.offsetV);
                buf.writeFloat(data.zoom);
                buf.writeFloat(data.rotation);
                buf.writeVarInt(data.alpha);
                buf.writeVarInt(data.brightness);
                buf.writeBoolean(data.flipX);
                buf.writeBoolean(data.flipY);
                buf.writeBoolean(data.renderBothSides);
                buf.writeBoolean(data.lit);
                buf.writeVarInt(data.renderDistance);
                buf.writeVarInt(data.sizeW);
                buf.writeVarInt(data.sizeH);
                buf.writeBoolean(data.autoSize);
                buf.writeBoolean(data.backing);
                buf.writeBoolean(data.border);
            },
            buf -> new FrameData(
                    buf.readUtf(MAX_SOURCE_LENGTH),
                    buf.readEnum(SourceKind.class),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean()));
}

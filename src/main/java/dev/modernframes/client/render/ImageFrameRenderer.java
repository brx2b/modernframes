package dev.modernframes.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.modernframes.ModernFrames;
import dev.modernframes.client.texture.FrameTextureManager;
import dev.modernframes.common.block.entity.ImageFrameBlockEntity;
import dev.modernframes.common.data.FrameData;
import dev.modernframes.common.util.FrameGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/**
 * Draws everything of a frame from its master block entity: the border ring, the dark backing
 * panel and the picture quad, all floating just in front of the wall.
 *
 * <p>The block models of the frame are intentionally empty. With per-block models, every block
 * of a multi-block frame repeated the border texture, so the interior showed a grid of frame
 * seams through translucent pictures. Drawing one ring and one panel for the whole rectangle
 * fixes that and keeps the frame visible even while no picture is configured.</p>
 *
 * <p>No pose rotations are used; the quad bases are derived from the block state with plain
 * world-space vectors, which avoids every blockstate/pose rotation convention pitfall.</p>
 */
public class ImageFrameRenderer implements BlockEntityRenderer<ImageFrameBlockEntity> {

    /** Standalone copy of the frame texture; entity render types cannot sample the block atlas. */
    private static final ResourceLocation FRAME_TEXTURE = ModernFrames.id("textures/frame/frame.png");
    /** Distance of the border/panel plane: the surface the old plate model used to have (0.5 px). */
    private static final float PLATE_PLANE = 0.03125F;
    /** Distance of the picture plane: 1 mm in front of the plate plane. */
    private static final float PLANE_OFFSET = 0.03225F;
    /** Half of the 1 px frame border, which is also the inset of the picture. */
    private static final float BORDER_HALF = 0.0625F;
    /** UV span of the texture's outer 1 px ring, used for the border. */
    private static final float RING_UV = 0.0625F;
    /** Pinned to the flat dark area of the texture; gives the backing panel its colour. */
    private static final float PANEL_UV = 0.53125F;

    public ImageFrameRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    /**
     * Culling box covering the whole multi-block rectangle, so the picture does not pop out
     * of view when the master block itself leaves the frustum.
     */
    @Override
    public AABB getRenderBoundingBox(ImageFrameBlockEntity be) {
        if (!be.isMaster()) {
            return new AABB(be.getBlockPos());
        }
        BlockState state = be.getBlockState();
        Direction right = FrameGeometry.right(state);
        Direction up = FrameGeometry.up(state);
        FrameData data = be.getData();
        int w = Math.max(1, data.sizeW());
        int h = Math.max(1, data.sizeH());
        AABB box = null;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                BlockPos p = FrameGeometry.cell(be.getBlockPos(), right, up, x, y);
                AABB cell = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1.0, p.getY() + 1.0, p.getZ() + 1.0);
                box = box == null ? cell : box.minmax(cell);
            }
        }
        return box == null ? new AABB(be.getBlockPos()) : box.inflate(0.5);
    }

    @Override
    public void render(ImageFrameBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        if (!be.isMaster()) {
            return;
        }
        FrameData data = be.getData();

        BlockState state = be.getBlockState();
        Direction normalDir = FrameGeometry.normal(state);
        Direction rightDir = FrameGeometry.right(state);
        Direction upDir = FrameGeometry.up(state);
        int sizeW = Math.max(1, data.sizeW());
        int sizeH = Math.max(1, data.sizeH());

        Vector3f normal = new Vector3f(normalDir.getStepX(), normalDir.getStepY(), normalDir.getStepZ());
        Vector3f right = new Vector3f(rightDir.getStepX(), rightDir.getStepY(), rightDir.getStepZ());
        Vector3f up = new Vector3f(upDir.getStepX(), upDir.getStepY(), upDir.getStepZ());
        Vector3f backNormal = new Vector3f(normal).negate();

        // Planes on the normal axis: the plate sits against the wall, the picture floats 1 mm in
        // front of it. Mirroring handles the negative-axis normals and the back-facing quads.
        boolean positiveNormal = normalDir.getStepX() + normalDir.getStepY() + normalDir.getStepZ() > 0;
        float plateDepth = positiveNormal ? PLATE_PLANE : 1.0F - PLATE_PLANE;
        float imageDepth = positiveNormal ? PLANE_OFFSET : 1.0F - PLANE_OFFSET;

        Vector3f centre = planeCentre(right, up, normalDir, sizeW, sizeH, imageDepth);

        // Per-frame render distance culling (against the centre of the whole rectangle).
        BlockPos pos = be.getBlockPos();
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double dx = camera.x - (pos.getX() + centre.x);
        double dy = camera.y - (pos.getY() + centre.y);
        double dz = camera.z - (pos.getZ() + centre.z);
        double maxDist = data.renderDistance();
        if (dx * dx + dy * dy + dz * dz > maxDist * maxDist) {
            return;
        }

        PoseStack.Pose pose = poseStack.last();

        // Border ring and backing panel: drawn even while no picture is configured, otherwise
        // an empty frame would be completely invisible.
        if (data.border() || data.backing()) {
            VertexConsumer frame = buffers.getBuffer(RenderType.entityTranslucent(FRAME_TEXTURE));
            drawFrame(frame, pose, normal, right, up, sizeW, sizeH, plateDepth, packedLight, packedOverlay,
                    data.border(), data.backing(), false);
            if (data.renderBothSides()) {
                drawFrame(frame, pose, backNormal, right, up, sizeW, sizeH, 1.0F - plateDepth, packedLight,
                        packedOverlay, data.border(), data.backing(), true);
            }
        }

        // Picture.
        FrameTextureManager.Entry entry = data.isConfigured() ? FrameTextureManager.get(data) : null;
        if (entry != null && entry.status() == FrameTextureManager.Status.READY) {
            int lum = Mth.clamp(data.brightness(), 0, 255);
            int alpha = Mth.clamp(data.alpha(), 0, 255);
            int light = data.lit() ? LightTexture.FULL_BRIGHT : packedLight;

            VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(entry.textureLocation()));
            float[] uv = computeUv(data, entry, sizeW, sizeH);
            float halfR = sizeW / 2.0F - BORDER_HALF;
            float halfU = sizeH / 2.0F - BORDER_HALF;

            drawQuad(vc, pose, data, uv, halfR, halfU, centre, right, up, normal, light, packedOverlay, lum, alpha, false);
            if (data.renderBothSides()) {
                Vector3f backCentre = planeCentre(right, up, normalDir, sizeW, sizeH, 1.0F - imageDepth);
                drawQuad(vc, pose, data, uv, halfR, halfU, backCentre, right, up, backNormal, light, packedOverlay, lum, alpha, true);
            }
        }
    }

    /**
     * Centre of the rectangle on a plane parallel to the wall: half the rectangle span along
     * {@code right} and {@code up}, with the normal axis pinned to {@code depth}.
     */
    private static Vector3f planeCentre(Vector3f right, Vector3f up, Direction normal, int sizeW, int sizeH, float depth) {
        float cx = right.x * (sizeW / 2.0F) + up.x * (sizeH / 2.0F);
        float cy = right.y * (sizeW / 2.0F) + up.y * (sizeH / 2.0F);
        float cz = right.z * (sizeW / 2.0F) + up.z * (sizeH / 2.0F);
        if (normal.getStepX() != 0) {
            cx = depth;
        } else if (normal.getStepY() != 0) {
            cy = depth;
        } else {
            cz = depth;
        }
        return new Vector3f(cx, cy, cz);
    }

    /**
     * Draws the border ring and/or the backing panel on one plate plane. Coordinates are
     * frame-local: {@code x} runs along {@code right}, {@code y} along {@code up}, and the
     * rectangle spans {@code (0,0)} to {@code (sizeW, sizeH)}.
     */
    private static void drawFrame(VertexConsumer vc, PoseStack.Pose pose, Vector3f normal, Vector3f right, Vector3f up,
                                  int sizeW, int sizeH, float depth, int light, int overlay,
                                  boolean border, boolean backing, boolean back) {
        float t = BORDER_HALF;
        float w = sizeW;
        float h = sizeH;

        if (border) {
            // Top strip: samples the texture's top row, one repeat per block along its length.
            quad(vc, pose, normal, right, up, depth, light, overlay, back,
                    0.0F, h - t, 0.0F, RING_UV,
                    w, h - t, w, RING_UV,
                    w, h, w, 0.0F,
                    0.0F, h, 0.0F, 0.0F);
            // Bottom strip: texture's bottom row.
            quad(vc, pose, normal, right, up, depth, light, overlay, back,
                    0.0F, 0.0F, 0.0F, 1.0F,
                    w, 0.0F, w, 1.0F,
                    w, t, w, 1.0F - RING_UV,
                    0.0F, t, 0.0F, 1.0F - RING_UV);
            // Left strip: texture's left column, only between the corner squares.
            quad(vc, pose, normal, right, up, depth, light, overlay, back,
                    0.0F, t, 0.0F, h - t,
                    t, t, RING_UV, h - t,
                    t, h - t, RING_UV, t,
                    0.0F, h - t, 0.0F, t);
            // Right strip: texture's right column.
            quad(vc, pose, normal, right, up, depth, light, overlay, back,
                    w - t, t, 1.0F - RING_UV, h - t,
                    w, t, 1.0F, h - t,
                    w, h - t, 1.0F, t,
                    w - t, h - t, 1.0F - RING_UV, t);
        }

        if (backing) {
            // Flat dark panel behind the picture; hides the wall (and its block seams) when the
            // picture is translucent or has transparent pixels.
            quad(vc, pose, normal, right, up, depth, light, overlay, back,
                    t, t, PANEL_UV, PANEL_UV,
                    w - t, t, PANEL_UV, PANEL_UV,
                    w - t, h - t, PANEL_UV, PANEL_UV,
                    t, h - t, PANEL_UV, PANEL_UV);
        }
    }

    /**
     * Emits a flat quad on a plate plane. Corners are in frame-local coordinates and each UV
     * follows its corner; back-facing quads are emitted in the reverse winding so their faces
     * match the flipped normal. These quads take their colour from the texture and block light,
     * so the tint is always plain white.
     */
    private static void quad(VertexConsumer vc, PoseStack.Pose pose, Vector3f normal, Vector3f right, Vector3f up,
                             float depth, int light, int overlay, boolean back,
                             float x0, float y0, float u0, float v0,
                             float x1, float y1, float u1, float v1,
                             float x2, float y2, float u2, float v2,
                             float x3, float y3, float u3, float v3) {
        if (back) {
            vertex(vc, pose, normal, right, up, x3, y3, depth, u3, v3, light, overlay);
            vertex(vc, pose, normal, right, up, x2, y2, depth, u2, v2, light, overlay);
            vertex(vc, pose, normal, right, up, x1, y1, depth, u1, v1, light, overlay);
            vertex(vc, pose, normal, right, up, x0, y0, depth, u0, v0, light, overlay);
        } else {
            vertex(vc, pose, normal, right, up, x0, y0, depth, u0, v0, light, overlay);
            vertex(vc, pose, normal, right, up, x1, y1, depth, u1, v1, light, overlay);
            vertex(vc, pose, normal, right, up, x2, y2, depth, u2, v2, light, overlay);
            vertex(vc, pose, normal, right, up, x3, y3, depth, u3, v3, light, overlay);
        }
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vector3f normal, Vector3f right, Vector3f up,
                               float x, float y, float depth, float u, float v, int light, int overlay) {
        float px = right.x * x + up.x * y;
        float py = right.y * x + up.y * y;
        float pz = right.z * x + up.z * y;
        if (normal.x != 0.0F) {
            px = depth;
        } else if (normal.y != 0.0F) {
            py = depth;
        } else {
            pz = depth;
        }
        vc.addVertex(pose, px, py, pz)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, normal.x, normal.y, normal.z);
    }

    /**
     * Compute the UV window: aspect-fit inside the picture area (whose aspect depends on the
     * frame size in blocks), then zoom/pan/rotate.
     *
     * @return {@code {cu, cv, halfU, halfV, cos, sin}}
     */
    private static float[] computeUv(FrameData data, FrameTextureManager.Entry entry, int sizeW, int sizeH) {
        float imageAspect = entry.height() > 0 ? entry.width() / (float) entry.height() : 1.0F;
        float panelAspect = (sizeW - BORDER_HALF * 2.0F) / (sizeH - BORDER_HALF * 2.0F);
        float fitW;
        float fitH;
        if (imageAspect >= panelAspect) {
            fitW = 1.0F;
            fitH = panelAspect / imageAspect;
        } else {
            fitW = imageAspect / panelAspect;
            fitH = 1.0F;
        }
        float zoom = Mth.clamp(data.zoom(), 0.125F, 8.0F);
        float halfU = fitW / (2.0F * zoom);
        float halfV = fitH / (2.0F * zoom);
        float cu = 0.5F + Mth.clamp(data.offsetU(), -1.0F, 1.0F) * (0.5F - halfU);
        float cv = 0.5F + Mth.clamp(data.offsetV(), -1.0F, 1.0F) * (0.5F - halfV);
        float rot = data.rotation() * Mth.DEG_TO_RAD;
        return new float[]{cu, cv, halfU, halfV, Mth.cos(rot), Mth.sin(rot)};
    }

    private static void drawQuad(VertexConsumer vc, PoseStack.Pose pose, FrameData data, float[] uv,
                                 float halfR, float halfU, Vector3f centre,
                                 Vector3f right, Vector3f up, Vector3f normal,
                                 int light, int overlay, int lum, int alpha, boolean back) {
        float cu = uv[0];
        float cv = uv[1];
        float windowU = uv[2];
        float windowV = uv[3];
        float cos = uv[4];
        float sin = uv[5];

        // Corners in CCW order seen from outside (BL, BR, TR, TL).
        float[] signX = {-1.0F, 1.0F, 1.0F, -1.0F};
        float[] signY = {-1.0F, -1.0F, 1.0F, 1.0F};

        for (int i = 0; i < 4; i++) {
            int corner = back ? 3 - i : i;
            float sx = signX[corner];
            float sy = signY[corner];

            float px = centre.x + right.x * sx * halfR + up.x * sy * halfU;
            float py = centre.y + right.y * sx * halfR + up.y * sy * halfU;
            float pz = centre.z + right.z * sx * halfR + up.z * sy * halfU;

            float fx = data.flipX() ? -sx : sx;
            float fy = data.flipY() ? -sy : sy;
            float rx = fx * cos - fy * sin;
            float ry = fx * sin + fy * cos;

            float u = Mth.clamp(cu + rx * windowU, 0.0F, 1.0F);
            float v = Mth.clamp(cv - ry * windowV, 0.0F, 1.0F);

            vc.addVertex(pose, px, py, pz)
                    .setColor(lum, lum, lum, alpha)
                    .setUv(u, v)
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, normal.x, normal.y, normal.z);
        }
    }
}

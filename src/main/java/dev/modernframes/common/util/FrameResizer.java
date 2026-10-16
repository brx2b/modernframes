package dev.modernframes.common.util;

import dev.modernframes.ModernFramesConfig;
import dev.modernframes.common.block.entity.ImageFrameBlockEntity;
import dev.modernframes.common.data.FrameData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side multi-block reshape operations.
 *
 * <p>Every operation validates the target rectangle before touching the world:</p>
 * <ul>
 *   <li>size and area limits from the common config,</li>
 *   <li>new cells must be replaceable (air, water, plants...) or already part of the frame,</li>
 *   <li>the master block keeps the minimum corner of the rectangle; moving the anchor is
 *       handled by {@link #relocateMaster}.</li>
 * </ul>
 *
 * <p>All methods return {@code null} on success or a translation key describing the failure.</p>
 */
public final class FrameResizer {

    public static final String ERROR_AREA_LIMIT = "modernframes.error.area_limit";
    public static final String ERROR_BLOCKED = "modernframes.error.blocked";
    public static final String ERROR_MIN_SIZE = "modernframes.error.size_min";
    public static final String ERROR_NO_PERMISSION = "modernframes.error.no_permission";

    private FrameResizer() {
    }

    /** Clamps a requested size to the configured limits. Returns {@code {width, height}}. */
    public static int[] fitToLimits(int width, int height) {
        int maxSize = Math.max(1, ModernFramesConfig.Common.RESIZE_MAX_SIZE.get());
        int maxArea = Math.max(1, ModernFramesConfig.Common.RESIZE_MAX_AREA.get());
        int w = Mth.clamp(width, 1, maxSize);
        int h = Mth.clamp(height, 1, maxSize);
        while ((long) w * h > maxArea && (w > 1 || h > 1)) {
            if (w >= h && w > 1) {
                w--;
            } else if (h > 1) {
                h--;
            }
        }
        return new int[]{w, h};
    }

    /** GUI save path: re-anchors the rectangle at the master block with a new size. */
    @Nullable
    public static String resizeTo(ServerLevel level, ImageFrameBlockEntity master, int newWidth, int newHeight) {
        int[] fit = fitToLimits(newWidth, newHeight);
        BlockState state = master.getBlockState();
        FrameData data = master.getData();
        List<BlockPos> owned = cells(master.getBlockPos(), FrameGeometry.right(state), FrameGeometry.up(state),
                data.sizeW(), data.sizeH());
        return applyRect(level, master, fit[0], fit[1], owned);
    }

    /** Right-click resize (block + clicked face + sneak to shrink). */
    @Nullable
    public static String push(ServerLevel level, BlockPos clicked, Direction face, boolean shrink, Player player) {
        ImageFrameBlockEntity master = FrameGeometry.findMaster(level, clicked);
        if (master == null) {
            return null;
        }
        if (!master.canEdit(player)) {
            return ERROR_NO_PERMISSION;
        }
        BlockState state = master.getBlockState();
        Direction right = FrameGeometry.right(state);
        Direction up = FrameGeometry.up(state);
        int axis = FrameGeometry.planeAxis(face, right, up);
        if (axis == 0) {
            return null;
        }
        boolean horizontal = Math.abs(axis) == 1;
        FrameData data = master.getData();
        int w = data.sizeW();
        int h = data.sizeH();
        List<BlockPos> owned = cells(master.getBlockPos(), right, up, w, h);

        if (shrink) {
            if ((horizontal && w <= 1) || (!horizontal && h <= 1)) {
                return ERROR_MIN_SIZE;
            }
            int newW = horizontal ? w - 1 : w;
            int newH = horizontal ? h : h - 1;
            if (axis > 0) {
                return applyRect(level, master, newW, newH, owned);
            }
            // Removing rows/columns from the low side moves the anchor one cell inwards first.
            BlockPos newAnchor = master.getBlockPos().relative(horizontal ? right : up);
            ImageFrameBlockEntity moved = relocateMaster(level, master, newAnchor);
            return applyRect(level, moved, newW, newH, owned);
        }

        int newW = horizontal ? w + 1 : w;
        int newH = horizontal ? h : h + 1;
        int[] fit = fitToLimits(newW, newH);
        if (fit[0] != newW || fit[1] != newH) {
            return ERROR_AREA_LIMIT;
        }
        if (axis > 0) {
            return applyRect(level, master, newW, newH, owned);
        }
        // Expanding towards the low side moves the anchor onto the new cell first.
        BlockPos newAnchor = master.getBlockPos().relative(horizontal ? right.getOpposite() : up.getOpposite());
        if (!isOurs(level, master, newAnchor) && !level.getBlockState(newAnchor).canBeReplaced()) {
            return ERROR_BLOCKED;
        }
        ImageFrameBlockEntity moved = relocateMaster(level, master, newAnchor);
        return applyRect(level, moved, newW, newH, owned);
    }

    /** Removes every block of the frame, except {@code exceptPos} (used when the player breaks one part). */
    public static void collapse(ServerLevel level, ImageFrameBlockEntity master, @Nullable BlockPos exceptPos) {
        BlockState state = master.getBlockState();
        Direction right = FrameGeometry.right(state);
        Direction up = FrameGeometry.up(state);
        FrameData data = master.getData();
        for (int x = 0; x < data.sizeW(); x++) {
            for (int y = 0; y < data.sizeH(); y++) {
                BlockPos p = FrameGeometry.cell(master.getBlockPos(), right, up, x, y);
                if (!p.equals(exceptPos) && isOurs(level, master, p)) {
                    level.removeBlock(p, false);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Applies a rectangle of {@code newW x newH} cells anchored at the current master position.
     *
     * @param owned every cell of the previous rectangle, so rows/columns that fall outside the
     *              new rectangle are removed even when the anchor has moved.
     */
    private static String applyRect(ServerLevel level, ImageFrameBlockEntity master, int newW, int newH,
                                    List<BlockPos> owned) {
        BlockState state = master.getBlockState();
        BlockPos origin = master.getBlockPos();
        Direction right = FrameGeometry.right(state);
        Direction up = FrameGeometry.up(state);

        // 1. every cell of the target rectangle must be free or already ours
        for (int x = 0; x < newW; x++) {
            for (int y = 0; y < newH; y++) {
                BlockPos p = FrameGeometry.cell(origin, right, up, x, y);
                if (!isOurs(level, master, p) && !level.getBlockState(p).canBeReplaced()) {
                    return ERROR_BLOCKED;
                }
            }
        }

        // 2. drop cells that are no longer part of the rectangle
        for (BlockPos p : owned) {
            if (!isInside(origin, newW, newH, p, right, up) && isOurs(level, master, p)) {
                level.removeBlock(p, false);
            }
        }

        // 3. fill the gaps and repoint every slave at the master
        for (int x = 0; x < newW; x++) {
            for (int y = 0; y < newH; y++) {
                BlockPos p = FrameGeometry.cell(origin, right, up, x, y);
                if (!isOurs(level, master, p)) {
                    level.setBlock(p, state, Block.UPDATE_ALL);
                }
                if (level.getBlockEntity(p) instanceof ImageFrameBlockEntity be && be != master) {
                    be.setAsSlave(master.getBlockPos());
                }
            }
        }

        // 4. store the new size on the master
        master.applyData(master.getData().withSize(newW, newH));
        master.setChanged();

        // 5. sync the whole rectangle to the clients
        for (int x = 0; x < newW; x++) {
            for (int y = 0; y < newH; y++) {
                BlockPos p = FrameGeometry.cell(origin, right, up, x, y);
                level.sendBlockUpdated(p, state, state, Block.UPDATE_ALL);
            }
        }
        return null;
    }

    /**
     * Moves the master role (and its data) to another block of the frame; the previous anchor
     * becomes a plain slave.
     */
    private static ImageFrameBlockEntity relocateMaster(ServerLevel level, ImageFrameBlockEntity oldMaster, BlockPos newPos) {
        BlockPos oldPos = oldMaster.getBlockPos();
        BlockState state = oldMaster.getBlockState();
        FrameData data = oldMaster.getData();
        Direction right = FrameGeometry.right(state);
        Direction up = FrameGeometry.up(state);
        int w = data.sizeW();
        int h = data.sizeH();

        if (!isOurs(level, oldMaster, newPos)) {
            level.setBlock(newPos, state, Block.UPDATE_ALL);
        }

        // Install a fresh master block entity at the new anchor.
        level.removeBlockEntity(newPos);
        ImageFrameBlockEntity master = new ImageFrameBlockEntity(newPos, state);
        master.applyData(data);
        master.setOwner(oldMaster.getOwner());
        level.setBlockEntity(master);

        // The previous anchor becomes a plain slave of the frame.
        level.removeBlockEntity(oldPos);
        ImageFrameBlockEntity slave = new ImageFrameBlockEntity(oldPos, state);
        slave.setAsSlave(newPos);
        level.setBlockEntity(slave);

        // Repoint the other slaves of the previous rectangle at the new master.
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                BlockPos p = FrameGeometry.cell(oldPos, right, up, x, y);
                if (p.equals(oldPos) || p.equals(newPos)) {
                    continue;
                }
                if (level.getBlockEntity(p) instanceof ImageFrameBlockEntity be && !be.isMaster()) {
                    be.setAsSlave(newPos);
                }
            }
        }

        level.sendBlockUpdated(newPos, state, state, Block.UPDATE_ALL);
        level.sendBlockUpdated(oldPos, state, state, Block.UPDATE_ALL);
        return master;
    }

    private static boolean isOurs(ServerLevel level, ImageFrameBlockEntity master, BlockPos p) {
        if (p.equals(master.getBlockPos())) {
            return true;
        }
        return level.getBlockEntity(p) instanceof ImageFrameBlockEntity be
                && !be.isMaster()
                && master.getBlockPos().equals(be.getMasterPos());
    }

    private static boolean isInside(BlockPos origin, int w, int h, BlockPos p, Direction right, Direction up) {
        int dx = p.getX() - origin.getX();
        int dy = p.getY() - origin.getY();
        int dz = p.getZ() - origin.getZ();
        int u = dx * right.getStepX() + dy * right.getStepY() + dz * right.getStepZ();
        int v = dx * up.getStepX() + dy * up.getStepY() + dz * up.getStepZ();
        return u >= 0 && u < w && v >= 0 && v < h;
    }

    private static List<BlockPos> cells(BlockPos origin, Direction right, Direction up, int w, int h) {
        List<BlockPos> list = new ArrayList<>(Math.max(1, w * h));
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                list.add(FrameGeometry.cell(origin, right, up, x, y));
            }
        }
        return list;
    }
}

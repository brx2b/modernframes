package dev.modernframes.common.util;

import dev.modernframes.common.block.ImageFrameBlock;
import dev.modernframes.common.block.entity.ImageFrameBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Plane basis vectors and rectangle maths shared by the renderer, the server-side reshape
 * operations and the screen.
 *
 * <p>A frame occupies a planar rectangle of blocks. That rectangle is anchored at the master
 * block (always its minimum corner) and extends along two unit axes derived from the block
 * state: {@link #right} (content "right" when looking at the picture) and {@link #up}.</p>
 */
public final class FrameGeometry {

    private FrameGeometry() {
    }

    /** Outward normal of the picture (the wall side is opposite to it). */
    public static Direction normal(BlockState state) {
        return switch (state.getValue(ImageFrameBlock.FACE)) {
            case FLOOR -> Direction.UP;
            case CEILING -> Direction.DOWN;
            case WALL -> state.getValue(ImageFrameBlock.FACING);
        };
    }

    /** Content "right" when looking at the picture. */
    public static Direction right(BlockState state) {
        return state.getValue(ImageFrameBlock.FACING).getCounterClockWise();
    }

    /** Content "up" when looking at the picture ({@code normal x right}). */
    public static Direction up(BlockState state) {
        Direction n = normal(state);
        Direction r = right(state);
        return Direction.fromDelta(
                n.getStepY() * r.getStepZ() - n.getStepZ() * r.getStepY(),
                n.getStepZ() * r.getStepX() - n.getStepX() * r.getStepZ(),
                n.getStepX() * r.getStepY() - n.getStepY() * r.getStepX());
    }

    /** Position of cell {@code (x, y)} of a frame anchored at {@code origin}. */
    public static BlockPos cell(BlockPos origin, Direction right, Direction up, int x, int y) {
        return origin.offset(
                right.getStepX() * x + up.getStepX() * y,
                right.getStepY() * x + up.getStepY() * y,
                right.getStepZ() * x + up.getStepZ() * y);
    }

    /**
     * Decomposes a clicked face into a resize axis.
     *
     * @return +1 towards {@code right}, -1 towards its opposite, +2 towards {@code up},
     *         -2 towards its opposite, 0 when the face is not in the picture plane.
     */
    public static int planeAxis(Direction clicked, Direction right, Direction up) {
        if (clicked == right) {
            return 1;
        }
        if (clicked == right.getOpposite()) {
            return -1;
        }
        if (clicked == up) {
            return 2;
        }
        if (clicked == up.getOpposite()) {
            return -2;
        }
        return 0;
    }

    /** Resolves the master block entity of the frame containing {@code pos}, if any. */
    @Nullable
    public static ImageFrameBlockEntity findMaster(BlockGetter level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ImageFrameBlockEntity be)) {
            return null;
        }
        if (be.isMaster()) {
            return be;
        }
        BlockPos masterPos = be.getMasterPos();
        if (masterPos != null
                && level.getBlockEntity(masterPos) instanceof ImageFrameBlockEntity master
                && master.isMaster()) {
            return master;
        }
        return null;
    }
}

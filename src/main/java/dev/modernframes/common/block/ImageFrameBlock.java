package dev.modernframes.common.block;

import com.mojang.serialization.MapCodec;
import dev.modernframes.client.ClientHooks;
import dev.modernframes.common.block.entity.ImageFrameBlockEntity;
import dev.modernframes.common.network.ResizeFramePayload;
import dev.modernframes.common.util.FrameGeometry;
import dev.modernframes.common.util.FrameResizer;
import dev.modernframes.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The image frame block.
 *
 * <p>{@link #FACING} is the direction the picture faces (outwards). {@link #FACE} decides
 * whether the frame hangs on a wall, lies on the floor or hangs under the ceiling. Frames can
 * span several blocks: every block of the rectangle runs this same code, the actual state
 * lives in the master block entity.</p>
 *
 * <p>Interactions: empty hand opens the screen; holding the frame item and right-clicking one
 * of the frame's side faces expands it in that direction (sneak to shrink instead); breaking
 * any block collapses the whole frame and drops a single item.</p>
 */
public class ImageFrameBlock extends BaseEntityBlock {

    public static final MapCodec<ImageFrameBlock> CODEC = simpleCodec(ImageFrameBlock::new);

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<AttachFace> FACE = BlockStateProperties.ATTACH_FACE;

    private static final Map<Direction, VoxelShape> WALL_SHAPES = new EnumMap<>(Direction.class);
    private static final VoxelShape FLOOR_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 0.5, 16.0);
    private static final VoxelShape CEILING_SHAPE = Block.box(0.0, 15.5, 0.0, 16.0, 16.0, 16.0);

    static {
        // FACING points away from the wall: facing=north means the wall is on the +Z side.
        WALL_SHAPES.put(Direction.NORTH, Block.box(0.0, 0.0, 15.5, 16.0, 16.0, 16.0));
        WALL_SHAPES.put(Direction.SOUTH, Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 0.5));
        WALL_SHAPES.put(Direction.WEST, Block.box(15.5, 0.0, 0.0, 16.0, 16.0, 16.0));
        WALL_SHAPES.put(Direction.EAST, Block.box(0.0, 0.0, 0.0, 0.5, 16.0, 16.0));
    }

    public ImageFrameBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.SOUTH)
                .setValue(FACE, AttachFace.WALL));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FACE);
    }

    // ------------------------------------------------------------------
    // Placement, support and shape
    // ------------------------------------------------------------------

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        AttachFace face = switch (clicked) {
            case UP -> AttachFace.FLOOR;
            case DOWN -> AttachFace.CEILING;
            default -> AttachFace.WALL;
        };
        Direction facing = face == AttachFace.WALL
                ? clicked
                : context.getHorizontalDirection().getOpposite();
        return this.defaultBlockState().setValue(FACE, face).setValue(FACING, facing);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ImageFrameBlockEntity be && !be.isMaster()) {
            return true; // only the master needs a wall; the rest of the rectangle hangs off it
        }
        Direction attachment = switch (state.getValue(FACE)) {
            case WALL -> state.getValue(FACING).getOpposite();
            case FLOOR -> Direction.DOWN;
            case CEILING -> Direction.UP;
        };
        return !level.getBlockState(pos.relative(attachment)).isAir();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACE)) {
            case FLOOR -> FLOOR_SHAPE;
            case CEILING -> CEILING_SHAPE;
            case WALL -> WALL_SHAPES.get(state.getValue(FACING));
        };
    }

    // ------------------------------------------------------------------
    // Block entity and interaction
    // ------------------------------------------------------------------

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ImageFrameBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            ImageFrameBlockEntity master = FrameGeometry.findMaster(level, pos);
            if (master != null) {
                ClientHooks.openFrameScreen(master);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(ModRegistry.IMAGE_FRAME_ITEM.get()) || hand != InteractionHand.MAIN_HAND || !player.mayBuild()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        Direction right = FrameGeometry.right(state);
        Direction up = FrameGeometry.up(state);
        if (FrameGeometry.planeAxis(hit.getDirection(), right, up) == 0) {
            // Front/back faces keep their normal placement behaviour.
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            PacketDistributor.sendToServer(new ResizeFramePayload(pos, hit.getDirection(), player.isShiftKeyDown()));
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel serverLevel
                && serverLevel.getBlockEntity(pos) instanceof ImageFrameBlockEntity be) {
            // A frame is a single object: breaking any part collapses the whole rectangle.
            BlockPos masterPos = be.isMaster() ? pos : be.getMasterPos();
            if (masterPos != null && serverLevel.getBlockEntity(masterPos) instanceof ImageFrameBlockEntity master) {
                FrameResizer.collapse(serverLevel, master, pos);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}

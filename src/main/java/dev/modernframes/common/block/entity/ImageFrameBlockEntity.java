package dev.modernframes.common.block.entity;

import dev.modernframes.ModernFramesConfig;
import dev.modernframes.common.data.FrameData;
import dev.modernframes.registry.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Stores the frame configuration and synchronises it through vanilla block-entity update
 * packets, so no custom server -> client payload is needed.
 *
 * <p>A multi-block frame is a rectangle of these blocks: exactly one of them is the
 * <em>master</em> (it holds the {@link FrameData} and renders the whole picture), every other
 * one is a <em>slave</em> that only remembers the master position. The master always sits on
 * the minimum corner of the rectangle.</p>
 */
public class ImageFrameBlockEntity extends BlockEntity {

    private static final String TAG_DATA = "FrameData";
    private static final String TAG_OWNER = "Owner";
    private static final String TAG_MASTER = "Master";

    private FrameData data = FrameData.EMPTY;
    @Nullable
    private UUID owner;
    /** {@code null} when this block IS the master of its frame. */
    @Nullable
    private BlockPos masterPos;

    public ImageFrameBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.IMAGE_FRAME_ENTITY.get(), pos, state);
    }

    // ------------------------------------------------------------------
    // Master / slave bookkeeping
    // ------------------------------------------------------------------

    public boolean isMaster() {
        return this.masterPos == null;
    }

    @Nullable
    public BlockPos getMasterPos() {
        return this.masterPos;
    }

    /** Turns this block into a slave of {@code master}. No network traffic is sent here. */
    public void setAsSlave(BlockPos master) {
        this.masterPos = master.immutable();
        this.data = FrameData.EMPTY;
        this.owner = null;
        this.setChanged();
    }

    /** Silent data replacement used by the server-side reshape operations. */
    public void applyData(FrameData newData) {
        this.data = newData.clamped();
    }

    // ------------------------------------------------------------------
    // Data access
    // ------------------------------------------------------------------

    public FrameData getData() {
        return this.data;
    }

    public void setData(FrameData newData) {
        this.setData(newData, null);
    }

    public void setData(FrameData newData, @Nullable Player editor) {
        if (!this.isMaster()) {
            return;
        }
        this.data = newData.clamped();
        if (editor != null) {
            this.owner = editor.getUUID();
        }
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Nullable
    public UUID getOwner() {
        return this.owner;
    }

    /** Restores the owner when the master role moves to another block of the frame. */
    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        this.setChanged();
    }

    /**
     * Permission check shared by the server handlers and the client screen. Called on a slave
     * it delegates to the master, so callers can pass any block of the frame.
     */
    public boolean canEdit(Player player) {
        if (!this.isMaster() && this.masterPos != null && this.level != null
                && this.level.getBlockEntity(this.masterPos) instanceof ImageFrameBlockEntity master) {
            return master.canEdit(player);
        }
        if (player.hasPermissions(2)) {
            return true;
        }
        return switch (ModernFramesConfig.Common.EDIT_POLICY.get()) {
            case EVERYONE -> true;
            case CREATIVE_ONLY -> player.isCreative();
            case OWNER -> this.owner == null || this.owner.equals(player.getUUID());
        };
    }

    // ------------------------------------------------------------------
    // Persistence and synchronisation
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.masterPos != null) {
            tag.putLong(TAG_MASTER, this.masterPos.asLong());
        } else {
            tag.put(TAG_DATA, this.data.save());
            if (this.owner != null) {
                tag.putUUID(TAG_OWNER, this.owner);
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.masterPos = tag.contains(TAG_MASTER) ? BlockPos.of(tag.getLong(TAG_MASTER)) : null;
        this.data = this.masterPos == null ? FrameData.load(tag.getCompound(TAG_DATA)) : FrameData.EMPTY;
        this.owner = this.masterPos == null && tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}

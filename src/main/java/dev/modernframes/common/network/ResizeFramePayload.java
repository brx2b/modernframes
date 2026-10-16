package dev.modernframes.common.network;

import dev.modernframes.ModernFrames;
import dev.modernframes.common.util.FrameResizer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: quick resize of a frame by right-clicking one of its side faces while
 * holding the frame item (sneaking shrinks that side instead of expanding it).
 */
public record ResizeFramePayload(BlockPos pos, Direction face, boolean shrink) implements CustomPacketPayload {

    public static final Type<ResizeFramePayload> TYPE = new Type<>(ModernFrames.id("resize_frame"));

    public static final StreamCodec<FriendlyByteBuf, ResizeFramePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeEnum(payload.face);
                buf.writeBoolean(payload.shrink);
            },
            buf -> new ResizeFramePayload(buf.readBlockPos(), buf.readEnum(Direction.class), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ResizeFramePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // Anti-cheat: only allow resizing frames the player is close to.
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64.0 * 64.0) {
                return;
            }
            String error = FrameResizer.push((ServerLevel) player.level(), payload.pos, payload.face, payload.shrink, player);
            if (error != null) {
                context.reply(new FrameResultPayload(payload.pos, false, error));
            }
        });
    }
}

package dev.modernframes.common.network;

import dev.modernframes.ModernFrames;
import dev.modernframes.client.ClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> client: the result of an edit request. {@code message} is a translation key
 * (or a plain message) shown to the player.
 */
public record FrameResultPayload(BlockPos pos, boolean success, String message) implements CustomPacketPayload {

    public static final Type<FrameResultPayload> TYPE = new Type<>(ModernFrames.id("frame_result"));

    public static final StreamCodec<FriendlyByteBuf, FrameResultPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeBoolean(payload.success);
                buf.writeUtf(payload.message, 256);
            },
            buf -> new FrameResultPayload(buf.readBlockPos(), buf.readBoolean(), buf.readUtf(256)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FrameResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientHooks.onFrameResult(payload));
    }
}

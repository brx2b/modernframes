package dev.modernframes.common.network;

import dev.modernframes.ModernFrames;
import dev.modernframes.common.block.entity.ImageFrameBlockEntity;
import dev.modernframes.common.data.FrameData;
import dev.modernframes.common.util.FrameGeometry;
import dev.modernframes.common.util.FrameResizer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: save the frame configuration after editing it in the screen. The payload
 * also carries the desired frame size, so saving may reshape the multi-block rectangle.
 */
public record SetFramePayload(BlockPos pos, FrameData data) implements CustomPacketPayload {

    public static final Type<SetFramePayload> TYPE = new Type<>(ModernFrames.id("set_frame"));

    public static final StreamCodec<FriendlyByteBuf, SetFramePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                FrameData.STREAM_CODEC.encode(buf, payload.data);
            },
            buf -> new SetFramePayload(buf.readBlockPos(), FrameData.STREAM_CODEC.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetFramePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            // Anti-cheat: only allow editing frames the player is close to.
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos)) > 64.0 * 64.0) {
                return;
            }
            ImageFrameBlockEntity frame = FrameGeometry.findMaster(level, payload.pos);
            if (frame == null) {
                return;
            }

            FrameData data = payload.data;
            String error = FramePolicy.validate(data);
            if (error != null) {
                context.reply(new FrameResultPayload(payload.pos, false, error));
                return;
            }
            if (!frame.canEdit(player)) {
                context.reply(new FrameResultPayload(payload.pos, false, "modernframes.error.no_permission"));
                return;
            }

            // Enforce the server limits, then reshape the frame if its size changed.
            int[] fit = FrameResizer.fitToLimits(data.sizeW(), data.sizeH());
            data = data.withSize(fit[0], fit[1]);
            if (fit[0] != frame.getData().sizeW() || fit[1] != frame.getData().sizeH()) {
                String resizeError = FrameResizer.resizeTo(level, frame, fit[0], fit[1]);
                if (resizeError != null) {
                    context.reply(new FrameResultPayload(payload.pos, false, resizeError));
                    return;
                }
            }

            frame.setData(data, player);
            context.reply(new FrameResultPayload(payload.pos, true, ""));
        });
    }
}

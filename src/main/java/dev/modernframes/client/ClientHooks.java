package dev.modernframes.client;

import dev.modernframes.client.screen.FrameScreen;
import dev.modernframes.common.block.entity.ImageFrameBlockEntity;
import dev.modernframes.common.network.FrameResultPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Client-only entry points invoked from common code through {@code level.isClientSide} guards.
 */
public final class ClientHooks {

    private ClientHooks() {
    }

    public static void openFrameScreen(ImageFrameBlockEntity frame) {
        Minecraft.getInstance().setScreen(new FrameScreen(frame));
    }

    public static void onFrameResult(FrameResultPayload payload) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player != null) {
            Component message = payload.success()
                    ? Component.translatable("modernframes.message.saved").withStyle(ChatFormatting.GREEN)
                    : Component.translatable(payload.message()).withStyle(ChatFormatting.RED);
            player.displayClientMessage(message, true);
        }
        if (client.screen instanceof FrameScreen screen) {
            screen.onServerResult(payload);
        }
    }
}

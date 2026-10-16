package dev.modernframes.client;

import dev.modernframes.ModernFrames;
import dev.modernframes.client.texture.FrameTextureManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Game-bus client events: animation ticking and cache cleanup on disconnect.
 */
@EventBusSubscriber(modid = ModernFrames.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        FrameTextureManager.tick();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FrameTextureManager.clearAll();
    }
}

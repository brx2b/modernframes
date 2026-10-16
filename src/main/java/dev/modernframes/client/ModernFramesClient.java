package dev.modernframes.client;

import dev.modernframes.ModernFrames;
import dev.modernframes.client.render.ImageFrameRenderer;
import dev.modernframes.client.texture.FrameImageLoader;
import dev.modernframes.registry.ModRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Mod-bus client setup: block entity renderer and image codec registration.
 */
@EventBusSubscriber(modid = ModernFrames.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModernFramesClient {

    private ModernFramesClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        FrameImageLoader.registerCodecs();
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModRegistry.IMAGE_FRAME_ENTITY.get(), ImageFrameRenderer::new);
    }
}

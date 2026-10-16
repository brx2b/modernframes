package dev.modernframes;

import dev.modernframes.common.network.ModNetwork;
import dev.modernframes.registry.ModRegistry;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ModernFrames — modern image frames for Minecraft NeoForge.
 *
 * <p>Displays pictures from remote URLs (any image API), from the local sandbox folder or from
 * absolute paths when the server policy allows it. Images are fetched and decoded asynchronously
 * and cached per-client, so the render thread never blocks.</p>
 */
@Mod(ModernFrames.MODID)
public final class ModernFrames {

    public static final String MODID = "modernframes";
    public static final Logger LOGGER = LoggerFactory.getLogger("ModernFrames");

    public ModernFrames(IEventBus modBus, ModContainer container) {
        ModRegistry.register(modBus);
        ModNetwork.register(modBus);

        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON,
                ModernFramesConfig.Common.SPEC);
        if (FMLEnvironment.dist.isClient()) {
            container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,
                    ModernFramesConfig.Client.SPEC);
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}

package dev.modernframes.registry;

import dev.modernframes.ModernFrames;
import dev.modernframes.common.block.ImageFrameBlock;
import dev.modernframes.common.block.entity.ImageFrameBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Central registry for blocks, items and block entities.
 */
public final class ModRegistry {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ModernFrames.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModernFrames.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ModernFrames.MODID);

    public static final DeferredBlock<ImageFrameBlock> IMAGE_FRAME = BLOCKS.register(
            "image_frame",
            () -> new ImageFrameBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(0.4F)
                    // Multi-block frames must not be partly blown up: blast-proof (but still
                    // player-breakable).
                    .explosionResistance(1200.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    public static final DeferredItem<BlockItem> IMAGE_FRAME_ITEM =
            ITEMS.registerSimpleBlockItem("image_frame", IMAGE_FRAME);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ImageFrameBlockEntity>> IMAGE_FRAME_ENTITY =
            BLOCK_ENTITY_TYPES.register("image_frame",
                    () -> BlockEntityType.Builder.of(ImageFrameBlockEntity::new, IMAGE_FRAME.get()).build(null));

    private ModRegistry() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        modBus.addListener(ModRegistry::addCreativeTabEntries);
    }

    private static void addCreativeTabEntries(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(IMAGE_FRAME_ITEM.get());
        }
    }
}

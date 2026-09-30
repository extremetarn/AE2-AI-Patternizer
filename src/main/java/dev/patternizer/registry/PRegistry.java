package dev.patternizer.registry;

import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import dev.patternizer.AIPatternizer;
import dev.patternizer.block.AiPatternizerBlock;
import dev.patternizer.menu.AiPatternizerMenu;

public final class PRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS,
            AIPatternizer.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS,
            AIPatternizer.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES,
            AIPatternizer.MOD_ID);

    public static final RegistryObject<Block> AI_PATTERNIZER = BLOCKS.register("ai_patternizer",
            AiPatternizerBlock::new);

    public static final RegistryObject<Item> AI_PATTERNIZER_ITEM = ITEMS.register("ai_patternizer",
            () -> new BlockItem(AI_PATTERNIZER.get(), new Item.Properties()));

    public static final RegistryObject<MenuType<AiPatternizerMenu>> AI_PATTERNIZER_MENU = MENUS.register(
            "ai_patternizer",
            () -> IForgeMenuType
                    .create((windowId, inv, data) -> new AiPatternizerMenu(windowId, inv, data.readBlockPos())));

    private PRegistry() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        MENUS.register(modBus);
    }
}

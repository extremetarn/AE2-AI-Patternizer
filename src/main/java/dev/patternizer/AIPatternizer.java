package dev.patternizer;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import dev.patternizer.net.PatternizerNetwork;
import dev.patternizer.registry.PRegistry;

/**
 * AI Patternizer —— AE2 附属 mod。
 * M1：AI 样板编写台方块 + 双端网络包 + 假数据编码链路（处理样板）。
 */
@Mod(AIPatternizer.MOD_ID)
public class AIPatternizer {
    public static final String MOD_ID = "aipatternizer";

    public AIPatternizer() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        PRegistry.register(modBus);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::buildCreativeTabContents);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(PatternizerNetwork::register);
    }

    private void buildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(PRegistry.AI_PATTERNIZER_ITEM);
        }
    }
}

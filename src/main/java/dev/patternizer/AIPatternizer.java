package dev.patternizer;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import dev.patternizer.config.PatternizerClientConfig;
import dev.patternizer.net.PatternizerNetwork;
import dev.patternizer.registry.PRegistry;

/**
 * AI Patternizer —— AE2 附属 mod。
 * M1：编写台方块 + 双端通信 + 假数据编码链路。
 * M2：LLM 接入（配置/OpenAI 客户端/检索器/自我修正循环）+ 按 spec 编码。
 */
@Mod(AIPatternizer.MOD_ID)
public class AIPatternizer {
    public static final String MOD_ID = "aipatternizer";

    public AIPatternizer() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, PatternizerClientConfig.SPEC,
                "aipatternizer-client.toml");

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

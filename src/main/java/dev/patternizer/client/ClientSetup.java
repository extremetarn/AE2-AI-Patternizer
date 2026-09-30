package dev.patternizer.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.client.gui.screens.MenuScreens;

import dev.patternizer.AIPatternizer;
import dev.patternizer.client.screen.AiPatternizerScreen;
import dev.patternizer.registry.PRegistry;

@Mod.EventBusSubscriber(modid = AIPatternizer.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(PRegistry.AI_PATTERNIZER_MENU.get(), AiPatternizerScreen::new));
    }
}

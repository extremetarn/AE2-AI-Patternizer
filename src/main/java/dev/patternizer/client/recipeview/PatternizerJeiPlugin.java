package dev.patternizer.client.recipeview;

import net.minecraft.resources.ResourceLocation;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;

import dev.patternizer.AIPatternizer;

/** JEI 插件注册点：捕获运行时供 JeiBridge 使用（@JeiPlugin 由 JEI 自动发现）。 */
@JeiPlugin
public class PatternizerJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = new ResourceLocation(AIPatternizer.MOD_ID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiBridge.setRuntime(jeiRuntime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiBridge.setRuntime(null);
    }
}

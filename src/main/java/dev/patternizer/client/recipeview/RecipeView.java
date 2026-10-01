package dev.patternizer.client.recipeview;

import net.minecraftforge.fml.ModList;

/** 桥选择入口：JEI 在场且运行时就绪 → JeiBridge，否则 NoBridge（§10.21）。 */
public final class RecipeView {

    private RecipeView() {
    }

    public static RecipeViewSource source() {
        if (ModList.get().isLoaded("jei") && JeiBridge.runtime() != null) {
            return JeiBridge.INSTANCE;
        }
        return NoBridge.INSTANCE;
    }

    public static boolean active() {
        return source() != NoBridge.INSTANCE;
    }
}

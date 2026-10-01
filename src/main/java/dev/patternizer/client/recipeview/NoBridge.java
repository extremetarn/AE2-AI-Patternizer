package dev.patternizer.client.recipeview;

import java.util.List;

/** 无配方 mod 时的空桥（§10.21 降级）。 */
public final class NoBridge implements RecipeViewSource {

    public static final NoBridge INSTANCE = new NoBridge();

    private NoBridge() {
    }

    @Override
    public List<String> infoTexts(String itemId) {
        return List.of();
    }

    @Override
    public List<ViewRecipe> recipesProducing(String itemId) {
        return List.of();
    }

    @Override
    public List<String> workstations(String recipeTypeId) {
        return List.of();
    }
}

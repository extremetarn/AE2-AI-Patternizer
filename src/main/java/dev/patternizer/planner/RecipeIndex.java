package dev.patternizer.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import dev.patternizer.net.RecipeResolver;

/**
 * output → recipes 反查索引（§10.17）：数据包加载后一次性预构建，展开时 O(1) 查询。
 * 复用 RecipeResolver.isValidRoute 的有效性过滤（纹饰/动态配方剔除）。
 * 数据包重载时经 {@link #invalidate()} 失效重建。
 */
public final class RecipeIndex {

    private static volatile Map<Item, List<Recipe<?>>> byOutput;

    private RecipeIndex() {
    }

    public static synchronized Map<Item, List<Recipe<?>>> get(RecipeManager recipeManager, Level level) {
        if (byOutput == null) {
            byOutput = build(recipeManager, level);
        }
        return byOutput;
    }

    public static synchronized void invalidate() {
        byOutput = null;
    }

    private static Map<Item, List<Recipe<?>>> build(RecipeManager recipeManager, Level level) {
        Map<Item, List<Recipe<?>>> map = new HashMap<>();
        for (RecipeType<?> type : ForgeRegistries.RECIPE_TYPES) {
            for (Recipe<?> recipe : RecipeResolver.allOf(recipeManager, type)) {
                var result = recipe.getResultItem(level.registryAccess());
                if (result.isEmpty() || !RecipeResolver.isValidRoute(recipe, level, result.getItem())) {
                    continue;
                }
                map.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(recipe);
            }
        }
        return map;
    }
}

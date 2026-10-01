package dev.patternizer.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import dev.patternizer.net.RecipeResolver;

/**
 * 缺口分析器（§7.3b）：从目标物品递归展开配方树，与 ME 网络现状比对，输出缺口清单。
 * - 备忘录去重（同一物品只展开一次，需求量累加）；
 * - 环检测（精华环类自引用配方 → 列入循环节点交玩家处理）；
 * - 节点数硬上限（§10.17）；
 * - 轻量选路：未知输入最少者优先——网络能做的原料多的路线自然胜出（§10.5 第一层）。
 */
public final class GapAnalyzer {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 缺口样板节点：缺什么、缺多少、用哪条配方造、还剩几条备选路线。 */
    public record PlanNode(Item item, long amount, Recipe<?> recipe, int alternatives) {

        public String recipeId() {
            return recipe.getId().toString();
        }

        public String recipeTypeId() {
            var key = ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
            return key != null ? key.toString() : "unknown:unknown";
        }
    }

    public record PlanResult(List<PlanNode> missing, List<Item> cycleItems, List<Item> manualItems,
            int totalNodes) {
    }

    private static final int MAX_NODES = 5000;

    private GapAnalyzer() {
    }

    public static PlanResult analyze(RecipeManager recipeManager, Level level, NetworkSurvey survey,
            Item target, long count) {
        Map<Item, List<Recipe<?>>> index = RecipeIndex.get(recipeManager, level);

        List<PlanNode> missing = new ArrayList<>();
        List<Item> cycles = new ArrayList<>();
        List<Item> manual = new ArrayList<>();
        Set<Item> expanded = new HashSet<>();
        Set<Item> visiting = new HashSet<>();
        Map<Item, Long> demand = new HashMap<>();
        int[] nodes = { 0 };

        // 根节点无条件展开：玩家点「整线补齐」是要让网络能造这个物品，
        // 库存里有没有/网络会不会做都不能短路（2026-10-01「共0节点」假阳性案）
        expand(index, level, survey, target, count, missing, cycles, manual, expanded, visiting, demand,
                nodes, true);

        // 需求量以最终累加值修正到节点上
        List<PlanNode> finalMissing = new ArrayList<>();
        for (PlanNode node : missing) {
            finalMissing.add(new PlanNode(node.item(), demand.getOrDefault(node.item(), node.amount()),
                    node.recipe(), node.alternatives()));
        }
        return new PlanResult(finalMissing, cycles, manual, nodes[0]);
    }

    private static void expand(Map<Item, List<Recipe<?>>> index, Level level, NetworkSurvey survey,
            Item item, long amount,
            List<PlanNode> missing, List<Item> cycles, List<Item> manual,
            Set<Item> expanded, Set<Item> visiting, Map<Item, Long> demand, int[] nodes, boolean isRoot) {
        if (nodes[0] >= MAX_NODES) {
            return;
        }
        // 满足判定：根节点只在「网络已会合成」时才算覆盖——「库存里有」不算
        // （整线补齐的语义是让网络能造，不是看仓库里现在有没有）；
        // 非根节点沿用「会做 or 存量够」双重短路
        boolean satisfied = isRoot
                ? survey.canCraft(item)
                : (survey.canCraft(item) || survey.stockOf(item) >= amount);
        if (satisfied) {
            return;
        }
        // 环检测：展开路径上再次遇到自己
        if (visiting.contains(item)) {
            if (!cycles.contains(item)) {
                cycles.add(item);
            }
            return;
        }
        // 已展开过：只累加需求
        if (expanded.contains(item)) {
            demand.merge(item, amount, Long::sum);
            return;
        }

        List<Recipe<?>> recipes = index.getOrDefault(item, List.of());
        if (recipes.isEmpty()) {
            // 叶子：无任何配方（矿石/掉落/仪式），列入手动清单
            if (!manual.contains(item)) {
                manual.add(item);
            }
            return;
        }

        Recipe<?> chosen = chooseBest(level, survey, recipes, visiting);
        if (chosen == null) {
            if (!manual.contains(item)) {
                manual.add(item);
            }
            return;
        }

        visiting.add(item);
        expanded.add(item);
        demand.merge(item, amount, Long::sum);
        nodes[0]++;
        missing.add(new PlanNode(item, amount, chosen, recipes.size() - 1));
        LOGGER.info("[aipatternizer] gap node: {} x{} <- {} (alternatives={})",
                ForgeRegistries.ITEMS.getKey(item), amount, chosen.getId(), recipes.size() - 1);

        long outCount = Math.max(1, chosen.getResultItem(level.registryAccess()).getCount());
        long runs = ceilDiv(amount, outCount);
        for (Map.Entry<Item, Integer> input : mergedInputs(chosen).entrySet()) {
            expand(index, level, survey, input.getKey(), runs * input.getValue(),
                    missing, cycles, manual, expanded, visiting, demand, nodes, false);
        }
        visiting.remove(item);
    }

    /**
     * 轻量选路（§10.5 第一层）：统计每条配方「网络不会做且存量不足」的输入数，
     * 最少者胜；会制造循环的路线（输入里有展开路径上的物品，如 星→块→星）重罚。
     * 网络能自给自足的路线自然胜，荒诞路线（拆工具得粒）自然输。
     */
    private static Recipe<?> chooseBest(Level level, NetworkSurvey survey, List<Recipe<?>> recipes,
            Set<Item> visiting) {
        Recipe<?> best = null;
        long bestScore = Long.MAX_VALUE;
        for (Recipe<?> recipe : recipes) {
            long unknown = 0;
            boolean cycleForming = false;
            for (Map.Entry<Item, Integer> input : mergedInputs(recipe).entrySet()) {
                if (visiting.contains(input.getKey())) {
                    cycleForming = true;
                }
                if (!survey.canCraft(input.getKey()) && survey.stockOf(input.getKey()) < input.getValue()) {
                    unknown++;
                }
            }
            long score = unknown + (cycleForming ? 1000 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = recipe;
            }
        }
        return best;
    }

    /** 配料按物品合并计数（跳过空配料，取首个变体）。 */
    public static Map<Item, Integer> mergedInputs(Recipe<?> recipe) {
        Map<Item, Integer> merged = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            var stack = RecipeResolver.firstStack(ingredient);
            if (!stack.isEmpty()) {
                merged.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return merged;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}

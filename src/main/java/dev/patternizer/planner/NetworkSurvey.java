package dev.patternizer.planner;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.AEKeyFilter;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

/**
 * ME 网络现状调查（§7.3a）：
 * - 可下单物品（合成服务枚举，即网络已有样板能合成什么）；
 * - 供应器里现有样板的产物集（v0.7 调研：getLogic().getPatternInv() + decodePattern）；
 * - 网络存量统计。
 */
public record NetworkSurvey(Set<Item> craftableItems, Set<Item> patternOutputs, Map<Item, Long> stock) {

    public static NetworkSurvey empty() {
        return new NetworkSurvey(Set.of(), Set.of(), Map.of());
    }

    public static NetworkSurvey of(IGrid grid) {
        // ① 合成服务：网络已会合成的物品
        Set<Item> craftable = new HashSet<>();
        for (var key : grid.getCraftingService().getCraftables(AEKeyFilter.none())) {
            if (key instanceof AEItemKey itemKey) {
                craftable.add(itemKey.getItem());
            }
        }

        // ② 供应器现有样板产物（与①互为佐证，样板可能在未上线的供应器里）
        Set<Item> patternOutputs = new HashSet<>();
        for (Class<?> machineClass : grid.getMachineClasses()) {
            if (!PatternProviderLogicHost.class.isAssignableFrom(machineClass)) {
                continue;
            }
            for (Object machine : grid.getMachines(machineClass)) {
                if (!(machine instanceof PatternProviderLogicHost host)) {
                    continue;
                }
                var inv = host.getLogic().getPatternInv();
                for (int slot = 0; slot < inv.size(); slot++) {
                    ItemStack pattern = inv.getStackInSlot(slot);
                    if (pattern.isEmpty()) {
                        continue;
                    }
                    var details = PatternDetailsHelper.decodePattern(pattern,
                            host.getBlockEntity().getLevel());
                    if (details != null) {
                        for (var out : details.getOutputs()) {
                            if (out.what() instanceof AEItemKey itemKey) {
                                patternOutputs.add(itemKey.getItem());
                            }
                        }
                    }
                }
            }
        }

        // ③ 网络存量
        Map<Item, Long> stock = new HashMap<>();
        for (var entry : grid.getStorageService().getInventory().getAvailableStacks()) {
            if (entry.getKey() instanceof AEItemKey itemKey) {
                stock.put(itemKey.getItem(), entry.getLongValue());
            }
        }
        return new NetworkSurvey(craftable, patternOutputs, stock);
    }

    /** 该物品网络已可自动合成（已有样板）。 */
    public boolean canCraft(Item item) {
        return craftableItems.contains(item) || patternOutputs.contains(item);
    }

    public long stockOf(Item item) {
        return stock.getOrDefault(item, 0L);
    }
}

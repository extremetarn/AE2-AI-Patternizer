package dev.patternizer.planner;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import appeng.api.networking.IGrid;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

/**
 * 自动落位器（v0.8）：把编码好的样板按配方类型自动写入对应机器的样板供应器。
 *
 * 网络机器扫描（v0.7）：枚举网络上的样板供应器，读取其推送方向上的贴附机器。
 * 放置规则：
 * - 合成样板（原生 crafting）→ 任意有空槽的供应器（分子装配室通用）；
 * - 处理/机器样板 → 贴附机器与配方类型词干匹配的供应器（如 ae2:inscriber ↔ inscriber 方块）；
 * - 找不到匹配 → 待办清单（样板交还玩家）。
 * 只写空槽，绝不覆盖供应器里已有的样板。
 */
public final class AutoPlacer {

    /** 待落位的样板及其配方类型。 */
    public record PlacedPattern(ItemStack pattern, String recipeTypeId) {
    }

    public record PlaceResult(int placed, List<String> placedLines, List<String> todoLines) {
    }

    private record ProviderInfo(BlockPos pos, PatternProviderLogicHost host, List<String> adjacentBlockPaths) {
    }

    private AutoPlacer() {
    }

    public static PlaceResult placeAll(IGrid grid, Level level, List<PlacedPattern> patterns) {
        List<ProviderInfo> providers = scanProviders(grid, level);
        int placed = 0;
        List<String> placedLines = new ArrayList<>();
        List<String> todoLines = new ArrayList<>();

        for (PlacedPattern pattern : patterns) {
            ProviderInfo target = findProvider(providers, pattern, level);
            if (target != null && insertIntoEmptySlot(target.host(), pattern.pattern())) {
                placed++;
                placedLines.add(pattern.pattern().getHoverName().getString() + " → "
                        + target.pos().toShortString());
            } else {
                todoLines.add(pattern.pattern().getHoverName().getString()
                        + "（" + pattern.recipeTypeId() + "：无匹配供应器或已满）");
            }
        }
        return new PlaceResult(placed, placedLines, todoLines);
    }

    /** 网络机器扫描：供应器位置 + 推送方向上的贴附机器路径列表。 */
    private static List<ProviderInfo> scanProviders(IGrid grid, Level level) {
        List<ProviderInfo> out = new ArrayList<>();
        for (Class<?> machineClass : grid.getMachineClasses()) {
            if (!PatternProviderLogicHost.class.isAssignableFrom(machineClass)) {
                continue;
            }
            for (Object machine : grid.getMachines(machineClass)) {
                if (!(machine instanceof PatternProviderLogicHost host)) {
                    continue;
                }
                BlockPos pos = host.getBlockEntity().getBlockPos();
                List<String> adjacent = new ArrayList<>();
                for (var dir : host.getTargets()) {
                    var block = level.getBlockState(pos.relative(dir)).getBlock();
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
                    if (id != null) {
                        adjacent.add(id.getPath());
                    }
                }
                out.add(new ProviderInfo(pos, host, adjacent));
            }
        }
        return out;
    }

    private static ProviderInfo findProvider(List<ProviderInfo> providers, PlacedPattern pattern,
            Level level) {
        // 合成样板：任意有空槽的供应器（分子装配室通用）
        if ("minecraft:crafting".equals(pattern.recipeTypeId())) {
            for (ProviderInfo provider : providers) {
                if (hasEmptySlot(provider.host())) {
                    return provider;
                }
            }
            return null;
        }
        // 机器样板：贴附机器与配方类型词干匹配
        String stem = stem(typePath(pattern.recipeTypeId()));
        for (ProviderInfo provider : providers) {
            if (!hasEmptySlot(provider.host())) {
                continue;
            }
            for (String blockPath : provider.adjacentBlockPaths()) {
                if (matchesStem(blockPath, stem)) {
                    return provider;
                }
            }
        }
        return null;
    }

    private static String typePath(String recipeTypeId) {
        int idx = recipeTypeId.indexOf(':');
        return idx >= 0 ? recipeTypeId.substring(idx + 1) : recipeTypeId;
    }

    /** 词干化：去掉配方类型名尾部的 -ing/-er/-tion 等，如 crushing→crush、smelting→smelt。 */
    private static String stem(String typePath) {
        String s = typePath;
        if (s.endsWith("ing") && s.length() > 5) {
            s = s.substring(0, s.length() - 3);
        }
        if (s.endsWith("ing") && s.length() > 4) {
            s = s.substring(0, s.length() - 3);
        }
        return s;
    }

    private static boolean matchesStem(String blockPath, String stem) {
        return !stem.isEmpty() && (blockPath.contains(stem) || stem.contains(blockPath));
    }

    private static boolean hasEmptySlot(PatternProviderLogicHost host) {
        var inv = host.getLogic().getPatternInv();
        for (int slot = 0; slot < inv.size(); slot++) {
            if (inv.getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 只写空槽，绝不覆盖供应器里已有的样板（v0.8 规则 1）。 */
    private static boolean insertIntoEmptySlot(PatternProviderLogicHost host, ItemStack pattern) {
        var inv = host.getLogic().getPatternInv();
        for (int slot = 0; slot < inv.size(); slot++) {
            if (inv.getStackInSlot(slot).isEmpty()) {
                inv.insertItem(slot, pattern, false);
                return true;
            }
        }
        return false;
    }
}

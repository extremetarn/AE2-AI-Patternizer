package dev.patternizer.net;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.StorageHelper;

/**
 * ME 网络存取帮助类：空白样板扣取与编码结果回写。
 */
public final class GridPatternIO {

    private GridPatternIO() {
    }

    /** 从网络提取指定 key（modulate 实际扣货），返回成功提取数量。 */
    public static long extract(IGrid grid, AEKey what, long amount, IActionSource source) {
        var storage = grid.getStorageService().getInventory();
        var energy = grid.getEnergyService();
        return StorageHelper.poweredExtraction(energy, storage, what, amount, source, Actionable.MODULATE);
    }

    /** 向网络插入指定 key（modulate 实际入货），返回成功插入数量。 */
    public static long insert(IGrid grid, AEKey what, long amount, IActionSource source) {
        var storage = grid.getStorageService().getInventory();
        var energy = grid.getEnergyService();
        return StorageHelper.poweredInsert(energy, storage, what, amount, source, Actionable.MODULATE);
    }
}

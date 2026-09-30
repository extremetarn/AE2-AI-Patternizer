package dev.patternizer.spec;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import dev.patternizer.spec.PatternSpec.Entry;
import dev.patternizer.spec.PatternSpec.Role;

/**
 * 催化剂布局计算（§6.2）：把 PatternSpec 转成 AE2 编码用的最终输入/输出数组。
 * - CONSUMED / CATALYST_DURABILITY（折批后按整件消耗）：进输入格；
 * - CATALYST_RETURNED：同时进输入格末位与副产物输出格（返还式）；
 * - CATALYST_PREPLACED：不进样板任何格。
 */
public final class CatalystLayout {

    private CatalystLayout() {
    }

    public static GenericStack[] buildInputs(PatternSpec spec) {
        List<GenericStack> consumed = new ArrayList<>();
        List<GenericStack> returnedCatalysts = new ArrayList<>();
        for (Entry e : spec.inputs) {
            switch (e.role) {
            case CONSUMED, CATALYST_DURABILITY -> consumed.add(toGenericStack(e));
            case CATALYST_RETURNED -> returnedCatalysts.add(toGenericStack(e));
            case CATALYST_PREPLACED -> {
                // 不写入样板
            }
            }
        }
        // 催化剂置于输入末位，保证最后发配（§6.2）
        consumed.addAll(returnedCatalysts);
        return consumed.toArray(new GenericStack[0]);
    }

    public static GenericStack[] buildOutputs(PatternSpec spec) {
        List<GenericStack> out = new ArrayList<>();
        for (Entry e : spec.outputs) {
            out.add(toGenericStack(e));
        }
        // 返还式催化剂追加为副产物（主产物 outputs[0] 永远不是催化剂，见 §10.16）
        for (Entry e : spec.inputs) {
            if (e.role == Role.CATALYST_RETURNED) {
                out.add(toGenericStack(e));
            }
        }
        return out.toArray(new GenericStack[0]);
    }

    private static GenericStack toGenericStack(Entry e) {
        if (e.isFluid()) {
            var fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(e.fluid));
            return new GenericStack(AEFluidKey.of(new FluidStack(fluid, 1000)), e.amount);
        }
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(e.item));
        return new GenericStack(AEItemKey.of(new ItemStack(item)), e.count);
    }
}

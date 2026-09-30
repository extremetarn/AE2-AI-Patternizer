package dev.patternizer.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import dev.patternizer.AIPatternizer;

/**
 * M1 集成验证：在装配了 AE2 的运行环境里走通官方编码 API——
 * 编码 → 产物是处理样板 → 能解码 → 主产物正确。
 */
@GameTestHolder(AIPatternizer.MOD_ID)
@PrefixGameTestTemplate(false)
public class EncodeGameTest {

    @GameTest(template = "empty")
    public static void encodeProcessingPattern(GameTestHelper helper) {
        GenericStack[] inputs = new GenericStack[] {
                new GenericStack(AEItemKey.of(new ItemStack(Items.IRON_INGOT)), 1)
        };
        GenericStack[] outputs = new GenericStack[] {
                new GenericStack(AEItemKey.of(new ItemStack(Items.IRON_NUGGET)), 9)
        };

        ItemStack encoded = PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);
        helper.assertFalse(encoded.isEmpty(), "encodeProcessingPattern returned empty stack");

        var processingPattern = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ae2", "processing_pattern"));
        helper.assertTrue(encoded.is(processingPattern),
                "encoded item is not ae2:processing_pattern but " + encoded.getItem());

        var details = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        helper.assertTrue(details != null, "decodePattern returned null");
        helper.assertTrue(details.getPrimaryOutput().what() instanceof AEItemKey key
                && key.getItem() == Items.IRON_NUGGET, "primary output is not iron nugget");
        helper.assertTrue(details.getPrimaryOutput().amount() == 9, "primary output amount is not 9");

        helper.succeed();
    }
}

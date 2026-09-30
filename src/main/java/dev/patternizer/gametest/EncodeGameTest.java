package dev.patternizer.gametest;

import java.util.List;

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
import dev.patternizer.spec.CatalystLayout;
import dev.patternizer.spec.PatternSpec;
import dev.patternizer.spec.PatternSpecValidator;
import dev.patternizer.spec.PatternSpecValidator.ValidationError;

/**
 * 集成验证：编码链路（M1）+ 校验器与催化剂布局（M2）。
 */
@GameTestHolder(AIPatternizer.MOD_ID)
@PrefixGameTestTemplate(false)
public class EncodeGameTest {

    private static PatternSpec.Entry itemEntry(String id, int count, PatternSpec.Role role) {
        PatternSpec.Entry e = new PatternSpec.Entry();
        e.item = id;
        e.count = count;
        e.role = role;
        return e;
    }

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

    @GameTest(template = "empty")
    public static void validatorAcceptsSimpleProcessing(GameTestHelper helper) {
        PatternSpec spec = new PatternSpec();
        spec.type = PatternSpec.Type.PROCESSING;
        spec.inputs.add(itemEntry("minecraft:iron_ingot", 1, PatternSpec.Role.CONSUMED));
        spec.inputs.add(itemEntry("minecraft:redstone", 2, PatternSpec.Role.CONSUMED));
        spec.outputs.add(itemEntry("minecraft:iron_nugget", 9, PatternSpec.Role.CONSUMED));

        List<ValidationError> errors = PatternSpecValidator.validate(spec);
        helper.assertTrue(errors.isEmpty(), "expected no validation errors but got: " + errors);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void validatorRejectsBadSpecs(GameTestHelper helper) {
        // 不存在的物品
        PatternSpec unknownItem = new PatternSpec();
        unknownItem.inputs.add(itemEntry("minecraft:not_a_real_item_xyz", 1, PatternSpec.Role.CONSUMED));
        unknownItem.outputs.add(itemEntry("minecraft:iron_nugget", 1, PatternSpec.Role.CONSUMED));
        helper.assertFalse(PatternSpecValidator.validate(unknownItem).isEmpty(),
                "unknown item should be rejected");

        // count = 0
        PatternSpec zeroCount = new PatternSpec();
        zeroCount.inputs.add(itemEntry("minecraft:iron_ingot", 0, PatternSpec.Role.CONSUMED));
        zeroCount.outputs.add(itemEntry("minecraft:iron_nugget", 1, PatternSpec.Role.CONSUMED));
        helper.assertFalse(PatternSpecValidator.validate(zeroCount).isEmpty(),
                "count=0 should be rejected");

        // 无耐久物品标 catalyst_durability
        PatternSpec badDurability = new PatternSpec();
        badDurability.inputs.add(itemEntry("minecraft:iron_ingot", 1, PatternSpec.Role.CATALYST_DURABILITY));
        badDurability.outputs.add(itemEntry("minecraft:iron_nugget", 1, PatternSpec.Role.CONSUMED));
        badDurability.durabilityBatch = new PatternSpec.DurabilityBatch();
        badDurability.durabilityBatch.tool = "minecraft:iron_ingot";
        badDurability.durabilityBatch.usesPerTool = 10;
        helper.assertFalse(PatternSpecValidator.validate(badDurability).isEmpty(),
                "durability role on damageless item should be rejected");

        // 槽位超限（82 格）
        PatternSpec tooMany = new PatternSpec();
        for (int i = 0; i < 82; i++) {
            tooMany.inputs.add(itemEntry("minecraft:iron_ingot", 1, PatternSpec.Role.CONSUMED));
        }
        tooMany.outputs.add(itemEntry("minecraft:iron_nugget", 1, PatternSpec.Role.CONSUMED));
        helper.assertFalse(PatternSpecValidator.validate(tooMany).isEmpty(),
                "82 input slots should be rejected");

        helper.succeed();
    }

    /** 返还式假合成：催化剂进输入末位 + 副产物输出格（§6.2）。 */
    @GameTest(template = "empty")
    public static void catalystReturnedLayout(GameTestHelper helper) {
        PatternSpec spec = new PatternSpec();
        spec.type = PatternSpec.Type.PROCESSING;
        spec.inputs.add(itemEntry("minecraft:iron_ingot", 4, PatternSpec.Role.CONSUMED));
        spec.inputs.add(itemEntry("minecraft:glass_bottle", 1, PatternSpec.Role.CATALYST_RETURNED));
        spec.outputs.add(itemEntry("minecraft:iron_nugget", 1, PatternSpec.Role.CONSUMED));

        List<ValidationError> errors = PatternSpecValidator.validate(spec);
        helper.assertTrue(errors.isEmpty(), "catalyst spec should validate but got: " + errors);

        GenericStack[] in = CatalystLayout.buildInputs(spec);
        GenericStack[] out = CatalystLayout.buildOutputs(spec);
        helper.assertTrue(in.length == 2, "expected 2 inputs but got " + in.length);
        helper.assertTrue(out.length == 2, "expected 2 outputs but got " + out.length);
        // 催化剂在输入末位
        helper.assertTrue(in[1].what() instanceof AEItemKey catalystKey
                && catalystKey.getItem() == Items.GLASS_BOTTLE, "catalyst should be the last input");
        // 主产物是铁粒，催化剂是副产物
        helper.assertTrue(out[0].what() instanceof AEItemKey primaryKey
                && primaryKey.getItem() == Items.IRON_NUGGET, "primary output should be iron nugget");
        helper.assertTrue(out[1].what() instanceof AEItemKey byKey
                && byKey.getItem() == Items.GLASS_BOTTLE, "catalyst should be a byproduct");

        // 端到端：编码 → 解码后与布局一致
        ItemStack encoded = PatternDetailsHelper.encodeProcessingPattern(in, out);
        var details = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        helper.assertTrue(details != null, "decodePattern returned null");
        helper.assertTrue(details.getOutputs().length == 2, "decoded pattern should have 2 outputs");
        helper.assertTrue(details.getPrimaryOutput().what() instanceof AEItemKey decodedPrimary
                && decodedPrimary.getItem() == Items.IRON_NUGGET, "decoded primary output mismatch");

        helper.succeed();
    }

    /** 网格接入：编写台与创造能源元件相邻时应成功加入 ME 网络。 */
    @GameTest(template = "empty")
    public static void gridConnection(GameTestHelper helper) {
        var energyCell = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ae2", "creative_energy_cell"));
        helper.assertTrue(energyCell != null, "ae2:creative_energy_cell not found in registry");
        var cellBlock = net.minecraft.world.level.block.Block.byItem(energyCell);

        net.minecraft.core.BlockPos cellPos = new net.minecraft.core.BlockPos(1, 2, 1);
        net.minecraft.core.BlockPos benchPos = new net.minecraft.core.BlockPos(2, 2, 1);
        helper.setBlock(cellPos, cellBlock);
        helper.setBlock(benchPos, dev.patternizer.registry.PRegistry.AI_PATTERNIZER.get());

        helper.succeedWhen(() -> {
            var be = helper.getBlockEntity(benchPos);
            helper.assertTrue(be instanceof dev.patternizer.block.AiPatternizerBlockEntity,
                    "AI Patternizer block entity missing");
            var grid = ((dev.patternizer.block.AiPatternizerBlockEntity) be).getGrid();
            helper.assertTrue(grid != null, "grid node not ready: no grid after joining network");
            // 必须与能源元件并入同一网格（≥2 节点），单节点网格说明线缆/宿主链路不通
            int nodeCount = 0;
            for (var ignored : grid.getNodes()) {
                nodeCount++;
            }
            helper.assertTrue(nodeCount >= 2,
                    "grid has only " + nodeCount + " node(s): block did not join the energy cell's network");
        });
    }
}

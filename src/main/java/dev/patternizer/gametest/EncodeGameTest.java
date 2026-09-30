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
import dev.patternizer.net.RecipeResolver;
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

    /** M3：RecipeResolver——合成样板经配方反查编码。 */
    @GameTest(template = "empty")
    public static void craftingViaResolver(GameTestHelper helper) {
        PatternSpec spec = targetSpec(PatternSpec.Type.CRAFTING, "minecraft:oak_planks");
        var resolution = RecipeResolver.resolveAndEncode(helper.getLevel().getServer(), helper.getLevel(), spec);
        helper.assertTrue(resolution instanceof RecipeResolver.Resolution.Encoded,
                "expected Encoded but got " + resolution);
        ItemStack encoded = ((RecipeResolver.Resolution.Encoded) resolution).stack();
        var details = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        helper.assertTrue(details != null, "decodePattern returned null");
        helper.assertTrue(details.getPrimaryOutput().what() instanceof AEItemKey key
                && key.getItem() == Items.OAK_PLANKS, "primary output should be oak planks");
        helper.succeed();
    }

    /** M3：切石样板（石头 → 石砖）。 */
    @GameTest(template = "empty")
    public static void stonecuttingViaResolver(GameTestHelper helper) {
        PatternSpec spec = targetSpec(PatternSpec.Type.STONECUTTING, "minecraft:stone_bricks");
        var resolution = RecipeResolver.resolveAndEncode(helper.getLevel().getServer(), helper.getLevel(), spec);
        helper.assertTrue(resolution instanceof RecipeResolver.Resolution.Encoded,
                "expected Encoded but got " + resolution);
        ItemStack encoded = ((RecipeResolver.Resolution.Encoded) resolution).stack();
        var details = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        helper.assertTrue(details != null, "decodePattern returned null");
        helper.assertTrue(details.getPrimaryOutput().what() instanceof AEItemKey key
                && key.getItem() == Items.STONE_BRICKS, "primary output should be stone bricks");
        helper.succeed();
    }

    /** M3：锻造样板（下界合金剑升级；同时验证 AccessTransformer 字段放开生效）。 */
    @GameTest(template = "empty")
    public static void smithingViaResolver(GameTestHelper helper) {
        PatternSpec spec = targetSpec(PatternSpec.Type.SMITHING, "minecraft:netherite_sword");
        var resolution = RecipeResolver.resolveAndEncode(helper.getLevel().getServer(), helper.getLevel(), spec);
        helper.assertTrue(resolution instanceof RecipeResolver.Resolution.Encoded,
                "expected Encoded but got " + resolution);
        ItemStack encoded = ((RecipeResolver.Resolution.Encoded) resolution).stack();
        var details = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        helper.assertTrue(details != null, "decodePattern returned null");
        helper.assertTrue(details.getPrimaryOutput().what() instanceof AEItemKey key
                && key.getItem() == Items.NETHERITE_SWORD, "primary output should be netherite sword");
        helper.succeed();
    }

    /** 回归：合成样板默认允许原料替换（木棍类配方不再钉死单一变体）。 */
    @GameTest(template = "empty")
    public static void craftingDefaultsToSubstitution(GameTestHelper helper) {
        PatternSpec spec = targetSpec(PatternSpec.Type.CRAFTING, "minecraft:iron_pickaxe");
        helper.assertTrue(spec.allowSubstitutes, "spec should default allowSubstitutes=true");
        var resolution = RecipeResolver.resolveAndEncode(helper.getLevel().getServer(), helper.getLevel(), spec);
        helper.assertTrue(resolution instanceof RecipeResolver.Resolution.Encoded,
                "expected Encoded but got " + resolution);
        ItemStack encoded = ((RecipeResolver.Resolution.Encoded) resolution).stack();
        var details = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        helper.assertTrue(details != null, "decodePattern returned null");
        helper.assertTrue(details instanceof appeng.crafting.pattern.AECraftingPattern craftingDetails
                && craftingDetails.canSubstitute(),
                "crafting pattern should allow substitution by default");
        helper.succeed();
    }

    /** M3：多配方冲突（苔石：圆石+藤蔓 / 圆石+苔藓块）→ 返回候选清单，选定后精确编码。 */
    @GameTest(template = "empty")
    public static void chooseRecipeWhenMultiple(GameTestHelper helper) {        PatternSpec spec = targetSpec(PatternSpec.Type.CRAFTING, "minecraft:mossy_cobblestone");
        var resolution = RecipeResolver.resolveAndEncode(helper.getLevel().getServer(), helper.getLevel(), spec);
        helper.assertTrue(resolution instanceof RecipeResolver.Resolution.ChooseRecipe,
                "expected ChooseRecipe but got " + resolution);
        var ids = ((RecipeResolver.Resolution.ChooseRecipe) resolution).recipeIds();
        helper.assertTrue(ids.size() >= 2, "expected >=2 candidate recipes but got " + ids);

        spec.recipeId = ids.get(0);
        var second = RecipeResolver.resolveAndEncode(helper.getLevel().getServer(), helper.getLevel(), spec);
        helper.assertTrue(second instanceof RecipeResolver.Resolution.Encoded,
                "expected Encoded after choosing a recipe but got " + second);
        helper.succeed();
    }

    private static PatternSpec targetSpec(PatternSpec.Type type, String target) {
        PatternSpec spec = new PatternSpec();
        spec.type = type;
        spec.target = target;
        return spec;
    }

    /** 回归（2026-10-01 ATM 案）：全大写拉丁缩写必须能被提取为 token。 */
    @GameTest(template = "empty")
    public static void latinAcronymTokenExtraction(GameTestHelper helper) {
        var tokens = dev.patternizer.client.search.ItemCandidateSearch.extractTokens("ATM镐的合成样板");
        helper.assertTrue(tokens.contains("atm"), "tokens should contain 'atm' but got: " + tokens);
        helper.succeed();
    }

    /** M3.5：不可收回黑名单——命中即强制预置式，布局中不再出现该物品。 */
    @GameTest(template = "empty")
    public static void catalystPolicyBlacklist(GameTestHelper helper) {        PatternSpec spec = new PatternSpec();
        spec.type = PatternSpec.Type.PROCESSING;
        spec.inputs.add(itemEntry("minecraft:iron_ingot", 4, PatternSpec.Role.CONSUMED));
        spec.inputs.add(itemEntry("mysticalagriculture:infusion_crystal", 1, PatternSpec.Role.CATALYST_RETURNED));
        spec.outputs.add(itemEntry("minecraft:iron_block", 1, PatternSpec.Role.CONSUMED));

        var hits = dev.patternizer.spec.CatalystPolicy.apply(spec, java.util.Set.of());
        helper.assertTrue(hits.size() == 1 && hits.get(0).equals("mysticalagriculture:infusion_crystal"),
                "expected one blacklist hit but got " + hits);
        helper.assertTrue(spec.inputs.get(1).role == PatternSpec.Role.CATALYST_PREPLACED,
                "role should be forced to CATALYST_PREPLACED");
        // 布局：预置式不占任何样板格
        GenericStack[] in = CatalystLayout.buildInputs(spec);
        GenericStack[] out = CatalystLayout.buildOutputs(spec);
        helper.assertTrue(in.length == 1, "preplaced catalyst should not occupy an input slot");
        helper.assertTrue(out.length == 1, "preplaced catalyst should not appear as byproduct");
        helper.succeed();
    }
    /** 网格接入：编写台与创造能源元件 + 控制器组网时应成功加入 ME 网络并上线。 */
    @GameTest(template = "empty")
    public static void gridConnection(GameTestHelper helper) {
        var energyCell = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ae2", "creative_energy_cell"));
        helper.assertTrue(energyCell != null, "ae2:creative_energy_cell not found in registry");
        var controller = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ae2", "controller"));
        helper.assertTrue(controller != null, "ae2:controller not found in registry");
        var cellBlock = net.minecraft.world.level.block.Block.byItem(energyCell);
        var controllerBlock = net.minecraft.world.level.block.Block.byItem(controller);

        // 复刻有控制器的真实网络：能源元件 — 控制器 — 编写台
        net.minecraft.core.BlockPos cellPos = new net.minecraft.core.BlockPos(1, 2, 1);
        net.minecraft.core.BlockPos controllerPos = new net.minecraft.core.BlockPos(1, 2, 2);
        net.minecraft.core.BlockPos benchPos = new net.minecraft.core.BlockPos(2, 2, 1);
        helper.setBlock(cellPos, cellBlock);
        helper.setBlock(controllerPos, controllerBlock);
        helper.setBlock(benchPos, dev.patternizer.registry.PRegistry.AI_PATTERNIZER.get());

        helper.succeedWhen(() -> {
            var be = helper.getBlockEntity(benchPos);
            helper.assertTrue(be instanceof dev.patternizer.block.AiPatternizerBlockEntity,
                    "AI Patternizer block entity missing");
            var pbe = (dev.patternizer.block.AiPatternizerBlockEntity) be;
            var grid = pbe.getGrid();
            helper.assertTrue(grid != null, "grid node not ready: no grid after joining network");
            // 必须与能源元件+控制器并入同一网格，单节点网格说明线缆/宿主链路不通
            int nodeCount = 0;
            for (var ignored : grid.getNodes()) {
                nodeCount++;
            }
            helper.assertTrue(nodeCount >= 3,
                    "grid has only " + nodeCount + " node(s): block did not join the network");
            // 有控制器时必须拿到频道上线（REQUIRE_CHANNEL 生效）
            helper.assertTrue(pbe.getGridNode().isOnline(),
                    "grid node is not online on a controller network (channel not acquired?)");
        });
    }
}

package dev.patternizer.client.recipeview;

import java.util.List;

/**
 * 配方浏览桥统一接口（§3.4 / §10.21）：JEI/EMI 各自实现，都缺省为 NoBridge。
 * 仅三类查询，实现类任何异常都应降级为空结果而非抛出。
 */
public interface RecipeViewSource {

    /** 物品的作者提示文本（JEI 信息页 / EMI info recipe），纯文本。 */
    List<String> infoTexts(String itemId);

    /** 产出该物品的全部配方（含机器配方全内容：物品 + 流体）。 */
    List<ViewRecipe> recipesProducing(String itemId);

    /** 配方类型对应的工作站物品 id 列表（机器归属）。 */
    List<String> workstations(String recipeTypeId);

    /** 桥内一条配方的视图。 */
    record ViewRecipe(String recipeTypeId, List<ViewIngredient> inputs, List<ViewIngredient> outputs) {
    }

    /** 桥内一个配料/产物：物品或流体。 */
    record ViewIngredient(boolean fluid, String id, long amount) {
    }
}

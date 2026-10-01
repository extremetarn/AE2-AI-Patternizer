package dev.patternizer.client.recipeview;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.vanilla.IJeiIngredientInfoRecipe;
import mezz.jei.api.runtime.IJeiRuntime;

import dev.patternizer.client.recipeview.RecipeViewSource.ViewIngredient;
import dev.patternizer.client.recipeview.RecipeViewSource.ViewRecipe;

/**
 * JEI 桥（§3.4）：从 JEI 运行时读取作者信息页、产出配方（含流体）、工作站映射。
 * 隐藏配料提取依赖 JEI 布局（GTCEu/Create 等插件自己会把自定义字段读进槽位）。
 * 任何一步失败都降级为空结果，绝不让 JEI 的锅崩掉本 mod（§10.21）。
 */
public final class JeiBridge implements RecipeViewSource {

    public static final JeiBridge INSTANCE = new JeiBridge();

    private static volatile IJeiRuntime runtime;

    private JeiBridge() {
    }

    public static void setRuntime(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    public static IJeiRuntime runtime() {
        return runtime;
    }

    @Override
    public List<String> infoTexts(String itemId) {
        IJeiRuntime rt = runtime;
        if (rt == null) {
            return List.of();
        }
        try {
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
            if (item == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            rt.getRecipeManager().createRecipeLookup(RecipeTypes.INFORMATION).includeHidden().get()
                    .filter(IJeiIngredientInfoRecipe.class::isInstance)
                    .map(IJeiIngredientInfoRecipe.class::cast)
                    .filter(recipe -> mentionsItem(recipe, item))
                    .forEach(recipe -> {
                        for (FormattedText line : recipe.getDescription()) {
                            String text = line.getString().trim();
                            if (!text.isEmpty()) {
                                out.add(text);
                            }
                        }
                    });
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean mentionsItem(IJeiIngredientInfoRecipe recipe, net.minecraft.world.item.Item item) {
        for (var typed : recipe.getIngredients()) {
            if (typed.getType() == VanillaTypes.ITEM_STACK
                    && typed.getIngredient() instanceof ItemStack stack
                    && stack.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<ViewRecipe> recipesProducing(String itemId) {
        IJeiRuntime rt = runtime;
        if (rt == null) {
            return List.of();
        }
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        if (item == null) {
            return List.of();
        }
        List<ViewRecipe> out = new ArrayList<>();
        try {
            var rm = rt.getRecipeManager();
            var focusFactory = rt.getJeiHelpers().getFocusFactory();
            IFocus<ItemStack> focus = focusFactory.createFocus(RecipeIngredientRole.OUTPUT,
                    VanillaTypes.ITEM_STACK, new ItemStack(item));
            IFocusGroup focusGroup = focusFactory.createFocusGroup(List.of(focus));

            rm.createRecipeCategoryLookup().includeHidden().get().forEach(category -> {
                var type = category.getRecipeType();
                // 信息页不是配方
                if (RecipeTypes.INFORMATION.getUid().equals(type.getUid())) {
                    return;
                }
                extractCategoryRecipes(rm, category, type, focus, focusGroup, out);
            });
        } catch (Exception ignored) {
        }
        return out;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void extractCategoryRecipes(mezz.jei.api.recipe.IRecipeManager rm,
            mezz.jei.api.recipe.category.IRecipeCategory category, mezz.jei.api.recipe.RecipeType type,
            IFocus<ItemStack> focus, IFocusGroup focusGroup, List<ViewRecipe> out) {
        try {
            rm.createRecipeLookup(type).limitFocus(List.of(focus)).includeHidden().get()
                    .forEach(recipe -> {
                        try {
                            java.util.Optional<mezz.jei.api.gui.IRecipeLayoutDrawable<?>> layout =
                                    (java.util.Optional<mezz.jei.api.gui.IRecipeLayoutDrawable<?>>) (java.util.Optional<?>)
                                            rm.createRecipeLayoutDrawable(category, recipe, focusGroup);
                            layout.ifPresent(drawable -> {
                                List<ViewIngredient> inputs = readSlots(
                                        drawable.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT));
                                List<ViewIngredient> outputs = readSlots(
                                        drawable.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT));
                                if (!inputs.isEmpty() && !outputs.isEmpty()) {
                                    out.add(new ViewRecipe(type.getUid().toString(), inputs, outputs));
                                }
                            });
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    /** 读槽位内容：物品 + 流体（JEI 插件已替我们读好自定义字段）。 */
    private static List<ViewIngredient> readSlots(
            List<? extends mezz.jei.api.gui.ingredient.IRecipeSlotView> slots) {
        List<ViewIngredient> out = new ArrayList<>();
        for (var slot : slots) {
            slot.getAllIngredients().forEach(typed -> {
                if (typed.getType() == VanillaTypes.ITEM_STACK
                        && typed.getIngredient() instanceof ItemStack stack) {
                    var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (id != null && !stack.isEmpty()) {
                        out.add(new ViewIngredient(false, id.toString(), stack.getCount()));
                    }
                    return;
                }
                // 流体（JEI ForgeTypes）
                Object ingredient = typed.getIngredient();
                if (ingredient instanceof net.minecraftforge.fluids.FluidStack fluidStack) {
                    var id = ForgeRegistries.FLUIDS.getKey(fluidStack.getFluid());
                    if (id != null && !fluidStack.isEmpty()) {
                        out.add(new ViewIngredient(true, id.toString(), fluidStack.getAmount()));
                    }
                }
            });
        }
        return out;
    }

    @Override
    public List<String> workstations(String recipeTypeId) {
        IJeiRuntime rt = runtime;
        if (rt == null) {
            return List.of();
        }
        try {
            var rm = rt.getRecipeManager();
            var typeOpt = rm.getRecipeType(new ResourceLocation(recipeTypeId));
            if (typeOpt.isEmpty()) {
                return List.of();
            }
            return rm.createRecipeCatalystLookup(typeOpt.get()).getItemStack()
                    .filter(s -> !s.isEmpty())
                    .map(s -> ForgeRegistries.ITEMS.getKey(s.getItem()))
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}

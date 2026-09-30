package dev.patternizer.spec;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import dev.patternizer.spec.PatternSpec.Entry;
import dev.patternizer.spec.PatternSpec.Role;

/**
 * 双端共用的规格校验器（§5.5）。返回结构化错误（lang key + 参数），
 * 客户端用于自我修正循环与本地化提示，服务端用于权威校验。
 */
public final class PatternSpecValidator {

    /** 一条校验错误：key 为 lang key，args 为翻译参数。 */
    public record ValidationError(String key, String... args) {
        public String serialize() {
            return key + "|" + String.join("|", args);
        }

        public static ValidationError deserialize(String line) {
            String[] parts = line.split("\\|", -1);
            String[] args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
            return new ValidationError(parts[0], args);
        }
    }

    /** 处理样板输入+输出总槽位上限（§10.6）。 */
    public static final int MAX_PROCESSING_SLOTS = 81;

    private PatternSpecValidator() {
    }

    public static List<ValidationError> validate(PatternSpec spec) {
        List<ValidationError> errors = new ArrayList<>();

        switch (spec.type) {
        case PROCESSING -> validateProcessing(spec, errors);
        case CRAFTING, STONECUTTING, SMITHING -> validateTargetOnly(spec, errors);
        }

        return errors;
    }

    private static void validateTargetOnly(PatternSpec spec, List<ValidationError> errors) {
        if (spec.target == null || spec.target.isBlank()) {
            errors.add(new ValidationError("error.spec.target_required"));
            return;
        }
        if (!itemExists(spec.target)) {
            errors.add(new ValidationError("error.spec.unknown_item", spec.target));
        }
    }

    private static void validateProcessing(PatternSpec spec, List<ValidationError> errors) {
        if (spec.inputs.isEmpty()) {
            errors.add(new ValidationError("error.spec.inputs_empty"));
        }
        if (spec.outputs.isEmpty()) {
            errors.add(new ValidationError("error.spec.outputs_empty"));
        }

        int slots = 0;
        boolean hasDurabilityRole = false;

        for (Entry e : spec.inputs) {
            validateEntry(e, errors);
            if (e.role != Role.CATALYST_PREPLACED) {
                slots++; // 预置式催化剂不占样板槽
            }
            switch (e.role) {
            case CATALYST_RETURNED -> {
                if (e.isFluid()) {
                    errors.add(new ValidationError("error.spec.catalyst_fluid", e.fluid));
                }
            }
            case CATALYST_DURABILITY -> {
                hasDurabilityRole = true;
                if (e.isFluid()) {
                    errors.add(new ValidationError("error.spec.durability_fluid", e.fluid));
                } else if (itemExists(e.item)) {
                    Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(e.item));
                    int maxDamage = new ItemStack(item).getMaxDamage();
                    if (maxDamage <= 0) {
                        errors.add(new ValidationError("error.spec.durability_no_damage", e.item));
                    } else if (spec.durabilityBatch != null
                            && spec.durabilityBatch.usesPerTool > maxDamage) {
                        errors.add(new ValidationError("error.spec.durability_uses_exceed",
                                String.valueOf(spec.durabilityBatch.usesPerTool), String.valueOf(maxDamage)));
                    }
                }
            }
            default -> {
            }
            }
        }

        for (Entry e : spec.outputs) {
            validateEntry(e, errors);
            slots++;
        }

        if (hasDurabilityRole) {
            if (spec.durabilityBatch == null || spec.durabilityBatch.tool == null) {
                errors.add(new ValidationError("error.spec.durability_batch_required"));
            } else if (!itemExists(spec.durabilityBatch.tool)) {
                errors.add(new ValidationError("error.spec.unknown_item", spec.durabilityBatch.tool));
            } else if (spec.durabilityBatch.usesPerTool < 1) {
                errors.add(new ValidationError("error.spec.durability_uses_range"));
            }
        }

        if (slots > MAX_PROCESSING_SLOTS) {
            errors.add(new ValidationError("error.spec.too_many_slots",
                    String.valueOf(slots), String.valueOf(MAX_PROCESSING_SLOTS)));
        }
    }

    private static void validateEntry(Entry e, List<ValidationError> errors) {
        if (e.isFluid()) {
            if (!ResourceLocation.isValidResourceLocation(e.fluid)
                    || !ForgeRegistries.FLUIDS.containsKey(new ResourceLocation(e.fluid))) {
                errors.add(new ValidationError("error.spec.unknown_fluid", e.fluid));
            }
            if (e.amount < 1) {
                errors.add(new ValidationError("error.spec.amount_range", e.fluid));
            }
        } else {
            if (!itemExists(e.item)) {
                errors.add(new ValidationError("error.spec.unknown_item", String.valueOf(e.item)));
            }
            if (e.count < 1) {
                errors.add(new ValidationError("error.spec.count_range", String.valueOf(e.item)));
            }
        }
    }

    private static boolean itemExists(String id) {
        return id != null && ResourceLocation.isValidResourceLocation(id)
                && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(id));
    }
}

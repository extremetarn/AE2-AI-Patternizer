package dev.patternizer.spec;

import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * PatternSpec 的 JSON 序列化/反序列化（§5.3）。
 * 解析对 LLM 输出宽松：三层提取（直接解析 → ```json 代码块 → 首尾大括号子串），见 §10.3。
 */
public final class PatternSpecJson {

    public static final class SpecParseException extends Exception {
        public SpecParseException(String message) {
            super(message);
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PatternSpecJson() {
    }

    public static PatternSpec parse(String raw) throws SpecParseException {
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(extractJson(raw));
            if (!el.isJsonObject()) {
                throw new SpecParseException("error.spec.not_object|");
            }
            root = el.getAsJsonObject();
        } catch (SpecParseException e) {
            throw e;
        } catch (Exception e) {
            throw new SpecParseException("error.spec.invalid_json|" + e.getClass().getSimpleName());
        }

        PatternSpec spec = new PatternSpec();
        spec.type = parseType(optString(root, "type", "processing"));
        spec.target = optString(root, "target", null);
        spec.recipeId = optString(root, "recipe_id", null);

        if (root.has("inputs") && root.get("inputs").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("inputs")) {
                if (e.isJsonObject()) {
                    spec.inputs.add(parseEntry(e.getAsJsonObject()));
                }
            }
        }
        if (root.has("outputs") && root.get("outputs").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("outputs")) {
                if (e.isJsonObject()) {
                    PatternSpec.Entry out = parseEntry(e.getAsJsonObject());
                    out.role = PatternSpec.Role.CONSUMED; // 输出格不接受 role
                    spec.outputs.add(out);
                }
            }
        }
        if (root.has("durability_batch") && root.get("durability_batch").isJsonObject()) {
            JsonObject db = root.getAsJsonObject("durability_batch");
            PatternSpec.DurabilityBatch batch = new PatternSpec.DurabilityBatch();
            batch.tool = optString(db, "tool", null);
            batch.usesPerTool = optInt(db, "uses_per_tool", 1);
            spec.durabilityBatch = batch;
        }
        spec.note = optString(root, "note", null);
        if (root.has("options") && root.get("options").isJsonObject()) {
            JsonObject o = root.getAsJsonObject("options");
            spec.allowSubstitutes = optBool(o, "allow_substitutes", false);
            spec.allowFluidSubstitutes = optBool(o, "allow_fluid_substitutes", false);
        }
        return spec;
    }

    /** 三层提取（§10.3）。 */
    public static String extractJson(String raw) throws SpecParseException {
        if (raw == null || raw.isBlank()) {
            throw new SpecParseException("error.spec.empty|");
        }
        String trimmed = raw.trim();
        // ② ```json ... ``` 代码块优先（模型最爱犯的格式）
        int fence = trimmed.indexOf("```");
        if (fence >= 0) {
            int start = trimmed.indexOf('\n', fence);
            int end = trimmed.indexOf("```", fence + 3);
            if (start > 0 && end > start) {
                String inner = trimmed.substring(start + 1, end).trim();
                if (inner.startsWith("{")) {
                    return inner;
                }
            }
        }
        // ① 整体即 JSON
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        // ③ 首个 { 到末个 }
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return trimmed.substring(first, last + 1);
        }
        throw new SpecParseException("error.spec.no_json|");
    }

    public static String write(PatternSpec spec) {
        JsonObject root = new JsonObject();
        root.addProperty("type", spec.type.name().toLowerCase(Locale.ROOT));
        if (spec.target != null) {
            root.addProperty("target", spec.target);
        }
        if (spec.recipeId != null) {
            root.addProperty("recipe_id", spec.recipeId);
        }
        JsonArray in = new JsonArray();
        for (PatternSpec.Entry e : spec.inputs) {
            in.add(writeEntry(e, true));
        }
        JsonArray out = new JsonArray();
        for (PatternSpec.Entry e : spec.outputs) {
            out.add(writeEntry(e, false));
        }
        root.add("inputs", in);
        root.add("outputs", out);
        if (spec.durabilityBatch != null) {
            JsonObject db = new JsonObject();
            db.addProperty("tool", spec.durabilityBatch.tool);
            db.addProperty("uses_per_tool", spec.durabilityBatch.usesPerTool);
            root.add("durability_batch", db);
        }
        if (spec.note != null) {
            root.addProperty("note", spec.note);
        }
        JsonObject opts = new JsonObject();
        opts.addProperty("allow_substitutes", spec.allowSubstitutes);
        opts.addProperty("allow_fluid_substitutes", spec.allowFluidSubstitutes);
        root.add("options", opts);
        return GSON.toJson(root);
    }

    private static JsonObject writeEntry(PatternSpec.Entry e, boolean withRole) {
        JsonObject o = new JsonObject();
        if (e.isFluid()) {
            o.addProperty("fluid", e.fluid);
            o.addProperty("amount", e.amount);
        } else {
            o.addProperty("item", e.item);
            o.addProperty("count", e.count);
        }
        if (withRole) {
            o.addProperty("role", e.role.name().toLowerCase(Locale.ROOT));
        }
        return o;
    }

    private static PatternSpec.Entry parseEntry(JsonObject o) throws SpecParseException {
        PatternSpec.Entry e = new PatternSpec.Entry();
        e.item = optString(o, "item", null);
        e.fluid = optString(o, "fluid", null);
        e.count = optInt(o, "count", 1);
        e.amount = optInt(o, "amount", 0);
        e.role = parseEnum(PatternSpec.Role.class, optString(o, "role", "consumed"), PatternSpec.Role.CONSUMED);
        if ((e.item == null) == (e.fluid == null)) {
            throw new SpecParseException("error.spec.entry_item_xor_fluid|" + o);
        }
        return e;
    }

    private static final java.util.Map<String, String> TYPE_ALIASES = java.util.Map.ofEntries(
            java.util.Map.entry("smithing_table", "smithing"),
            java.util.Map.entry("forge", "smithing"),
            java.util.Map.entry("forging", "smithing"),
            java.util.Map.entry("upgrade", "smithing"),
            java.util.Map.entry("trim", "smithing"),
            java.util.Map.entry("stonecut", "stonecutting"),
            java.util.Map.entry("stonecutter", "stonecutting"),
            java.util.Map.entry("cutting", "stonecutting"),
            java.util.Map.entry("craft", "crafting"),
            java.util.Map.entry("crafting_table", "crafting"),
            java.util.Map.entry("process", "processing"),
            java.util.Map.entry("machine", "processing"));

    /** 解析 type 字段：先按别名表归一，再按枚举解析（非法值回退 processing）。 */
    private static PatternSpec.Type parseType(String raw) {
        if (raw != null) {
            String alias = TYPE_ALIASES.get(raw.trim().toLowerCase(Locale.ROOT));
            if (alias != null) {
                raw = alias;
            }
        }
        return parseEnum(PatternSpec.Type.class, raw, PatternSpec.Type.PROCESSING);
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> cls, String raw, T fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(cls, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static String optString(JsonObject o, String key, String fallback) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : fallback;
    }

    private static int optInt(JsonObject o, String key, int fallback) {
        try {
            JsonElement el = o.get(key);
            return el != null && el.isJsonPrimitive() ? el.getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean optBool(JsonObject o, String key, boolean fallback) {
        try {
            JsonElement el = o.get(key);
            return el != null && el.isJsonPrimitive() ? el.getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}

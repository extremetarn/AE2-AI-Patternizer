package dev.patternizer.client.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 物品候选检索器（§5.4）：从玩家自然语言中提取关键词，
 * 在注册表上做模糊打分，取 Top N 个 "modid:name" 喂给 LLM，防止 token 爆炸。
 * 必须在客户端线程调用（依赖当前语言的物品译名）。
 */
public final class ItemCandidateSearch {

    private static final int DEFAULT_LIMIT = 80;
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
    private static final Pattern LATIN_WORD = Pattern.compile("[a-zA-Z][a-z0-9_]{2,}");
    private static final Pattern REGISTRY_REF = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");

    private ItemCandidateSearch() {
    }

    public static List<String> search(String prompt) {
        return search(prompt, DEFAULT_LIMIT);
    }

    public static List<String> search(String prompt, int limit) {
        Set<String> tokens = extractTokens(prompt);
        Set<String> exactRefs = new HashSet<>();
        Matcher refMatcher = REGISTRY_REF.matcher(prompt);
        while (refMatcher.find()) {
            exactRefs.add(refMatcher.group().toLowerCase(Locale.ROOT));
        }

        record Scored(String id, int score) {
        }
        List<Scored> scored = new ArrayList<>();

        for (Item item : ForgeRegistries.ITEMS) {
            var id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) {
                continue;
            }
            String idStr = id.toString();
            int score = 0;

            if (exactRefs.contains(idStr)) {
                score += 1000;
            }

            String displayName = item.getDefaultInstance().getHoverName().getString();
            String path = id.getPath();
            String namespace = id.getNamespace();
            for (String token : tokens) {
                if (token.length() < 2) {
                    continue;
                }
                if (displayName.contains(token)) {
                    score += token.length() * 4;
                }
                if (path.contains(token.toLowerCase(Locale.ROOT))) {
                    score += token.length() * 2;
                }
                if (namespace.contains(token.toLowerCase(Locale.ROOT))) {
                    score += 2;
                }
            }

            if (score > 0) {
                scored.add(new Scored(idStr, score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(limit)
                .map(Scored::id)
                .toList();
    }

    /** 提取关键词：CJK 连续段整段 + 二元组，拉丁词整词。 */
    static Set<String> extractTokens(String prompt) {
        Set<String> tokens = new HashSet<>();
        Matcher cjk = CJK_RUN.matcher(prompt);
        while (cjk.find()) {
            String run = cjk.group();
            tokens.add(run);
            for (int i = 0; i + 2 <= run.length(); i++) {
                tokens.add(run.substring(i, i + 2));
            }
        }
        Matcher latin = LATIN_WORD.matcher(prompt);
        while (latin.find()) {
            tokens.add(latin.group().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    /** 流体候选：M2 仅做注册名匹配（流体译名获取路径复杂，M3 再补）。 */
    public static List<String> searchFluids(String prompt, int limit) {
        Set<String> tokens = extractTokens(prompt);
        List<String> out = new ArrayList<>();
        // 常见流体无条件带上，保证基础可用
        List<String> common = new ArrayList<>(List.of("minecraft:water", "minecraft:lava"));
        for (var fluid : ForgeRegistries.FLUIDS) {
            var id = ForgeRegistries.FLUIDS.getKey(fluid);
            if (id == null || common.contains(id.toString())) {
                continue;
            }
            String path = id.getPath();
            for (String token : tokens) {
                if (token.length() >= 2 && path.contains(token.toLowerCase(Locale.ROOT))) {
                    common.add(id.toString());
                    break;
                }
            }
            if (common.size() >= limit) {
                break;
            }
        }
        out.addAll(common);
        return out;
    }
}

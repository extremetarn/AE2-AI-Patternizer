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
        Set<String> latinTokens = tokens.stream()
                .filter(t -> t.chars().anyMatch(c -> c < 0x80 && Character.isLetter(c)))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> exactRefs = new HashSet<>();
        Matcher refMatcher = REGISTRY_REF.matcher(prompt);
        while (refMatcher.find()) {
            exactRefs.add(refMatcher.group().toLowerCase(Locale.ROOT));
        }
        String longestCjk = tokens.stream()
                .filter(t -> t.chars().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);

        // 第一遍：统计每个关键词的文档频率 df（出现在多少物品的名称/路径中）
        // —— IDF 的核心：泛词（"合成/样板"，df 几千）权重自然低于专词（"atm"，df 几十）
        java.util.Map<String, Integer> df = new java.util.HashMap<>();
        int totalItems = 0;
        for (Item item : ForgeRegistries.ITEMS) {
            var id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) {
                continue;
            }
            totalItems++;
            String displayLower = item.getDefaultInstance().getHoverName().getString()
                    .toLowerCase(Locale.ROOT);
            String path = id.getPath();
            for (String token : tokens) {
                if (df.getOrDefault(token, 0) >= DF_CAP) {
                    continue;
                }
                if (displayLower.contains(token) || path.contains(token)) {
                    df.merge(token, 1, Integer::sum);
                }
            }
        }

        final int n = Math.max(1, totalItems);
        java.util.Map<String, Double> idf = new java.util.HashMap<>();
        for (String token : tokens) {
            idf.put(token, Math.log(1.0 + n / (1.0 + df.getOrDefault(token, 0))));
        }
        LOGGER.info("[aipatternizer] search df/idf: {}", df.entrySet().stream()
                .map(e -> e.getKey() + "(df=" + e.getValue() + ",idf="
                        + String.format(java.util.Locale.ROOT, "%.1f", idf.get(e.getKey())) + ")")
                .collect(java.util.stream.Collectors.joining(" ")));

        // 第二遍：按字段加权的相关度打分
        record Scored(String id, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        java.util.Map<String, String[]> nsInfoCache = new java.util.HashMap<>();

        for (Item item : ForgeRegistries.ITEMS) {
            var id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) {
                continue;
            }
            String idStr = id.toString();
            double score = 0;

            if (exactRefs.contains(idStr)) {
                score += 1000;
            }

            String displayLower = item.getDefaultInstance().getHoverName().getString()
                    .toLowerCase(Locale.ROOT);
            String path = id.getPath();
            for (String token : tokens) {
                double w = idf.get(token);
                if (displayLower.contains(token)) {
                    score += w * 4;
                }
                if (path.contains(token)) {
                    score += w * 2;
                }
            }
            // 显示名与用户输入的最长 CJK 段完全相等（用户精确输入物品名）
            if (longestCjk != null && displayLower.equals(longestCjk)) {
                score += 20;
            }

            // 模组名与缩写匹配（"ATM" ↔ AllTheModium 缩写场景）
            if (!latinTokens.isEmpty()) {
                String[] info = nsInfoCache.computeIfAbsent(id.getNamespace(),
                        ItemCandidateSearch::modNameInfo);
                if (info != null) {
                    for (String token : latinTokens) {
                        if (info[0].equals(token)) {
                            score += idf.get(token) * 8;
                        } else if (token.length() >= 4 && info[1].contains(token)) {
                            score += idf.get(token) * 2;
                        }
                    }
                }
            }

            if (score > 0) {
                scored.add(new Scored(idStr, score));
            }
        }

        var ranked = scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(limit)
                .toList();
        LOGGER.info("[aipatternizer] search top10: {}", ranked.subList(0, Math.min(10, ranked.size()))
                .stream()
                .map(s -> s.id() + "=" + String.format(java.util.Locale.ROOT, "%.1f", s.score()))
                .collect(java.util.stream.Collectors.joining(" ")));
        return ranked.stream().map(Scored::id).toList();
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final int DF_CAP = 5000;

    /**
     * 取模组的 [大写缩写（小写）, 全名（小写）]，如 AllTheModium → ["atm", "allthemodium"]；
     * 无模组信息（minecraft 等）返回 null。
     */
    private static String[] modNameInfo(String namespace) {
        try {
            var container = net.minecraftforge.fml.ModList.get().getModContainerById(namespace);
            if (container.isEmpty()) {
                return null;
            }
            String name = container.get().getModInfo().getDisplayName();
            StringBuilder acronym = new StringBuilder();
            for (char c : name.toCharArray()) {
                if (Character.isUpperCase(c) || Character.isDigit(c)) {
                    acronym.append(Character.toLowerCase(c));
                }
            }
            return new String[] { acronym.toString(), name.toLowerCase(Locale.ROOT) };
        } catch (Exception e) {
            return null;
        }
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

    /** 候选物品的耐久属性（§5.4.5 催化剂线索）：仅返回 maxDamage>0 的条目。 */
    public static java.util.Map<String, Integer> durabilityInfo(List<String> itemIds) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (String idStr : itemIds) {
            var item = ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(idStr));
            if (item == null) {
                continue;
            }
            int maxDamage = item.getDefaultInstance().getMaxDamage();
            if (maxDamage > 0) {
                out.put(idStr, maxDamage);
            }
        }
        return out;
    }
}

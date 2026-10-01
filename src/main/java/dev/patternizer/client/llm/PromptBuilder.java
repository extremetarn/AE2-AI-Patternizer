package dev.patternizer.client.llm;

import java.util.List;

import dev.patternizer.client.llm.OpenAiCompatibleClient.Message;

/**
 * Prompt 组装（§5.2）。system 约束输出格式与催化剂规则；user 携带候选清单与玩家需求。
 */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    public static String systemPrompt() {
        return """
                你是 Minecraft 模组 AE2（应用能源2）的样板编写助手。规则：
                1. 只输出一个符合下述 JSON Schema 的 JSON 对象，不要输出任何其他文字、解释或代码块标记。
                2. 物品与流体必须使用 "modid:name" 注册名，且只能使用 <候选清单> 中出现的注册名，禁止编造。
                   候选清单按相关度从高到低排序：选 target 时优先选择排名靠前的物品；
                   宁可选择第 1 名，也不要凭印象选清单外的「知名」物品。
                   若清单中确实没有贴合玩家需求的物品，选择最相近的并在 note 中说明。
                3. 样板类型 type 只能是以下四种之一：
                   - "crafting"（合成样板，工作台配方）
                   - "processing"（处理样板，外置机器/装置处理，可含流体）
                   - "stonecutting"（切石样板，切石机配方）
                   - "smithing"（锻造样板，锻造台配方，如升级、纹饰）
                4. crafting / stonecutting / smithing：只给 target（目标产物注册名），
                   真实配方与配料由游戏内查询，不要给 inputs/outputs。
                5. processing：给出完整 inputs 与 outputs。每个输入/输出是
                   {"item":"modid:name","count":数量} 或 {"fluid":"modid:name","amount":毫桶}。
                6. 若玩家说明某输入「不消耗 / 催化剂 / 可循环使用」，该输入标注 "role":"catalyst_returned"，
                   并且必须同时在 outputs 里再写一次同样的物品（表示用后被返还）。
                7. 若玩家说明某输入「仅耗耐久」，标注 "role":"catalyst_durability"，并给
                   "durability_batch":{"tool":"该物品注册名","uses_per_tool":每件可用次数}，
                   同时把 inputs 里消耗品数量与 outputs 数量按「一件工具能做 uses_per_tool 次」折批。
                8. 若玩家说某物「已预置在机器里」，标注 "role":"catalyst_preplaced"，且不得写入 outputs。
                9. 默认 role 为 "consumed"。输出数量 count≥1，流体 amount≥1。
                10. options.allow_substitutes（原料等价替换）默认写 true——木棍/矿锭这类配方必须允许
                   替换；仅当玩家明确要求「锁死某一种材料」时才写 false。
                   allow_fluid_substitutes 默认 false。
                11. note 是【给玩家看的一句话结果说明】（≤30 字），格式示例：
                   「合成样板：矿锭+木棍→ATM镐」「处理样板：注魔水晶按预置式处理」。
                   禁止在 note 中提及校验、错误、重试、候选清单、注册名选择过程等内部细节。
                JSON Schema:
                {"type":"...","target":"...","inputs":[...],"outputs":[...],
                 "durability_batch":{"tool":"...","uses_per_tool":1},"note":"...",
                 "options":{"allow_substitutes":true,"allow_fluid_substitutes":false}}
                """;
    }

    public static String userPrompt(String playerRequest, List<String> itemCandidates, List<String> fluidCandidates,
            java.util.Map<String, Integer> durabilityInfo, java.util.Map<String, List<String>> infoTexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("候选物品清单: ").append(itemCandidates).append('\n');
        sb.append("候选流体清单: ").append(fluidCandidates).append('\n');
        if (durabilityInfo != null && !durabilityInfo.isEmpty()) {
            sb.append("候选物品的耐久上限: ").append(durabilityInfo)
                    .append("（玩家说某物「仅耗耐久」时用 catalyst_durability，uses_per_tool 不得超过该耐久值）\n");
        }
        if (infoTexts != null && !infoTexts.isEmpty()) {
            sb.append("作者提示（整合包作者写给玩家的自然语言说明，可能中英混杂；"
                    + "其中提到消耗性/耐久/特殊条件时，以它为最高优先级依据标注 role）:\n");
            infoTexts.forEach((id, texts) -> sb.append("  ").append(id).append(" -> ")
                    .append(String.join(" / ", texts)).append('\n'));
        }
        sb.append("玩家需求: ").append(playerRequest);
        return sb.toString();
    }

    /** 自我修正循环的追打消息（§5.5）。 */
    public static String correctionPrompt(List<String> errorLines) {
        return "上次输出有这些错误：\n" + String.join("\n", errorLines)
                + "\n请仅输出修正后的完整 JSON（仍然只输出 JSON，不要解释）。";
    }

    public static List<Message> initialMessages(String playerRequest, List<String> items, List<String> fluids,
            java.util.Map<String, Integer> durabilityInfo, java.util.Map<String, List<String>> infoTexts) {
        return new java.util.ArrayList<>(List.of(
                new Message("system", systemPrompt()),
                new Message("user", userPrompt(playerRequest, items, fluids, durabilityInfo, infoTexts))));
    }
}

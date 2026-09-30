package dev.patternizer.client.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.patternizer.client.llm.OpenAiCompatibleClient.Message;
import dev.patternizer.client.search.ItemCandidateSearch;
import dev.patternizer.config.PatternizerClientConfig;
import dev.patternizer.spec.PatternSpec;
import dev.patternizer.spec.PatternSpecJson;
import dev.patternizer.spec.PatternSpecValidator;
import dev.patternizer.spec.PatternSpecValidator.ValidationError;

/**
 * LLM 生成编排（客户端）：检索候选 → 组 prompt → 调 LLM →
 * 解析 + 校验 → 失败则回喂错误自动重试（自我修正循环，§5.5）。
 * 结果回调统一切回客户端主线程由调用方处理。
 */
public final class LlmGenerateService {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public sealed interface Result {
        record Ok(PatternSpec spec, String rawJson, List<String> policyHits) implements Result {
        }

        record Failed(List<String> errorLines) implements Result {
        }
    }

    private LlmGenerateService() {
    }

    /**
     * @param prompt 玩家自然语言需求（必须在客户端线程调用，检索器要读译名）
     */
    public static void generate(String prompt, Consumer<Result> callback) {
        List<String> items = ItemCandidateSearch.search(prompt);
        List<String> fluids = ItemCandidateSearch.searchFluids(prompt, 20);
        var durabilityInfo = ItemCandidateSearch.durabilityInfo(items);
        java.util.Set<String> itemSet = new java.util.HashSet<>(items);
        java.util.Set<String> fluidSet = new java.util.HashSet<>(fluids);
        LOGGER.info("[aipatternizer] generate for prompt='{}' | item candidates={} fluids={} top10={}",
                prompt, items.size(), fluids.size(),
                items.subList(0, Math.min(10, items.size())));

        List<Message> messages = PromptBuilder.initialMessages(prompt, items, fluids, durabilityInfo);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient();
        int maxRetries = PatternizerClientConfig.MAX_RETRIES.get();

        attempt(client, messages, 0, maxRetries, itemSet, fluidSet, callback);
    }

    /** 硬校验：target 与所有物品/流体条目必须来自候选清单（双子物质案的防线）。 */
    private static List<String> checkCandidates(PatternSpec spec, java.util.Set<String> items,
            java.util.Set<String> fluids) {
        List<String> errors = new ArrayList<>();
        if (spec.target != null && !items.contains(spec.target)) {
            errors.add("error.spec.not_in_candidates|" + spec.target);
        }
        for (var e : spec.inputs) {
            if (e.isFluid() && !fluids.contains(e.fluid)) {
                errors.add("error.spec.not_in_candidates|" + e.fluid);
            } else if (!e.isFluid() && e.item != null && !items.contains(e.item)) {
                errors.add("error.spec.not_in_candidates|" + e.item);
            }
        }
        for (var e : spec.outputs) {
            if (e.isFluid() && !fluids.contains(e.fluid)) {
                errors.add("error.spec.not_in_candidates|" + e.fluid);
            } else if (!e.isFluid() && e.item != null && !items.contains(e.item)) {
                errors.add("error.spec.not_in_candidates|" + e.item);
            }
        }
        return errors;
    }

    private static void attempt(OpenAiCompatibleClient client, List<Message> messages,
            int attempt, int maxRetries, java.util.Set<String> itemSet, java.util.Set<String> fluidSet,
            Consumer<Result> callback) {
        client.chatComplete(messages).whenComplete((content, error) -> {
            if (error != null) {
                // 解包两种形态：LlmException 本体（超时/HTTP 错误）或 CompletionException 包装
                String code;
                if (error instanceof OpenAiCompatibleClient.LlmException le) {
                    code = le.getMessage();
                } else if (error.getCause() instanceof OpenAiCompatibleClient.LlmException le) {
                    code = le.getMessage();
                } else {
                    code = "error.llm.unknown";
                }
                LOGGER.warn("[aipatternizer] generation failed at attempt {}: {}", attempt, code);
                callback.accept(new Result.Failed(List.of(code)));
                return;
            }
            List<String> errorLines = new ArrayList<>();
            PatternSpec spec = null;
            try {
                spec = PatternSpecJson.parse(content);
                for (ValidationError ve : PatternSpecValidator.validate(spec)) {
                    errorLines.add(ve.key() + " " + String.join(" ", ve.args()));
                }
                // 候选清单硬校验（防训练知识漂移：双子物质案 AI 凭印象选了清单外的 mekanism 机器）
                if (spec != null) {
                    errorLines.addAll(checkCandidates(spec, itemSet, fluidSet));
                }
            } catch (PatternSpecJson.SpecParseException e) {
                errorLines.add(e.getMessage());
            }

            if (spec != null && errorLines.isEmpty()) {
                // 催化剂策略（§10.15）：不可收回黑名单强制预置式
                List<String> policyHits = dev.patternizer.spec.CatalystPolicy.apply(
                        spec, dev.patternizer.config.PatternizerClientConfig.unreturnableSet());
                LOGGER.info("[aipatternizer] generation ok: type={} target={} inputs={} outputs={}",
                        spec.type, spec.target, spec.inputs.size(), spec.outputs.size());
                callback.accept(new Result.Ok(spec, content, policyHits));
                return;
            }
            if (attempt >= maxRetries) {
                LOGGER.warn("[aipatternizer] validation failed after {} attempt(s): {} | last model output: {}",
                        attempt + 1, errorLines,
                        content == null ? "<null>"
                                : content.substring(0, Math.min(800, content.length())));
                callback.accept(new Result.Failed(errorLines));
                return;
            }
            LOGGER.info("[aipatternizer] attempt {} validation errors, self-correcting: {}", attempt + 1, errorLines);
            // 自我修正：把错误回喂给模型
            messages.add(new Message("assistant", content));
            messages.add(new Message("user", PromptBuilder.correctionPrompt(errorLines)));
            attempt(client, messages, attempt + 1, maxRetries, itemSet, fluidSet, callback);
        });
    }
}

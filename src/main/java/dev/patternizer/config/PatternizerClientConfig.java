package dev.patternizer.config;

import java.util.List;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 客户端配置（§4.3）：LLM API 接入参数。
 * 仅存于客户端 config/aipatternizer-client.toml，API Key 绝不进数据包与日志。
 */
public final class PatternizerClientConfig {

    public enum ProxyMode {
        DIRECT, SYSTEM, CUSTOM
    }

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> BASE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> MODEL;
    public static final ForgeConfigSpec.IntValue TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_RETRIES;
    public static final ForgeConfigSpec.DoubleValue TEMPERATURE;
    public static final ForgeConfigSpec.BooleanValue STREAM_OUTPUT;
    public static final ForgeConfigSpec.EnumValue<ProxyMode> PROXY_MODE;
    public static final ForgeConfigSpec.ConfigValue<String> PROXY_HOST;
    public static final ForgeConfigSpec.IntValue PROXY_PORT;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> UNRETURNABLE_CATALYSTS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("llm");
        BASE_URL = b.comment("OpenAI-compatible API base URL")
                .define("baseUrl", "https://api.openai.com/v1");
        API_KEY = b.comment("API key. Stored locally only; never sent to the game server or written to logs.")
                .define("apiKey", "");
        MODEL = b.comment("Model name")
                .define("model", "gpt-4o-mini");
        TIMEOUT_SECONDS = b.comment("Request timeout in seconds")
                .defineInRange("timeoutSeconds", 60, 5, 300);
        STREAM_OUTPUT = b.comment("Stream LLM output (SSE) and show it live in the patternizer GUI")
                .define("streamOutput", true);
        MAX_RETRIES = b.comment("Max self-correction retries when the model output fails validation")
                .defineInRange("maxRetries", 3, 0, 8);
        TEMPERATURE = b.comment("Sampling temperature")
                .defineInRange("temperature", 0.2, 0.0, 1.0);
        b.pop();

        b.push("proxy");
        PROXY_MODE = b.comment("Proxy mode: DIRECT / SYSTEM / CUSTOM")
                .defineEnum("mode", ProxyMode.DIRECT);
        PROXY_HOST = b.comment("Custom proxy host")
                .define("host", "127.0.0.1");
        PROXY_PORT = b.comment("Custom proxy port")
                .defineInRange("port", 7890, 1, 65535);
        b.pop();

        b.push("catalyst");
        UNRETURNABLE_CATALYSTS = b.comment(
                "Additional 'unreturnable' catalysts (modid:name) on top of the built-in blacklist.",
                "Items listed here are forced from catalyst_returned to catalyst_preplaced.")
                .defineList("extraUnreturnable", List.of(), o -> o instanceof String);
        b.pop();

        SPEC = b.build();
    }

    private PatternizerClientConfig() {
    }

    public static java.util.Set<String> unreturnableSet() {
        // 设计原则 9：大小写不敏感——配置里写大写也归一到小写
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String s : UNRETURNABLE_CATALYSTS.get()) {
            out.add(s.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    public static void save() {
        SPEC.save();
    }
}

package dev.patternizer.client.llm;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.patternizer.config.PatternizerClientConfig;

/**
 * OpenAI 兼容协议的 LLM 客户端（§5.1）。
 * POST {baseUrl}/chat/completions，异步 + 超时 + 429/5xx 指数退避（§10.8）。
 * 安全约束：API Key 只进 Authorization 头，永不写日志（§10.9）。
 */
public final class OpenAiCompatibleClient {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static String abbreviate(String body) {
        if (body == null) {
            return "<null>";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    /** 沿 cause 链走到最内层，返回根因异常名（避免显示 CompletionException 这类外壳）。 */
    private static String rootCauseName(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName();
    }

    /** 一条对话消息。 */
    public record Message(String role, String content) {
    }

    public static final class LlmException extends Exception {
        private final boolean retryable;

        public LlmException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private static final int MAX_BACKOFF_RETRIES = 2;
    private static final long[] BACKOFF_SECONDS = { 2, 4 };

    private final HttpClient http;

    public OpenAiCompatibleClient() {
        this.http = buildHttpClient();
    }

    private static HttpClient buildHttpClient() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(PatternizerClientConfig.TIMEOUT_SECONDS.get()));
        switch (PatternizerClientConfig.PROXY_MODE.get()) {
        case SYSTEM -> b.proxy(ProxySelector.getDefault());
        case CUSTOM -> b.proxy(ProxySelector.of(new InetSocketAddress(
                PatternizerClientConfig.PROXY_HOST.get(), PatternizerClientConfig.PROXY_PORT.get())));
        default -> {
        }
        }
        return b.build();
    }

    /**
     * @return 助手消息文本；失败时 future 以 LlmException 完成
     */
    public CompletableFuture<String> chatComplete(List<Message> messages) {
        CompletableFuture<String> future = new CompletableFuture<>();
        sendWithBackoff(messages, 0, future);
        return future;
    }

    /**
     * SSE 流式生成：stream=true，逐 chunk 回调 onDelta（累计全文），
     * 完成后 future 拿到全文。端点不支持流式时回退为一次性 JSON 解析。
     */
    public CompletableFuture<String> chatCompleteStream(List<Message> messages, Consumer<String> onDelta) {
        CompletableFuture<String> future = new CompletableFuture<>();
        sendStreamWithBackoff(messages, 0, onDelta, future);
        return future;
    }

    private HttpRequest buildRequest(List<Message> messages, boolean stream) {
        String baseUrl = PatternizerClientConfig.BASE_URL.get().trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", PatternizerClientConfig.MODEL.get().trim());
        body.addProperty("temperature", PatternizerClientConfig.TEMPERATURE.get());
        if (stream) {
            body.addProperty("stream", true);
        } else {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            body.add("response_format", responseFormat);
        }
        JsonArray arr = new JsonArray();
        for (Message m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            arr.add(o);
        }
        body.add("messages", arr);

        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(PatternizerClientConfig.TIMEOUT_SECONDS.get()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + PatternizerClientConfig.API_KEY.get().trim())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    /** 拉取可用模型列表（GET {baseUrl}/models），同时兼作连接测试。 */
    public CompletableFuture<List<String>> fetchModels() {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        String apiKey = PatternizerClientConfig.API_KEY.get().trim();
        if (apiKey.isEmpty()) {
            future.completeExceptionally(new LlmException("error.llm.no_api_key", false));
            return future;
        }
        String baseUrl = PatternizerClientConfig.BASE_URL.get().trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models"))
                .timeout(Duration.ofSeconds(PatternizerClientConfig.TIMEOUT_SECONDS.get()))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        future.completeExceptionally(
                                new LlmException("error.llm.io|" + error.getClass().getSimpleName(), true));
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        future.completeExceptionally(
                                new LlmException("error.llm.http|" + response.statusCode(), false));
                        return;
                    }
                    try {
                        List<String> models = new ArrayList<>();
                        for (var el : JsonParser.parseString(response.body())
                                .getAsJsonObject().getAsJsonArray("data")) {
                            models.add(el.getAsJsonObject().get("id").getAsString());
                        }
                        models.sort(String::compareTo);
                        future.complete(models);
                    } catch (Exception e) {
                        future.completeExceptionally(
                                new LlmException("error.llm.bad_response|" + e.getClass().getSimpleName(), false));
                    }
                });
        return future;
    }

    private void sendWithBackoff(List<Message> messages, int backoffAttempt,
            CompletableFuture<String> future) {
        String apiKey = PatternizerClientConfig.API_KEY.get().trim();
        if (apiKey.isEmpty()) {
            future.completeExceptionally(new LlmException("error.llm.no_api_key", false));
            return;
        }
        HttpRequest request = buildRequest(messages, false);

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        LOGGER.warn("[aipatternizer] LLM request IO error: {}", error.toString());
                        retryOrFail(messages, backoffAttempt, future,
                                new LlmException("error.llm.io|" + rootCauseName(error), true));
                        return;
                    }
                    int status = response.statusCode();
                    if (status >= 200 && status < 300) {
                        try {
                            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                            String content = root.getAsJsonArray("choices")
                                    .get(0).getAsJsonObject()
                                    .getAsJsonObject("message")
                                    .get("content").getAsString();
                            future.complete(content);
                        } catch (Exception e) {
                            LOGGER.warn("[aipatternizer] LLM response parse error, body head: {}",
                                    abbreviate(response.body()));
                            future.completeExceptionally(
                                    new LlmException("error.llm.bad_response|" + e.getClass().getSimpleName(), false));
                        }
                    } else {
                        LOGGER.warn("[aipatternizer] LLM HTTP {}: {}", status, abbreviate(response.body()));
                        boolean retryable = status == 429 || status >= 500;
                        retryOrFail(messages, backoffAttempt, future,
                                new LlmException("error.llm.http|" + status, retryable));
                    }
                });
    }

    /** SSE 流式发送：逐行读 data: 块，累计全文回调 onDelta。 */
    private void sendStreamWithBackoff(List<Message> messages, int backoffAttempt,
            Consumer<String> onDelta, CompletableFuture<String> future) {
        String apiKey = PatternizerClientConfig.API_KEY.get().trim();
        if (apiKey.isEmpty()) {
            future.completeExceptionally(new LlmException("error.llm.no_api_key", false));
            return;
        }
        HttpRequest request = buildRequest(messages, true);

        http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        LOGGER.warn("[aipatternizer] LLM stream IO error: {}", error.toString());
                        retryOrFailStream(messages, backoffAttempt, onDelta, future,
                                new LlmException("error.llm.io|" + rootCauseName(error), true));
                        return;
                    }
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        String head = "";
                        try (var lines = response.body()) {
                            head = lines.limit(4).reduce("", (a, b) -> a + b);
                        }
                        LOGGER.warn("[aipatternizer] LLM stream HTTP {}: {}", status, abbreviate(head));
                        boolean retryable = status == 429 || status >= 500;
                        retryOrFailStream(messages, backoffAttempt, onDelta, future,
                                new LlmException("error.llm.http|" + status, retryable));
                        return;
                    }
                    CompletableFuture.runAsync(() -> {
                        StringBuilder sb = new StringBuilder();
                        try (var lines = response.body()) {
                            lines.forEach(line -> {
                                if (!line.startsWith("data:")) {
                                    return;
                                }
                                String payload = line.substring(5).trim();
                                if ("[DONE]".equals(payload) || payload.isEmpty()) {
                                    return;
                                }
                                try {
                                    JsonObject chunk = JsonParser.parseString(payload).getAsJsonObject();
                                    var choices = chunk.getAsJsonArray("choices");
                                    if (choices == null || choices.isEmpty()) {
                                        return;
                                    }
                                    var delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                                    if (delta != null && delta.has("content")) {
                                        sb.append(delta.get("content").getAsString());
                                        onDelta.accept(sb.toString());
                                    }
                                } catch (Exception ignored) {
                                    // 单个 chunk 解析失败跳过（心跳/注释行等）
                                }
                            });
                            future.complete(sb.toString());
                        } catch (Exception e) {
                            LOGGER.warn("[aipatternizer] LLM stream read error: {}", e.toString());
                            future.completeExceptionally(
                                    new LlmException("error.llm.io|" + rootCauseName(e), true));
                        }
                    });
                });
    }

    private void retryOrFailStream(List<Message> messages, int backoffAttempt, Consumer<String> onDelta,
            CompletableFuture<String> future, LlmException error) {
        if (error.retryable() && backoffAttempt < MAX_BACKOFF_RETRIES) {
            long delay = BACKOFF_SECONDS[Math.min(backoffAttempt, BACKOFF_SECONDS.length - 1)];
            CompletableFuture.runAsync(
                    () -> sendStreamWithBackoff(messages, backoffAttempt + 1, onDelta, future),
                    CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS));
        } else {
            future.completeExceptionally(error);
        }
    }

    private void retryOrFail(List<Message> messages, int backoffAttempt,
            CompletableFuture<String> future, LlmException error) {
        if (error.retryable() && backoffAttempt < MAX_BACKOFF_RETRIES) {
            long delay = BACKOFF_SECONDS[Math.min(backoffAttempt, BACKOFF_SECONDS.length - 1)];
            CompletableFuture.runAsync(
                    () -> sendWithBackoff(messages, backoffAttempt + 1, future),
                    CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS));
        } else {
            future.completeExceptionally(error);
        }
    }
}

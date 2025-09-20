package com.jacylunatic.aicover.aicover.service.generators;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jacylunatic.aicover.aicover.model.ProgressUpdate;
import com.jacylunatic.aicover.aicover.model.TongyiSetting;
import com.jacylunatic.aicover.aicover.service.ImageGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class TongyiImageGenerator implements ImageGenerator {

    private final ReactiveSettingFetcher settingFetcher;
    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static class PollAgainException extends RuntimeException {}

    @Override
    public String getPlatformIdentifier() {
        return "tongyi";
    }

    @Override
    public Flux<ProgressUpdate> generateImage(String prompt, String model, String size) {
        Mono<String> apiKeyMono = settingFetcher.fetch(TongyiSetting.GROUP, TongyiSetting.class)
            .switchIfEmpty(Mono.just(new TongyiSetting()))
            .flatMap(setting -> {
                String apiKey = setting.getApiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    return Mono.error(new IllegalStateException("未在插件设置中找到通义万相的 API-KEY"));
                }
                return Mono.just(apiKey);
            });

        return apiKeyMono.flux().concatMap(apiKey ->
            Flux.concat(
                Mono.just(new ProgressUpdate("正在提交至通义万相...")),
                submitGenerationTask(prompt, model, size, apiKey)
                    .flux()
                    .concatMap(taskId -> pollTaskResultWithProgress(taskId, apiKey))
            )
        );
    }

    private Mono<String> submitGenerationTask(String prompt, String model, String size, String apiKey) {
        String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
        Map<String, Object> requestBody = Map.of(
            "model", model, "input", Map.of("prompt", prompt), "parameters", Map.of("size", size, "n", 1)
        );
        return webClient.post()
            .uri(url)
            .header("Authorization", "Bearer " + apiKey)
            .header("X-DashScope-Async", "enable")
            .header("Content-Type", "application/json")
            .bodyValue(requestBody)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("AI 服务请求失败: " + parseErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .flatMap(this::parseTaskIdFromResponse);
    }
    
    private Flux<ProgressUpdate> pollTaskResultWithProgress(String taskId, String apiKey) {
        String url = "https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId;

        return webClient.get()
            .uri(url)
            .header("Authorization", "Bearer " + apiKey)
            .retrieve()
            .bodyToMono(String.class)
            .flatMap(this::checkTaskStatusAndDecideNextAction)
            .retryWhen(Retry.fixedDelay(60, Duration.ofSeconds(2))
                .filter(error -> error instanceof PollAgainException)
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                    new RuntimeException("图片生成超时，请稍后再试。")))
            .flux();
    }

    private Mono<ProgressUpdate> checkTaskStatusAndDecideNextAction(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            String taskStatus = root.at("/output/task_status").asText();
            log.info("查询到任务状态: {}", taskStatus);

            switch (taskStatus) {
                case "SUCCEEDED":
                    JsonNode urlNode = root.at("/output/results/0/url");
                    if (urlNode.isMissingNode() || !urlNode.isTextual()) {
                        return Mono.just(ProgressUpdate.error("任务成功，但未在响应中找到图片 URL。"));
                    }
                    // --- 核心修正：使用 intermediateSuccess ---
                    return Mono.just(ProgressUpdate.intermediateSuccess(urlNode.asText(), "AI 绘图成功！"));
                case "FAILED":
                    String errorMessage = root.at("/output/message").asText("任务执行失败");
                    return Mono.just(ProgressUpdate.error(errorMessage));
                case "PENDING":
                case "RUNNING":
                    return Mono.error(new PollAgainException());
                default:
                    return Mono.just(ProgressUpdate.error("未知的任务状态: " + taskStatus));
            }
        } catch (JsonProcessingException e) {
            return Mono.just(ProgressUpdate.error("解析任务状态响应失败: " + e.getMessage()));
        }
    }

    private Mono<String> parseTaskIdFromResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode taskIdNode = root.at("/output/task_id");
            if (taskIdNode.isMissingNode() || !taskIdNode.isTextual()) {
                return Mono.error(new RuntimeException("无法从响应中解析出任务 ID: " + jsonResponse));
            }
            return Mono.just(taskIdNode.asText());
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("解析任务ID响应失败", e));
        }
    }
    
    private String parseErrorMessage(String errorBody) {
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            return root.at("/message").asText(errorBody);
        } catch (JsonProcessingException e) {
            return errorBody;
        }
    }
}


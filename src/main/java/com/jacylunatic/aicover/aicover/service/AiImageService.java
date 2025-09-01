package com.jacylunatic.aicover.aicover.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jacylunatic.aicover.aicover.model.AiCoverSetting;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiImageService {

    private final ReactiveSettingFetcher settingFetcher;
    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Mono<String> generateImage(String prompt, String model, String size) {
        // 步骤 1: 获取 API-KEY 并提交任务
        return settingFetcher.fetch(AiCoverSetting.GROUP, AiCoverSetting.class)
            .switchIfEmpty(Mono.just(new AiCoverSetting()))
            .flatMap(setting -> {
                String apiKey = setting.getApiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    return Mono.error(new IllegalStateException("未在插件设置中找到有效的 API-KEY"));
                }
                return submitGenerationTask(prompt, model, size, apiKey);
            })
            // 步骤 2: 轮询任务结果
            .flatMap(taskId -> pollTaskResult(taskId, settingFetcher));
    }

    /**
     * 步骤 1: 提交图片生成任务，获取 Task ID。
     */
    private Mono<String> submitGenerationTask(String prompt, String model, String size, String apiKey) {
        // 使用官方文档提供的正确 URL
        String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";

        // 构建符合官方文档规范的请求体
        Map<String, Object> requestBody = Map.of(
            "model", model,
            "input", Map.of("prompt", prompt),
            "parameters", Map.of("size", size, "n", 1)
        );
        
        log.info("向阿里云百炼提交生成任务, body: {}", requestBody);

        return webClient.post()
            .uri(url)
            .header("Authorization", "Bearer " + apiKey)
            .header("X-DashScope-Async", "enable") // 添加必需的异步头
            .header("Content-Type", "application/json")
            .bodyValue(requestBody)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> {
                    log.error("提交任务时出错: {}, 响应: {}", response.statusCode(), errorBody);
                    return Mono.error(new RuntimeException("AI 服务请求失败: " + parseErrorMessage(errorBody)));
                }))
            .bodyToMono(String.class)
            .flatMap(this::parseTaskIdFromResponse);
    }

    /**
     * 步骤 2: 轮询任务结果，直到成功或超时。
     */
    private Mono<String> pollTaskResult(String taskId, ReactiveSettingFetcher settingFetcher) {
        return settingFetcher.fetch(AiCoverSetting.GROUP, AiCoverSetting.class)
            .switchIfEmpty(Mono.just(new AiCoverSetting()))
            .flatMap(setting -> {
                String apiKey = setting.getApiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    return Mono.error(new IllegalStateException("API-KEY 在轮询时丢失"));
                }
                String url = "https://dashscope.aliyuncs.com/api/v1/tasks/" + taskId;

                return webClient.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .flatMap(this::checkTaskStatusAndGetUrl);
            })
            // 轮询逻辑：如果任务未完成，则等待2秒后重试，最多重试15次（总计约30秒）
            .retryWhen(Retry.fixedDelay(15, Duration.ofSeconds(2))
                .filter(error -> error instanceof TaskNotFinishedException)
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                    new RuntimeException("图片生成超时，请稍后再试。")));
    }

    /**
     * 检查任务状态，如果成功则返回 URL，如果还在运行则抛出特定异常以触发重试。
     */
    private Mono<String> checkTaskStatusAndGetUrl(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            String taskStatus = root.at("/output/task_status").asText();
            log.info("查询到任务状态: {}", taskStatus);

            switch (taskStatus) {
                case "SUCCEEDED":
                    JsonNode urlNode = root.at("/output/results/0/url");
                    if (urlNode.isMissingNode() || !urlNode.isTextual()) {
                        return Mono.error(new RuntimeException("任务成功，但未在响应中找到图片 URL。"));
                    }
                    return Mono.just(urlNode.asText());
                case "FAILED":
                    String errorMessage = root.at("/output/message").asText("任务执行失败");
                    return Mono.error(new RuntimeException(errorMessage));
                case "PENDING":
                case "RUNNING":
                    // 抛出自定义异常，以被 retryWhen 捕获并触发下一次轮询
                    return Mono.error(new TaskNotFinishedException());
                default:
                    return Mono.error(new RuntimeException("未知的任务状态: " + taskStatus));
            }
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("解析任务状态响应失败", e));
        }
    }

    private Mono<String> parseTaskIdFromResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode taskIdNode = root.at("/output/task_id");
            if (taskIdNode.isMissingNode() || !taskIdNode.isTextual()) {
                return Mono.error(new RuntimeException("无法从响应中解析出任务 ID: " + jsonResponse));
            }
            log.info("成功获取到任务 ID: {}", taskIdNode.asText());
            return Mono.just(taskIdNode.asText());
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("解析任务ID响应失败", e));
        }
    }
    
    private String parseErrorMessage(String errorBody) {
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode messageNode = root.at("/message");
            if (messageNode.isTextual()) {
                return messageNode.asText();
            }
        } catch (JsonProcessingException e) {
            // Ignore if it's not a valid JSON
        }
        return errorBody;
    }

    // 自定义异常，用于控制轮询逻辑
    private static class TaskNotFinishedException extends RuntimeException {}
}


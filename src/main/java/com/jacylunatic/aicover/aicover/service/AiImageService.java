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

    private final AlistService alistService;

    /**
     * 调用 AI 服务生成图片，并根据选项决定是否上传到 Alist。
     * @param uploadToAlist 是否上传到 Alist
     * @return 包含最终图片 URL 的 Mono<String>
     */
    public Mono<String> generateImage(String prompt, String model, String size, boolean uploadToAlist) {
        // 步骤 1: 获取临时 URL
        return getTempImageUrl(prompt, model, size)
            .flatMap(tempImageUrl -> {
                if (uploadToAlist) {
                    log.info("检测到上传 Alist 选项，正在调用 AlistService...");
                    // 如果需要上传，则调用 AlistService
                    return alistService.uploadImageFromUrl(tempImageUrl);
                } else {
                    log.info("未选择上传 Alist，直接返回临时 URL。");
                    // 否则，直接返回临时 URL
                    return Mono.just(tempImageUrl);
                }
            });
    }

    /**
     * 从阿里云百炼获取临时的图片 URL。
     */
    private Mono<String> getTempImageUrl(String prompt, String model, String size) {
        return settingFetcher.fetch(AiCoverSetting.GROUP, AiCoverSetting.class)
            .switchIfEmpty(Mono.just(new AiCoverSetting()))
            .flatMap(setting -> {
                String apiKey = setting.getApiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    return Mono.error(new IllegalStateException("未在插件设置中找到有效的 API-KEY"));
                }
                return submitGenerationTask(prompt, model, size, apiKey);
            })
            .flatMap(this::pollTaskResult);
    }
    
    private Mono<String> submitGenerationTask(String prompt, String model, String size, String apiKey) {
        String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "input", Map.of("prompt", prompt),
            "parameters", Map.of("size", size, "n", 1)
        );
        
        log.info("向阿里云百炼提交生成任务, body: {}", requestBody);

        return webClient.post()
            .uri(url)
            .header("Authorization", "Bearer " + apiKey)
            .header("X-DashScope-Async", "enable")
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
    
    private Mono<String> pollTaskResult(String taskId) {
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
            .retryWhen(Retry.fixedDelay(15, Duration.ofSeconds(2))
                .filter(error -> error instanceof TaskNotFinishedException)
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                    new RuntimeException("图片生成超时，请稍后再试。")));
    }

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
            // Not a valid JSON, return original body
        }
        return errorBody;
    }

    // 自定义异常，用于控制轮询逻辑
    private static class TaskNotFinishedException extends RuntimeException {}
}


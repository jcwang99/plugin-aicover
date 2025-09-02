package com.jacylunatic.aicover.aicover.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jacylunatic.aicover.aicover.model.AiCoverSetting;
import com.jacylunatic.aicover.aicover.model.GenerateImageResponse;
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
     * @return 包含最终图片 URL 和潜在警告信息的响应对象
     */
    public Mono<GenerateImageResponse> generateImage(String prompt, String model, String size, boolean uploadToAlist) {
        return getTempImageUrl(prompt, model, size)
            .flatMap(tempImageUrl -> {
                if (uploadToAlist) {
                    log.info("检测到上传 Alist 选项，正在调用 AlistService...");
                    return alistService.uploadImageFromUrl(tempImageUrl)
                        .map(GenerateImageResponse::new)
                        // --- 核心修正：添加 onErrorResume 回退逻辑 ---
                        .onErrorResume(error -> {
                            log.warn("Alist 上传失败，将返回原始临时链接。错误详情: {}", error.getMessage());
                            String warningMessage = "图片已生成，但上传到 Alist 失败: " + error.getMessage();
                            // 返回一个包含原始 URL 和警告信息的响应
                            return Mono.just(new GenerateImageResponse(tempImageUrl, warningMessage));
                        });
                } else {
                    log.info("未选择上传 Alist，直接返回临时 URL。");
                    return Mono.just(new GenerateImageResponse(tempImageUrl));
                }
            });
    }
    
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
            .retryWhen(Retry.fixedDelay(60, Duration.ofSeconds(2))
                .filter(error -> error instanceof TaskNotFinishedException)
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                    new RuntimeException("图片生成超时，请稍后再试。")));
    }

    private Mono<String> checkTaskStatusAndGetUrl(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            String taskStatus = root.at("/output/task_status").asText();
            if ("SUCCEEDED".equals(taskStatus)) {
                JsonNode urlNode = root.at("/output/results/0/url");
                if (urlNode.isMissingNode() || !urlNode.isTextual()) {
                    return Mono.error(new RuntimeException("任务成功，但未在响应中找到图片 URL。"));
                }
                return Mono.just(urlNode.asText());
            } else if ("FAILED".equals(taskStatus)) {
                return Mono.error(new RuntimeException(root.at("/output/message").asText("任务执行失败")));
            } else if ("PENDING".equals(taskStatus) || "RUNNING".equals(taskStatus)) {
                return Mono.error(new TaskNotFinishedException());
            } else {
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

    private static class TaskNotFinishedException extends RuntimeException {}
}


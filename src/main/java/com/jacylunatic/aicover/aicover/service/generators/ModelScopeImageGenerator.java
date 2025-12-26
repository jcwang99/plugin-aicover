package com.jacylunatic.aicover.aicover.service.generators;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jacylunatic.aicover.aicover.model.AiPlatformSettings;
import com.jacylunatic.aicover.aicover.model.ProgressUpdate;
import com.jacylunatic.aicover.aicover.service.ImageGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class ModelScopeImageGenerator implements ImageGenerator {

    private final ReactiveSettingFetcher settingFetcher;
    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BASE_URL = "https://api-inference.modelscope.cn/";

    @Override
    public String getPlatformIdentifier() {
        return "modelscope";
    }

    @Override
    public Flux<ProgressUpdate> generateImage(String prompt, String model, String size) {
        Mono<String> apiKeyMono = settingFetcher.fetch(AiPlatformSettings.GROUP, AiPlatformSettings.class)
            .switchIfEmpty(Mono.just(new AiPlatformSettings()))
            .flatMap(setting -> {
                String apiKey = setting.getModelscopeApiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    return Mono.error(new IllegalStateException("未在插件设置中找到魔塔（ModelScope）的 API Key (MODELSCOPE_SDK_TOKEN)"));
                }
                return Mono.just(apiKey);
            });

        return apiKeyMono.flux().concatMap(apiKey ->
            Flux.concat(
                Mono.just(new ProgressUpdate("正在提交至魔塔（ModelScope）...")),
                submitGenerationTask(prompt, model, size, apiKey)
                    .flux()
                    .concatMap(taskId -> pollTaskResultWithProgress(taskId, apiKey))
            )
        );
    }

    private Mono<String> submitGenerationTask(String prompt, String model, String size, String apiKey) {
        String url = BASE_URL + "v1/images/generations";
        
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        // 如果提供了尺寸，转换为魔塔 API 需要的格式（WxH，使用小写 x）
        if (size != null && !size.isBlank()) {
            // 将格式从 "1024*1024" 转换为 "1024x1024"
            String formattedSize = size.replace('*', 'x');
            requestBody.put("size", formattedSize);
        }

        log.info("[ModelScope] Submitting generation task with model: {}, prompt: {}, size: {}", model, prompt, size);

        return webClient.post()
            .uri(url)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("X-ModelScope-Async-Mode", "true")
            .bodyValue(requestBody)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("魔塔 API 请求失败: " + parseErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .flatMap(this::parseTaskIdFromResponse);
    }

    private Flux<ProgressUpdate> pollTaskResultWithProgress(String taskId, String apiKey) {
        String url = BASE_URL + "v1/tasks/" + taskId;

        log.info("[ModelScope] Polling task status for task_id: {}", taskId);

        // 使用 Flux.interval 定期轮询，每次轮询都发送进度更新以保持 SSE 连接
        // 使用 concatMap 确保每次轮询按顺序执行，避免并发问题
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(5))
            .concatMap(attempt -> {
                if (attempt >= 60) {
                    // 超过最大轮询次数
                    return Mono.just(ProgressUpdate.error("图片生成超时（5分钟），请稍后再试。"));
                }
                
                // 执行一次轮询，每次轮询都会发送进度更新
                return pollOnce(url, apiKey);
            })
            .takeUntil(update -> {
                // 收到最终结果（成功或失败）时停止轮询
                return update.getFinalImageUrl() != null || Boolean.TRUE.equals(update.getIsError());
            })
            .take(61); // 最多 60 次轮询 + 1 次超时消息
    }

    private Mono<ProgressUpdate> pollOnce(String url, String apiKey) {
        return webClient.get()
            .uri(url)
            .header("Authorization", "Bearer " + apiKey)
            .header("X-ModelScope-Task-Type", "image_generation")
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("查询任务状态失败: " + parseErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .flatMap(this::checkTaskStatusAndReturnProgress)
            .onErrorResume(error -> {
                log.error("[ModelScope] Error polling task status", error);
                return Mono.just(ProgressUpdate.error("查询任务状态时出错: " + error.getMessage()));
            });
    }

    private Mono<ProgressUpdate> checkTaskStatusAndReturnProgress(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            String taskStatus = root.at("/task_status").asText();
            log.info("[ModelScope] Task status: {}", taskStatus);

            switch (taskStatus) {
                case "SUCCEED":
                    JsonNode outputImages = root.at("/output_images");
                    if (outputImages.isMissingNode() || !outputImages.isArray() || outputImages.size() == 0) {
                        return Mono.just(ProgressUpdate.error("任务成功，但未在响应中找到图片 URL。"));
                    }
                    String imageUrl = outputImages.get(0).asText();
                    if (imageUrl == null || imageUrl.isBlank()) {
                        return Mono.just(ProgressUpdate.error("任务成功，但图片 URL 为空。"));
                    }
                    return Mono.just(ProgressUpdate.intermediateSuccess(imageUrl, "魔塔绘图成功！"));
                case "FAILED":
                    String errorMessage = root.at("/message").asText("任务执行失败");
                    return Mono.just(ProgressUpdate.error("魔塔图片生成失败: " + errorMessage));
                case "PENDING":
                case "RUNNING":
                case "PROCESSING":
                    // 关键：在处理中状态时，发送进度更新以保持 SSE 连接活跃
                    String statusMessage = "任务处理中... (" + taskStatus + ")";
                    return Mono.just(new ProgressUpdate(statusMessage));
                default:
                    return Mono.just(ProgressUpdate.error("未知的任务状态: " + taskStatus));
            }
        } catch (JsonProcessingException e) {
            log.error("[ModelScope] Failed to parse task status response", e);
            return Mono.just(ProgressUpdate.error("解析任务状态响应失败: " + e.getMessage()));
        }
    }

    private Mono<String> parseTaskIdFromResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode taskIdNode = root.at("/task_id");
            if (taskIdNode.isMissingNode() || !taskIdNode.isTextual()) {
                log.error("[ModelScope] Cannot parse task_id from response: {}", jsonResponse);
                return Mono.error(new RuntimeException("无法从响应中解析出任务 ID: " + jsonResponse));
            }
            String taskId = taskIdNode.asText();
            log.info("[ModelScope] Task ID parsed: {}", taskId);
            return Mono.just(taskId);
        } catch (JsonProcessingException e) {
            log.error("[ModelScope] Failed to parse task ID from response", e);
            return Mono.error(new RuntimeException("解析任务ID响应失败", e));
        }
    }

    private String parseErrorMessage(String errorBody) {
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            // 魔塔 API 错误格式：{"errors":{"message":"..."}}
            JsonNode errorsNode = root.at("/errors/message");
            if (!errorsNode.isMissingNode() && errorsNode.isTextual()) {
                return errorsNode.asText();
            }
            // 如果没有找到，尝试直接读取 message 字段
            JsonNode messageNode = root.at("/message");
            if (!messageNode.isMissingNode() && messageNode.isTextual()) {
                return messageNode.asText();
            }
            return errorBody;
        } catch (JsonProcessingException e) {
            return errorBody;
        }
    }
}

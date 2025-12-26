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

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class SiliconFlowImageGenerator implements ImageGenerator {

    private final ReactiveSettingFetcher settingFetcher;
    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getPlatformIdentifier() {
        return "siliconflow";
    }

    @Override
    public Flux<ProgressUpdate> generateImage(String prompt, String model, String size) {
        // --- 核心改造：获取统一的 AI 平台设置 ---
        Mono<AiPlatformSettings> settingMono = settingFetcher.fetch(AiPlatformSettings.GROUP, AiPlatformSettings.class)
            .switchIfEmpty(Mono.just(new AiPlatformSettings()));

        return settingMono.flux().concatMap(setting -> {
            // --- 核心改造：调用 siliconflowApiKey 的 getter ---
            String apiKey = setting.getSiliconflowApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return Flux.just(ProgressUpdate.error("未在插件设置中找到硅基流动的 API Key。"));
            }
            return Flux.concat(
                Mono.just(new ProgressUpdate("正在提交至硅基流动...")),
                callApi(prompt, model, size, apiKey)
            );
        });
    }

    private Flux<ProgressUpdate> callApi(String prompt, String model, String size, String apiKey) {
        String url = "https://api.siliconflow.cn/v1/images/generations";
        String formattedSize = size.replace('*', 'x');

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        requestBody.put("image_size", formattedSize);

        log.info("[SiliconFlow] Sending request to SiliconFlow API with body: {}", requestBody);

        return webClient.post()
            .uri(url)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .bodyValue(requestBody)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("硅基流动 API 请求失败: " + errorBody))))
            .bodyToMono(String.class)
            .flatMap(this::parseResponse)
            .flux();
    }

    private Mono<ProgressUpdate> parseResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode urlNode = root.at("/images/0/url");

            if (urlNode.isMissingNode() || !urlNode.isTextual()) {
                log.error("在硅基流动响应中未找到图片 URL。响应: {}", jsonResponse);
                String errorMessage = root.at("/error/message").asText("无法从硅基流动响应中解析图片URL");
                return Mono.just(ProgressUpdate.error(errorMessage));
            }
            return Mono.just(ProgressUpdate.finalSuccess(urlNode.asText(), "硅基流动绘图成功！"));
        } catch (JsonProcessingException e) {
            log.error("解析硅基流动响应 JSON 时出错", e);
            return Mono.just(ProgressUpdate.error("解析硅基流动响应失败: " + e.getMessage()));
        }
    }
}


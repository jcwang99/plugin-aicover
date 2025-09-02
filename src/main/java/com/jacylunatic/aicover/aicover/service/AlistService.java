package com.jacylunatic.aicover.aicover.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jacylunatic.aicover.aicover.model.AlistSetting;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlistService {

    private final ReactiveSettingFetcher settingFetcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final WebClient fastWebClient = WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(
            HttpClient.create()
                .responseTimeout(Duration.ofSeconds(20))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
        ))
        .build();

    private static final WebClient uploadWebClient = WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(
            HttpClient.create()
                .responseTimeout(Duration.ofMinutes(2))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
        ))
        .build();

    public Mono<String> uploadImageFromUrl(String tempImageUrl) {
        return settingFetcher.fetch(AlistSetting.GROUP, AlistSetting.class)
            .switchIfEmpty(Mono.just(new AlistSetting()))
            .flatMap(setting -> {
                if (!isAlistConfigured(setting)) {
                    return Mono.error(new IllegalStateException("Alist 配置不完整，请检查插件设置。"));
                }
                return login(setting)
                    .flatMap(token -> downloadImage(tempImageUrl)
                        .flatMap(imageData -> uploadToAlist(imageData, token, setting))
                        .flatMap(uploadPath -> refreshAlistStorage(uploadPath, token, setting)
                            .doOnError(e -> log.warn("[AlistService] Alist storage refresh failed, proceeding anyway.", e))
                            .onErrorResume(e -> Mono.empty())
                            .thenReturn(uploadPath)
                        )
                        .flatMap(uploadPath -> pollForSignedUrl(uploadPath, token, setting))
                    );
            });
    }

    private Mono<String> login(AlistSetting setting) {
        String loginUrl = setting.getAlistUrl() + "/api/auth/login";
        return fastWebClient.post()
            .uri(loginUrl)
            .bodyValue(Map.of("username", setting.getAlistUsername(), "password", setting.getAlistPassword()))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("Alist 登录失败: " + parseAlistErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .filter(responseBody -> responseBody != null && !responseBody.equalsIgnoreCase("null") && !responseBody.isBlank())
            .switchIfEmpty(Mono.error(new RuntimeException("Alist 登录认证失败，服务器返回了空响应。请检查您的用户名和密码是否正确。")))
            .flatMap(this::parseTokenFromResponse);
    }

    private Mono<byte[]> downloadImage(String imageUrl) {
        return Mono.fromCallable(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(imageUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(20000);
                if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                    try (InputStream inputStream = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                        byte[] data = new byte[4096];
                        int nRead;
                        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                            buffer.write(data, 0, nRead);
                        }
                        return buffer.toByteArray();
                    }
                } else {
                    throw new RuntimeException("下载临时图片失败: " + connection.getResponseCode());
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> uploadToAlist(byte[] imageData, String token, AlistSetting setting) {
        String fileName = UUID.randomUUID() + ".png";
        String uploadPath = setting.getAlistUploadPath() + "/" + fileName;
        log.info("[AlistService] 正在上传图片到 Alist 路径: {}", uploadPath);

        return uploadWebClient.put()
            .uri(setting.getAlistUrl() + "/api/fs/put")
            .header("Authorization", token)
            .header("File-Path", uploadPath)
            .bodyValue(imageData)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("Alist 上传失败: " + parseAlistErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .flatMap(this::checkAlistUploadResponse)
            .thenReturn(uploadPath);
    }

    /**
     * --- 核心修正：使用官方 /api/fs/list 接口刷新缓存 ---
     */
    private Mono<Void> refreshAlistStorage(String uploadPath, String token, AlistSetting setting) {
        String parentPath = uploadPath.substring(0, uploadPath.lastIndexOf('/'));
        if (parentPath.isEmpty()) {
            parentPath = "/";
        }
        log.info("[AlistService] 正在使用 /api/fs/list 刷新 Alist 缓存，路径: {}", parentPath);

        return fastWebClient.post()
            .uri(setting.getAlistUrl() + "/api/fs/list")
            .header("Authorization", token)
            .header("Content-Type", "application/json")
            .bodyValue(Map.of("path", parentPath, "refresh", true)) // 设置 refresh: true
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("Alist 缓存刷新失败: " + parseAlistErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .flatMap(responseBody -> {
                log.info("[AlistService] Alist 缓存刷新请求已发送。响应: {}", responseBody);
                try {
                    JsonNode root = objectMapper.readTree(responseBody);
                    if (root.at("/code").asInt(-1) == 200) {
                        return Mono.empty();
                    } else {
                        String message = root.at("/message").asText("未知的刷新错误");
                        return Mono.error(new RuntimeException("Alist 缓存刷新失败: " + message));
                    }
                } catch (JsonProcessingException e) {
                    return Mono.error(new RuntimeException("解析 Alist 刷新响应失败", e));
                }
            })
            .then();
    }

    private Mono<String> checkAlistUploadResponse(String jsonResponse) {
        log.info("[AlistService] Alist 上传成功，正在检查响应内容: {}", jsonResponse);
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            if (root.at("/code").asInt(-1) == 200) {
                return Mono.just(jsonResponse);
            } else {
                String message = root.at("/message").asText("未知的 Alist 上传错误");
                return Mono.error(new RuntimeException("Alist 上传失败: " + message));
            }
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("解析 Alist 上传响应失败", e));
        }
    }

    private Mono<String> pollForSignedUrl(String path, String token, AlistSetting setting) {
        return getSignedUrl(path, token, setting)
            .retryWhen(Retry.fixedDelay(5, Duration.ofSeconds(2))
                .filter(error -> error instanceof AlistObjectNotFoundException)
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                    new RuntimeException("获取 Alist 签名链接超时，文件可能未成功同步。")));
    }

    private Mono<String> getSignedUrl(String path, String token, AlistSetting setting) {
        String getUrl = setting.getAlistUrl() + "/api/fs/get";
        log.info("[AlistService] 正在为路径 {} 获取签名链接...", path);

        return fastWebClient.post()
            .uri(getUrl)
            .header("Authorization", token)
            .header("Content-Type", "application/json")
            .bodyValue(Map.of("path", path))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("获取 Alist 签名链接失败: " + parseAlistErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .flatMap(this::parseSignedUrlFromResponse);
    }

    private Mono<String> parseSignedUrlFromResponse(String jsonResponse) {
        log.info("[Debug AlistService] 准备解析签名链接响应: {}", jsonResponse);
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            if (root.at("/code").asInt(-1) == 500 && "object not found".equals(root.at("/message").asText(""))) {
                log.warn("[AlistService] 对象未找到，将在2秒后重试...");
                return Mono.error(new AlistObjectNotFoundException());
            }
            JsonNode dataNode = root.at("/data");
            if (dataNode.isMissingNode()) {
                return Mono.error(new RuntimeException("获取签名失败，响应中缺少 'data' 字段。"));
            }
            String rawUrl = dataNode.at("/raw_url").asText();
            if (!StringUtils.hasText(rawUrl)) {
                return Mono.error(new RuntimeException("获取签名失败，响应中缺少 'raw_url' 字段。"));
            }
            log.info("[AlistService] 成功获取带签名的 Alist URL: {}", rawUrl);
            return Mono.just(rawUrl);
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("解析 Alist 签名响应失败", e));
        }
    }

    private Mono<String> parseTokenFromResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode tokenNode = root.at("/data/token");
            if (tokenNode.isMissingNode() || !tokenNode.isTextual()) {
                return Mono.error(new RuntimeException("无法从 Alist 登录响应中解析 Token。"));
            }
            return Mono.just(tokenNode.asText());
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("解析 Alist Token 响应失败", e));
        }
    }

    private boolean isAlistConfigured(AlistSetting setting) {
        return StringUtils.hasText(setting.getAlistUrl()) &&
               StringUtils.hasText(setting.getAlistUsername()) &&
               StringUtils.hasText(setting.getAlistPassword()) &&
               StringUtils.hasText(setting.getAlistUploadPath());
    }

    private String parseAlistErrorMessage(String errorBody) {
        try {
            JsonNode root = objectMapper.readTree(errorBody);
            JsonNode messageNode = root.at("/message");
            if (messageNode.isTextual()) {
                return messageNode.asText();
            }
        } catch (JsonProcessingException e) {
            // Not a valid JSON
        }
        return errorBody;
    }

    private static class AlistObjectNotFoundException extends RuntimeException {}
}


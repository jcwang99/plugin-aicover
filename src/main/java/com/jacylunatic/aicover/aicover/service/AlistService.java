package com.jacylunatic.aicover.aicover.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jacylunatic.aicover.aicover.model.AlistSetting;
import com.jacylunatic.aicover.aicover.model.ProgressUpdate;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
            HttpClient.create().responseTimeout(Duration.ofSeconds(20))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)))
        .build();

    private static final WebClient uploadWebClient = WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(
            HttpClient.create().responseTimeout(Duration.ofMinutes(2))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)))
        .build();

    public Flux<ProgressUpdate> uploadImageFromUrl(String tempImageUrl) {
        log.info("[Debug AlistService] uploadImageFromUrl method ENTERED. URL: {}", tempImageUrl);
        return prepareAlistContext()
            .flatMapMany(context -> {
                log.info("[Debug AlistService] flatMapMany has been entered. Starting Alist process...");
                AlistSetting setting = (AlistSetting) context.get("setting");
                String token = (String) context.get("token");

                return Flux.concat(
                    Mono.just(new ProgressUpdate("Alist 登录成功！"))
                        .doOnNext(p -> log.info("[Debug AlistService] Step 1: Emitting 'Login Success'")),
                    Mono.just(new ProgressUpdate("正在下载临时图片..."))
                         .doOnNext(p -> log.info("[Debug AlistService] Step 2: Emitting 'Downloading Image'")),
                    downloadImage(tempImageUrl)
                        .doOnSuccess(bytes -> log.info("[Debug AlistService] Step 2 SUCCESS: Image downloaded ({} bytes)", bytes != null ? bytes.length : 0))
                        .flux()
                        .concatMap(imageData -> Flux.concat(
                            Mono.just(new ProgressUpdate("正在上传至 Alist..."))
                                .doOnNext(p -> log.info("[Debug AlistService] Step 3: Emitting 'Uploading to Alist'")),
                            uploadToAlist(imageData, token, setting)
                                .doOnSuccess(path -> log.info("[Debug AlistService] Step 3 SUCCESS: Uploaded to path {}", path))
                                .flux()
                                .concatMap(uploadPath -> Flux.concat(
                                    Mono.just(new ProgressUpdate("正在刷新 Alist 缓存..."))
                                        .doOnNext(p -> log.info("[Debug AlistService] Step 4: Emitting 'Refreshing cache'")),
                                    refreshAlistStorage(uploadPath, token, setting)
                                        .then(Mono.just(new ProgressUpdate("缓存刷新成功！")))
                                        .doOnSuccess(p -> log.info("[Debug AlistService] Step 4 SUCCESS: Cache refreshed")),
                                    Mono.just(new ProgressUpdate("正在获取最终链接..."))
                                         .doOnNext(p -> log.info("[Debug AlistService] Step 5: Emitting 'Polling for URL'")),
                                    pollForSignedUrl(uploadPath, token, setting)
                                        .doOnSuccess(p -> log.info("[Debug AlistService] Step 5 SUCCESS: Got final URL"))
                                ))
                        ))
                );
            });
    }

    private Mono<Map<String, Object>> prepareAlistContext() {
        log.info("[Debug AlistService] prepareAlistContext method ENTERED.");
        return settingFetcher.fetch(AlistSetting.GROUP, AlistSetting.class)
            .doOnNext(setting -> log.info("[Debug AlistService] Successfully fetched settings: {}", setting))
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("[Debug AlistService] No Alist settings found, using default empty object.");
                return Mono.just(new AlistSetting());
            }))
            .flatMap(setting -> {
                log.info("[Debug AlistService] Checking if Alist is configured with settings: {}", setting);
                if (!isAlistConfigured(setting)) {
                    return Mono.error(new IllegalStateException("Alist 配置不完整，请检查插件设置。"));
                }
                log.info("[Debug AlistService] Alist configuration is valid, proceeding to login.");
                return login(setting).map(token -> Map.of("setting", setting, "token", token));
            })
            .doOnNext(context -> log.info("[Debug AlistService] Context Mono is about to emit value: {}", context))
            .doOnError(error -> log.error("[Debug AlistService] Error in prepareAlistContext stream.", error));
    }


    private Mono<String> login(AlistSetting setting) {
        String loginUrl = setting.getAlistUrl() + "/api/auth/login";
        Map<String, String> loginBody = Map.of("username", setting.getAlistUsername(), "password", setting.getAlistPassword());
        log.info("[Debug AlistService] Preparing to login to Alist at {} with username '{}'", loginUrl, setting.getAlistUsername());

        return fastWebClient.post().uri(loginUrl)
            .bodyValue(loginBody)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> {
                    log.error("Alist 登录请求返回错误状态码: {}, 响应体: {}", response.statusCode(), errorBody);
                    return Mono.error(new RuntimeException("Alist 登录失败: " + parseAlistErrorMessage(errorBody)));
                }))
            .bodyToMono(String.class)
            .doOnSubscribe(subscription -> log.info("[Debug AlistService] Sending login request..."))
            .doOnSuccess(responseBody -> log.info("[Debug AlistService] Received login response. Body: '{}'", responseBody))
            .doOnError(error -> log.error("[Debug AlistService] Login request failed.", error))
            .flatMap(this::parseTokenFromResponse);
    }

    private Mono<String> parseTokenFromResponse(String jsonResponse) {
        log.info("[Debug AlistService] Attempting to parse token from response: {}", jsonResponse);
        if (!StringUtils.hasText(jsonResponse) || "null".equalsIgnoreCase(jsonResponse.trim())) {
             log.error("[Debug AlistService] Login response body is null or empty. This often indicates incorrect credentials.");
             return Mono.error(new RuntimeException("Alist 登录认证失败，请检查用户名和密码。"));
        }
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode tokenNode = root.at("/data/token");
            log.info("[Debug AlistService] Parsed JSON, '/data/token' node is: {}", tokenNode);

            if (tokenNode.isMissingNode() || !tokenNode.isTextual()) {
                log.error("[Debug AlistService] Token node is missing or not text. Full response: {}", jsonResponse);
                String errorMessage = root.at("/message").asText("无法从 Alist 登录响应中解析 Token");
                return Mono.error(new RuntimeException("Alist 登录失败: " + errorMessage));
            }
            String token = tokenNode.asText();
            log.info("[Debug AlistService] Successfully parsed token.");
            return Mono.just(token);
        } catch (JsonProcessingException e) {
            log.error("[Debug AlistService] Failed to parse login response JSON.", e);
            return Mono.error(new RuntimeException("解析 Alist Token 响应失败", e));
        }
    }

    private Mono<byte[]> downloadImage(String imageUrl) {
        log.info("[Debug AlistService] Attempting to download image from: {}", imageUrl);
        return Mono.fromCallable(() -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            try {
                int responseCode = connection.getResponseCode();
                 log.info("[Debug AlistService] Image download response code: {}", responseCode);
                if (responseCode >= 200 && responseCode < 300) {
                    try (InputStream inputStream = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                        inputStream.transferTo(buffer);
                        byte[] bytes = buffer.toByteArray();
                        log.info("[Debug AlistService] Image download successful, {} bytes.", bytes.length);
                        return bytes;
                    }
                } else {
                    throw new RuntimeException("下载临时图片失败: " + responseCode);
                }
            } finally {
                connection.disconnect();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> uploadToAlist(byte[] imageData, String token, AlistSetting setting) {
        String fileName = UUID.randomUUID() + ".png";
        String uploadPath = setting.getAlistUploadPath() + "/" + fileName;
        log.info("[Debug AlistService] Attempting to upload {} bytes to Alist path: {}", imageData.length, uploadPath);

        return uploadWebClient.put().uri(setting.getAlistUrl() + "/api/fs/put")
            .header("Authorization", token)
            .header("File-Path", uploadPath)
            .bodyValue(imageData)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("Alist 上传失败: " + parseAlistErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .doOnSuccess(response -> log.info("[Debug AlistService] Received upload response: {}", response))
            .flatMap(this::checkAlistResponse)
            .thenReturn(uploadPath);
    }

    private Mono<Void> refreshAlistStorage(String uploadPath, String token, AlistSetting setting) {
        String parentPath = uploadPath.substring(0, uploadPath.lastIndexOf('/'));
        if (parentPath.isEmpty()) parentPath = "/";
        log.info("[Debug AlistService] Attempting to refresh Alist cache for path: {}", parentPath);

        return fastWebClient.post().uri(setting.getAlistUrl() + "/api/fs/list")
            .header("Authorization", token)
            .header("Content-Type", "application/json")
            .bodyValue(Map.of("path", parentPath, "refresh", true))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("Alist 缓存刷新失败: " + parseAlistErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .doOnSuccess(response -> log.info("[Debug AlistService] Received cache refresh response: {}", response))
            .flatMap(this::checkAlistResponse)
            .then();
    }

    private Mono<ProgressUpdate> pollForSignedUrl(String path, String token, AlistSetting setting) {
        log.info("[Debug AlistService] Starting to poll for signed URL for path: {}", path);
        return getSignedUrl(path, token, setting)
            .retryWhen(Retry.fixedDelay(5, Duration.ofSeconds(2))
                .filter(error -> error instanceof AlistObjectNotFoundException)
                .doBeforeRetry(signal -> log.warn("[Debug AlistService] Object not found, retrying... Attempt #{}}", signal.totalRetries() + 1))
                .onRetryExhaustedThrow((spec, signal) -> new RuntimeException("获取 Alist 签名链接超时。")));
    }

    private Mono<ProgressUpdate> getSignedUrl(String path, String token, AlistSetting setting) {
        log.info("[Debug AlistService] Requesting signed URL for path: {}", path);
        return fastWebClient.post().uri(setting.getAlistUrl() + "/api/fs/get")
            .header("Authorization", token)
            .header("Content-Type", "application/json")
            .bodyValue(Map.of("path", path))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("获取 Alist 签名链接失败: " + parseAlistErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .flatMap(jsonResponse -> parseSignedUrlFromResponse(jsonResponse, setting));
    }

    private Mono<ProgressUpdate> parseSignedUrlFromResponse(String jsonResponse, AlistSetting setting) {
        log.info("[Debug AlistService] Attempting to parse signed URL from response: {}", jsonResponse);
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            if (root.at("/code").asInt(-1) == 500 && "object not found".equals(root.at("/message").asText(""))) {
                return Mono.error(new AlistObjectNotFoundException());
            }
            String rawUrl = root.at("/data/raw_url").asText();
            if (!StringUtils.hasText(rawUrl)) {
                return Mono.error(new RuntimeException("获取签名失败，响应中缺少 'raw_url'。"));
            }
            String finalUrl = fixUrlProtocol(rawUrl, setting.getAlistUrl());
            log.info("[Debug AlistService] Successfully parsed final URL: {}", finalUrl);
            return Mono.just(ProgressUpdate.finalSuccess(finalUrl, "Alist 上传成功！"));
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("解析 Alist 签名响应失败", e));
        }
    }

    private Mono<String> checkAlistResponse(String jsonResponse) {
        log.info("[Debug AlistService] Checking Alist response: {}", jsonResponse);
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            if (root.at("/code").asInt(-1) == 200) {
                 log.info("[Debug AlistService] Alist response is OK (code 200).");
                return Mono.just(jsonResponse);
            } else {
                String message = root.at("/message").asText("未知的 Alist 错误");
                 log.error("[Debug AlistService] Alist response indicates an error: {}", message);
                return Mono.error(new RuntimeException(message));
            }
        } catch (JsonProcessingException e) {
             log.error("[Debug AlistService] Failed to parse Alist response JSON.", e);
            return Mono.error(new RuntimeException("解析 Alist 响应失败", e));
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
            return objectMapper.readTree(errorBody).at("/message").asText(errorBody);
        } catch (JsonProcessingException e) {
            return errorBody;
        }
    }
    
    private String fixUrlProtocol(String originalUrl, String configUrl) {
        try {
            URL original = new URL(originalUrl);
            URL config = new URL(configUrl);
            if (!original.getProtocol().equalsIgnoreCase(config.getProtocol())) {
                return new URL(config.getProtocol(), original.getHost(), original.getPort(), original.getFile()).toString();
            }
        } catch (Exception e) {
            log.warn("URL 协议修正失败，将返回原始 URL。错误: {}", e.getMessage());
        }
        return originalUrl;
    }

    private static class AlistObjectNotFoundException extends RuntimeException {}
}


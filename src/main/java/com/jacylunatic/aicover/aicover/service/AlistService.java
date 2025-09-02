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
                        .doOnSuccess(bytes -> log.info("[Debug AlistService] Step 2 SUCCESS: Image downloaded ({} bytes)", bytes.length))
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
                                        .map(finalUrl -> ProgressUpdate.success(finalUrl, "Alist 上传成功！"))
                                        .doOnSuccess(p -> log.info("[Debug AlistService] Step 5 SUCCESS: Got final URL"))
                                ))
                        ))
                );
            });
    }

    private Mono<Map<String, Object>> prepareAlistContext() {
        log.info("[Debug AlistService] prepareAlistContext method ENTERED.");
        return settingFetcher.fetch(AlistSetting.GROUP, AlistSetting.class)
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
                return login(setting).map(token -> {
                    log.info("[Debug AlistService] Login successful, context prepared.");
                    return Map.of("setting", setting, "token", token);
                });
            })
            .doOnNext(context -> log.info("[Debug AlistService] Context Mono is about to emit value: {}", context))
            .doOnError(error -> log.error("[Debug AlistService] Error in prepareAlistContext stream.", error));
    }


    private Mono<String> login(AlistSetting setting) {
        String loginUrl = setting.getAlistUrl() + "/api/auth/login";
        return fastWebClient.post().uri(loginUrl)
            .bodyValue(Map.of("username", setting.getAlistUsername(), "password", setting.getAlistPassword()))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("Alist 登录失败: " + parseAlistErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .filter(responseBody -> responseBody != null && !responseBody.isBlank() && !responseBody.equalsIgnoreCase("null"))
            .switchIfEmpty(Mono.error(new RuntimeException("Alist 登录认证失败，请检查用户名和密码。")))
            .flatMap(this::parseTokenFromResponse);
    }

    private Mono<byte[]> downloadImage(String imageUrl) {
        return Mono.fromCallable(() -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            try {
                if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                    try (InputStream inputStream = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                        inputStream.transferTo(buffer);
                        return buffer.toByteArray();
                    }
                } else {
                    throw new RuntimeException("下载临时图片失败: " + connection.getResponseCode());
                }
            } finally {
                connection.disconnect();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> uploadToAlist(byte[] imageData, String token, AlistSetting setting) {
        String fileName = UUID.randomUUID() + ".png";
        String uploadPath = setting.getAlistUploadPath() + "/" + fileName;

        return uploadWebClient.put().uri(setting.getAlistUrl() + "/api/fs/put")
            .header("Authorization", token)
            .header("File-Path", uploadPath)
            .bodyValue(imageData)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("Alist 上传失败: " + parseAlistErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .flatMap(this::checkAlistResponse)
            .thenReturn(uploadPath);
    }

    private Mono<Void> refreshAlistStorage(String uploadPath, String token, AlistSetting setting) {
        String parentPath = uploadPath.substring(0, uploadPath.lastIndexOf('/'));
        if (parentPath.isEmpty()) parentPath = "/";

        return fastWebClient.post().uri(setting.getAlistUrl() + "/api/fs/list")
            .header("Authorization", token)
            .header("Content-Type", "application/json")
            .bodyValue(Map.of("path", parentPath, "refresh", true))
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                .flatMap(errorBody -> Mono.error(new RuntimeException("Alist 缓存刷新失败: " + parseAlistErrorMessage(errorBody)))))
            .bodyToMono(String.class)
            .flatMap(this::checkAlistResponse)
            .then();
    }

    private Mono<String> pollForSignedUrl(String path, String token, AlistSetting setting) {
        return getSignedUrl(path, token, setting)
            .retryWhen(Retry.fixedDelay(5, Duration.ofSeconds(2))
                .filter(error -> error instanceof AlistObjectNotFoundException)
                .onRetryExhaustedThrow((spec, signal) -> new RuntimeException("获取 Alist 签名链接超时。")));
    }

    private Mono<String> getSignedUrl(String path, String token, AlistSetting setting) {
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

    private Mono<String> parseSignedUrlFromResponse(String jsonResponse, AlistSetting setting) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            if (root.at("/code").asInt(-1) == 500 && "object not found".equals(root.at("/message").asText(""))) {
                return Mono.error(new AlistObjectNotFoundException());
            }
            String rawUrl = root.at("/data/raw_url").asText();
            if (!StringUtils.hasText(rawUrl)) {
                return Mono.error(new RuntimeException("获取签名失败，响应中缺少 'raw_url'。"));
            }
            return Mono.just(fixUrlProtocol(rawUrl, setting.getAlistUrl()));
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("解析 Alist 签名响应失败", e));
        }
    }

    private Mono<String> parseTokenFromResponse(String jsonResponse) {
        try {
            JsonNode tokenNode = objectMapper.readTree(jsonResponse).at("/data/token");
            if (tokenNode.isMissingNode() || !tokenNode.isTextual()) {
                return Mono.error(new RuntimeException("无法从 Alist 登录响应中解析 Token。"));
            }
            return Mono.just(tokenNode.asText());
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("解析 Alist Token 响应失败", e));
        }
    }

    private Mono<String> checkAlistResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            if (root.at("/code").asInt(-1) == 200) {
                return Mono.just(jsonResponse);
            } else {
                return Mono.error(new RuntimeException(root.at("/message").asText("未知的 Alist 错误")));
            }
        } catch (JsonProcessingException e) {
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


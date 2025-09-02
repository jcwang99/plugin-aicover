package com.jacylunatic.aicover.aicover.controller;

import com.jacylunatic.aicover.aicover.model.AiCoverSetting;
import com.jacylunatic.aicover.aicover.model.ModelInfo;
import com.jacylunatic.aicover.aicover.model.ProgressUpdate;
import com.jacylunatic.aicover.aicover.service.AiImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/plugins/aicover")
@RequiredArgsConstructor
public class AiImageController {

    private final AiImageService aiImageService;
    private final ReactiveSettingFetcher settingFetcher;

    @GetMapping(value = "generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ProgressUpdate> generateImage(
        @RequestParam("prompt") String prompt,
        @RequestParam("model") String model,
        @RequestParam("size") String size,
        // --- Debug Step 1: 接收参数为 String 类型以查看原始值 ---
        @RequestParam(name = "uploadToAlist", defaultValue = "false") String uploadToAlistRaw
    ) {
        // --- Debug Step 2: 手动解析并添加详细日志 ---
        boolean uploadToAlist = Boolean.parseBoolean(uploadToAlistRaw);

        log.info("[Controller Debug] Received raw 'uploadToAlist' parameter as String: '{}'", uploadToAlistRaw);
        log.info("[Controller Debug] Parsed 'uploadToAlist' to boolean: {}", uploadToAlist);

        log.info("[Controller] Forwarding generate image stream request: prompt='{}', model='{}', size='{}', uploadToAlist={}",
            prompt, model, size, uploadToAlist);

        // 将解析后的布尔值传递给服务
        return aiImageService.generateImage(prompt, model, size, uploadToAlist);
    }

    @GetMapping("models")
    public Mono<List<ModelInfo>> getModels() {
        return settingFetcher.fetch(AiCoverSetting.GROUP, AiCoverSetting.class)
            .defaultIfEmpty(new AiCoverSetting())
            .map(setting -> {
                String modelsString = setting.getModels();
                if (modelsString == null || modelsString.isBlank()) {
                    return List.of(new ModelInfo("通义万相 V1", "wanx-v1"));
                }
                return Arrays.stream(modelsString.split("\\r?\\n"))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && line.contains(","))
                    .map(line -> {
                        String[] parts = line.split(",", 2);
                        if (parts.length == 2) {
                            return new ModelInfo(parts[0].trim(), parts[1].trim());
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            });
    }
}

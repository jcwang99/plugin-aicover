package com.jacylunatic.aicover.aicover.controller;

import com.jacylunatic.aicover.aicover.model.AiCoverSetting;
import com.jacylunatic.aicover.aicover.model.GenerateImageRequest;
import com.jacylunatic.aicover.aicover.model.GenerateImageResponse;
import com.jacylunatic.aicover.aicover.model.ModelInfo;
import com.jacylunatic.aicover.aicover.service.AiImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
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

    @PostMapping("generate")
    public Mono<GenerateImageResponse> generateImage(@RequestBody GenerateImageRequest request) {
        log.info("[Debug AiImageController] Received generate image request: {}", request);
        
        // 直接返回 Service 提供的响应对象
        return aiImageService.generateImage(
            request.getPrompt(),
            request.getModel(),
            request.getSize(),
            request.isUploadToAlist()
        );
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

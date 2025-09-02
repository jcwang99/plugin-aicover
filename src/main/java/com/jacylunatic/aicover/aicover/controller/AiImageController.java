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
        return aiImageService.generateImage(request.getPrompt(), request.getModel(), request.getSize())
            .map(GenerateImageResponse::new);
    }

    @GetMapping("models")
    public Mono<List<ModelInfo>> getModels() {
        log.info("[Debug] /models 接口被调用。");
        return settingFetcher.fetch(AiCoverSetting.GROUP, AiCoverSetting.class)
            .defaultIfEmpty(new AiCoverSetting())
            .map(setting -> {
                // --- 核心 Debug 信息 ---
                log.info("[Debug] 从数据库中获取到的 AiCoverSetting 对象为: {}", setting);
                String modelsString = setting.getModels();
                log.info("[Debug] 从设置对象中提取的原始 models 字符串为: '{}'", modelsString);
                // --- 结束 ---

                if (modelsString == null || modelsString.isBlank()) {
                    log.warn("[Debug] models 字符串为空，返回默认模型列表。");
                    return List.of(new ModelInfo("通义万相 V1", "wanx-v1"));
                }
                
                List<ModelInfo> parsedModels = Arrays.stream(modelsString.split("\\r?\\n"))
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
                
                log.info("[Debug] 解析后，模型列表包含 {} 个模型。", parsedModels.size());
                return parsedModels;
            });
    }
}


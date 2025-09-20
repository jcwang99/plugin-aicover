package com.jacylunatic.aicover.aicover.controller;

import com.jacylunatic.aicover.aicover.model.ModelInfo;
import com.jacylunatic.aicover.aicover.model.PluginMasterSetting;
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
        @RequestParam("model") String model, // 现在会接收 "platform:modelId" 格式
        @RequestParam("size") String size,
        @RequestParam(name = "uploadToAlist", defaultValue = "false") Boolean uploadToAlist
    ) {
        log.info("[Controller] Received generate image stream request: prompt='{}', model='{}', size='{}', uploadToAlist={}",
            prompt, model, size, uploadToAlist);

        return aiImageService.generateImage(prompt, model, size, uploadToAlist);
    }

    /**
     * --- 核心改造 ---
     * 改造 getModels 接口，使其能够解析新格式的模型列表，并返回包含平台信息的数据。
     */
    @GetMapping("models")
    public Mono<List<ModelInfo>> getModels() {
        return settingFetcher.fetch(PluginMasterSetting.GROUP, PluginMasterSetting.class)
            .defaultIfEmpty(new PluginMasterSetting())
            .map(setting -> {
                String modelsString = setting.getModels();
                if (modelsString == null || modelsString.isBlank()) {
                    // 提供一个默认值，以防用户未配置
                    return List.of(new ModelInfo("通义万相", "tongyi:wanx-v1"));
                }
                // 解析 "平台标识符,显示名称,模型ID" 格式
                return Arrays.stream(modelsString.split("\\r?\\n"))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && line.contains(","))
                    .map(line -> {
                        String[] parts = line.split(",", 3);
                        if (parts.length == 3) {
                            String platform = parts[0].trim();
                            String displayName = parts[1].trim();
                            String modelId = parts[2].trim();
                            // 将 platform 和 modelId 组合成新的 value
                            return new ModelInfo(displayName, platform + ":" + modelId);
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            });
    }
}


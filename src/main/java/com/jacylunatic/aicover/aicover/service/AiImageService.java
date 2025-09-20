package com.jacylunatic.aicover.aicover.service;

import com.jacylunatic.aicover.aicover.model.ProgressUpdate;
import com.jacylunatic.aicover.aicover.service.generators.SiliconFlowImageGenerator;
import com.jacylunatic.aicover.aicover.service.generators.TongyiImageGenerator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import run.halo.app.plugin.ReactiveSettingFetcher;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiImageService {

    // 依赖注入 ReactiveSettingFetcher 以传递给子服务
    private final ReactiveSettingFetcher settingFetcher;
    private final AlistService alistService;

    // 这个列表将在 init() 方法中被手动填充
    private List<ImageGenerator> imageGenerators;

    /**
     * --- 最终修正：使用 @PostConstruct 手动创建和注册服务 ---
     * <p>
     * 这个方法会在 Spring 完成所有依赖注入后自动执行。
     * 我们在这里手动创建所有 ImageGenerator 的实例，彻底解决依赖注入问题。
     */
    @PostConstruct
    public void init() {
        this.imageGenerators = List.of(
            new TongyiImageGenerator(settingFetcher),
            new SiliconFlowImageGenerator(settingFetcher)
        );

        log.info("============================================================");
        log.info("[Debug AiImageService] AiImageService Initialized via @PostConstruct.");
        log.info("[Debug AiImageService] Manually created ImageGenerator beans: {}", imageGenerators.size());
        if (imageGenerators.isEmpty()) {
            log.error("[Debug AiImageService] CRITICAL: No ImageGenerator implementations were created!");
        } else {
            imageGenerators.forEach(generator ->
                log.info("[Debug AiImageService]   - Registered generator for platform: '{}'", generator.getPlatformIdentifier())
            );
        }
        log.info("============================================================");
    }

    public Flux<ProgressUpdate> generateImage(String prompt, String modelWithPlatform, String size, boolean uploadToAlist) {
        String[] parts = modelWithPlatform.split(":", 2);
        if (parts.length != 2) {
            return Flux.just(ProgressUpdate.error("无效的模型ID格式。"));
        }
        String platform = parts[0];
        String model = parts[1];

        ImageGenerator generator = imageGenerators.stream()
            .filter(gen -> gen.getPlatformIdentifier().equalsIgnoreCase(platform))
            .findFirst()
            .orElse(null);

        if (generator == null) {
            return Flux.just(ProgressUpdate.error("找不到支持平台 '" + platform + "' 的服务。"));
        }
        
        Flux<ProgressUpdate> aiGenerationStream = generator.generateImage(prompt, model, size).cache(1);

        if (!uploadToAlist) {
            return aiGenerationStream.flatMap(update -> {
                 if (update.getFinalImageUrl() != null && !Boolean.TRUE.equals(update.getIsFinal())) {
                    return Flux.just(ProgressUpdate.finalSuccess(update.getFinalImageUrl(), update.getMessage()));
                 }
                 return Flux.just(update);
            });
        }

        Flux<ProgressUpdate> alistUploadStream = aiGenerationStream
            .last()
            .flatMapMany(lastUpdate -> {
                String tempUrl = lastUpdate.getFinalImageUrl();
                if (tempUrl == null) {
                    return Flux.just(ProgressUpdate.error("AI绘图失败，无法继续上传到Alist。"));
                }
                return alistService.uploadImageFromUrl(tempUrl)
                    .onErrorResume(error -> {
                        String warningMessage = "图片已生成，但上传到 Alist 失败: " + error.getMessage();
                        return Flux.just(
                            ProgressUpdate.error(warningMessage),
                            ProgressUpdate.finalSuccess(tempUrl, "已回退并使用原始链接。")
                        );
                    });
            });

        return Flux.concat(
            aiGenerationStream.filter(update -> update.getFinalImageUrl() == null),
            alistUploadStream
        );
    }
}


package com.jacylunatic.aicover.aicover.controller;

import com.jacylunatic.aicover.aicover.model.GenerateImageRequest;
import com.jacylunatic.aicover.aicover.model.GenerateImageResponse;
import com.jacylunatic.aicover.aicover.service.AiImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * AI 封面生成 API 控制器。
 * @RestController 注解会告诉 Spring Boot 这是一个 API 入口。
 */
@RestController
@RequestMapping("/api/plugins/aicover")
@RequiredArgsConstructor
public class AiImageController {

    private final AiImageService aiImageService;

    /**
     * 处理图片生成请求。
     * @PostMapping("/generate") 会将此方法注册到 POST /api/plugins/aicover/generate 路径。
     * @param request 包含提示词等信息的前端请求体。
     * @return 包含图片 URL 的响应。
     */
    @PostMapping("/generate")
    public Mono<GenerateImageResponse> generate(@RequestBody GenerateImageRequest request) {
        return aiImageService.generateImage(request.getPrompt(), request.getModel(), request.getSize())
                .map(GenerateImageResponse::new);
    }
}

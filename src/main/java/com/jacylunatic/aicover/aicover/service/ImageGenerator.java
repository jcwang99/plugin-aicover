package com.jacylunatic.aicover.aicover.service;

import com.jacylunatic.aicover.aicover.model.ProgressUpdate;
import reactor.core.publisher.Flux;

/**
 * AI 图像生成器接口。
 * <p>
 * 这是策略模式的核心，定义了所有 AI 绘图服务提供商必须实现的标准。
 * 每个实现类代表一个不同的 AI 平台（如通义万相、硅基流动等）。
 */
public interface ImageGenerator {

    /**
     * 获取此服务提供商的唯一标识符。
     * 这个标识符将用于匹配模型列表中的平台名称。
     *
     * @return 平台的唯一标识符 (例如: "tongyi", "siliconflow")
     */
    String getPlatformIdentifier();

    /**
     * 核心的图像生成方法。
     *
     * @param prompt 提示词
     * @param model  模型 ID
     * @param size   图片尺寸
     * @return 一个包含实时进度更新的事件流 (Flux)
     */
    Flux<ProgressUpdate> generateImage(String prompt, String model, String size);

}

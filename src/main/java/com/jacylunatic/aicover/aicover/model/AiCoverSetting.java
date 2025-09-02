package com.jacylunatic.aicover.aicover.model;

import lombok.Data;
/**
 * 用于承载插件设置的数据类。
 */
@Data
public class AiCoverSetting {
    /**
     * 这个 GROUP 常量必须与 setting.yaml 中定义的 'group' 完全匹配。
     */
    public static final String GROUP = "api-settings";

    /**
     * 这个字段名必须与 setting.yaml 中表单项的 'name' 完全匹配。
     */
    private String apiKey;

    /**
     * 对应 setting.yaml 中 'models' 文本域的字段。
     * Lombok 的 @Data 注解会自动为此字段生成 getModels() 和 setModels() 方法。
     */
    private String models;
}


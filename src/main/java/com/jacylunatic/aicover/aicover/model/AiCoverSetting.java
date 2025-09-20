package com.jacylunatic.aicover.aicover.model;

import lombok.Data;
/**
 * 用于承载插件设置的数据类。目前理论已经无用，但保留以防未来需要。
 */
@Data
public class AiCoverSetting {
    /**
     * 这个 GROUP 常量必须与 aetting.yaml 中定义的 'group' 完全匹配。
     */
    public static final String GROUP = "api-settings";

    private String apiKey;
    private String models;

}


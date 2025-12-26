package com.jacylunatic.aicover.aicover.model;

import lombok.Data;

/**
 * 统一管理所有 AI 绘图平台认证信息的数据类。
 */
@Data
public class AiPlatformSettings {
    /**
     * 这个 GROUP 常量必须与 aetting.yaml 中定义的 'group' 完全匹配。
     */
    public static final String GROUP = "ai-platform-settings";

    /**
     * 通义万相的 API Key。
     * 字段名 'tongyiApiKey' 必须与 setting.yaml 中表单项的 'name' 完全匹配。
     */
    private String tongyiApiKey;

    /**
     * 硅基流动的 API Key。
     * 字段名 'siliconflowApiKey' 必须与 setting.yaml 中表单项的 'name' 完全匹配。
     */
    private String siliconflowApiKey;

    /**
     * 魔塔（ModelScope）的 API Key (MODELSCOPE_SDK_TOKEN)。
     * 字段名 'modelscopeApiKey' 必须与 setting.yaml 中表单项的 'name' 完全匹配。
     */
    private String modelscopeApiKey;

    // 未来如果新增平台，只需在此处添加新的字段即可，
    // 例如: private String dalleApiKey;
}

package com.jacylunatic.aicover.aicover.model;

import lombok.Data;

/**
 * 专门用于承载硅基流动设置的数据类。
 */
@Data
public class SiliconFlowSetting {
    /**
     * 这个 GROUP 常量必须与 setting.yaml 中定义的 'group' 完全匹配。
     */
    public static final String GROUP = "siliconflow-settings";

    private String apiKey;
}

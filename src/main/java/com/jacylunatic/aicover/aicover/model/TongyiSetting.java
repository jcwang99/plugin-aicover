package com.jacylunatic.aicover.aicover.model;

import lombok.Data;

/**
 * 专门用于承载通义万相设置的数据类。已作废
 */
@Data
public class TongyiSetting {
    /**
     * 这个 GROUP 常量必须与 setting.yaml 中定义的 'group' 完全匹配。
     */
    public static final String GROUP = "tongyi-settings";

    private String apiKey;
}

package com.jacylunatic.aicover.aicover.model;

import lombok.Data;

/**
 * 专门用于承载插件全局设置的数据类，例如模型列表。
 */
@Data
public class PluginMasterSetting {
    /**
     * 这个 GROUP 常量必须与 setting.yaml 中定义的 'group' 完全匹配。
     */
    public static final String GROUP = "master-settings";

    private String models;
}

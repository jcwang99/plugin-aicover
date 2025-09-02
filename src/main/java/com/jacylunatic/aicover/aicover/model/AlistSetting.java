package com.jacylunatic.aicover.aicover.model;

import lombok.Data;

/**
 * 专门用于承载 Alist 设置的数据类。
 */
@Data
public class AlistSetting {
    /**
     * 这个 GROUP 常量必须与 aetting.yaml 中定义的 'group' 完全匹配。
     */
    public static final String GROUP = "alist-settings";

    private String alistUrl;
    private String alistUsername;
    private String alistPassword;
    private String alistUploadPath;
}

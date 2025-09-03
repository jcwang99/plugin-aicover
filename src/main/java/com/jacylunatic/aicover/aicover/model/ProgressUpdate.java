package com.jacylunatic.aicover.aicover.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProgressUpdate {

    private String message;
    private Boolean isError;
    private String finalImageUrl;

    /**
     * --- 核心改造 ---
     * 新增 isFinal 字段，用于明确告知前端任务是否已结束。
     */
    private Boolean isFinal = false;

    public ProgressUpdate(String message) {
        this.message = message;
        this.isError = false;
        this.isFinal = false;
    }

    public ProgressUpdate(String message, boolean isError) {
        this.message = message;
        this.isError = isError;
        this.isFinal = false;
    }
    
    public static ProgressUpdate error(String message) {
        ProgressUpdate update = new ProgressUpdate(message, true);
        // 普通错误不是最终状态，除非是致命错误
        return update;
    }
    
    /**
     * 最终成功状态的工厂方法
     */
    public static ProgressUpdate finalSuccess(String finalImageUrl, String message) {
        ProgressUpdate update = new ProgressUpdate(message, false);
        update.setFinalImageUrl(finalImageUrl);
        update.setIsFinal(true); // 标记为最终状态
        return update;
    }

    /**
     * 包含中间结果的成功状态 (AI绘图成功，但还需上传Alist)
     */
    public static ProgressUpdate intermediateSuccess(String imageUrl, String message) {
        ProgressUpdate update = new ProgressUpdate(message, false);
        update.setFinalImageUrl(imageUrl);
        update.setIsFinal(false); // 明确标记为非最终状态
        return update;
    }
}
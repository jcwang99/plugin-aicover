package com.jacylunatic.aicover.aicover.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 后端向前端推送实时进度的标准数据模型。
 * 用于服务器发送事件 (SSE)。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // 仅序列化非空字段
public class ProgressUpdate {

    /**
     * 当前进度的描述文本。
     * 例如："正在提交任务...", "正在登录 Alist..."
     */
    private String message;

    /**
     * 标记这条信息是否为错误信息。
     * 前端可以根据这个字段用不同的颜色显示消息。
     */
    private Boolean isError;

    /**
     * 最终生成的图片 URL。
     * 这个字段只在任务全部成功完成的最后一条消息中才会有值。
     */
    private String finalImageUrl;

    /**
     * 方便创建普通进度消息的构造函数。
     */
    public ProgressUpdate(String message) {
        this.message = message;
    }

    /**
     * 方便创建错误消息的静态工厂方法。
     */
    public static ProgressUpdate error(String message) {
        return new ProgressUpdate(message, true, null);
    }
    
    /**
     * 方便创建最终成功消息的静态工厂方法。
     */
    public static ProgressUpdate success(String finalImageUrl, String message) {
        return new ProgressUpdate(message, false, finalImageUrl);
    }
}

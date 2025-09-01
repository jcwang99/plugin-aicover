package com.jacylunatic.aicover.aicover.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用于封装发送给前端的、包含图片 URL 的响应数据。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GenerateImageResponse {
    private String imageUrl;
}

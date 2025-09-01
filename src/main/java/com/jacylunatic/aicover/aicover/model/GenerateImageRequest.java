package com.jacylunatic.aicover.aicover.model;

import lombok.Data;

/**
 * 用于封装从前端发送来的图片生成请求的数据。
 */
@Data
public class GenerateImageRequest {
    private String prompt;
    private String model;
    private String size;
}

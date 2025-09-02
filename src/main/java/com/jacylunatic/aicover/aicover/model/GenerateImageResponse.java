package com.jacylunatic.aicover.aicover.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // 仅在字段不为 null 时序列化
public class GenerateImageResponse {

    private String imageUrl;
    private String warning;

    public GenerateImageResponse(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public GenerateImageResponse(String imageUrl, String warning) {
        this.imageUrl = imageUrl;
        this.warning = warning;
    }
}
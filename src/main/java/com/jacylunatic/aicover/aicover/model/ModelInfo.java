package com.jacylunatic.aicover.aicover.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用于向前段传递模型信息的数据传输对象 (DTO)。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelInfo {
    private String name;
    private String id;
}

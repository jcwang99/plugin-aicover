package com.jacylunatic.aicover.aicover.model;

import lombok.Data;

/**
 * A data class to hold settings from plugin.yaml.
 */
@Data
public class AiCoverSetting {
    /**
     * This GROUP constant must match the 'settingName' in plugin.yaml.
     */
    public static final String GROUP = "api-settings";

    private String apiKey;
}

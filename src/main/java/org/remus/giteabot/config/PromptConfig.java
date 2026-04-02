package org.remus.giteabot.config;

import lombok.Data;

@Data
public class PromptConfig {

    private String file;

    private String model;

    private String giteaToken;
}

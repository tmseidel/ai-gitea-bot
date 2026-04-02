package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "prompts")
public class PromptConfigProperties {

    /**
     * Directory where prompt markdown files are located.
     */
    private String dir = "prompts";

    /**
     * Map of named prompt configurations. The key is the prompt name used in the webhook query parameter.
     * Example:
     * <pre>
     * prompts.definitions.default.file=default.md
     * prompts.definitions.security.file=security-review.md
     * prompts.definitions.security.model=claude-opus-4-20250514
     * </pre>
     */
    private Map<String, PromptConfig> definitions = new LinkedHashMap<>();
}

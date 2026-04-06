package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentConfigProperties {

    /**
     * Whether the issue implementation agent feature is enabled.
     */
    private boolean enabled = false;

    /**
     * Maximum number of files the agent can modify in a single implementation.
     */
    private int maxFiles = 10;

    /**
     * Maximum tokens for AI responses during issue implementation.
     * This is typically higher than the default for code reviews since
     * implementation responses include full file contents.
     */
    private int maxTokens = 16384;

    /**
     * Whitelist of repositories (in "owner/repo" format) where the agent is active.
     * If empty, the agent is active on all repositories.
     */
    private List<String> allowedRepos = List.of();

    /**
     * Prefix for branches created by the agent.
     */
    private String branchPrefix = "ai-agent/";

    /**
     * Validation settings.
     */
    private ValidationConfig validation = new ValidationConfig();

    @Data
    public static class ValidationConfig {
        /**
         * Whether to validate generated code syntax before committing.
         */
        private boolean enabled = true;

        /**
         * Maximum number of validation/correction iterations.
         * If code fails validation, it will be sent back to AI for fixes up to this many times.
         */
        private int maxRetries = 3;

        /**
         * Whether to run a full build (mvn compile / gradle build) for validation.
         * This catches more errors but requires cloning the repository.
         */
        private boolean buildEnabled = false;

        /**
         * Timeout in seconds for build commands.
         */
        private int buildTimeoutSeconds = 300;
    }
}

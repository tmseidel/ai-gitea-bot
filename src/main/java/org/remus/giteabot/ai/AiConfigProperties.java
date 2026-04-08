package org.remus.giteabot.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiConfigProperties {

    /**
     * The AI provider to use. Supported values: "anthropic", "openai", "ollama".
     */
    private String provider = "anthropic";

    /**
     * Default model name for the selected provider.
     */
    private String model = "claude-sonnet-4-20250514";

    /**
     * Maximum number of tokens per AI response.
     */
    private int maxTokens = 4096;

    /**
     * Maximum number of characters per diff chunk sent to the AI.
     */
    private int maxDiffCharsPerChunk = 120000;

    /**
     * Maximum number of diff chunks to review.
     */
    private int maxDiffChunks = 8;

    /**
     * Truncated chunk size in characters used when retrying after a prompt-too-long error.
     */
    private int retryTruncatedChunkChars = 60000;

    /**
     * Anthropic-specific configuration.
     */
    private AnthropicConfig anthropic = new AnthropicConfig();

    /**
     * OpenAI-specific configuration.
     */
    private OpenAiConfig openai = new OpenAiConfig();

    /**
     * Ollama-specific configuration.
     */
    private OllamaConfig ollama = new OllamaConfig();

    /**
     * llama.cpp server-specific configuration.
     */
    private LlamaCppConfig llamacpp = new LlamaCppConfig();

    @Data
    public static class AnthropicConfig {
        private String apiUrl = "https://api.anthropic.com";
        private String apiKey = "";
        private String apiVersion = "2023-06-01";
    }

    @Data
    public static class OpenAiConfig {
        private String apiUrl = "https://api.openai.com";
        private String apiKey = "";
    }

    @Data
    public static class OllamaConfig {
        private String apiUrl = "http://localhost:11434";
    }

    @Data
    public static class LlamaCppConfig {
        /**
         * Base URL for the llama.cpp server API.
         * Default port 8081 to avoid conflict with the bot's default port 8080.
         */
        private String apiUrl = "http://localhost:8081";
    }
}

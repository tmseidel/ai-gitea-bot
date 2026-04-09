package org.remus.giteabot.config;

import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiConfigProperties;
import org.remus.giteabot.ai.anthropic.AnthropicAiClient;
import org.remus.giteabot.ai.llamacpp.LlamaCppClient;
import org.remus.giteabot.ai.ollama.OllamaClient;
import org.remus.giteabot.ai.openai.OpenAiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    @Bean
    public RestClient giteaRestClient(@Value("${gitea.url}") String giteaUrl,
                                      @Value("${gitea.token}") String giteaToken) {
        return RestClient.builder()
                .baseUrl(giteaUrl)
                .defaultHeader("Authorization", "token " + giteaToken)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "anthropic", matchIfMissing = true)
    public AiClient anthropicAiClient(AiConfigProperties config) {
        RestClient restClient = RestClient.builder()
                .baseUrl(config.getAnthropic().getApiUrl())
                .defaultHeader("x-api-key", config.getAnthropic().getApiKey())
                .defaultHeader("anthropic-version", config.getAnthropic().getApiVersion())
                .defaultHeader("Content-Type", "application/json")
                .build();

        return new AnthropicAiClient(restClient, config.getModel(), config.getMaxTokens(),
                config.getMaxDiffCharsPerChunk(), config.getMaxDiffChunks(),
                config.getRetryTruncatedChunkChars());
    }

    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "openai")
    public AiClient openAiClient(AiConfigProperties config) {
        RestClient restClient = RestClient.builder()
                .baseUrl(config.getOpenai().getApiUrl())
                .defaultHeader("Authorization", "Bearer " + config.getOpenai().getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();

        return new OpenAiClient(restClient, config.getModel(), config.getMaxTokens(),
                config.getMaxDiffCharsPerChunk(), config.getMaxDiffChunks(),
                config.getRetryTruncatedChunkChars());
    }

    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "ollama")
    public AiClient ollamaClient(AiConfigProperties config) {
        RestClient restClient = RestClient.builder()
                .baseUrl(config.getOllama().getApiUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();

        return new OllamaClient(restClient, config.getModel(), config.getMaxTokens(),
                config.getMaxDiffCharsPerChunk(), config.getMaxDiffChunks(),
                config.getRetryTruncatedChunkChars());
    }

    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "llamacpp")
    public AiClient llamaCppClient(AiConfigProperties config) {
        RestClient restClient = RestClient.builder()
                .baseUrl(config.getLlamacpp().getApiUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();

        return new LlamaCppClient(restClient, config.getModel(), config.getMaxTokens(),
                config.getMaxDiffCharsPerChunk(), config.getMaxDiffChunks(),
                config.getRetryTruncatedChunkChars());
    }
}

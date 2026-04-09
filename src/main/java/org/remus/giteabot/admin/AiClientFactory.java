package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AnthropicCompatibleChatModel;
import org.remus.giteabot.ai.OpenAiCompatibleChatModel;
import org.remus.giteabot.ai.SpringAiChatModelClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory that creates and caches {@link AiClient} instances from persisted
 * {@link AiIntegration} entities.  Clients are cached by integration ID and
 * {@link AiIntegration#getUpdatedAt()} so that a configuration change in the
 * database automatically triggers a new client to be built.
 * <p>
 * Uses Spring AI's {@link ChatModel} interface for all providers. Ollama uses
 * Spring AI's built-in {@link OllamaChatModel}. Anthropic, OpenAI, and llama.cpp
 * use lightweight {@link ChatModel} adapters that work around a binary incompatibility
 * between Spring AI 1.x and Spring Framework 7 (Spring Boot 4).
 *
 * @see AnthropicCompatibleChatModel
 * @see OpenAiCompatibleChatModel
 */
@Slf4j
@Service
public class AiClientFactory {

    private final AiIntegrationService aiIntegrationService;

    /** Cache key = integrationId, value = (updatedAt-millis, client). */
    private final ConcurrentMap<Long, CachedClient> cache = new ConcurrentHashMap<>();

    public AiClientFactory(AiIntegrationService aiIntegrationService) {
        this.aiIntegrationService = aiIntegrationService;
    }

    /**
     * Returns an {@link AiClient} configured according to the given integration.
     * Results are cached and re-created when the integration's updatedAt changes.
     */
    public AiClient getClient(AiIntegration integration) {
        CachedClient cached = cache.get(integration.getId());
        long updatedMillis = integration.getUpdatedAt().toEpochMilli();
        if (cached != null && cached.updatedAtMillis == updatedMillis) {
            return cached.client;
        }

        AiClient client = buildClient(integration);
        cache.put(integration.getId(), new CachedClient(updatedMillis, client));
        log.info("Built new AiClient for integration '{}' (provider={})", integration.getName(), integration.getProviderType());
        return client;
    }

    /**
     * Removes all cached clients (useful after deletion).
     */
    public void evict(Long integrationId) {
        cache.remove(integrationId);
    }

    private AiClient buildClient(AiIntegration integration) {
        String model = integration.getModel();
        int maxTokens = integration.getMaxTokens();
        int maxDiffCharsPerChunk = integration.getMaxDiffCharsPerChunk();
        int maxDiffChunks = integration.getMaxDiffChunks();
        int retryTruncatedChunkChars = integration.getRetryTruncatedChunkChars();

        ChatModel chatModel = createChatModel(integration);
        return new SpringAiChatModelClient(chatModel, model, maxTokens,
                maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
    }

    ChatModel createChatModel(AiIntegration integration) {
        String providerType = integration.getProviderType();
        String apiUrl = integration.getApiUrl();
        String decryptedApiKey = aiIntegrationService.decryptApiKey(integration);

        return switch (providerType) {
            case "anthropic" -> {
                String apiVersion = integration.getApiVersion() != null ? integration.getApiVersion() : "2023-06-01";
                RestClient restClient = RestClient.builder()
                        .baseUrl(apiUrl)
                        .defaultHeader("x-api-key", decryptedApiKey != null ? decryptedApiKey : "")
                        .defaultHeader("anthropic-version", apiVersion)
                        .defaultHeader("Content-Type", "application/json")
                        .build();
                yield new AnthropicCompatibleChatModel(restClient, integration.getModel(), integration.getMaxTokens());
            }
            case "openai" -> {
                RestClient restClient = RestClient.builder()
                        .baseUrl(apiUrl)
                        .defaultHeader("Authorization", "Bearer " + (decryptedApiKey != null ? decryptedApiKey : ""))
                        .defaultHeader("Content-Type", "application/json")
                        .build();
                yield new OpenAiCompatibleChatModel(restClient, integration.getModel(), integration.getMaxTokens());
            }
            case "ollama" -> {
                OllamaApi api = OllamaApi.builder()
                        .baseUrl(apiUrl)
                        .build();
                yield OllamaChatModel.builder()
                        .ollamaApi(api)
                        .defaultOptions(OllamaChatOptions.builder()
                                .model(integration.getModel())
                                .numPredict(integration.getMaxTokens())
                                .build())
                        .build();
            }
            case "llamacpp" -> {
                // llama.cpp exposes an OpenAI-compatible API
                RestClient restClient = RestClient.builder()
                        .baseUrl(apiUrl)
                        .defaultHeader("Content-Type", "application/json")
                        .build();
                yield new OpenAiCompatibleChatModel(restClient, integration.getModel(), integration.getMaxTokens());
            }
            default -> throw new IllegalArgumentException("Unknown AI provider type: " + providerType);
        };
    }

    private record CachedClient(long updatedAtMillis, AiClient client) {}
}

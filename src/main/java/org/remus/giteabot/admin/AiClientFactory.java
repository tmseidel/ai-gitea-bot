package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.anthropic.AnthropicAiClient;
import org.remus.giteabot.ai.llamacpp.LlamaCppClient;
import org.remus.giteabot.ai.ollama.OllamaClient;
import org.remus.giteabot.ai.openai.OpenAiClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory that creates and caches {@link AiClient} instances from persisted
 * {@link AiIntegration} entities.  Clients are cached by integration ID and
 * {@link AiIntegration#getUpdatedAt()} so that a configuration change in the
 * database automatically triggers a new client to be built.
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
        String providerType = integration.getProviderType();
        String apiUrl = integration.getApiUrl();
        String decryptedApiKey = aiIntegrationService.decryptApiKey(integration);
        String model = integration.getModel();
        int maxTokens = integration.getMaxTokens();
        int maxDiffCharsPerChunk = integration.getMaxDiffCharsPerChunk();
        int maxDiffChunks = integration.getMaxDiffChunks();
        int retryTruncatedChunkChars = integration.getRetryTruncatedChunkChars();

        return switch (providerType) {
            case "anthropic" -> {
                String apiVersion = integration.getApiVersion() != null ? integration.getApiVersion() : "2023-06-01";
                RestClient restClient = RestClient.builder()
                        .baseUrl(apiUrl)
                        .defaultHeader("x-api-key", decryptedApiKey)
                        .defaultHeader("anthropic-version", apiVersion)
                        .defaultHeader("Content-Type", "application/json")
                        .build();
                yield new AnthropicAiClient(restClient, model, maxTokens,
                        maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
            }
            case "openai" -> {
                RestClient restClient = RestClient.builder()
                        .baseUrl(apiUrl)
                        .defaultHeader("Authorization", "Bearer " + decryptedApiKey)
                        .defaultHeader("Content-Type", "application/json")
                        .build();
                yield new OpenAiClient(restClient, model, maxTokens,
                        maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
            }
            case "ollama" -> {
                RestClient restClient = RestClient.builder()
                        .baseUrl(apiUrl)
                        .defaultHeader("Content-Type", "application/json")
                        .build();
                yield new OllamaClient(restClient, model, maxTokens,
                        maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
            }
            case "llamacpp" -> {
                RestClient restClient = RestClient.builder()
                        .baseUrl(apiUrl)
                        .defaultHeader("Content-Type", "application/json")
                        .build();
                yield new LlamaCppClient(restClient, model, maxTokens,
                        maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
            }
            default -> throw new IllegalArgumentException("Unknown AI provider type: " + providerType);
        };
    }

    private record CachedClient(long updatedAtMillis, AiClient client) {}
}

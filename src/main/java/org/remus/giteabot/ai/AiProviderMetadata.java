package org.remus.giteabot.ai;

import org.remus.giteabot.admin.AiIntegration;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Metadata and factory interface for AI provider integrations.
 * Each AI provider (Anthropic, OpenAI, Ollama, llama.cpp) implements this interface
 * to define its type, default configuration, and how to build its client.
 */
public interface AiProviderMetadata {

    /**
     * Returns the unique provider type identifier (e.g., "anthropic", "openai").
     */
    String getProviderType();

    /**
     * Returns the default API URL for this provider.
     */
    String getDefaultApiUrl();

    /**
     * Returns a list of suggested/recommended models for this provider.
     * May be empty for providers like Ollama where models are user-configured.
     */
    List<String> getSuggestedModels();

    /**
     * Returns whether this provider requires an API key.
     */
    boolean requiresApiKey();

    /**
     * Builds a configured RestClient for this provider.
     *
     * @param integration the AI integration configuration
     * @param decryptedApiKey the decrypted API key (may be null for providers that don't require it)
     * @return configured RestClient
     */
    RestClient buildRestClient(AiIntegration integration, String decryptedApiKey);

    /**
     * Creates an AiClient instance for this provider.
     *
     * @param restClient the configured RestClient
     * @param integration the AI integration configuration
     * @return configured AiClient
     */
    AiClient createClient(RestClient restClient, AiIntegration integration);
}


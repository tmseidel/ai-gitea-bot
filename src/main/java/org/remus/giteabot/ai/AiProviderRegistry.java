package org.remus.giteabot.ai;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry that collects all {@link AiProviderMetadata} implementations
 * and provides convenient access to provider metadata and configuration.
 */
@Service
public class AiProviderRegistry {

    private final Map<String, AiProviderMetadata> providersByType;

    public AiProviderRegistry(List<AiProviderMetadata> providers) {
        this.providersByType = new LinkedHashMap<>();
        for (AiProviderMetadata provider : providers) {
            providersByType.put(provider.getProviderType(), provider);
        }
    }

    /**
     * Returns all registered provider types in registration order.
     */
    public List<String> getProviderTypes() {
        return List.copyOf(providersByType.keySet());
    }

    /**
     * Returns the metadata for a specific provider type.
     */
    public Optional<AiProviderMetadata> getProvider(String providerType) {
        return Optional.ofNullable(providersByType.get(providerType));
    }

    /**
     * Returns the metadata for a specific provider type, throwing if not found.
     */
    public AiProviderMetadata getProviderOrThrow(String providerType) {
        return getProvider(providerType)
                .orElseThrow(() -> new IllegalArgumentException("Unknown AI provider type: " + providerType));
    }

    /**
     * Returns a map of provider type to default API URL.
     */
    public Map<String, String> getDefaultApiUrls() {
        Map<String, String> urls = new LinkedHashMap<>();
        for (AiProviderMetadata provider : providersByType.values()) {
            urls.put(provider.getProviderType(), provider.getDefaultApiUrl());
        }
        return urls;
    }

    /**
     * Returns a map of provider type to suggested models.
     */
    public Map<String, List<String>> getSuggestedModels() {
        Map<String, List<String>> models = new LinkedHashMap<>();
        for (AiProviderMetadata provider : providersByType.values()) {
            models.put(provider.getProviderType(), provider.getSuggestedModels());
        }
        return models;
    }

    /**
     * Returns all registered providers.
     */
    public List<AiProviderMetadata> getAllProviders() {
        return List.copyOf(providersByType.values());
    }
}


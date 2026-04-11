package org.remus.giteabot.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry for repository provider metadata.
 * Collects all {@link RepositoryProviderMetadata} beans and provides lookup by provider type.
 */
@Slf4j
@Service
public class RepositoryProviderRegistry {

    private final Map<RepositoryType, RepositoryProviderMetadata> providers;

    public RepositoryProviderRegistry(List<RepositoryProviderMetadata> providerList) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(
                        RepositoryProviderMetadata::getProviderType,
                        Function.identity()
                ));
        log.info("Registered {} repository providers: {}",
                providers.size(),
                providers.keySet());
    }

    /**
     * Returns the metadata for the given provider type.
     *
     * @param type the repository provider type
     * @return the provider metadata
     * @throws IllegalArgumentException if no provider is registered for the given type
     */
    public RepositoryProviderMetadata getProvider(RepositoryType type) {
        RepositoryProviderMetadata metadata = providers.get(type);
        if (metadata == null) {
            throw new IllegalArgumentException("No repository provider registered for type: " + type);
        }
        return metadata;
    }

    /**
     * Returns all registered provider types.
     */
    public List<RepositoryType> getRegisteredTypes() {
        return List.copyOf(providers.keySet());
    }

    /**
     * Returns all registered provider metadata instances.
     */
    public List<RepositoryProviderMetadata> getAllProviders() {
        return List.copyOf(providers.values());
    }
}


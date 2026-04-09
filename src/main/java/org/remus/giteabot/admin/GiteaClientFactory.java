package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory that creates and caches {@link RestClient} instances from persisted
 * {@link GitIntegration} entities.  Clients are cached by integration ID and
 * {@link GitIntegration#getUpdatedAt()} so that configuration changes
 * automatically produce fresh clients.
 */
@Slf4j
@Service
public class GiteaClientFactory {

    private final GitIntegrationService gitIntegrationService;

    /** Cache key = integrationId, value = (updatedAt-millis, restClient). */
    private final ConcurrentMap<Long, CachedClient> cache = new ConcurrentHashMap<>();

    public GiteaClientFactory(GitIntegrationService gitIntegrationService) {
        this.gitIntegrationService = gitIntegrationService;
    }

    /**
     * Returns a {@link RestClient} configured for the given Git integration
     * (base URL + bearer token).  Results are cached and re-created when the
     * integration's updatedAt changes.
     */
    public RestClient getClient(GitIntegration integration) {
        CachedClient cached = cache.get(integration.getId());
        long updatedMillis = integration.getUpdatedAt().toEpochMilli();
        if (cached != null && cached.updatedAtMillis == updatedMillis) {
            return cached.client;
        }

        RestClient client = buildClient(integration);
        cache.put(integration.getId(), new CachedClient(updatedMillis, client));
        log.info("Built new Gitea RestClient for integration '{}' (url={})", integration.getName(), integration.getUrl());
        return client;
    }

    /**
     * Returns the decrypted token for the given integration.
     */
    public String getDecryptedToken(GitIntegration integration) {
        return gitIntegrationService.decryptToken(integration);
    }

    public void evict(Long integrationId) {
        cache.remove(integrationId);
    }

    private RestClient buildClient(GitIntegration integration) {
        String decryptedToken = gitIntegrationService.decryptToken(integration);
        return RestClient.builder()
                .baseUrl(integration.getUrl())
                .defaultHeader("Authorization", "token " + decryptedToken)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    private record CachedClient(long updatedAtMillis, RestClient client) {}
}

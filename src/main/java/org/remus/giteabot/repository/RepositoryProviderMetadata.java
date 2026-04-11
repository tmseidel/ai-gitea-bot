package org.remus.giteabot.repository;

import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.web.client.RestClient;

/**
 * Metadata and factory interface for Git repository provider integrations.
 * Each repository provider (GitHub, Gitea, GitLab, etc.) implements this interface
 * to define its type, authentication, and client creation.
 */
public interface RepositoryProviderMetadata {

    /**
     * Returns the repository provider type this metadata handles.
     */
    RepositoryType getProviderType();

    /**
     * Builds a configured RestClient for this provider.
     *
     * @param integration    the Git integration configuration
     * @param decryptedToken the decrypted access token
     * @return configured RestClient pointing at the API URL
     */
    RestClient buildRestClient(GitIntegration integration, String decryptedToken);

    /**
     * Creates the credentials record from the Git integration configuration.
     *
     * @param integration    the Git integration configuration
     * @param decryptedToken the decrypted access token
     * @return credentials containing all authentication information
     */
    RepositoryCredentials createCredentials(GitIntegration integration, String decryptedToken);

    /**
     * Creates a RepositoryApiClient instance for this provider.
     *
     * @param restClient  the configured RestClient
     * @param credentials the repository credentials
     * @return configured RepositoryApiClient
     */
    RepositoryApiClient createClient(RestClient restClient, RepositoryCredentials credentials);
}


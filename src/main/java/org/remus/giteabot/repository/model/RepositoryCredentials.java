package org.remus.giteabot.repository.model;

import org.jspecify.annotations.Nullable;

/**
 * Credentials for authenticating with a Git repository provider.
 * This record encapsulates all authentication-related information needed
 * by repository API clients.
 *
 * @param baseUrl   the API base URL (e.g., "https://api.github.com")
 * @param cloneUrl  the web URL for git clone operations (e.g., "https://github.com")
 * @param username  the username for authentication (required for Bitbucket App Passwords, optional otherwise)
 * @param token     the access token or app password
 */
public record RepositoryCredentials(
        String baseUrl,
        String cloneUrl,
        @Nullable String username,
        String token
) {
    /**
     * Creates credentials without a username (for GitHub, Gitea, etc.).
     */
    public static RepositoryCredentials of(String baseUrl, String cloneUrl, String token) {
        return new RepositoryCredentials(baseUrl, cloneUrl, null, token);
    }

    /**
     * Creates credentials with a username (for Bitbucket App Passwords).
     */
    public static RepositoryCredentials of(String baseUrl, String cloneUrl, String username, String token) {
        return new RepositoryCredentials(baseUrl, cloneUrl, username, token);
    }

    /**
     * Returns true if a username is configured.
     */
    public boolean hasUsername() {
        return username != null && !username.isBlank();
    }
}


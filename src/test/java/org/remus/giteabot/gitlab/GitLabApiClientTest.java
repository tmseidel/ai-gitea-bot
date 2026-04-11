package org.remus.giteabot.gitlab;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GitLabApiClient} verifying that it correctly implements
 * {@link RepositoryApiClient} and exposes the expected base URL, clone URL, and token.
 */
class GitLabApiClientTest {

    private static final RepositoryCredentials CREDS =
            RepositoryCredentials.of("https://gitlab.example.com", "https://gitlab.example.com", "glpat-token123");

    @Test
    void implementsRepositoryApiClient() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertEquals("https://gitlab.example.com", client.getBaseUrl());
    }

    @Test
    void getCloneUrl_returnsConfiguredUrl() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertEquals("https://gitlab.example.com", client.getCloneUrl());
    }

    @Test
    void getToken_returnsConfiguredToken() {
        GitLabApiClient client = new GitLabApiClient(null, CREDS);
        assertEquals("glpat-token123", client.getToken());
    }

    @Test
    void constructorWithSelfHostedUrl() {
        var selfHostedCreds = RepositoryCredentials.of(
                "https://git.mycompany.com", "https://git.mycompany.com", "glpat-abc");
        GitLabApiClient client = new GitLabApiClient(null, selfHostedCreds);
        assertEquals("https://git.mycompany.com", client.getBaseUrl());
        assertEquals("https://git.mycompany.com", client.getCloneUrl());
    }

    @Test
    void encodeProjectPath_buildsProjectPath() {
        assertEquals("owner/repo", GitLabApiClient.encodeProjectPath("owner", "repo"));
        assertEquals("my-org/my-project", GitLabApiClient.encodeProjectPath("my-org", "my-project"));
    }
}

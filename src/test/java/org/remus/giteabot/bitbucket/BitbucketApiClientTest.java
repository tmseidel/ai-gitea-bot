package org.remus.giteabot.bitbucket;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BitbucketApiClient} verifying that it correctly implements
 * {@link RepositoryApiClient} and exposes the expected base URL, clone URL, and token.
 */
class BitbucketApiClientTest {

    private static RepositoryCredentials creds(String username) {
        return RepositoryCredentials.of(
                "https://api.bitbucket.org/2.0", "https://bitbucket.org", username, "bb_token");
    }

    private static RepositoryCredentials creds() {
        return RepositoryCredentials.of(
                "https://api.bitbucket.org/2.0", "https://bitbucket.org", "bb_token");
    }

    @Test
    void implementsRepositoryApiClient() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertInstanceOf(RepositoryApiClient.class, client);
    }

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertEquals("https://api.bitbucket.org/2.0", client.getBaseUrl());
    }

    @Test
    void getCloneUrl_returnsConfiguredUrl() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertEquals("https://bitbucket.org", client.getCloneUrl());
    }

    @Test
    void getToken_returnsConfiguredToken() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertEquals("bb_token", client.getToken());
    }

    @Test
    void addReaction_noOp() {
        // Bitbucket doesn't support reactions; verify no exception is thrown
        BitbucketApiClient client = new BitbucketApiClient(null, creds());
        assertDoesNotThrow(() -> client.addReaction("workspace", "repo", 1L, "+1"));
    }

    @Test
    void getCredentials_returnsUsername() {
        BitbucketApiClient client = new BitbucketApiClient(null, creds("myuser"));
        assertEquals("myuser", client.getCredentials().username());
        assertTrue(client.getCredentials().hasUsername());
    }
}

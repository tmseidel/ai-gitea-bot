package org.remus.giteabot.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.GitIntegration;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubProviderMetadataTest {

    private GitHubProviderMetadata metadata;

    @BeforeEach
    void setUp() {
        metadata = new GitHubProviderMetadata();
    }

    @Test
    void getProviderType_returnsGitHub() {
        assertThat(metadata.getProviderType()).isEqualTo(RepositoryType.GITHUB);
    }

    @Test
    void resolveApiUrl_publicGitHub_convertsToApi() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://github.com");
        integration.setProviderType(RepositoryType.GITHUB);

        String apiUrl = metadata.resolveApiUrl(integration);

        assertThat(apiUrl).isEqualTo("https://api.github.com");
    }

    @Test
    void resolveApiUrl_alreadyApiUrl_unchanged() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://api.github.com");
        integration.setProviderType(RepositoryType.GITHUB);

        String apiUrl = metadata.resolveApiUrl(integration);

        assertThat(apiUrl).isEqualTo("https://api.github.com");
    }

    @Test
    void resolveApiUrl_githubEnterprise_addsApiV3() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://github.example.com");
        integration.setProviderType(RepositoryType.GITHUB);

        String apiUrl = metadata.resolveApiUrl(integration);

        assertThat(apiUrl).isEqualTo("https://github.example.com/api/v3");
    }

    @Test
    void resolveCloneUrl_publicGitHubApi_convertsToWeb() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://api.github.com");
        integration.setProviderType(RepositoryType.GITHUB);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://github.com");
    }

    @Test
    void resolveCloneUrl_githubEnterpriseApi_removesApiV3() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://github.example.com/api/v3");
        integration.setProviderType(RepositoryType.GITHUB);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://github.example.com");
    }

    @Test
    void resolveCloneUrl_regularUrl_unchanged() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://github.com");
        integration.setProviderType(RepositoryType.GITHUB);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://github.com");
    }

    @Test
    void buildAuthorizationHeader_usesBearer() {
        String header = metadata.buildAuthorizationHeader("ghp_token123");

        assertThat(header).isEqualTo("Bearer ghp_token123");
    }
}


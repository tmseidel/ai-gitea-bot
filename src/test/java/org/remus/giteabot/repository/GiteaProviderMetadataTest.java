package org.remus.giteabot.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.GitIntegration;

import static org.assertj.core.api.Assertions.assertThat;

class GiteaProviderMetadataTest {

    private GiteaProviderMetadata metadata;

    @BeforeEach
    void setUp() {
        metadata = new GiteaProviderMetadata();
    }

    @Test
    void getProviderType_returnsGitea() {
        assertThat(metadata.getProviderType()).isEqualTo(RepositoryType.GITEA);
    }

    @Test
    void resolveApiUrl_returnsOriginalUrl() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://gitea.example.com");
        integration.setProviderType(RepositoryType.GITEA);

        String apiUrl = metadata.resolveApiUrl(integration);

        assertThat(apiUrl).isEqualTo("https://gitea.example.com");
    }

    @Test
    void resolveCloneUrl_removesApiV1Suffix() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://gitea.example.com/api/v1");
        integration.setProviderType(RepositoryType.GITEA);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://gitea.example.com");
    }

    @Test
    void resolveCloneUrl_regularUrl_unchanged() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://gitea.example.com");
        integration.setProviderType(RepositoryType.GITEA);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://gitea.example.com");
    }

    @Test
    void buildAuthorizationHeader_usesTokenPrefix() {
        String header = metadata.buildAuthorizationHeader("mytoken123");

        assertThat(header).isEqualTo("token mytoken123");
    }
}


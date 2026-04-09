package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.anthropic.AnthropicAiClient;
import org.remus.giteabot.ai.llamacpp.LlamaCppClient;
import org.remus.giteabot.ai.ollama.OllamaClient;
import org.remus.giteabot.ai.openai.OpenAiClient;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiClientFactoryTest {

    @Mock
    private AiIntegrationService aiIntegrationService;

    @InjectMocks
    private AiClientFactory aiClientFactory;

    @Test
    void getClient_anthropic_createsAnthropicClient() {
        AiIntegration integration = createIntegration("anthropic", "sk-test");
        when(aiIntegrationService.decryptApiKey(integration)).thenReturn("sk-test");

        AiClient client = aiClientFactory.getClient(integration);
        assertInstanceOf(AnthropicAiClient.class, client);
    }

    @Test
    void getClient_openai_createsOpenAiClient() {
        AiIntegration integration = createIntegration("openai", "sk-test");
        when(aiIntegrationService.decryptApiKey(integration)).thenReturn("sk-test");

        AiClient client = aiClientFactory.getClient(integration);
        assertInstanceOf(OpenAiClient.class, client);
    }

    @Test
    void getClient_ollama_createsOllamaClient() {
        AiIntegration integration = createIntegration("ollama", null);
        when(aiIntegrationService.decryptApiKey(integration)).thenReturn(null);

        AiClient client = aiClientFactory.getClient(integration);
        assertInstanceOf(OllamaClient.class, client);
    }

    @Test
    void getClient_llamacpp_createsLlamaCppClient() {
        AiIntegration integration = createIntegration("llamacpp", null);
        when(aiIntegrationService.decryptApiKey(integration)).thenReturn(null);

        AiClient client = aiClientFactory.getClient(integration);
        assertInstanceOf(LlamaCppClient.class, client);
    }

    @Test
    void getClient_unknownProvider_throwsException() {
        AiIntegration integration = createIntegration("unknown", null);
        when(aiIntegrationService.decryptApiKey(integration)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> aiClientFactory.getClient(integration));
    }

    @Test
    void getClient_cachesResult_returnsSameInstance() {
        AiIntegration integration = createIntegration("ollama", null);
        when(aiIntegrationService.decryptApiKey(integration)).thenReturn(null);

        AiClient first = aiClientFactory.getClient(integration);
        AiClient second = aiClientFactory.getClient(integration);
        assertSame(first, second);
        // decryptApiKey should only be called once due to caching
        verify(aiIntegrationService, times(1)).decryptApiKey(integration);
    }

    @Test
    void getClient_rebuildsClientWhenUpdatedAtChanges() {
        AiIntegration integration = createIntegration("ollama", null);
        when(aiIntegrationService.decryptApiKey(integration)).thenReturn(null);

        AiClient first = aiClientFactory.getClient(integration);
        // Simulate configuration update
        integration.setUpdatedAt(Instant.now().plusSeconds(60));
        AiClient second = aiClientFactory.getClient(integration);
        assertNotSame(first, second);
    }

    private AiIntegration createIntegration(String providerType, String apiKey) {
        AiIntegration integration = new AiIntegration();
        integration.setId(1L);
        integration.setName("test-" + providerType);
        integration.setProviderType(providerType);
        integration.setApiUrl("http://localhost:8080");
        integration.setApiKey(apiKey);
        integration.setApiVersion("2023-06-01");
        integration.setModel("test-model");
        integration.setMaxTokens(4096);
        integration.setMaxDiffCharsPerChunk(120000);
        integration.setMaxDiffChunks(8);
        integration.setRetryTruncatedChunkChars(60000);
        integration.setUpdatedAt(Instant.now());
        return integration;
    }
}

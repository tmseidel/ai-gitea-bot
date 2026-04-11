package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiIntegrationServiceTest {

    @Mock
    private AiIntegrationRepository aiIntegrationRepository;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private AiIntegrationService aiIntegrationService;

    @Test
    void save_encryptsApiKey() {
        AiIntegration integration = new AiIntegration();
        integration.setApiKey("plain-api-key");
        when(encryptionService.encrypt("plain-api-key")).thenReturn("encrypted-value");
        when(aiIntegrationRepository.save(any(AiIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiIntegration result = aiIntegrationService.save(integration);

        assertEquals("encrypted-value", result.getApiKey());
        verify(encryptionService).encrypt("plain-api-key");
    }

    @Test
    void save_alwaysCallsEncrypt() {
        AiIntegration integration = new AiIntegration();
        integration.setApiKey("any-api-key");
        when(encryptionService.encrypt("any-api-key")).thenReturn("encrypted-value");
        when(aiIntegrationRepository.save(any(AiIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiIntegration result = aiIntegrationService.save(integration);

        assertEquals("encrypted-value", result.getApiKey());
        verify(encryptionService).encrypt("any-api-key");
    }

    @Test
    void save_nullApiKey_staysNull() {
        AiIntegration integration = new AiIntegration();
        integration.setApiKey(null);
        when(aiIntegrationRepository.save(any(AiIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AiIntegration result = aiIntegrationService.save(integration);

        assertNull(result.getApiKey());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void decryptApiKey_callsDecrypt() {
        AiIntegration integration = new AiIntegration();
        integration.setApiKey("encrypted-value");
        when(encryptionService.decrypt("encrypted-value")).thenReturn("plain-api-key");

        String result = aiIntegrationService.decryptApiKey(integration);

        assertEquals("plain-api-key", result);
        verify(encryptionService).decrypt("encrypted-value");
    }

    @Test
    void decryptApiKey_nullKey_returnsNull() {
        AiIntegration integration = new AiIntegration();
        integration.setApiKey(null);

        String result = aiIntegrationService.decryptApiKey(integration);

        assertNull(result);
        verify(encryptionService, never()).decrypt(anyString());
    }

    @Test
    void deleteById_delegatesToRepository() {
        aiIntegrationService.deleteById(1L);

        verify(aiIntegrationRepository).deleteById(1L);
    }
}

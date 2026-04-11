package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@Transactional
public class AiIntegrationService {

    private final AiIntegrationRepository aiIntegrationRepository;
    private final EncryptionService encryptionService;

    public AiIntegrationService(AiIntegrationRepository aiIntegrationRepository, EncryptionService encryptionService) {
        this.aiIntegrationRepository = aiIntegrationRepository;
        this.encryptionService = encryptionService;
    }

    @Transactional(readOnly = true)
    public List<AiIntegration> findAll() {
        return aiIntegrationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<AiIntegration> findById(Long id) {
        return aiIntegrationRepository.findById(id);
    }

    public AiIntegration save(AiIntegration integration) {
        String apiKey = integration.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            integration.setApiKey(encryptionService.encrypt(apiKey));
        }
        return aiIntegrationRepository.save(integration);
    }

    public void deleteById(Long id) {
        aiIntegrationRepository.deleteById(id);
    }

    public String decryptApiKey(AiIntegration integration) {
        String apiKey = integration.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(apiKey);
    }
}

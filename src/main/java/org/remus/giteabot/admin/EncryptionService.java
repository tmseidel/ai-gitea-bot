package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service for encrypting and decrypting sensitive data like API keys.
 *
 * If APP_ENCRYPTION_KEY environment variable is set, values are encrypted using AES-GCM.
 * If not set, values are stored unencrypted (suitable for development, not recommended for production).
 */
@Slf4j
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final String ENCRYPTED_PREFIX = "ENC:";

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();
    private final boolean encryptionEnabled;

    public EncryptionService(@Value("${app.encryption-key:#{null}}") String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            log.warn("No APP_ENCRYPTION_KEY configured. API keys will be stored UNENCRYPTED. " +
                    "Set APP_ENCRYPTION_KEY environment variable for production use.");
            this.secretKey = null;
            this.encryptionEnabled = false;
        } else {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] keyBytes = digest.digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
                this.secretKey = new SecretKeySpec(keyBytes, "AES");
                this.encryptionEnabled = true;
                log.info("Encryption enabled with APP_ENCRYPTION_KEY");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to initialize encryption key", e);
            }
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }

        if (!encryptionEnabled) {
            // No encryption - return plain text
            return plainText;
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }

        // Check if value is encrypted (has prefix)
        if (!cipherText.startsWith(ENCRYPTED_PREFIX)) {
            // Not encrypted - return as-is (plain text storage)
            return cipherText;
        }

        if (!encryptionEnabled) {
            throw new IllegalStateException("Cannot decrypt: APP_ENCRYPTION_KEY is not configured. " +
                    "Set the same encryption key that was used when the data was encrypted.");
        }

        try {
            String base64Data = cipherText.substring(ENCRYPTED_PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(base64Data);

            if (combined.length < IV_LENGTH) {
                throw new IllegalArgumentException("Ciphertext is too short");
            }

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed - wrong encryption key or corrupted data", e);
            throw new IllegalStateException("Decryption failed. Check APP_ENCRYPTION_KEY.", e);
        }
    }

    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }
}

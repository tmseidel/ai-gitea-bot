package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceTest {

    private EncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        encryptionService = new EncryptionService("test-key");
    }

    @Test
    void encryptAndDecrypt_roundTrip_succeeds() {
        String original = "my-secret-api-key";

        String encrypted = encryptionService.encrypt(original);
        String decrypted = encryptionService.decrypt(encrypted);

        assertEquals(original, decrypted);
    }

    @Test
    void encrypt_nullInput_returnsNull() {
        assertNull(encryptionService.encrypt(null));
    }

    @Test
    void encrypt_blankInput_returnsBlank() {
        assertEquals("   ", encryptionService.encrypt("   "));
    }

    @Test
    void decrypt_nullInput_returnsNull() {
        assertNull(encryptionService.decrypt(null));
    }

    @Test
    void decrypt_blankInput_returnsBlank() {
        assertEquals("   ", encryptionService.decrypt("   "));
    }

    @Test
    void encrypt_differentInputs_produceDifferentOutputs() {
        String encrypted1 = encryptionService.encrypt("secret-one");
        String encrypted2 = encryptionService.encrypt("secret-two");

        assertNotEquals(encrypted1, encrypted2);
    }

    @Test
    void encrypt_sameInput_producesDifferentOutputs() {
        String input = "same-secret";

        String encrypted1 = encryptionService.encrypt(input);
        String encrypted2 = encryptionService.encrypt(input);

        assertNotEquals(encrypted1, encrypted2, "Same input should produce different ciphertexts due to random IV");
        // Both should still decrypt to the same value
        assertEquals(input, encryptionService.decrypt(encrypted1));
        assertEquals(input, encryptionService.decrypt(encrypted2));
    }

    @Test
    void noEncryptionKey_encrypt_returnsPlainText() {
        EncryptionService noKeyService = new EncryptionService(null);
        String input = "plain-api-key";

        String result = noKeyService.encrypt(input);

        assertEquals(input, result);
        assertFalse(noKeyService.isEncryptionEnabled());
    }

    @Test
    void noEncryptionKey_decrypt_returnsPlainText() {
        EncryptionService noKeyService = new EncryptionService(null);
        String input = "plain-api-key";

        String result = noKeyService.decrypt(input);

        assertEquals(input, result);
    }

    @Test
    void noEncryptionKey_decrypt_legacyEncPrefix_stripsPrefix() {
        EncryptionService noKeyService = new EncryptionService(null);
        String input = "ENC:some-encrypted-value";

        String result = noKeyService.decrypt(input);

        assertEquals("some-encrypted-value", result);
    }

    @Test
    void decrypt_invalidBase64_returnsOriginal() {
        String notBase64 = "this-is-not-base64!@#$%";

        String result = encryptionService.decrypt(notBase64);

        assertEquals(notBase64, result);
    }
}

package org.remus.giteabot.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptServiceTest {

    @TempDir
    Path tempDir;

    private PromptConfigProperties properties;
    private PromptService promptService;

    @BeforeEach
    void setUp() {
        properties = new PromptConfigProperties();
        properties.setDir(tempDir.toString());
        properties.setDefinitions(new LinkedHashMap<>());
        promptService = new PromptService(properties);
    }

    @Test
    void getSystemPrompt_noPromptName_returnsDefault() {
        String result = promptService.getSystemPrompt(null);
        assertEquals(PromptService.DEFAULT_SYSTEM_PROMPT, result);
    }

    @Test
    void getSystemPrompt_blankPromptName_returnsDefault() {
        String result = promptService.getSystemPrompt("  ");
        assertEquals(PromptService.DEFAULT_SYSTEM_PROMPT, result);
    }

    @Test
    void getSystemPrompt_unknownPromptName_returnsDefault() {
        String result = promptService.getSystemPrompt("nonexistent");
        assertEquals(PromptService.DEFAULT_SYSTEM_PROMPT, result);
    }

    @Test
    void getSystemPrompt_validPromptName_loadsFromFile() throws IOException {
        Path promptFile = tempDir.resolve("security.md");
        Files.writeString(promptFile, "You are a security-focused reviewer.");

        PromptConfig config = new PromptConfig();
        config.setFile("security.md");
        properties.getDefinitions().put("security", config);

        String result = promptService.getSystemPrompt("security");
        assertEquals("You are a security-focused reviewer.", result);
    }

    @Test
    void getSystemPrompt_missingFile_returnsDefault() {
        PromptConfig config = new PromptConfig();
        config.setFile("missing.md");
        properties.getDefinitions().put("missing", config);

        String result = promptService.getSystemPrompt("missing");
        assertEquals(PromptService.DEFAULT_SYSTEM_PROMPT, result);
    }

    @Test
    void getSystemPrompt_defaultConfigured_loadsDefaultFile() throws IOException {
        Path promptFile = tempDir.resolve("default.md");
        Files.writeString(promptFile, "Custom default prompt.");

        PromptConfig config = new PromptConfig();
        config.setFile("default.md");
        properties.getDefinitions().put("default", config);

        String result = promptService.getSystemPrompt(null);
        assertEquals("Custom default prompt.", result);
    }

    @Test
    void resolveModel_noPromptName_returnsDefault() {
        String result = promptService.resolveModel(null, "default-model");
        assertEquals("default-model", result);
    }

    @Test
    void resolveModel_promptWithModel_returnsOverride() {
        PromptConfig config = new PromptConfig();
        config.setModel("custom-model");
        properties.getDefinitions().put("custom", config);

        String result = promptService.resolveModel("custom", "default-model");
        assertEquals("custom-model", result);
    }

    @Test
    void resolveModel_promptWithoutModel_returnsDefault() {
        PromptConfig config = new PromptConfig();
        properties.getDefinitions().put("nomodel", config);

        String result = promptService.resolveModel("nomodel", "default-model");
        assertEquals("default-model", result);
    }

    @Test
    void resolveGiteaToken_noPromptName_returnsDefault() {
        String result = promptService.resolveGiteaToken(null, "default-token");
        assertEquals("default-token", result);
    }

    @Test
    void resolveGiteaToken_promptWithToken_returnsOverride() {
        PromptConfig config = new PromptConfig();
        config.setGiteaToken("custom-token");
        properties.getDefinitions().put("custom", config);

        String result = promptService.resolveGiteaToken("custom", "default-token");
        assertEquals("custom-token", result);
    }

    @Test
    void resolveGiteaToken_promptWithoutToken_returnsDefault() {
        PromptConfig config = new PromptConfig();
        properties.getDefinitions().put("notoken", config);

        String result = promptService.resolveGiteaToken("notoken", "default-token");
        assertEquals("default-token", result);
    }

    @Test
    void getPromptConfig_returnsNullForUnknown() {
        assertNull(promptService.getPromptConfig("nonexistent"));
    }

    @Test
    void getPromptConfig_returnsConfigForKnown() {
        PromptConfig config = new PromptConfig();
        config.setFile("test.md");
        config.setModel("test-model");
        properties.getDefinitions().put("test", config);

        PromptConfig result = promptService.getPromptConfig("test");
        assertNotNull(result);
        assertEquals("test.md", result.getFile());
        assertEquals("test-model", result.getModel());
    }
}

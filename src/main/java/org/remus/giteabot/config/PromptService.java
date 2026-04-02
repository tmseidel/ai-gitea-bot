package org.remus.giteabot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class PromptService {

    static final String DEFAULT_SYSTEM_PROMPT = """
            You are an experienced software engineer performing a code review.
            Analyze the provided pull request diff and provide a constructive review.
            Focus on:
            - Potential bugs or logic errors
            - Security concerns
            - Performance issues
            - Code style and best practices
            - Suggestions for improvement
            
            Format your review as clear, actionable feedback.
            If the changes look good, say so briefly.
            Do not repeat the diff back. Be concise but thorough.
            """;

    private final PromptConfigProperties properties;

    public PromptService(PromptConfigProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolves a prompt configuration by name.
     * Returns null if the name is not configured.
     */
    public PromptConfig getPromptConfig(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return properties.getDefinitions().get(name);
    }

    /**
     * Loads the system prompt for the given prompt name.
     * If the name is null or not configured, returns the default system prompt.
     * If the configured markdown file cannot be read, falls back to the default system prompt.
     */
    public String getSystemPrompt(String promptName) {
        if (promptName == null || promptName.isBlank()) {
            return loadDefaultPromptFile();
        }

        PromptConfig config = properties.getDefinitions().get(promptName);
        if (config == null || config.getFile() == null || config.getFile().isBlank()) {
            log.warn("No prompt configuration found for name '{}', using default system prompt", promptName);
            return loadDefaultPromptFile();
        }

        return loadPromptFile(config.getFile());
    }

    /**
     * Resolves the model to use, checking the prompt config override first.
     */
    public String resolveModel(String promptName, String defaultModel) {
        if (promptName == null || promptName.isBlank()) {
            return defaultModel;
        }
        PromptConfig config = properties.getDefinitions().get(promptName);
        if (config != null && config.getModel() != null && !config.getModel().isBlank()) {
            return config.getModel();
        }
        return defaultModel;
    }

    /**
     * Resolves the Gitea token to use, checking the prompt config override first.
     */
    public String resolveGiteaToken(String promptName, String defaultToken) {
        if (promptName == null || promptName.isBlank()) {
            return defaultToken;
        }
        PromptConfig config = properties.getDefinitions().get(promptName);
        if (config != null && config.getGiteaToken() != null && !config.getGiteaToken().isBlank()) {
            return config.getGiteaToken();
        }
        return defaultToken;
    }

    private String loadDefaultPromptFile() {
        PromptConfig defaultConfig = properties.getDefinitions().get("default");
        if (defaultConfig != null && defaultConfig.getFile() != null && !defaultConfig.getFile().isBlank()) {
            return loadPromptFile(defaultConfig.getFile());
        }
        return DEFAULT_SYSTEM_PROMPT;
    }

    private String loadPromptFile(String fileName) {
        Path filePath = Path.of(properties.getDir()).resolve(fileName);
        try {
            String content = Files.readString(filePath);
            log.debug("Loaded prompt from file: {}", filePath);
            return content;
        } catch (IOException e) {
            log.warn("Failed to read prompt file '{}', using default system prompt: {}", filePath, e.getMessage());
            return DEFAULT_SYSTEM_PROMPT;
        }
    }
}

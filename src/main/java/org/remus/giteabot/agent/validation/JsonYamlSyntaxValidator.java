package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates JSON and YAML file syntax.
 */
@Slf4j
@Component
public class JsonYamlSyntaxValidator implements SyntaxValidator {

    private static final List<String> JSON_EXTENSIONS = List.of(".json");
    private static final List<String> YAML_EXTENSIONS = List.of(".yml", ".yaml");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Yaml yaml = new Yaml();

    @Override
    public boolean canValidate(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase();
        return JSON_EXTENSIONS.stream().anyMatch(lower::endsWith) ||
               YAML_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    @Override
    public List<ValidationError> validate(String filePath, String content) {
        List<ValidationError> errors = new ArrayList<>();

        if (content == null || content.isBlank()) {
            // Empty JSON/YAML might be intentional
            return errors;
        }

        String lower = filePath.toLowerCase();
        if (JSON_EXTENSIONS.stream().anyMatch(lower::endsWith)) {
            validateJson(filePath, content, errors);
        } else if (YAML_EXTENSIONS.stream().anyMatch(lower::endsWith)) {
            validateYaml(filePath, content, errors);
        }

        return errors;
    }

    private void validateJson(String filePath, String content, List<ValidationError> errors) {
        try {
            objectMapper.readTree(content);
        } catch (Exception e) {
            String message = e.getMessage();
            // Try to extract line number from Jackson error
            int line = extractLineNumber(message);
            errors.add(new ValidationError(filePath, line, message));
        }
    }

    private void validateYaml(String filePath, String content, List<ValidationError> errors) {
        try {
            yaml.load(content);
        } catch (YAMLException e) {
            String message = e.getMessage();
            int line = extractLineNumber(message);
            errors.add(new ValidationError(filePath, line, message));
        }
    }

    private int extractLineNumber(String message) {
        // Try to find line number patterns like "line 5" or "at line 5"
        if (message == null) return 0;

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("line\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(message);

        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}



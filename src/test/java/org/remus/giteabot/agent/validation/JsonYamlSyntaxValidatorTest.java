package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonYamlSyntaxValidatorTest {

    private final JsonYamlSyntaxValidator validator = new JsonYamlSyntaxValidator();

    @Test
    void canValidate_jsonFiles_returnsTrue() {
        assertThat(validator.canValidate("config.json")).isTrue();
        assertThat(validator.canValidate("data/test.json")).isTrue();
        assertThat(validator.canValidate("package.json")).isTrue();
    }

    @Test
    void canValidate_yamlFiles_returnsTrue() {
        assertThat(validator.canValidate("config.yml")).isTrue();
        assertThat(validator.canValidate("config.yaml")).isTrue();
        assertThat(validator.canValidate("docker-compose.yml")).isTrue();
    }

    @Test
    void canValidate_otherFiles_returnsFalse() {
        assertThat(validator.canValidate("Main.java")).isFalse();
        assertThat(validator.canValidate("script.py")).isFalse();
        assertThat(validator.canValidate("README.md")).isFalse();
        assertThat(validator.canValidate(null)).isFalse();
    }

    @Test
    void validate_validJson_returnsNoErrors() {
        String content = """
                {
                  "name": "test",
                  "version": "1.0.0",
                  "dependencies": {
                    "lodash": "^4.17.21"
                  }
                }
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("package.json", content);

        assertThat(errors).isEmpty();
    }

    @Test
    void validate_invalidJson_missingComma_returnsError() {
        String content = """
                {
                  "name": "test"
                  "version": "1.0.0"
                }
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("config.json", content);

        assertThat(errors).hasSize(1);
    }

    @Test
    void validate_invalidJson_unclosedBrace_returnsError() {
        String content = """
                {
                  "name": "test",
                  "nested": {
                    "key": "value"
                }
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("config.json", content);

        assertThat(errors).hasSize(1);
    }

    @Test
    void validate_validYaml_returnsNoErrors() {
        String content = """
                name: test
                version: 1.0.0
                services:
                  web:
                    image: nginx
                    ports:
                      - "80:80"
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("docker-compose.yml", content);

        assertThat(errors).isEmpty();
    }

    @Test
    void validate_invalidYaml_badIndentation_returnsError() {
        String content = """
                name: test
                services:
                  web:
                  image: nginx
                    ports:
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("config.yml", content);

        assertThat(errors).hasSize(1);
    }

    @Test
    void validate_emptyContent_returnsNoErrors() {
        // Empty content might be intentional
        List<SyntaxValidator.ValidationError> errors = validator.validate("config.json", "");
        assertThat(errors).isEmpty();
    }

    @Test
    void validate_nullContent_returnsNoErrors() {
        List<SyntaxValidator.ValidationError> errors = validator.validate("config.json", null);
        assertThat(errors).isEmpty();
    }

    @Test
    void validate_jsonArray_returnsNoErrors() {
        String content = """
                [
                  {"id": 1, "name": "Alice"},
                  {"id": 2, "name": "Bob"}
                ]
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("users.json", content);

        assertThat(errors).isEmpty();
    }
}


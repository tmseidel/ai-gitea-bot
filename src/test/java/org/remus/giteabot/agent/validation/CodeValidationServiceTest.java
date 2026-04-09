package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.model.FileChange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeValidationServiceTest {

    private final JavaSyntaxValidator javaValidator = new JavaSyntaxValidator();
    private final JsonYamlSyntaxValidator jsonYamlValidator = new JsonYamlSyntaxValidator();
    private final CodeValidationService service = new CodeValidationService(
            List.of(javaValidator, jsonYamlValidator)
    );

    @Test
    void validateAll_validFiles_returnsNoErrors() {
        List<FileChange> changes = List.of(
                FileChange.builder()
                        .path("src/Main.java")
                        .operation(FileChange.Operation.CREATE)
                        .content("public class Main { public static void main(String[] args) {} }")
                        .build(),
                FileChange.builder()
                        .path("config.json")
                        .operation(FileChange.Operation.CREATE)
                        .content("{\"key\": \"value\"}")
                        .build()
        );

        List<SyntaxValidator.ValidationError> errors = service.validateAll(changes);

        assertThat(errors).isEmpty();
    }

    @Test
    void validateAll_invalidJavaFile_returnsErrors() {
        List<FileChange> changes = List.of(
                FileChange.builder()
                        .path("src/Broken.java")
                        .operation(FileChange.Operation.CREATE)
                        .content("public class Broken { void method() { int x = 5 } }") // missing semicolon
                        .build()
        );

        List<SyntaxValidator.ValidationError> errors = service.validateAll(changes);

        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).filePath()).isEqualTo("src/Broken.java");
    }

    @Test
    void validateAll_deletedFiles_areSkipped() {
        List<FileChange> changes = List.of(
                FileChange.builder()
                        .path("src/ToDelete.java")
                        .operation(FileChange.Operation.DELETE)
                        .content("this is invalid java syntax but we don't care")
                        .build()
        );

        List<SyntaxValidator.ValidationError> errors = service.validateAll(changes);

        assertThat(errors).isEmpty();
    }

    @Test
    void validateAll_unknownFileType_returnsNoErrors() {
        List<FileChange> changes = List.of(
                FileChange.builder()
                        .path("script.py")
                        .operation(FileChange.Operation.CREATE)
                        .content("print('hello')") // Python, no validator
                        .build(),
                FileChange.builder()
                        .path("README.md")
                        .operation(FileChange.Operation.UPDATE)
                        .content("# Title\n\nSome content")
                        .build()
        );

        List<SyntaxValidator.ValidationError> errors = service.validateAll(changes);

        assertThat(errors).isEmpty();
    }

    @Test
    void validateAll_multipleFiles_withMixedValidity_returnsOnlyErrors() {
        List<FileChange> changes = List.of(
                FileChange.builder()
                        .path("Valid.java")
                        .operation(FileChange.Operation.CREATE)
                        .content("public class Valid {}")
                        .build(),
                FileChange.builder()
                        .path("Invalid.java")
                        .operation(FileChange.Operation.CREATE)
                        .content("public class Invalid { syntax error")
                        .build(),
                FileChange.builder()
                        .path("config.json")
                        .operation(FileChange.Operation.CREATE)
                        .content("{\"valid\": true}")
                        .build()
        );

        List<SyntaxValidator.ValidationError> errors = service.validateAll(changes);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).filePath()).isEqualTo("Invalid.java");
    }

    @Test
    void buildErrorReport_withErrors_formatsCorrectly() {
        List<SyntaxValidator.ValidationError> errors = List.of(
                new SyntaxValidator.ValidationError("Test.java", 5, 10, "';' expected", "ERROR"),
                new SyntaxValidator.ValidationError("Test.java", 8, "'}' expected"),
                new SyntaxValidator.ValidationError("config.json", "Unexpected token")
        );

        String report = service.buildErrorReport(errors);

        assertThat(report).contains("Syntax Validation Errors");
        assertThat(report).contains("Test.java");
        assertThat(report).contains("Line 5");
        assertThat(report).contains("';' expected");
        assertThat(report).contains("config.json");
    }

    @Test
    void buildErrorReport_noErrors_returnsSuccessMessage() {
        String report = service.buildErrorReport(List.of());

        assertThat(report).contains("No validation errors");
    }

    @Test
    void validateFile_validJava_returnsNoErrors() {
        List<SyntaxValidator.ValidationError> errors = service.validateFile(
                "MyClass.java",
                "public class MyClass { private int value; }"
        );

        assertThat(errors).isEmpty();
    }

    @Test
    void validateFile_noMatchingValidator_returnsNoErrors() {
        List<SyntaxValidator.ValidationError> errors = service.validateFile(
                "script.rb",
                "puts 'Hello, Ruby!'"
        );

        assertThat(errors).isEmpty();
    }
}


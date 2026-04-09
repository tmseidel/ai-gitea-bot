package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.model.FileChange;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates syntax validation across multiple file types.
 * Aggregates results from all registered validators.
 */
@Slf4j
@Service
public class CodeValidationService {

    private final List<SyntaxValidator> validators;

    public CodeValidationService(List<SyntaxValidator> validators) {
        this.validators = validators;
        log.info("Code validation service initialized with {} validators", validators.size());
    }

    /**
     * Validates all file changes and returns aggregated errors.
     *
     * @param fileChanges the list of file changes to validate
     * @return list of all validation errors, empty if all files are valid
     */
    public List<SyntaxValidator.ValidationError> validateAll(List<FileChange> fileChanges) {
        List<SyntaxValidator.ValidationError> allErrors = new ArrayList<>();

        for (FileChange change : fileChanges) {
            // Skip deleted files
            if (change.getOperation() == FileChange.Operation.DELETE) {
                continue;
            }

            List<SyntaxValidator.ValidationError> fileErrors = validateFile(change.getPath(), change.getContent());
            allErrors.addAll(fileErrors);
        }

        if (!allErrors.isEmpty()) {
            log.info("Validation found {} error(s) in {} file(s)",
                    allErrors.size(),
                    allErrors.stream().map(SyntaxValidator.ValidationError::filePath).distinct().count());
        }

        return allErrors;
    }

    /**
     * Validates a single file.
     *
     * @param filePath the file path
     * @param content  the file content
     * @return list of validation errors for this file
     */
    public List<SyntaxValidator.ValidationError> validateFile(String filePath, String content) {
        for (SyntaxValidator validator : validators) {
            if (validator.canValidate(filePath)) {
                log.debug("Validating {} with {}", filePath, validator.getClass().getSimpleName());
                return validator.validate(filePath, content);
            }
        }

        // No validator available for this file type
        log.debug("No validator available for file type: {}", filePath);
        return List.of();
    }

    /**
     * Builds a human-readable error report for the AI to understand.
     *
     * @param errors the validation errors
     * @return formatted error report
     */
    public String buildErrorReport(List<SyntaxValidator.ValidationError> errors) {
        if (errors.isEmpty()) {
            return "No validation errors found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Syntax Validation Errors\n\n");
        sb.append("The following syntax errors were found in the generated code:\n\n");

        // Group errors by file
        errors.stream()
                .collect(java.util.stream.Collectors.groupingBy(SyntaxValidator.ValidationError::filePath))
                .forEach((filePath, fileErrors) -> {
                    sb.append("### `").append(filePath).append("`\n\n");
                    for (SyntaxValidator.ValidationError error : fileErrors) {
                        if (error.line() > 0) {
                            sb.append("- **Line ").append(error.line()).append("**: ");
                        } else {
                            sb.append("- ");
                        }
                        sb.append(error.message()).append("\n");
                    }
                    sb.append("\n");
                });

        sb.append("Please fix these errors and regenerate the code.\n");

        return sb.toString();
    }
}


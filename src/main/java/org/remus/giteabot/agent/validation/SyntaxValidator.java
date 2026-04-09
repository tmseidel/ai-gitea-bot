package org.remus.giteabot.agent.validation;

import java.util.List;

/**
 * Validates file syntax before committing changes.
 * Returns a list of validation errors (empty if valid).
 */
public interface SyntaxValidator {

    /**
     * Validates the syntax of the given file content.
     *
     * @param filePath the path of the file
     * @param content  the file content to validate
     * @return list of validation errors, empty if the content is valid
     */
    List<ValidationError> validate(String filePath, String content);

    /**
     * Checks if this validator can handle the given file type.
     *
     * @param filePath the file path to check
     * @return true if this validator can validate the file type
     */
    boolean canValidate(String filePath);

    /**
     * Represents a syntax validation error.
     */
    record ValidationError(
            String filePath,
            int line,
            int column,
            String message,
            String severity
    ) {
        public ValidationError(String filePath, int line, String message) {
            this(filePath, line, 0, message, "ERROR");
        }

        public ValidationError(String filePath, String message) {
            this(filePath, 0, 0, message, "ERROR");
        }

        @Override
        public String toString() {
            if (line > 0) {
                return String.format("%s:%d: %s", filePath, line, message);
            }
            return String.format("%s: %s", filePath, message);
        }
    }
}



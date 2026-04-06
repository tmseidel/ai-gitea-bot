package org.remus.giteabot.agent.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileChange {

    /**
     * Relative file path within the repository.
     */
    private String path;

    /**
     * The full content of the file (for CREATE or full UPDATE).
     * If null and diff is provided, the diff will be applied to the existing file.
     */
    private String content;

    /**
     * Optional: A diff to apply to the existing file (for UPDATE operations).
     * Format: search/replace blocks like:
     * <<<<<<< SEARCH
     * old code
     * =======
     * new code
     * >>>>>>> REPLACE
     */
    private String diff;

    /**
     * The operation to perform: CREATE, UPDATE, or DELETE.
     */
    private Operation operation;

    /**
     * Returns true if this change uses diff-based modification.
     */
    public boolean isDiffBased() {
        return diff != null && !diff.isBlank() && operation == Operation.UPDATE;
    }

    public enum Operation {
        CREATE,
        UPDATE,
        DELETE
    }
}

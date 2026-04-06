package org.remus.giteabot.agent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ImplementationPlan {

    /**
     * Short summary of the planned implementation.
     */
    private String summary;

    /**
     * Files the AI requests to see before proceeding.
     * If non-empty, fetch these files and continue the conversation.
     */
    private List<String> requestFiles;

    /**
     * List of file changes to implement.
     */
    private List<FileChange> fileChanges;

    /**
     * Branch name to be created for the implementation.
     */
    private String branchName;

    /**
     * Returns true if the AI is requesting additional files.
     */
    public boolean hasFileRequests() {
        return requestFiles != null && !requestFiles.isEmpty();
    }

    /**
     * Returns true if there are actual file changes to apply.
     */
    public boolean hasFileChanges() {
        return fileChanges != null && !fileChanges.isEmpty();
    }
}

package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.agent.DiffApplyService;
import org.remus.giteabot.agent.model.FileChange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionServiceTest {

    private DiffApplyService diffApplyService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        diffApplyService = new DiffApplyService();
    }

    @Test
    void prepareWorkspace_withDiffBasedUpdate_appliesDiffCorrectly() throws IOException {
        // Given: A repository with an existing file
        Path repoDir = tempDir.resolve("repo");
        Files.createDirectories(repoDir);

        // Simulate an existing file in the cloned repo
        Path existingFile = repoDir.resolve("src/main/java/Task.java");
        Files.createDirectories(existingFile.getParent());
        String originalContent = """
                package com.example;
                
                public class Task {
                    private Long id;
                    private String title;
                }
                """;
        Files.writeString(existingFile, originalContent);

        // A diff-based update that adds an assignee field
        String diff = """
                <<<<<<< SEARCH
                    private Long id;
                    private String title;
                =======
                    private Long id;
                    private String title;
                    private String assignee;
                >>>>>>> REPLACE
                """;

        FileChange change = FileChange.builder()
                .path("src/main/java/Task.java")
                .operation(FileChange.Operation.UPDATE)
                .diff(diff)
                .content("") // Empty content because it's diff-based
                .build();

        // When: We apply the diff manually (simulating what prepareWorkspace does internally)
        assertThat(change.isDiffBased()).isTrue();
        String newContent = diffApplyService.applyDiff(originalContent, diff);

        // Then: The diff should be applied correctly, not writing empty content
        assertThat(newContent).contains("private String assignee;");
        assertThat(newContent).contains("private Long id;");
        assertThat(newContent).contains("private String title;");
        assertThat(newContent).isNotEmpty();
    }

    @Test
    void prepareWorkspace_withContentBasedUpdate_writesContent() {
        // Given: A full content update (not diff-based)
        String newContent = """
                package com.example;
                
                public class Task {
                    private Long id;
                    private String title;
                    private String assignee;
                }
                """;

        FileChange change = FileChange.builder()
                .path("src/main/java/Task.java")
                .operation(FileChange.Operation.UPDATE)
                .content(newContent)
                .diff(null) // No diff, full content replacement
                .build();

        // Then: isDiffBased should be false
        assertThat(change.isDiffBased()).isFalse();
        assertThat(change.getContent()).isNotEmpty();
        assertThat(change.getContent()).contains("private String assignee;");
    }

    @Test
    void prepareWorkspace_withCreateOperation_writesFullContent() {
        String content = """
                package com.example;
                
                public class NewClass {
                }
                """;

        FileChange change = FileChange.builder()
                .path("src/main/java/NewClass.java")
                .operation(FileChange.Operation.CREATE)
                .content(content)
                .build();

        assertThat(change.isDiffBased()).isFalse();
        assertThat(change.getContent()).isEqualTo(content);
    }

    @Test
    void fileChange_isDiffBased_returnsTrueWhenDiffIsSet() {
        FileChange diffBased = FileChange.builder()
                .path("test.java")
                .operation(FileChange.Operation.UPDATE)
                .diff("<<<<<<< SEARCH\nold\n=======\nnew\n>>>>>>> REPLACE")
                .content("")
                .build();

        FileChange contentBased = FileChange.builder()
                .path("test.java")
                .operation(FileChange.Operation.UPDATE)
                .content("new content")
                .diff(null)
                .build();

        assertThat(diffBased.isDiffBased()).isTrue();
        assertThat(contentBased.isDiffBased()).isFalse();
    }

    @Test
    void fileChange_isDiffBased_returnsFalseForEmptyDiff() {
        FileChange emptyDiff = FileChange.builder()
                .path("test.java")
                .operation(FileChange.Operation.UPDATE)
                .diff("")
                .content("some content")
                .build();

        assertThat(emptyDiff.isDiffBased()).isFalse();
    }
}







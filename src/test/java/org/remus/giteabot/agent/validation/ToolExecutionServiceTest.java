package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.model.FileChange;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionServiceTest {

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

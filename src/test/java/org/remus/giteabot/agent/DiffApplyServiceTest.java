package org.remus.giteabot.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiffApplyServiceTest {

    private final DiffApplyService service = new DiffApplyService();

    @Test
    void applyDiff_singleBlock_replacesContent() {
        String original = """
                public class Test {
                    public void hello() {
                        System.out.println("Hello");
                    }
                }
                """;

        String diff = """
                <<<<<<< SEARCH
                        System.out.println("Hello");
                =======
                        System.out.println("Hello, World!");
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("Hello, World!");
        assertThat(result).doesNotContain("\"Hello\"");
    }

    @Test
    void applyDiff_multipleBlocks_replacesAll() {
        String original = """
                public class Test {
                    private int x = 1;
                    private int y = 2;
                }
                """;

        String diff = """
                <<<<<<< SEARCH
                    private int x = 1;
                =======
                    private int x = 10;
                >>>>>>> REPLACE
                
                <<<<<<< SEARCH
                    private int y = 2;
                =======
                    private int y = 20;
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("x = 10");
        assertThat(result).contains("y = 20");
    }

    @Test
    void applyDiff_addNewMethod() {
        String original = """
                public class Test {
                    public void existing() {
                    }
                }
                """;

        String diff = """
                <<<<<<< SEARCH
                    public void existing() {
                    }
                }
                =======
                    public void existing() {
                    }
                    
                    public void newMethod() {
                        // New implementation
                    }
                }
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("newMethod()");
        assertThat(result).contains("existing()");
    }

    @Test
    void applyDiff_emptyDiff_returnsOriginal() {
        String original = "original content";

        String result = service.applyDiff(original, "");

        assertThat(result).isEqualTo(original);
    }

    @Test
    void applyDiff_nullDiff_returnsOriginal() {
        String original = "original content";

        String result = service.applyDiff(original, null);

        assertThat(result).isEqualTo(original);
    }

    @Test
    void applyDiff_searchNotFound_throwsException() {
        String original = "some content";
        String diff = """
                <<<<<<< SEARCH
                not found text
                =======
                replacement
                >>>>>>> REPLACE
                """;

        assertThatThrownBy(() -> service.applyDiff(original, diff))
                .isInstanceOf(DiffApplyService.DiffApplyException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void parseDiffBlocks_validDiff_parsesBlocks() {
        String diff = """
                <<<<<<< SEARCH
                old1
                =======
                new1
                >>>>>>> REPLACE
                
                <<<<<<< SEARCH
                old2
                =======
                new2
                >>>>>>> REPLACE
                """;

        var blocks = service.parseDiffBlocks(diff);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).search()).isEqualTo("old1");
        assertThat(blocks.get(0).replace()).isEqualTo("new1");
        assertThat(blocks.get(1).search()).isEqualTo("old2");
        assertThat(blocks.get(1).replace()).isEqualTo("new2");
    }

    @Test
    void parseDiffBlocks_multilineContent() {
        String diff = """
                <<<<<<< SEARCH
                line1
                line2
                line3
                =======
                newline1
                newline2
                >>>>>>> REPLACE
                """;

        var blocks = service.parseDiffBlocks(diff);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).search()).contains("line1");
        assertThat(blocks.get(0).search()).contains("line3");
    }

    @Test
    void applyDiff_deleteLines() {
        String original = """
                line1
                line2
                line3
                """;

        String diff = """
                <<<<<<< SEARCH
                line2
                =======
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("line1");
        assertThat(result).contains("line3");
        assertThat(result).doesNotContain("line2");
    }
}


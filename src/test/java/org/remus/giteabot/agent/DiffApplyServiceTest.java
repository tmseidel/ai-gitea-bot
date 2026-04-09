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

    @Test
    void applyDiff_emptySearchBlock_appendsContent() {
        String original = "existing content";

        String diff = """
                <<<<<<< SEARCH
                =======
                new content
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).isEqualTo("existing content\nnew content");
    }

    @Test
    void applyDiff_placeholderComment_appendsContent() {
        String original = "/* some existing CSS */\nbody { margin: 0; }";

        String diff = """
                <<<<<<< SEARCH
                /* Add any existing CSS content here */
                =======
                .assignee {
                    font-style: italic;
                }
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("/* some existing CSS */");
        assertThat(result).contains("body { margin: 0; }");
        assertThat(result).contains(".assignee {");
        assertThat(result).contains("font-style: italic;");
    }

    @Test
    void applyDiff_placeholderComment_onEmptyFile_replacesContent() {
        String original = "";

        String diff = """
                <<<<<<< SEARCH
                /* Add your code here */
                =======
                .new-class { color: red; }
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).isEqualTo(".new-class { color: red; }");
    }

    @Test
    void applyDiff_placeholderHtmlComment_appendsContent() {
        String original = "<html><body></body></html>";

        String diff = """
                <<<<<<< SEARCH
                <!-- Add existing content here -->
                =======
                <div>New content</div>
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("<html>");
        assertThat(result).contains("<div>New content</div>");
    }

    @Test
    void applyDiff_placeholderLineComment_appendsContent() {
        String original = "var x = 1;";

        String diff = """
                <<<<<<< SEARCH
                // Add your existing code here
                =======
                var y = 2;
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("var x = 1;");
        assertThat(result).contains("var y = 2;");
    }

    @Test
    void applyDiff_placeholderHashComment_appendsContent() {
        String original = "import os";

        String diff = """
                <<<<<<< SEARCH
                # Add your existing code here
                =======
                import sys
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("import os");
        assertThat(result).contains("import sys");
    }

    @Test
    void applyDiff_appendPattern_appendsNewContent() {
        // Test the CSS append pattern where REPLACE starts with SEARCH
        String original = """
                .task-list li.done .task-main {
                    text-decoration: line-through;
                    opacity: 0.6;
                }""";

        String diff = """
                <<<<<<< SEARCH
                .task-list li.done .task-main {
                    text-decoration: line-through;
                    opacity: 0.6;
                }
                =======
                .task-list li.done .task-main {
                    text-decoration: line-through;
                    opacity: 0.6;
                }
                
                .assignee {
                    display: block;
                    color: #666;
                    font-style: italic;
                    margin-top: 0.25rem;
                }
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains(".task-list li.done .task-main");
        assertThat(result).contains("text-decoration: line-through");
        assertThat(result).contains(".assignee {");
        assertThat(result).contains("font-style: italic");
    }

    @Test
    void applyDiff_searchNotFoundWithExactContent_appendsViaAppendPattern() {
        // When AI generates a search block that doesn't exactly match but replace contains search
        String original = ".existing { margin: 0; }";

        String diff = """
                <<<<<<< SEARCH
                .existing { margin: 0; }
                =======
                .existing { margin: 0; }
                
                .new-class { color: red; }
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains(".existing { margin: 0; }");
        assertThat(result).contains(".new-class { color: red; }");
    }

    @Test
    void applyDiff_trailingWhitespaceDifference_shouldMatch() {
        String original = "line1\noriginalLine\nline3";

        String diff = """
                <<<<<<< SEARCH
                originalLine
                =======
                modified-line
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("line1");
        assertThat(result).contains("modified-line");
        assertThat(result).contains("line3");
        assertThat(result).doesNotContain("originalLine");
    }

    @Test
    void applyDiff_crlfLineEndings_shouldMatch() {
        // File has CRLF line endings, but search has LF
        String original = "    private String description;\r\n\r\n    @Column(nullable = false)\r\n    private boolean completed;";

        String diff = """
                <<<<<<< SEARCH
                    private String description;

                    @Column(nullable = false)
                    private boolean completed;
                =======
                    private String description;

                    private String assignee;

                    @Column(nullable = false)
                    private boolean completed;
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("private String assignee;");
        assertThat(result).contains("private String description;");
        assertThat(result).contains("private boolean completed;");
    }

    @Test
    void applyDiff_differentEmptyLineCount_shouldMatch() {
        // File has no empty line, but search expects one
        String original = "    private String description;\n    @Column(nullable = false)\n    private boolean completed;";

        String diff = """
                <<<<<<< SEARCH
                    private String description;

                    @Column(nullable = false)
                    private boolean completed;
                =======
                    private String description;

                    private String assignee;

                    @Column(nullable = false)
                    private boolean completed;
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("private String assignee;");
    }

    @Test
    void applyDiff_indentationDifferences_shouldMatch() {
        // File has different indentation
        String original = "private String description;\n@Column(nullable = false)\nprivate boolean completed;";

        String diff = """
                <<<<<<< SEARCH
                    private String description;
                    @Column(nullable = false)
                    private boolean completed;
                =======
                    private String description;
                    private String assignee;
                    @Column(nullable = false)
                    private boolean completed;
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("private String assignee;");
    }

    @Test
    void applyDiff_multipleEmptyLines_shouldMatch() {
        // File has multiple empty lines where search expects one
        String original = "    private String description;\n\n\n    @Column\n    private boolean completed;";

        String diff = """
                <<<<<<< SEARCH
                    private String description;

                    @Column
                    private boolean completed;
                =======
                    private String description;
                    private String assignee;
                    @Column
                    private boolean completed;
                >>>>>>> REPLACE
                """;

        String result = service.applyDiff(original, diff);

        assertThat(result).contains("private String assignee;");
    }
}


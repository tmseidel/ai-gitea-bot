package org.remus.giteabot.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies search/replace diffs to file content.
 * Supports a simple diff format with SEARCH/REPLACE blocks.
 */
@Slf4j
@Service
public class DiffApplyService {

    // Pattern to match SEARCH/REPLACE blocks
    private static final Pattern DIFF_BLOCK_PATTERN = Pattern.compile(
            "<<<<<<< SEARCH\\s*\\n(.*?)=======\\s*\\n(.*?)>>>>>>> REPLACE",
            Pattern.DOTALL
    );

    /**
     * Applies a diff to the original file content.
     *
     * @param originalContent the original file content
     * @param diff            the diff containing SEARCH/REPLACE blocks
     * @return the modified content
     * @throws DiffApplyException if a search block cannot be found
     */
    public String applyDiff(String originalContent, String diff) {
        if (diff == null || diff.isBlank()) {
            return originalContent;
        }

        List<SearchReplace> blocks = parseDiffBlocks(diff);
        if (blocks.isEmpty()) {
            log.warn("No valid SEARCH/REPLACE blocks found in diff");
            return originalContent;
        }

        String result = originalContent;
        for (SearchReplace block : blocks) {
            result = applyBlock(result, block);
        }

        return result;
    }

    /**
     * Parses SEARCH/REPLACE blocks from the diff.
     */
    List<SearchReplace> parseDiffBlocks(String diff) {
        List<SearchReplace> blocks = new ArrayList<>();
        Matcher matcher = DIFF_BLOCK_PATTERN.matcher(diff);

        while (matcher.find()) {
            String search = matcher.group(1);
            String replace = matcher.group(2);

            // Trim trailing newline from search/replace if present
            if (search.endsWith("\n")) {
                search = search.substring(0, search.length() - 1);
            }
            if (replace.endsWith("\n")) {
                replace = replace.substring(0, replace.length() - 1);
            }

            blocks.add(new SearchReplace(search, replace));
        }

        return blocks;
    }

    /**
     * Applies a single search/replace block.
     */
    private String applyBlock(String content, SearchReplace block) {
        String search = block.search();
        String replace = block.replace();

        // Handle empty search block - append to end of file
        if (search.isBlank()) {
            if (content.isBlank()) {
                return replace;
            }
            return content + "\n" + replace;
        }

        // Handle placeholder search blocks (e.g., comments that don't exist in file)
        if (isPlaceholderComment(search) && !content.contains(search)) {
            log.info("Search block appears to be a placeholder comment, appending replace content");
            if (content.isBlank()) {
                return replace;
            }
            return content + "\n" + replace;
        }

        // Try exact match first
        if (content.contains(search)) {
            return content.replace(search, replace);
        }

        // Try with normalized whitespace (trim lines)
        String normalizedSearch = normalizeWhitespace(search);
        String[] lines = content.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean found = false;

        for (int i = 0; i < lines.length; i++) {
            if (!found && matchesNormalized(content, i, search, normalizedSearch)) {
                // Found the start of the search block
                int searchLines = search.split("\n", -1).length;
                result.append(replace);
                i += searchLines - 1; // Skip the matched lines
                found = true;
                if (i < lines.length - 1) {
                    result.append("\n");
                }
            } else {
                result.append(lines[i]);
                if (i < lines.length - 1) {
                    result.append("\n");
                }
            }
        }

        if (!found) {
            // Check if this is an "append pattern" where REPLACE starts with SEARCH
            // This happens when AI wants to append content to end of file
            if (isAppendPattern(search, replace)) {
                log.info("Detected append pattern, appending new content to end of file");
                String newContent = extractAppendContent(search, replace);
                if (content.isBlank()) {
                    return newContent;
                }
                return content + "\n" + newContent;
            }

            // Try fuzzy matching: find the search block with trailing whitespace differences
            String trimmedSearch = search.stripTrailing();
            if (!trimmedSearch.equals(search) && content.contains(trimmedSearch)) {
                log.info("Found search block with trailing whitespace differences, applying replacement");
                return content.replace(trimmedSearch, replace);
            }

            log.warn("Could not find search block in file. Search text (first 100 chars): {}",
                    search.length() > 100 ? search.substring(0, 100) + "..." : search);
            throw new DiffApplyException("Search block not found in file content");
        }

        return result.toString();
    }

    private boolean matchesNormalized(String content, int startLine, String search, String normalizedSearch) {
        String[] contentLines = content.split("\n", -1);
        String[] searchLines = search.split("\n", -1);

        if (startLine + searchLines.length > contentLines.length) {
            return false;
        }

        StringBuilder contentBlock = new StringBuilder();
        for (int i = 0; i < searchLines.length; i++) {
            contentBlock.append(contentLines[startLine + i].trim());
            if (i < searchLines.length - 1) {
                contentBlock.append("\n");
            }
        }

        return contentBlock.toString().equals(normalizedSearch);
    }

    private String normalizeWhitespace(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i].trim());
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Checks if the search block appears to be a placeholder comment
     * that was generated by AI but doesn't represent actual file content.
     */
    private boolean isPlaceholderComment(String search) {
        String trimmed = search.trim();
        // Check for common placeholder patterns
        if (trimmed.startsWith("/*") && trimmed.endsWith("*/")) {
            String inner = trimmed.substring(2, trimmed.length() - 2).toLowerCase();
            return inner.contains("existing") || inner.contains("placeholder") ||
                   inner.contains("add") || inner.contains("content here") ||
                   inner.contains("your code") || inner.contains("rest of");
        }
        if (trimmed.startsWith("//") || trimmed.startsWith("#")) {
            String inner = trimmed.substring(trimmed.startsWith("//") ? 2 : 1).toLowerCase();
            return inner.contains("existing") || inner.contains("placeholder") ||
                   inner.contains("add") || inner.contains("content here") ||
                   inner.contains("your code") || inner.contains("rest of");
        }
        if (trimmed.startsWith("<!--") && trimmed.endsWith("-->")) {
            String inner = trimmed.substring(4, trimmed.length() - 3).toLowerCase();
            return inner.contains("existing") || inner.contains("placeholder") ||
                   inner.contains("add") || inner.contains("content here") ||
                   inner.contains("your code") || inner.contains("rest of");
        }
        return false;
    }

    /**
     * Checks if the replace block starts with the search block (append pattern).
     * This indicates the AI wants to append content after the search block.
     */
    private boolean isAppendPattern(String search, String replace) {
        if (search.isBlank() || replace.isBlank()) {
            return false;
        }
        String normalizedSearch = normalizeWhitespace(search);
        String normalizedReplace = normalizeWhitespace(replace);
        return normalizedReplace.startsWith(normalizedSearch) &&
               normalizedReplace.length() > normalizedSearch.length();
    }

    /**
     * Extracts the content to append when an append pattern is detected.
     */
    private String extractAppendContent(String search, String replace) {
        // Find where the new content starts in replace
        String normalizedSearch = normalizeWhitespace(search);
        String normalizedReplace = normalizeWhitespace(replace);

        if (normalizedReplace.startsWith(normalizedSearch)) {
            // The replace contains search + new content
            // Return just the new content portion from the original (non-normalized) replace
            int searchLineCount = search.split("\n", -1).length;
            String[] replaceLines = replace.split("\n", -1);

            if (replaceLines.length > searchLineCount) {
                StringBuilder newContent = new StringBuilder();
                for (int i = searchLineCount; i < replaceLines.length; i++) {
                    if (i > searchLineCount) {
                        newContent.append("\n");
                    }
                    newContent.append(replaceLines[i]);
                }
                return newContent.toString();
            }
        }
        return replace;
    }

    record SearchReplace(String search, String replace) {}

    public static class DiffApplyException extends RuntimeException {
        public DiffApplyException(String message) {
            super(message);
        }
    }
}


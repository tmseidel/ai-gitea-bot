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
     * Uses multiple strategies to find the search text:
     * 1. Exact match
     * 2. Normalized line endings (CRLF -> LF)
     * 3. Trimmed trailing whitespace per line
     * 4. Fuzzy line-by-line matching (ignoring leading/trailing whitespace)
     * 5. Collapsed whitespace matching (for empty line differences)
     * 6. Append pattern detection (REPLACE starts with SEARCH)
     * 7. Similarity-based matching (tolerates small typos using Levenshtein distance)
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

        // Strategy 1: Exact match
        if (content.contains(search)) {
            log.debug("Applied diff using exact match");
            return content.replace(search, replace);
        }

        // Strategy 2: Normalize line endings (CRLF -> LF)
        String normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n");
        String normalizedSearch = search.replace("\r\n", "\n").replace("\r", "\n");
        if (normalizedContent.contains(normalizedSearch)) {
            log.debug("Applied diff using normalized line endings");
            String result = normalizedContent.replace(normalizedSearch, replace);
            // Restore original line ending style if content had CRLF
            if (content.contains("\r\n")) {
                result = result.replace("\n", "\r\n");
            }
            return result;
        }

        // Strategy 3: Try with trailing whitespace stripped from each line
        String trimmedTrailingSearch = trimTrailingWhitespacePerLine(normalizedSearch);
        String trimmedTrailingContent = trimTrailingWhitespacePerLine(normalizedContent);
        if (trimmedTrailingContent.contains(trimmedTrailingSearch)) {
            log.debug("Applied diff using trailing whitespace normalization");
            // Find where it matches and replace in normalized content
            int idx = trimmedTrailingContent.indexOf(trimmedTrailingSearch);
            int endIdx = idx + trimmedTrailingSearch.length();
            // Map back to original normalized content positions
            int originalStart = mapToOriginalPosition(normalizedContent, trimmedTrailingContent, idx);
            int originalEnd = mapToOriginalPosition(normalizedContent, trimmedTrailingContent, endIdx);
            String result = normalizedContent.substring(0, originalStart) + replace + normalizedContent.substring(originalEnd);
            if (content.contains("\r\n")) {
                result = result.replace("\n", "\r\n");
            }
            return result;
        }

        // Strategy 4: Fuzzy line-by-line matching (trim each line)
        FuzzyMatchResult fuzzyResult = findFuzzyMatch(normalizedContent, normalizedSearch);
        if (fuzzyResult != null) {
            log.debug("Applied diff using fuzzy line-by-line matching at line {}", fuzzyResult.startLine);
            String result = applyFuzzyReplace(normalizedContent, fuzzyResult, replace);
            if (content.contains("\r\n")) {
                result = result.replace("\n", "\r\n");
            }
            return result;
        }

        // Strategy 5: Try matching without empty lines (collapsed whitespace)
        FuzzyMatchResult collapsedResult = findCollapsedMatch(normalizedContent, normalizedSearch);
        if (collapsedResult != null) {
            log.debug("Applied diff using collapsed empty line matching at line {}", collapsedResult.startLine);
            String result = applyFuzzyReplace(normalizedContent, collapsedResult, replace);
            if (content.contains("\r\n")) {
                result = result.replace("\n", "\r\n");
            }
            return result;
        }

        // Strategy 6: Check if this is an "append pattern" where REPLACE starts with SEARCH
        if (isAppendPattern(search, replace)) {
            log.info("Detected append pattern, appending new content to end of file");
            String newContent = extractAppendContent(search, replace);
            if (content.isBlank()) {
                return newContent;
            }
            return content + "\n" + newContent;
        }

        // Strategy 7: Similarity-based fuzzy matching (handles small typos like andExpected vs andExpect)
        FuzzyMatchResult similarityResult = findSimilarityMatch(normalizedContent, normalizedSearch);
        if (similarityResult != null) {
            log.info("Applied diff using similarity-based fuzzy matching at line {} (small typos tolerated)",
                    similarityResult.startLine);
            String result = applyFuzzyReplace(normalizedContent, similarityResult, replace);
            if (content.contains("\r\n")) {
                result = result.replace("\n", "\r\n");
            }
            return result;
        }

        log.warn("Could not find search block in file. Search text (first 100 chars): {}",
                search.length() > 100 ? search.substring(0, 100) + "..." : search);
        String searchPreview = search.length() > 200 ? search.substring(0, 200) + "..." : search;
        throw new DiffApplyException("Search block not found in file content. The file may have been modified or the AI provided an incorrect search pattern.\n\nExpected to find:\n```\n" + searchPreview + "\n```");
    }

    /**
     * Trims trailing whitespace from each line.
     */
    private String trimTrailingWhitespacePerLine(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i].stripTrailing());
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Maps a position in trimmed content back to original content.
     * This is an approximation that works for simple trailing whitespace removal.
     */
    private int mapToOriginalPosition(String original, String trimmed, int trimmedPos) {
        String[] originalLines = original.split("\n", -1);
        String[] trimmedLines = trimmed.split("\n", -1);

        int currentTrimmedPos = 0;
        int currentOriginalPos = 0;

        for (int i = 0; i < trimmedLines.length && currentTrimmedPos <= trimmedPos; i++) {
            if (currentTrimmedPos + trimmedLines[i].length() >= trimmedPos) {
                // Position is within this line
                int offsetInLine = trimmedPos - currentTrimmedPos;
                return currentOriginalPos + Math.min(offsetInLine, originalLines[i].length());
            }
            currentTrimmedPos += trimmedLines[i].length() + 1; // +1 for newline
            currentOriginalPos += originalLines[i].length() + 1;
        }
        return currentOriginalPos;
    }

    /**
     * Finds a fuzzy match by trimming each line and comparing.
     */
    private FuzzyMatchResult findFuzzyMatch(String content, String search) {
        String[] contentLines = content.split("\n", -1);
        String[] searchLines = search.split("\n", -1);

        // Trim search lines
        String[] trimmedSearchLines = new String[searchLines.length];
        for (int i = 0; i < searchLines.length; i++) {
            trimmedSearchLines[i] = searchLines[i].trim();
        }

        for (int i = 0; i <= contentLines.length - searchLines.length; i++) {
            boolean matches = true;
            for (int j = 0; j < searchLines.length; j++) {
                if (!contentLines[i + j].trim().equals(trimmedSearchLines[j])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return new FuzzyMatchResult(i, searchLines.length);
            }
        }
        return null;
    }

    /**
     * Finds a match by collapsing consecutive empty lines.
     * This handles cases where the AI assumes there's one empty line but the file has none or multiple.
     */
    private FuzzyMatchResult findCollapsedMatch(String content, String search) {
        String[] contentLines = content.split("\n", -1);
        String[] searchLines = search.split("\n", -1);

        // Get non-empty trimmed search lines
        List<String> nonEmptySearchLines = new ArrayList<>();
        for (String line : searchLines) {
            if (!line.trim().isEmpty()) {
                nonEmptySearchLines.add(line.trim());
            }
        }

        if (nonEmptySearchLines.isEmpty()) {
            return null;
        }

        // Try to find a sequence of matching non-empty lines in content
        for (int i = 0; i < contentLines.length; i++) {
            int searchIdx = 0;
            int matchStartLine = -1;
            int matchEndLine = -1;

            for (int j = i; j < contentLines.length && searchIdx < nonEmptySearchLines.size(); j++) {
                String trimmedContentLine = contentLines[j].trim();
                if (trimmedContentLine.isEmpty()) {
                    continue; // Skip empty lines in content
                }

                if (trimmedContentLine.equals(nonEmptySearchLines.get(searchIdx))) {
                    if (matchStartLine == -1) {
                        matchStartLine = j;
                    }
                    matchEndLine = j;
                    searchIdx++;
                } else {
                    break; // Sequence broken
                }
            }

            if (searchIdx == nonEmptySearchLines.size() && matchStartLine != -1) {
                // Found a complete match
                return new FuzzyMatchResult(matchStartLine, matchEndLine - matchStartLine + 1);
            }
        }
        return null;
    }

    /**
     * Finds a match using line similarity comparison.
     * This handles cases where the AI has small typos (like "andExpected" instead of "andExpect").
     * Uses Levenshtein distance with a threshold based on line length.
     */
    private FuzzyMatchResult findSimilarityMatch(String content, String search) {
        String[] contentLines = content.split("\n", -1);
        String[] searchLines = search.split("\n", -1);

        // Filter out empty search lines for matching
        List<String> nonEmptySearchLines = new ArrayList<>();
        for (String line : searchLines) {
            if (!line.trim().isEmpty()) {
                nonEmptySearchLines.add(line.trim());
            }
        }

        if (nonEmptySearchLines.isEmpty() || nonEmptySearchLines.size() < 3) {
            // Require at least 3 non-empty lines for similarity matching to avoid false positives
            return null;
        }

        // Try to find a sequence of similar lines in content
        for (int i = 0; i < contentLines.length; i++) {
            int searchIdx = 0;
            int matchStartLine = -1;
            int matchEndLine = -1;
            int totalMismatches = 0;

            for (int j = i; j < contentLines.length && searchIdx < nonEmptySearchLines.size(); j++) {
                String trimmedContentLine = contentLines[j].trim();
                if (trimmedContentLine.isEmpty()) {
                    continue; // Skip empty lines in content
                }

                String searchLine = nonEmptySearchLines.get(searchIdx);

                // Check if lines are similar enough
                if (arLinesSimilar(trimmedContentLine, searchLine)) {
                    if (matchStartLine == -1) {
                        matchStartLine = j;
                    }
                    matchEndLine = j;
                    searchIdx++;

                    // Count non-exact matches
                    if (!trimmedContentLine.equals(searchLine)) {
                        totalMismatches++;
                    }
                } else {
                    break; // Sequence broken
                }
            }

            // Found a complete match, but limit total mismatches to avoid false positives
            // Allow at most 30% of lines to have typos
            int maxAllowedMismatches = Math.max(2, nonEmptySearchLines.size() * 3 / 10);
            if (searchIdx == nonEmptySearchLines.size() && matchStartLine != -1
                    && totalMismatches <= maxAllowedMismatches) {
                return new FuzzyMatchResult(matchStartLine, matchEndLine - matchStartLine + 1);
            }
        }
        return null;
    }

    /**
     * Checks if two lines are similar enough to be considered a match.
     * Uses Levenshtein distance with a threshold based on line length.
     */
    private boolean arLinesSimilar(String line1, String line2) {
        if (line1.equals(line2)) {
            return true;
        }

        // For very short lines, require exact match
        if (line1.length() < 10 || line2.length() < 10) {
            return false;
        }

        int distance = levenshteinDistance(line1, line2);
        int maxLength = Math.max(line1.length(), line2.length());

        // Allow up to 10% difference, with minimum of 3 and maximum of 15 characters
        // This handles common typos like "andExpected" vs "andExpect" (distance=2)
        int threshold = Math.min(15, Math.max(3, maxLength / 10));

        return distance <= threshold;
    }

    /**
     * Calculates the Levenshtein distance between two strings.
     * This is the minimum number of single-character edits (insertions, deletions, substitutions)
     * required to change one string into the other.
     */
    private int levenshteinDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();

        // Use two rows instead of full matrix to save memory
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        // Initialize first row
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            // Swap rows
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }

        return prev[n];
    }

    /**
     * Applies a replacement using fuzzy match result.
     */
    private String applyFuzzyReplace(String content, FuzzyMatchResult match, String replace) {
        String[] lines = content.split("\n", -1);
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < match.startLine; i++) {
            result.append(lines[i]);
            result.append("\n");
        }

        result.append(replace);

        int afterMatch = match.startLine + match.lineCount;
        if (afterMatch < lines.length) {
            result.append("\n");
            for (int i = afterMatch; i < lines.length; i++) {
                result.append(lines[i]);
                if (i < lines.length - 1) {
                    result.append("\n");
                }
            }
        }

        return result.toString();
    }

    private record FuzzyMatchResult(int startLine, int lineCount) {}

    /**
     * Normalizes whitespace by trimming each line.
     */
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


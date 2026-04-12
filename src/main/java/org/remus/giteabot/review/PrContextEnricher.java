package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.RepositoryApiClient;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enriches pull request context for AI code review by gathering additional
 * information from the repository: full file contents, repository tree,
 * commit messages, and referenced issue content.
 */
@Slf4j
public class PrContextEnricher {

    static final int MAX_FILE_CONTENT_CHARS = 30000;
    static final int MAX_SINGLE_FILE_CHARS = 10000;
    static final int MAX_TREE_FILES = 500;
    static final int MAX_COMMIT_MESSAGES = 50;
    private static final Pattern ISSUE_REF_PATTERN = Pattern.compile(
            "(?:(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s+#(\\d+))|(?:#(\\d+))",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DIFF_FILE_PATTERN = Pattern.compile(
            "^diff --git a/(.+?) b/(.+?)$", Pattern.MULTILINE
    );

    private final RepositoryApiClient repositoryClient;

    public PrContextEnricher(RepositoryApiClient repositoryClient) {
        this.repositoryClient = repositoryClient;
    }

    /**
     * Builds enriched context for a pull request review.
     *
     * @param owner   repository owner
     * @param repo    repository name
     * @param prNumber pull request number
     * @param diff    the pull request diff
     * @param headRef the head branch/ref of the PR (for fetching file contents)
     * @param prBody  the pull request description (for extracting issue references)
     * @return formatted additional context string, or empty string if no context could be gathered
     */
    public String buildEnrichedContext(String owner, String repo, Long prNumber,
                                       String diff, String headRef, String prBody) {
        StringBuilder context = new StringBuilder();

        // 1. Repository tree
        String treeContext = buildRepositoryTreeContext(owner, repo, headRef);
        if (!treeContext.isEmpty()) {
            context.append(treeContext).append("\n");
        }

        // 2. Full file contents of changed files
        String fileContents = buildChangedFileContents(owner, repo, diff, headRef);
        if (!fileContents.isEmpty()) {
            context.append(fileContents).append("\n");
        }

        // 3. Commit messages
        String commitMessages = buildCommitMessagesContext(owner, repo, prNumber);
        if (!commitMessages.isEmpty()) {
            context.append(commitMessages).append("\n");
        }

        // 4. Referenced issue content
        String issueContext = buildReferencedIssueContext(owner, repo, prBody);
        if (!issueContext.isEmpty()) {
            context.append(issueContext).append("\n");
        }

        return context.toString();
    }

    /**
     * Builds a formatted repository tree context.
     */
    String buildRepositoryTreeContext(String owner, String repo, String ref) {
        try {
            List<Map<String, Object>> tree = repositoryClient.getRepositoryTree(owner, repo, ref);
            if (tree == null || tree.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder("**Repository structure:**\n```\n");
            int count = 0;
            for (Map<String, Object> entry : tree) {
                if (count >= MAX_TREE_FILES) {
                    sb.append("... (").append(tree.size() - count).append(" more files)\n");
                    break;
                }
                String type = (String) entry.getOrDefault("type", "blob");
                if ("blob".equals(type)) {
                    String path = (String) entry.getOrDefault("path", "");
                    sb.append("  ").append(path).append("\n");
                    count++;
                }
            }
            sb.append("```\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to fetch repository tree for {}/{}: {}", owner, repo, e.getMessage());
            return "";
        }
    }

    /**
     * Extracts changed file paths from a unified diff and fetches their full contents.
     */
    String buildChangedFileContents(String owner, String repo, String diff, String ref) {
        if (diff == null || diff.isBlank() || ref == null || ref.isBlank()) {
            return "";
        }

        List<String> changedFiles = extractChangedFilePaths(diff);
        if (changedFiles.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("**Changed files (complete content):**\n");
        int totalChars = 0;

        for (String filePath : changedFiles) {
            if (totalChars >= MAX_FILE_CONTENT_CHARS) {
                sb.append("\n... (remaining changed files omitted due to size limits)\n");
                break;
            }

            try {
                String content = repositoryClient.getFileContent(owner, repo, filePath, ref);
                if (content != null && !content.isEmpty()) {
                    if (content.length() > MAX_SINGLE_FILE_CHARS) {
                        content = content.substring(0, MAX_SINGLE_FILE_CHARS) + "\n... (truncated)";
                    }
                    sb.append("\n--- ").append(filePath).append(" ---\n");
                    sb.append("```\n").append(content).append("\n```\n");
                    totalChars += content.length();
                }
            } catch (Exception e) {
                log.debug("Could not fetch content for {}: {}", filePath, e.getMessage());
            }
        }

        return sb.toString();
    }

    /**
     * Builds commit messages context from the PR's commits.
     */
    String buildCommitMessagesContext(String owner, String repo, Long prNumber) {
        try {
            List<Map<String, Object>> commits = repositoryClient.getPullRequestCommits(owner, repo, prNumber);
            if (commits == null || commits.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder("**Commit messages:**\n");
            int count = 0;
            for (Map<String, Object> commit : commits) {
                if (count >= MAX_COMMIT_MESSAGES) {
                    sb.append("... (").append(commits.size() - count).append(" more commits)\n");
                    break;
                }
                String message = extractCommitMessage(commit);
                String sha = extractCommitSha(commit);
                if (message != null && !message.isBlank()) {
                    sb.append("- ");
                    if (sha != null && sha.length() >= 7) {
                        sb.append(sha, 0, 7).append(" ");
                    }
                    // Only include first line of commit message
                    String firstLine = message.lines().findFirst().orElse(message);
                    sb.append(firstLine).append("\n");
                    count++;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to fetch commits for PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage());
            return "";
        }
    }

    /**
     * Extracts issue references from the PR body and fetches their content.
     */
    String buildReferencedIssueContext(String owner, String repo, String prBody) {
        if (prBody == null || prBody.isBlank()) {
            return "";
        }

        Set<Long> issueNumbers = extractIssueReferences(prBody);
        if (issueNumbers.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("**Referenced issues:**\n");
        for (Long issueNumber : issueNumbers) {
            try {
                Map<String, Object> issue = repositoryClient.getIssueDetails(owner, repo, issueNumber);
                if (issue != null && !issue.isEmpty()) {
                    String title = (String) issue.getOrDefault("title", "");
                    String body = (String) issue.getOrDefault("body", "");
                    sb.append("\n#").append(issueNumber);
                    if (title != null && !title.isBlank()) {
                        sb.append(" - ").append(title);
                    }
                    sb.append("\n");
                    if (body != null && !body.isBlank()) {
                        sb.append(body).append("\n");
                    }
                }
            } catch (Exception e) {
                log.debug("Could not fetch issue #{}: {}", issueNumber, e.getMessage());
            }
        }

        return sb.toString();
    }

    /**
     * Extracts file paths from a unified diff format.
     * Parses lines like "diff --git a/path/to/file b/path/to/file".
     */
    List<String> extractChangedFilePaths(String diff) {
        Set<String> paths = new LinkedHashSet<>();
        Matcher matcher = DIFF_FILE_PATTERN.matcher(diff);
        while (matcher.find()) {
            // Use the "b" path (new file path)
            String bPath = matcher.group(2);
            if (bPath != null && !bPath.isBlank() && !"/dev/null".equals(bPath)) {
                paths.add(bPath);
            }
        }
        return new ArrayList<>(paths);
    }

    /**
     * Extracts issue number references from text.
     * Matches patterns like "#123", "fixes #123", "closes #456", "resolves #789".
     */
    Set<Long> extractIssueReferences(String text) {
        Set<Long> issueNumbers = new LinkedHashSet<>();
        Matcher matcher = ISSUE_REF_PATTERN.matcher(text);
        while (matcher.find()) {
            String number = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (number != null) {
                try {
                    issueNumbers.add(Long.parseLong(number));
                } catch (NumberFormatException e) {
                    // Ignore invalid numbers
                }
            }
        }
        return issueNumbers;
    }

    /**
     * Extracts the commit message from a commit map.
     * Handles different API response formats (GitHub/Gitea nest message under "commit").
     */
    @SuppressWarnings("unchecked")
    private String extractCommitMessage(Map<String, Object> commit) {
        // GitHub and Gitea: commit.commit.message
        if (commit.containsKey("commit") && commit.get("commit") instanceof Map) {
            Map<String, Object> inner = (Map<String, Object>) commit.get("commit");
            return (String) inner.get("message");
        }
        // GitLab and others: commit.message
        return (String) commit.get("message");
    }

    /**
     * Extracts the commit SHA from a commit map.
     */
    private String extractCommitSha(Map<String, Object> commit) {
        if (commit.containsKey("sha")) {
            return (String) commit.get("sha");
        }
        // GitLab uses "id" for commit SHA
        if (commit.containsKey("id")) {
            return (String) commit.get("id");
        }
        return null;
    }
}

package org.remus.giteabot.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.model.FileChange;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.validation.BuildValidationService;
import org.remus.giteabot.agent.validation.CodeValidationService;
import org.remus.giteabot.agent.validation.SyntaxValidator;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class IssueImplementationService {

    private static final String AGENT_PROMPT_NAME = "agent";
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*\\n(.*?)\\n\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_BLOCK_UNCLOSED_PATTERN = Pattern.compile("```json\\s*\\n(\\{.*)", Pattern.DOTALL);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("(\\{\\s*\"summary\"\\s*:.*)", Pattern.DOTALL);
    private static final int MAX_FILE_CONTENT_CHARS = 100000;  // Increased for more context
    private static final int MAX_TREE_FILES_FOR_CONTEXT = 500; // Show more files in tree

    private final GiteaApiClient giteaApiClient;
    private final AiClient aiClient;
    private final PromptService promptService;
    private final AgentConfigProperties agentConfig;
    private final AgentSessionService sessionService;
    private final CodeValidationService validationService;
    private final BuildValidationService buildValidationService;
    private final ObjectMapper objectMapper;

    public IssueImplementationService(GiteaApiClient giteaApiClient,
                                      AiClient aiClient, PromptService promptService,
                                      AgentConfigProperties agentConfig, AgentSessionService sessionService,
                                      CodeValidationService validationService,
                                      BuildValidationService buildValidationService) {
        this.giteaApiClient = giteaApiClient;
        this.aiClient = aiClient;
        this.promptService = promptService;
        this.agentConfig = agentConfig;
        this.sessionService = sessionService;
        this.validationService = validationService;
        this.buildValidationService = buildValidationService;
        this.objectMapper = new ObjectMapper();
    }

    @Async
    public void handleIssueAssigned(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        String repoFullName = payload.getRepository().getFullName();
        Long issueNumber = payload.getIssue().getNumber();
        String issueTitle = payload.getIssue().getTitle();
        String issueBody = payload.getIssue().getBody();

        log.info("Starting implementation for issue #{} '{}' in {}", issueNumber, issueTitle, repoFullName);

        // Check if there's already a session for this issue
        Optional<AgentSession> existingSession = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (existingSession.isPresent()) {
            log.info("Session already exists for issue #{}, skipping initial implementation", issueNumber);
            return;
        }

        // Create a new session
        AgentSession session = sessionService.createSession(owner, repo, issueNumber, issueTitle);

        String branchName = null;
        try {
            // Post initial progress comment
            giteaApiClient.postComment(owner, repo, issueNumber,
                    "🤖 **AI Agent**: I've been assigned to this issue. Analyzing repository structure...",
                    null);

            // Get default branch
            String defaultBranch = giteaApiClient.getDefaultBranch(owner, repo, null);
            log.info("Default branch for {}: {}", repoFullName, defaultBranch);

            // Fetch repository tree
            List<Map<String, Object>> tree = giteaApiClient.getRepositoryTree(owner, repo, defaultBranch, null);
            String treeContext = buildTreeContext(tree);

            // Get system prompt for agent
            String systemPrompt = promptService.getSystemPrompt(AGENT_PROMPT_NAME);

            // STEP 1: Ask AI which files it needs
            log.info("Step 1: Asking AI which files are needed for issue #{}", issueNumber);
            String fileRequestPrompt = buildFileRequestPrompt(issueTitle, issueBody, treeContext);
            sessionService.addMessage(session, "user", fileRequestPrompt);

            String fileRequestResponse = aiClient.chat(new ArrayList<>(), fileRequestPrompt, systemPrompt, null,
                    agentConfig.getMaxTokens());
            sessionService.addMessage(session, "assistant", fileRequestResponse);

            // Parse requested files
            List<String> requestedFiles = parseRequestedFiles(fileRequestResponse, tree);
            log.info("AI requested {} files for context", requestedFiles.size());

            // Fetch requested file contents
            String fileContext = fetchSpecificFiles(owner, repo, defaultBranch, requestedFiles);

            // STEP 2: Generate implementation with file context
            log.info("Step 2: Generating implementation for issue #{}", issueNumber);
            String implementationPrompt = buildImplementationPromptWithContext(issueTitle, issueBody, treeContext, fileContext);

            // Generate implementation with validation and iterative correction
            ImplementationPlan plan = generateValidatedImplementation(
                    session, implementationPrompt, systemPrompt, owner, repo, issueNumber, defaultBranch);

            if (plan == null || plan.getFileChanges() == null || plan.getFileChanges().isEmpty()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                giteaApiClient.postComment(owner, repo, issueNumber,
                        "🤖 **AI Agent**: I was unable to generate a valid implementation plan for this issue. " +
                        "The issue may be too complex or ambiguous for automated implementation.\n\n" +
                        "You can mention me in a comment to provide more details or clarification.",
                        null);
                return;
            }

            // Enforce max files limit
            if (plan.getFileChanges().size() > agentConfig.getMaxFiles()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                giteaApiClient.postComment(owner, repo, issueNumber,
                        String.format("🤖 **AI Agent**: The generated plan requires %d file changes, " +
                                "but the maximum allowed is %d. Please break this issue into smaller tasks.",
                                plan.getFileChanges().size(), agentConfig.getMaxFiles()),
                        null);
                return;
            }

            // Create branch name
            branchName = agentConfig.getBranchPrefix() + "issue-" + issueNumber;
            sessionService.setBranchName(session, branchName);

            // Create feature branch
            giteaApiClient.createBranch(owner, repo, branchName, defaultBranch, null);
            log.info("Created branch '{}' for issue #{}", branchName, issueNumber);

            // Commit file changes and track them in the session
            for (FileChange change : plan.getFileChanges()) {
                String commitMessage = String.format("agent: %s %s (issue #%d)",
                        change.getOperation().name().toLowerCase(), change.getPath(), issueNumber);

                switch (change.getOperation()) {
                    case CREATE -> giteaApiClient.createOrUpdateFile(owner, repo, change.getPath(),
                            change.getContent(), commitMessage, branchName, null, null);
                    case UPDATE -> {
                        String sha = giteaApiClient.getFileSha(owner, repo, change.getPath(), branchName, null);
                        giteaApiClient.createOrUpdateFile(owner, repo, change.getPath(),
                                change.getContent(), commitMessage, branchName, sha, null);
                    }
                    case DELETE -> {
                        String sha = giteaApiClient.getFileSha(owner, repo, change.getPath(), branchName, null);
                        giteaApiClient.deleteFile(owner, repo, change.getPath(),
                                commitMessage, branchName, sha, null);
                    }
                }

                // Record file change in session
                sessionService.addFileChange(session, change.getPath(), change.getOperation().name(), null);
            }

            // Create pull request
            String prTitle = String.format("AI Agent: %s (fixes #%d)", issueTitle, issueNumber);
            String prBody = buildPrBody(issueNumber, plan);
            Long prNumber = giteaApiClient.createPullRequest(owner, repo, prTitle, prBody,
                    branchName, defaultBranch, null);

            // Update session with PR number
            sessionService.setPrNumber(session, prNumber);

            // Comment on issue with link to PR
            String successComment = String.format(
                    "🤖 **AI Agent**: Implementation complete! I've created PR #%d with the following changes:\n\n" +
                    "**Summary**: %s\n\n" +
                    "**Files changed** (%d):\n%s\n\n" +
                    "Please review the changes carefully. If you need modifications, mention me in a comment " +
                    "on this issue and I'll continue working on it.",
                    prNumber, plan.getSummary(), plan.getFileChanges().size(),
                    plan.getFileChanges().stream()
                            .map(fc -> String.format("- `%s` (%s)", fc.getPath(), fc.getOperation()))
                            .collect(Collectors.joining("\n")));

            giteaApiClient.postComment(owner, repo, issueNumber, successComment, null);
            log.info("Successfully created PR #{} for issue #{} in {}", prNumber, issueNumber, repoFullName);

        } catch (Exception e) {
            log.error("Failed to implement issue #{} in {}: {}", issueNumber, repoFullName, e.getMessage(), e);

            sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);

            // Clean up branch on failure
            if (branchName != null) {
                try {
                    giteaApiClient.deleteBranch(owner, repo, branchName, null);
                } catch (Exception deleteError) {
                    log.warn("Failed to clean up branch '{}': {}", branchName, deleteError.getMessage());
                }
            }

            // Post failure comment
            try {
                giteaApiClient.postComment(owner, repo, issueNumber,
                        String.format("🤖 **AI Agent**: Implementation failed with error: `%s`\n\n" +
                                "The created branch has been cleaned up. You can mention me in a comment " +
                                "to try again with more details.",
                                e.getMessage()),
                        null);
            } catch (Exception commentError) {
                log.error("Failed to post failure comment on issue #{}: {}", issueNumber, commentError.getMessage());
            }
        }
    }

    /**
     * Generates implementation with validation and iterative correction.
     * If the generated code has syntax errors and validation is enabled,
     * the errors are sent back to the AI for correction.
     *
     * @return a valid ImplementationPlan, or null if generation/validation failed
     */
    private ImplementationPlan generateValidatedImplementation(
            AgentSession session, String userMessage, String systemPrompt,
            String owner, String repo, Long issueNumber, String defaultBranch) {

        int maxRetries = agentConfig.getValidation().isEnabled()
                ? agentConfig.getValidation().getMaxRetries()
                : 1;

        // Store initial user message in session
        sessionService.addMessage(session, "user", userMessage);

        String currentMessage = userMessage;
        List<AiMessage> conversationHistory = new ArrayList<>();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            log.info("Generating implementation for issue #{}, attempt {}/{}", issueNumber, attempt, maxRetries);

            // Call AI to generate implementation
            String aiResponse = aiClient.chat(conversationHistory, currentMessage, systemPrompt, null,
                    agentConfig.getMaxTokens());

            // Store AI response in session
            sessionService.addMessage(session, "assistant", aiResponse);

            // Parse AI response
            ImplementationPlan plan = parseAiResponse(aiResponse);
            if (plan == null || plan.getFileChanges() == null || plan.getFileChanges().isEmpty()) {
                log.warn("Failed to parse implementation plan on attempt {}", attempt);
                return null;
            }

            // If validation is disabled, return the plan as-is
            if (!agentConfig.getValidation().isEnabled()) {
                return plan;
            }

            // VALIDATION: Try build validation first, fall back to syntax validation
            String validationErrors = null;

            if (agentConfig.getValidation().isBuildEnabled()) {
                // Build validation - catches more errors
                BuildValidationService.BuildResult buildResult = buildValidationService.validateWithBuild(
                        owner, repo, defaultBranch, plan.getFileChanges());

                if (!buildResult.success()) {
                    validationErrors = buildResult.getErrorSummary();
                    log.info("Build validation failed on attempt {}: {}", attempt, buildResult.message());
                }
            } else {
                // Syntax validation only
                List<SyntaxValidator.ValidationError> errors = validationService.validateAll(plan.getFileChanges());
                if (!errors.isEmpty()) {
                    validationErrors = validationService.buildErrorReport(errors);
                    log.info("Syntax validation found {} error(s) on attempt {}/{}", errors.size(), attempt, maxRetries);
                }
            }

            if (validationErrors == null) {
                log.info("Generated code passed validation on attempt {}", attempt);
                return plan;
            }

            // If this was the last attempt, post a warning but still return the plan
            if (attempt >= maxRetries) {
                log.warn("Max validation retries reached, proceeding with code that has errors");

                // Notify user about validation issues
                String warningComment = String.format(
                        "⚠️ **AI Agent**: The generated code has errors that couldn't be " +
                        "automatically fixed after %d attempt(s).\n\n" +
                        "```\n%s\n```\n" +
                        "The code will still be committed for review, but may require manual fixes.",
                        maxRetries, validationErrors.length() > 2000
                                ? validationErrors.substring(0, 2000) + "..."
                                : validationErrors);

                try {
                    giteaApiClient.postComment(owner, repo, issueNumber, warningComment, null);
                } catch (Exception e) {
                    log.warn("Failed to post validation warning: {}", e.getMessage());
                }

                return plan;
            }

            // Build error feedback for the AI
            String errorFeedback = buildValidationErrorFeedback(validationErrors);

            // Update conversation for next iteration
            conversationHistory.add(AiMessage.builder()
                    .role("user")
                    .content(currentMessage)
                    .build());
            conversationHistory.add(AiMessage.builder()
                    .role("assistant")
                    .content(aiResponse)
                    .build());

            currentMessage = errorFeedback;
            sessionService.addMessage(session, "user", errorFeedback);
        }

        return null;
    }

    /**
     * Builds an error feedback message for the AI to fix validation errors.
     */
    private String buildValidationErrorFeedback(String errors) {
        return String.format("""
                ## Validation Failed
                ```
                %s
                ```
                Fix the errors. Output corrected JSON with complete file contents.
                """, errors);
    }

    /**
     * Handles a comment on an issue that mentions the bot.
     * This allows users to request changes or continue work after initial implementation.
     */
    @Async
    public void handleIssueComment(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        String repoFullName = payload.getRepository().getFullName();
        Long issueNumber = payload.getIssue().getNumber();
        Long commentId = payload.getComment().getId();
        String commentBody = payload.getComment().getBody();

        log.info("Handling agent comment #{} on issue #{} in {}", commentId, issueNumber, repoFullName);

        // Look up the session for this issue
        Optional<AgentSession> sessionOpt = sessionService.getSessionByIssue(owner, repo, issueNumber);
        if (sessionOpt.isEmpty()) {
            log.info("No agent session found for issue #{}, ignoring comment", issueNumber);
            return;
        }

        AgentSession session = sessionOpt.get();

        try {
            // Add reaction to acknowledge
            try {
                giteaApiClient.addReaction(owner, repo, commentId, "eyes", null);
            } catch (Exception e) {
                log.warn("Failed to add reaction to comment #{}: {}", commentId, e.getMessage());
            }

            // Update session status
            sessionService.setStatus(session, AgentSession.AgentSessionStatus.UPDATING);

            // Get current branch and default branch
            String branchName = session.getBranchName();
            String defaultBranch = giteaApiClient.getDefaultBranch(owner, repo, null);
            String workingBranch = branchName != null ? branchName : defaultBranch;

            // Build user message - AI already has context from conversation history
            String userMessage = buildContinuationPrompt(commentBody);

            // Store user message in session
            sessionService.addMessage(session, "user", userMessage);

            // Get conversation history
            List<AiMessage> history = sessionService.toAiMessages(session);

            // Get system prompt
            String systemPrompt = promptService.getSystemPrompt(AGENT_PROMPT_NAME);

            // Call AI
            log.info("Requesting AI to continue implementation for issue #{}", issueNumber);
            String aiResponse = aiClient.chat(history.subList(0, history.size() - 1), userMessage,
                    systemPrompt, null, agentConfig.getMaxTokens());

            // Store AI response in session
            sessionService.addMessage(session, "assistant", aiResponse);

            // Parse AI response
            ImplementationPlan plan = parseAiResponse(aiResponse);
            if (plan == null || plan.getFileChanges() == null || plan.getFileChanges().isEmpty()) {
                // AI responded but no code changes - post the response as a comment
                giteaApiClient.postComment(owner, repo, issueNumber,
                        "🤖 **AI Agent**: " + extractNonJsonResponse(aiResponse),
                        null);
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.PR_CREATED);
                return;
            }

            // Apply the new changes
            if (branchName == null) {
                // Create a new branch if we don't have one
                branchName = agentConfig.getBranchPrefix() + "issue-" + issueNumber;
                giteaApiClient.createBranch(owner, repo, branchName, defaultBranch, null);
                sessionService.setBranchName(session, branchName);
            }

            for (FileChange change : plan.getFileChanges()) {
                String commitMessage = String.format("agent: %s %s (issue #%d, follow-up)",
                        change.getOperation().name().toLowerCase(), change.getPath(), issueNumber);

                switch (change.getOperation()) {
                    case CREATE -> giteaApiClient.createOrUpdateFile(owner, repo, change.getPath(),
                            change.getContent(), commitMessage, branchName, null, null);
                    case UPDATE -> {
                        String sha = giteaApiClient.getFileSha(owner, repo, change.getPath(), branchName, null);
                        giteaApiClient.createOrUpdateFile(owner, repo, change.getPath(),
                                change.getContent(), commitMessage, branchName, sha, null);
                    }
                    case DELETE -> {
                        String sha = giteaApiClient.getFileSha(owner, repo, change.getPath(), branchName, null);
                        giteaApiClient.deleteFile(owner, repo, change.getPath(),
                                commitMessage, branchName, sha, null);
                    }
                }

                // Record file change in session
                sessionService.addFileChange(session, change.getPath(), change.getOperation().name(), null);
            }

            // Create PR if we don't have one yet
            if (session.getPrNumber() == null) {
                String prTitle = String.format("AI Agent: %s (fixes #%d)", session.getIssueTitle(), issueNumber);
                String prBody = buildPrBody(issueNumber, plan);
                Long prNumber = giteaApiClient.createPullRequest(owner, repo, prTitle, prBody,
                        branchName, defaultBranch, null);
                sessionService.setPrNumber(session, prNumber);
            }

            // Update session status
            sessionService.setStatus(session, AgentSession.AgentSessionStatus.PR_CREATED);

            // Post success comment
            String updateComment = String.format(
                    "🤖 **AI Agent**: I've made the following additional changes:\n\n" +
                    "**Summary**: %s\n\n" +
                    "**Files changed** (%d):\n%s\n\n" +
                    "The changes have been pushed to PR #%d.",
                    plan.getSummary(), plan.getFileChanges().size(),
                    plan.getFileChanges().stream()
                            .map(fc -> String.format("- `%s` (%s)", fc.getPath(), fc.getOperation()))
                            .collect(Collectors.joining("\n")),
                    session.getPrNumber());

            giteaApiClient.postComment(owner, repo, issueNumber, updateComment, null);
            log.info("Successfully applied follow-up changes for issue #{}", issueNumber);

        } catch (Exception e) {
            log.error("Failed to handle comment on issue #{}: {}", issueNumber, e.getMessage(), e);

            try {
                giteaApiClient.postComment(owner, repo, issueNumber,
                        String.format("🤖 **AI Agent**: Failed to process your request: `%s`\n\n" +
                                "Please try again or provide more details.",
                                e.getMessage()),
                        null);
            } catch (Exception commentError) {
                log.error("Failed to post error comment on issue #{}: {}", issueNumber, commentError.getMessage());
            }

            // Restore previous status if we were updating
            if (session.getStatus() == AgentSession.AgentSessionStatus.UPDATING) {
                sessionService.setStatus(session,
                        session.getPrNumber() != null ? AgentSession.AgentSessionStatus.PR_CREATED
                                                      : AgentSession.AgentSessionStatus.FAILED);
            }
        }
    }

    /**
     * Builds a prompt asking the AI which files it needs to see for the task.
     */
    private String buildFileRequestPrompt(String issueTitle, String issueBody, String treeContext) {
        return String.format("""
                ## Issue
                **Title**: %s
                **Description**: %s
                
                ## Repository Files
                %s
                
                Which files do you need to see? Output JSON:
                ```json
                {"reasoning": "...", "requestedFiles": ["path/file1", "path/file2"]}
                ```
                Request max 20 files (files to modify, related interfaces/DTOs, configs).
                """, issueTitle, issueBody != null ? issueBody : "(none)", treeContext);
    }

    /**
     * Parses the AI's response for requested files.
     */
    private List<String> parseRequestedFiles(String aiResponse, List<Map<String, Object>> tree) {
        List<String> requestedFiles = new ArrayList<>();

        // Build set of valid paths
        java.util.Set<String> validPaths = new java.util.HashSet<>();
        for (Map<String, Object> entry : tree) {
            String path = (String) entry.getOrDefault("path", "");
            String type = (String) entry.getOrDefault("type", "blob");
            if ("blob".equals(type)) {
                validPaths.add(path);
            }
        }

        // Try to extract JSON from response
        String jsonStr = extractJsonFromResponse(aiResponse);
        if (jsonStr != null) {
            try {
                FileRequestResponse response = objectMapper.readValue(jsonStr, FileRequestResponse.class);
                if (response != null && response.getRequestedFiles() != null) {
                    for (String file : response.getRequestedFiles()) {
                        if (validPaths.contains(file)) {
                            requestedFiles.add(file);
                        } else {
                            log.debug("Requested file not found in tree: {}", file);
                        }
                    }
                }
            } catch (JacksonException e) {
                log.warn("Failed to parse file request response: {}", e.getMessage());
            }
        }

        // If parsing failed, fall back to pattern matching
        if (requestedFiles.isEmpty()) {
            for (String path : validPaths) {
                if (aiResponse.contains(path)) {
                    requestedFiles.add(path);
                }
            }
        }

        // Limit to 30 files
        if (requestedFiles.size() > 30) {
            requestedFiles = requestedFiles.subList(0, 30);
        }

        return requestedFiles;
    }

    /**
     * Fetches specific file contents from the repository.
     */
    private String fetchSpecificFiles(String owner, String repo, String ref, List<String> filePaths) {
        StringBuilder sb = new StringBuilder();
        int totalChars = 0;

        for (String path : filePaths) {
            if (totalChars > MAX_FILE_CONTENT_CHARS) {
                sb.append("\n(File context truncated due to size limits)\n");
                break;
            }
            try {
                String content = giteaApiClient.getFileContent(owner, repo, path, ref, null);
                if (content != null && !content.isEmpty()) {
                    sb.append("\n--- File: ").append(path).append(" ---\n");
                    sb.append(content).append("\n");
                    totalChars += content.length();
                }
            } catch (Exception e) {
                log.debug("Could not fetch file content for {}: {}", path, e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * Builds the implementation prompt with the file context provided.
     */
    private String buildImplementationPromptWithContext(String issueTitle, String issueBody,
                                                         String treeContext, String fileContext) {
        return String.format("""
                ## Issue
                **Title**: %s
                **Description**: %s
                
                ## Repository
                %s
                
                ## File Contents
                %s
                
                Implement the issue. Output JSON per system prompt format.
                """, issueTitle, issueBody != null ? issueBody : "(none)", treeContext, fileContext);
    }

    private String buildContinuationPrompt(String userComment) {
        return userComment;
    }

    private String extractNonJsonResponse(String aiResponse) {
        // Try to extract the text before any JSON block
        int jsonStart = aiResponse.indexOf("```json");
        if (jsonStart > 0) {
            return aiResponse.substring(0, jsonStart).strip();
        }

        // If no JSON block, check if it looks like JSON
        if (aiResponse.strip().startsWith("{")) {
            return "I've analyzed your request but couldn't generate a specific response.";
        }

        return aiResponse;
    }

    String buildTreeContext(List<Map<String, Object>> tree) {
        if (tree == null || tree.isEmpty()) {
            return "No files found in repository.";
        }
        StringBuilder sb = new StringBuilder("Repository file tree:\n");
        int count = 0;
        for (Map<String, Object> entry : tree) {
            if (count >= MAX_TREE_FILES_FOR_CONTEXT) {
                sb.append("... (truncated, ").append(tree.size() - count).append(" more files)\n");
                break;
            }
            String type = (String) entry.getOrDefault("type", "blob");
            String path = (String) entry.getOrDefault("path", "");
            if ("blob".equals(type)) {
                sb.append("  ").append(path).append("\n");
            }
            count++;
        }
        return sb.toString();
    }

    String fetchRelevantFileContents(String owner, String repo, String ref,
                                             List<Map<String, Object>> tree,
                                             String issueTitle, String issueBody) {
        // Build a map of all file paths for quick lookup
        Map<String, Boolean> allPaths = new java.util.HashMap<>();
        for (Map<String, Object> entry : tree) {
            String path = (String) entry.getOrDefault("path", "");
            String type = (String) entry.getOrDefault("type", "blob");
            if ("blob".equals(type)) {
                allPaths.put(path, true);
            }
        }

        // Pick source files mentioned in the issue or common configuration files
        List<String> relevantPaths = new ArrayList<>();
        java.util.Set<String> relevantPackages = new java.util.HashSet<>();
        String issueLower = (issueTitle + " " + (issueBody != null ? issueBody : "")).toLowerCase();

        for (Map<String, Object> entry : tree) {
            String path = (String) entry.getOrDefault("path", "");
            String type = (String) entry.getOrDefault("type", "blob");
            if (!"blob".equals(type)) continue;

            // Include files explicitly mentioned in the issue
            if (issueLower.contains(path.toLowerCase())) {
                relevantPaths.add(path);
                // Track the package of mentioned Java files
                if (path.endsWith(".java")) {
                    String packagePath = getPackagePath(path);
                    if (packagePath != null) {
                        relevantPackages.add(packagePath);
                    }
                }
                continue;
            }

            // Check if any part of the path is mentioned (e.g., "Task" matches "Task.java")
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            String fileNameWithoutExt = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            if (fileNameWithoutExt.length() > 3 && issueLower.contains(fileNameWithoutExt.toLowerCase())) {
                relevantPaths.add(path);
                if (path.endsWith(".java")) {
                    String packagePath = getPackagePath(path);
                    if (packagePath != null) {
                        relevantPackages.add(packagePath);
                    }
                }
                continue;
            }

            // Include key configuration files
            if (path.endsWith("pom.xml") || path.endsWith("build.gradle")
                    || path.equals("README.md") || path.endsWith("application.properties")) {
                relevantPaths.add(path);
            }
        }

        // Add sibling files from relevant packages (for context on existing code structure)
        for (String packagePath : relevantPackages) {
            for (String path : allPaths.keySet()) {
                if (path.startsWith(packagePath) && path.endsWith(".java") && !relevantPaths.contains(path)) {
                    relevantPaths.add(path);
                }
            }
        }

        // Also include common domain/model/entity files that might define base classes
        for (String path : allPaths.keySet()) {
            if (path.endsWith(".java") && !relevantPaths.contains(path)) {
                String lower = path.toLowerCase();
                // Include likely interface, base class, or configuration files
                if (lower.contains("/domain/") || lower.contains("/model/") ||
                    lower.contains("/entity/") || lower.contains("/config/") ||
                    lower.contains("/dto/") || lower.contains("/repository/") ||
                    lower.contains("/service/") || lower.contains("/controller/")) {
                    // Check if file name matches something in the issue
                    String fileName = path.substring(path.lastIndexOf('/') + 1);
                    String baseName = fileName.replace(".java", "").toLowerCase();
                    if (issueLower.contains(baseName)) {
                        relevantPaths.add(path);
                    }
                }
            }
        }

        // Limit to a reasonable number but higher than before
        if (relevantPaths.size() > 30) {
            relevantPaths = relevantPaths.subList(0, 30);
        }

        log.debug("Fetching {} relevant files for context: {}", relevantPaths.size(), relevantPaths);

        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        for (String path : relevantPaths) {
            if (totalChars > MAX_FILE_CONTENT_CHARS) {
                sb.append("\n(File context truncated due to size limits)\n");
                break;
            }
            try {
                String content = giteaApiClient.getFileContent(owner, repo, path, ref, null);
                if (content != null && !content.isEmpty()) {
                    sb.append("\n--- File: ").append(path).append(" ---\n");
                    sb.append(content).append("\n");
                    totalChars += content.length();
                }
            } catch (Exception e) {
                log.debug("Could not fetch file content for {}: {}", path, e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * Extracts the package path from a Java file path.
     * E.g., "src/main/java/com/example/task/domain/Task.java" -> "src/main/java/com/example/task/domain/"
     */
    private String getPackagePath(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        if (lastSlash > 0) {
            return filePath.substring(0, lastSlash + 1);
        }
        return null;
    }


    ImplementationPlan parseAiResponse(String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) {
            log.warn("Empty AI response");
            return null;
        }

        String jsonStr = extractJsonFromResponse(aiResponse);
        if (jsonStr == null) {
            log.warn("Could not extract JSON from AI response");
            return null;
        }

        // Try to repair truncated JSON if necessary
        jsonStr = repairTruncatedJson(jsonStr);

        try {
            AiImplementationResponse response = objectMapper.readValue(jsonStr, AiImplementationResponse.class);
            if (response == null || response.getFileChanges() == null) {
                log.warn("Parsed AI response has no file changes");
                return null;
            }

            List<FileChange> fileChanges = response.getFileChanges().stream()
                    .map(fc -> FileChange.builder()
                            .path(fc.getPath())
                            .content(fc.getContent() != null ? fc.getContent() : "")
                            .operation(parseOperation(fc.getOperation()))
                            .build())
                    .toList();

            return ImplementationPlan.builder()
                    .summary(response.getSummary())
                    .fileChanges(fileChanges)
                    .build();
        } catch (JacksonException e) {
            log.error("Failed to parse AI response as JSON: {}", e.getMessage());
            log.debug("JSON content that failed to parse: {}", jsonStr);
            return null;
        }
    }

    /**
     * Extracts JSON from the AI response using multiple strategies.
     */
    String extractJsonFromResponse(String aiResponse) {
        // Strategy 1: Look for properly closed ```json ... ``` block
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(aiResponse);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }

        // Strategy 2: Look for unclosed ```json block (truncated response)
        matcher = JSON_BLOCK_UNCLOSED_PATTERN.matcher(aiResponse);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }

        // Strategy 3: Look for JSON object starting with {"summary":
        matcher = JSON_OBJECT_PATTERN.matcher(aiResponse);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }

        // Strategy 4: Try to find any JSON object in the response
        int jsonStart = aiResponse.indexOf('{');
        if (jsonStart >= 0) {
            return aiResponse.substring(jsonStart).strip();
        }

        return null;
    }

    /**
     * Attempts to repair truncated JSON by closing open structures.
     * This is a best-effort approach for handling incomplete AI responses.
     */
    String repairTruncatedJson(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }

        // Remove any trailing incomplete content after an unclosed string
        // Look for the last complete file change entry
        int lastCompleteObject = findLastCompleteFileChange(json);
        if (lastCompleteObject > 0 && lastCompleteObject < json.length() - 10) {
            json = json.substring(0, lastCompleteObject);
        }

        // Count brackets to check if JSON is complete
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        char prevChar = 0;

        for (char c : json.toCharArray()) {
            if (c == '"' && prevChar != '\\') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') braces++;
                else if (c == '}') braces--;
                else if (c == '[') brackets++;
                else if (c == ']') brackets--;
            }
            prevChar = c;
        }

        // If unbalanced, try to close the structures
        if (braces > 0 || brackets > 0 || inString) {
            StringBuilder repaired = new StringBuilder(json);

            // Close unclosed string
            if (inString) {
                repaired.append("\"");
            }

            // Close brackets and braces
            while (brackets > 0) {
                repaired.append("]");
                brackets--;
            }
            while (braces > 0) {
                repaired.append("}");
                braces--;
            }

            return repaired.toString();
        }

        return json;
    }

    /**
     * Finds the position after the last complete fileChange object in the JSON.
     */
    private int findLastCompleteFileChange(String json) {
        // Look for the pattern: }] or }, followed by valid JSON continuation
        // This finds the last position where a fileChange entry was complete
        int lastComplete = -1;
        int searchFrom = 0;

        while (true) {
            // Find closing of a fileChange object
            int closeBrace = json.indexOf('}', searchFrom);
            if (closeBrace < 0) break;

            // Check if followed by ] (end of array) or , (next entry)
            int nextNonWhitespace = closeBrace + 1;
            while (nextNonWhitespace < json.length() &&
                   Character.isWhitespace(json.charAt(nextNonWhitespace))) {
                nextNonWhitespace++;
            }

            if (nextNonWhitespace < json.length()) {
                char nextChar = json.charAt(nextNonWhitespace);
                if (nextChar == ']' || nextChar == ',') {
                    lastComplete = nextNonWhitespace + 1;
                }
            }

            searchFrom = closeBrace + 1;
        }

        return lastComplete;
    }

    private FileChange.Operation parseOperation(String operation) {
        if (operation == null) return FileChange.Operation.CREATE;
        return switch (operation.toUpperCase()) {
            case "UPDATE" -> FileChange.Operation.UPDATE;
            case "DELETE" -> FileChange.Operation.DELETE;
            default -> FileChange.Operation.CREATE;
        };
    }

    private String buildPrBody(Long issueNumber, ImplementationPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Fixes #%d%n%n", issueNumber));
        sb.append("## Summary\n\n");
        sb.append(plan.getSummary()).append("\n\n");
        sb.append("## Changes\n\n");
        for (FileChange fc : plan.getFileChanges()) {
            sb.append(String.format("- **%s**: `%s`%n", fc.getOperation(), fc.getPath()));
        }
        sb.append("\n---\n");
        sb.append("*This PR was automatically generated by the AI implementation agent. Please review carefully before merging.*\n");
        return sb.toString();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiImplementationResponse {
        private String summary;
        private List<AiFileChange> fileChanges;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiFileChange {
        private String path;
        private String operation;
        private String content;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FileRequestResponse {
        private String reasoning;
        private List<String> requestedFiles;
    }
}

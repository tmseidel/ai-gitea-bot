package org.remus.giteabot.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.model.FileChange;
import org.remus.giteabot.agent.model.ImplementationPlan;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core issue-implementation (agent) business logic.  Not a Spring-managed
 * singleton — instances are created per-bot by
 * {@link org.remus.giteabot.admin.BotWebhookService} with the bot's own
 * {@link AiClient} and {@link RepositoryApiClient}.
 */
@Slf4j
public class IssueImplementationService {

    private static final String AGENT_PROMPT_NAME = "agent";
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*\\n(.*?)\\n\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_BLOCK_UNCLOSED_PATTERN = Pattern.compile("```json\\s*\\n(\\{.*)", Pattern.DOTALL);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("(\\{\\s*\"summary\"\\s*:.*)", Pattern.DOTALL);
    private static final int MAX_FILE_CONTENT_CHARS = 100000;  // Increased for more context
    private static final int MAX_TREE_FILES_FOR_CONTEXT = 500; // Show more files in tree

    private final RepositoryApiClient repositoryClient;
    private final AiClient aiClient;
    private final PromptService promptService;
    private final AgentConfigProperties agentConfig;
    private final AgentSessionService sessionService;
    private final ToolExecutionService toolExecutionService;
    private final DiffApplyService diffApplyService;
    private final ObjectMapper objectMapper;

    public IssueImplementationService(RepositoryApiClient repositoryClient,
                                      AiClient aiClient, PromptService promptService,
                                      AgentConfigProperties agentConfig, AgentSessionService sessionService,
                                      ToolExecutionService toolExecutionService,
                                      DiffApplyService diffApplyService) {
        this.repositoryClient = repositoryClient;
        this.aiClient = aiClient;
        this.promptService = promptService;
        this.agentConfig = agentConfig;
        this.sessionService = sessionService;
        this.toolExecutionService = toolExecutionService;
        this.diffApplyService = diffApplyService;
        this.objectMapper = new ObjectMapper();
    }

    public void handleIssueAssigned(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        String repoFullName = payload.getRepository().getFullName();
        Long issueNumber = payload.getIssue().getNumber();
        String issueTitle = payload.getIssue().getTitle();
        String issueBody = payload.getIssue().getBody();
        String issueRef = normalizeBranchRef(payload.getIssue().getRef());

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
            repositoryClient.postComment(owner, repo, issueNumber,
                    "🤖 **AI Agent**: I've been assigned to this issue. Analyzing repository structure...");

            // Determine base branch: use issue ref if set, otherwise default branch
            String baseBranch;
            if (issueRef != null && !issueRef.isBlank()) {
                baseBranch = issueRef;
                log.info("Using issue branch '{}' as base for issue #{}", baseBranch, issueNumber);
            } else {
                baseBranch = repositoryClient.getDefaultBranch(owner, repo);
                log.info("No issue branch set, using default branch '{}' for issue #{}", baseBranch, issueNumber);
            }

            // Fetch repository tree
            List<Map<String, Object>> tree = repositoryClient.getRepositoryTree(owner, repo, baseBranch);
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
            String fileContext = fetchSpecificFiles(owner, repo, baseBranch, requestedFiles);

            // STEP 2: Generate implementation with file context
            log.info("Step 2: Generating implementation for issue #{}", issueNumber);
            String implementationPrompt = buildImplementationPromptWithContext(issueTitle, issueBody, treeContext, fileContext);

            // Generate implementation with validation and iterative correction
            ImplementationPlan plan = generateValidatedImplementation(
                    session, implementationPrompt, systemPrompt, owner, repo, issueNumber, baseBranch);

            if (plan == null || plan.getFileChanges() == null || plan.getFileChanges().isEmpty()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postComment(owner, repo, issueNumber,
                        "🤖 **AI Agent**: I was unable to generate a valid implementation plan for this issue. " +
                        "The issue may be too complex or ambiguous for automated implementation.\n\n" +
                        "You can mention me in a comment to provide more details or clarification.");
                return;
            }

            // Enforce max files limit
            if (plan.getFileChanges().size() > agentConfig.getMaxFiles()) {
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);
                repositoryClient.postComment(owner, repo, issueNumber,
                        String.format("🤖 **AI Agent**: The generated plan requires %d file changes, " +
                                "but the maximum allowed is %d. Please break this issue into smaller tasks.",
                                plan.getFileChanges().size(), agentConfig.getMaxFiles()));
                return;
            }

            // Create branch name
            branchName = agentConfig.getBranchPrefix() + "issue-" + issueNumber;
            sessionService.setBranchName(session, branchName);

            // Create feature branch from base branch
            repositoryClient.createBranch(owner, repo, branchName, baseBranch);
            log.info("Created branch '{}' from '{}' for issue #{}", branchName, baseBranch, issueNumber);

            // Commit file changes and track them in the session
            for (FileChange change : plan.getFileChanges()) {
                String commitMessage = String.format("agent: %s %s (issue #%d)",
                        change.getOperation().name().toLowerCase(), change.getPath(), issueNumber);

                applyFileChange(owner, repo, branchName, change, commitMessage, session);

                // Record file change in session
                sessionService.addFileChange(session, change.getPath(), change.getOperation().name(), null);
            }

            // Create pull request targeting the base branch
            String prTitle = String.format("AI Agent: %s (fixes #%d)", issueTitle, issueNumber);
            String prBody = buildPrBody(issueNumber, plan);
            Long prNumber = repositoryClient.createPullRequest(owner, repo, prTitle, prBody,
                    branchName, baseBranch);

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

            repositoryClient.postComment(owner, repo, issueNumber, successComment);
            log.info("Successfully created PR #{} for issue #{} in {}", prNumber, issueNumber, repoFullName);

        } catch (Exception e) {
            log.error("Failed to implement issue #{} in {}: {}", issueNumber, repoFullName, e.getMessage(), e);

            sessionService.setStatus(session, AgentSession.AgentSessionStatus.FAILED);

            // Clean up branch on failure
            if (branchName != null) {
                try {
                    repositoryClient.deleteBranch(owner, repo, branchName);
                } catch (Exception deleteError) {
                    log.warn("Failed to clean up branch '{}': {}", branchName, deleteError.getMessage());
                }
            }

            // Post failure comment
            try {
                repositoryClient.postComment(owner, repo, issueNumber,
                        String.format("🤖 **AI Agent**: Implementation failed with error: `%s`\n\n" +
                                "The created branch has been cleaned up. You can mention me in a comment " +
                                "to try again with more details.",
                                e.getMessage()));
            } catch (Exception commentError) {
                log.error("Failed to post failure comment on issue #{}: {}", issueNumber, commentError.getMessage());
            }
        }
    }

    /**
     * Generates implementation with AI-driven validation using external tools.
     * The AI decides which tools to run for validation and can iterate on errors.
     *
     * @return a valid ImplementationPlan, or null if generation/validation failed
     */
    private ImplementationPlan generateValidatedImplementation(
            AgentSession session, String userMessage, String systemPrompt,
            String owner, String repo, Long issueNumber, String defaultBranch) {

        int maxRetries = agentConfig.getValidation().isEnabled()
                ? agentConfig.getValidation().getMaxRetries()
                : 1;
        int maxFileRequestRounds = 3;
        int maxToolExecutions = agentConfig.getValidation().getMaxToolExecutions();

        // Add available tools info to the initial message
        List<String> availableTools = toolExecutionService.getAvailableTools();
        String toolsInfo = "\n\n**Available validation tools**: " + String.join(", ", availableTools);
        userMessage = userMessage + toolsInfo;

        // Store initial user message in session
        sessionService.addMessage(session, "user", userMessage);

        String currentMessage = userMessage;
        List<AiMessage> conversationHistory = new ArrayList<>();
        int fileRequestRounds = 0;
        int toolExecutions = 0;
        Path workspaceDir = null;
        ImplementationPlan lastValidPlan = null;

        try {
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                log.info("Generating implementation for issue #{}, attempt {}/{}", issueNumber, attempt, maxRetries);

                // Call AI to generate implementation
                String aiResponse = aiClient.chat(conversationHistory, currentMessage, systemPrompt, null,
                        agentConfig.getMaxTokens());

                // Store AI response in session
                sessionService.addMessage(session, "assistant", aiResponse);

                // Post the AI's reasoning as a comment (excluding JSON)
                postAiThinkingComment(owner, repo, issueNumber, aiResponse);

                // Parse AI response
                ImplementationPlan plan = parseAiResponse(aiResponse);
                if (plan == null) {
                    log.warn("Failed to parse implementation plan on attempt {}", attempt);
                    return lastValidPlan;
                }

                // Handle file requests - AI wants to see more files before implementing
                if (plan.hasFileRequests() && !plan.hasFileChanges() && fileRequestRounds < maxFileRequestRounds) {
                    fileRequestRounds++;
                    log.info("AI requesting {} additional files (round {}/{})",
                            plan.getRequestFiles().size(), fileRequestRounds, maxFileRequestRounds);

                    String fileContext = fetchSpecificFiles(owner, repo, defaultBranch, plan.getRequestFiles());
                    String filesMessage = "Here are the requested files:\n" + fileContext +
                            "\n\nNow implement the issue. Output JSON with fileChanges and runTool for validation.";

                    conversationHistory.add(AiMessage.builder().role("user").content(currentMessage).build());
                    conversationHistory.add(AiMessage.builder().role("assistant").content(aiResponse).build());

                    currentMessage = filesMessage;
                    sessionService.addMessage(session, "user", filesMessage);
                    attempt--;
                    continue;
                }

                // If we have file changes, save them as the last valid plan
                if (plan.hasFileChanges()) {
                    lastValidPlan = plan;
                }

                // If no file changes and no tool request, fail
                if (!plan.hasFileChanges() && !plan.hasToolRequest()) {
                    log.warn("No file changes in implementation plan on attempt {}", attempt);
                    return lastValidPlan;
                }

                // If validation is disabled, return the plan as-is
                if (!agentConfig.getValidation().isEnabled()) {
                    return plan;
                }

                // Handle tool execution for AI-driven validation
                if (plan.hasToolRequest() && plan.hasFileChanges() && toolExecutions < maxToolExecutions) {
                    toolExecutions++;

                    // Prepare workspace if not already done
                    if (workspaceDir == null) {
                        ToolExecutionService.WorkspaceResult workspaceResult = toolExecutionService.prepareWorkspace(owner, repo, defaultBranch, plan.getFileChanges(), repositoryClient.getCloneUrl(), repositoryClient.getToken());
                        if (!workspaceResult.success()) {
                            log.error("Failed to prepare workspace for validation: {}", workspaceResult.error());
                            // Post error as comment so it's visible
                            repositoryClient.postComment(owner, repo, issueNumber,
                                    "⚠️ **Workspace preparation failed**\n\n" + workspaceResult.error());
                            return plan; // Return plan without validation
                        }
                        workspaceDir = workspaceResult.workspacePath();
                    } else {
                        // Update existing workspace with new file changes
                        for (FileChange change : plan.getFileChanges()) {
                            Path filePath = workspaceDir.resolve(change.getPath());
                            try {
                                switch (change.getOperation()) {
                                    case CREATE, UPDATE -> {
                                        java.nio.file.Files.createDirectories(filePath.getParent());
                                        java.nio.file.Files.writeString(filePath, change.getContent());
                                    }
                                    case DELETE -> java.nio.file.Files.deleteIfExists(filePath);
                                }
                            } catch (Exception e) {
                                log.warn("Failed to update workspace file {}: {}", change.getPath(), e.getMessage());
                            }
                        }
                    }

                    // Execute the tool
                    ImplementationPlan.ToolRequest toolRequest = plan.getToolRequest();
                    log.info("AI requested tool execution: {} {}", toolRequest.getTool(),
                            toolRequest.getArgs() != null ? String.join(" ", toolRequest.getArgs()) : "");

                    ToolExecutionService.ToolResult result = toolExecutionService.executeTool(
                            workspaceDir, toolRequest.getTool(), toolRequest.getArgs());

                    // Build feedback message for AI
                    String toolFeedback = buildToolFeedback(toolRequest, result);

                    // Post tool result as comment
                    postToolResultComment(owner, repo, issueNumber, toolRequest, result);

                    // If tool succeeded, we're done
                    if (result.success()) {
                        log.info("Validation tool succeeded on attempt {}", attempt);
                        return plan;
                    }

                    // Tool failed - send feedback to AI for fixing
                    // IMPORTANT: Include all previously successful file changes in the feedback
                    // so the AI knows which changes to preserve when fixing
                    String previousChangesInfo = buildPreviousChangesInfo(lastValidPlan);
                    String toolFeedbackWithContext = toolFeedback + previousChangesInfo;

                    conversationHistory.add(AiMessage.builder().role("user").content(currentMessage).build());
                    conversationHistory.add(AiMessage.builder().role("assistant").content(aiResponse).build());

                    currentMessage = toolFeedbackWithContext;
                    sessionService.addMessage(session, "user", toolFeedbackWithContext);

                    // Store reference to preserve changes from this iteration
                    ImplementationPlan previousPlan = plan;

                    // Get next response and parse it
                    aiResponse = aiClient.chat(conversationHistory, currentMessage, systemPrompt, null,
                            agentConfig.getMaxTokens());
                    sessionService.addMessage(session, "assistant", aiResponse);
                    postAiThinkingComment(owner, repo, issueNumber, aiResponse);

                    plan = parseAiResponse(aiResponse);
                    if (plan != null && plan.hasFileChanges()) {
                        // Merge: If AI didn't include all previous files, add the missing ones
                        plan = mergeFileChanges(previousPlan, plan);
                        lastValidPlan = plan;
                    } else if (lastValidPlan != null) {
                        // AI couldn't provide fixes, return last valid plan
                        return lastValidPlan;
                    }
                    continue;
                }

                // No tool request but has file changes - ask AI to provide a validation tool
                if (plan.hasFileChanges() && !plan.hasToolRequest()) {
                    log.info("AI provided file changes without runTool - requesting validation tool");

                    String toolRequestMessage = buildMissingToolFeedback();

                    conversationHistory.add(AiMessage.builder().role("user").content(currentMessage).build());
                    conversationHistory.add(AiMessage.builder().role("assistant").content(aiResponse).build());

                    currentMessage = toolRequestMessage;
                    sessionService.addMessage(session, "user", toolRequestMessage);
                    continue;
                }
            }

            return lastValidPlan;

        } finally {
            // Clean up workspace
            if (workspaceDir != null) {
                toolExecutionService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    private String buildToolFeedback(ImplementationPlan.ToolRequest toolRequest, ToolExecutionService.ToolResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Tool Execution Result\n\n");
        sb.append("**Command**: `").append(toolRequest.getTool());
        if (toolRequest.getArgs() != null && !toolRequest.getArgs().isEmpty()) {
            sb.append(" ").append(String.join(" ", toolRequest.getArgs()));
        }
        sb.append("`\n\n");

        if (result.success()) {
            sb.append("✅ **Success** (exit code 0)\n");
        } else {
            sb.append("❌ **Failed** (exit code ").append(result.exitCode()).append(")\n");
        }

        sb.append("\n").append(result.formatForAi());

        if (!result.success()) {
            sb.append("\nFix the errors and provide updated `fileChanges`. ");
            sb.append("Include `runTool` to validate again.");
        }

        return sb.toString();
    }

    private void postToolResultComment(String owner, String repo, Long issueNumber,
                                       ImplementationPlan.ToolRequest toolRequest,
                                       ToolExecutionService.ToolResult result) {
        try {
            StringBuilder comment = new StringBuilder();
            comment.append("🔧 **Tool Execution**: `").append(toolRequest.getTool());
            if (toolRequest.getArgs() != null && !toolRequest.getArgs().isEmpty()) {
                comment.append(" ").append(String.join(" ", toolRequest.getArgs()));
            }
            comment.append("`\n\n");

            if (result.success()) {
                comment.append("✅ **Success**\n");
            } else {
                comment.append("❌ **Failed** (exit code ").append(result.exitCode()).append(")\n");
                if (!result.output().isEmpty()) {
                    String output = result.output();
                    if (output.length() > 2000) {
                        output = output.substring(0, 2000) + "\n... (truncated)";
                    }
                    comment.append("\n```\n").append(output).append("```\n");
                }
            }

            repositoryClient.postComment(owner, repo, issueNumber, comment.toString());
        } catch (Exception e) {
            log.warn("Failed to post tool result comment: {}", e.getMessage());
        }
    }

    private String buildMissingToolFeedback() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Missing Validation Tool\n\n");
        sb.append("Your response included `fileChanges` but no `runTool` for validation.\n\n");
        sb.append("**Validation is mandatory.** Please provide the same file changes again, ");
        sb.append("but this time include a `runTool` to validate the code.\n\n");
        sb.append("Detect the build system from the file tree and request the appropriate tool:\n");
        sb.append("- Maven: `{\"tool\": \"mvn\", \"args\": [\"compile\", \"-q\", \"-B\"]}`\n");
        sb.append("- Gradle: `{\"tool\": \"gradle\", \"args\": [\"compileJava\", \"-q\"]}`\n");
        sb.append("- npm: `{\"tool\": \"npm\", \"args\": [\"run\", \"build\"]}`\n");
        sb.append("- etc.\n\n");
        sb.append("Output JSON with both `fileChanges` and `runTool`.");
        return sb.toString();
    }

    /**
     * Merges file changes from two plans.
     * If the new plan doesn't include a file from the previous plan, that file is preserved.
     * If both plans have the same file, the new version takes precedence.
     *
     * @param previousPlan The previous plan with file changes
     * @param newPlan      The new plan (may have fewer files if AI only fixed some)
     * @return A merged plan with all file changes
     */
    private ImplementationPlan mergeFileChanges(ImplementationPlan previousPlan, ImplementationPlan newPlan) {
        if (previousPlan == null || !previousPlan.hasFileChanges()) {
            return newPlan;
        }
        if (newPlan == null || !newPlan.hasFileChanges()) {
            return previousPlan;
        }

        // Build a map of file paths to changes from the new plan
        Map<String, FileChange> newChangesMap = new java.util.HashMap<>();
        for (FileChange fc : newPlan.getFileChanges()) {
            newChangesMap.put(fc.getPath(), fc);
        }

        // Add missing files from previous plan
        List<FileChange> mergedChanges = new ArrayList<>(newPlan.getFileChanges());
        for (FileChange fc : previousPlan.getFileChanges()) {
            if (!newChangesMap.containsKey(fc.getPath())) {
                log.info("Preserving file change from previous plan: {}", fc.getPath());
                mergedChanges.add(fc);
            }
        }

        return ImplementationPlan.builder()
                .summary(newPlan.getSummary() != null ? newPlan.getSummary() : previousPlan.getSummary())
                .fileChanges(mergedChanges)
                .toolRequest(newPlan.getToolRequest())
                .requestFiles(newPlan.getRequestFiles())
                .build();
    }

    /**
     * Builds information about previously made changes that need to be preserved.
     * This is used when a tool fails to ensure the AI includes all previous changes
     * when providing fixes.
     */
    private String buildPreviousChangesInfo(ImplementationPlan lastValidPlan) {
        if (lastValidPlan == null || !lastValidPlan.hasFileChanges()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## IMPORTANT: Preserve Previous Changes\n\n");
        sb.append("Your previous response included the following file changes that need to be preserved.\n");
        sb.append("When you fix the errors, you MUST include ALL these changes in your response, ");
        sb.append("not just the fix. If you omit any of these files, those changes will be lost.\n\n");
        sb.append("**Files from your previous response** (").append(lastValidPlan.getFileChanges().size()).append("):\n");

        for (FileChange fc : lastValidPlan.getFileChanges()) {
            sb.append("- `").append(fc.getPath()).append("` (").append(fc.getOperation()).append(")\n");
        }

        sb.append("\nInclude all these files in your `fileChanges` array, updating any that need fixes.\n");

        return sb.toString();
    }

    /**
     * Executes tool validation loop for follow-up comments.
     * Similar to the validation in generateValidatedImplementation but for comment handling.
     *
     * @return The final valid plan after validation, or null if validation failed
     */
    private ImplementationPlan executeToolValidationLoop(
            AgentSession session, ImplementationPlan plan, String owner, String repo,
            String workingBranch, Long issueNumber, String systemPrompt, String lastAiResponse) {

        int maxToolExecutions = agentConfig.getValidation().getMaxToolExecutions();
        int toolExecutions = 0;
        Path workspaceDir = null;
        ImplementationPlan currentPlan = plan;
        String currentAiResponse = lastAiResponse;
        List<AiMessage> conversationHistory = sessionService.toAiMessages(session);

        try {
            while (currentPlan.hasToolRequest() && toolExecutions < maxToolExecutions) {
                toolExecutions++;

                // Prepare workspace if not already done
                if (workspaceDir == null) {
                    ToolExecutionService.WorkspaceResult workspaceResult =
                            toolExecutionService.prepareWorkspace(owner, repo, workingBranch, currentPlan.getFileChanges(), repositoryClient.getCloneUrl(), repositoryClient.getToken());
                    if (!workspaceResult.success()) {
                        log.error("Failed to prepare workspace for validation: {}", workspaceResult.error());
                        repositoryClient.postComment(owner, repo, issueNumber,
                                "⚠️ **Workspace preparation failed**\n\n" + workspaceResult.error());
                        return currentPlan; // Return plan without validation
                    }
                    workspaceDir = workspaceResult.workspacePath();
                } else {
                    // Update existing workspace with new file changes
                    for (FileChange change : currentPlan.getFileChanges()) {
                        Path filePath = workspaceDir.resolve(change.getPath());
                        try {
                            switch (change.getOperation()) {
                                case CREATE, UPDATE -> {
                                    java.nio.file.Files.createDirectories(filePath.getParent());
                                    java.nio.file.Files.writeString(filePath, change.getContent());
                                }
                                case DELETE -> java.nio.file.Files.deleteIfExists(filePath);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to update workspace file {}: {}", change.getPath(), e.getMessage());
                        }
                    }
                }

                // Execute the tool
                ImplementationPlan.ToolRequest toolRequest = currentPlan.getToolRequest();
                log.info("Executing validation tool (follow-up): {} {}", toolRequest.getTool(),
                        toolRequest.getArgs() != null ? String.join(" ", toolRequest.getArgs()) : "");

                ToolExecutionService.ToolResult result = toolExecutionService.executeTool(
                        workspaceDir, toolRequest.getTool(), toolRequest.getArgs());

                // Post tool result as comment
                postToolResultComment(owner, repo, issueNumber, toolRequest, result);

                // If tool succeeded, we're done
                if (result.success()) {
                    log.info("Validation tool succeeded for follow-up changes");
                    return currentPlan;
                }

                // Tool failed - send feedback to AI for fixing
                String toolFeedback = buildToolFeedback(toolRequest, result);
                String previousChangesInfo = buildPreviousChangesInfo(currentPlan);
                String feedbackMessage = toolFeedback + previousChangesInfo;

                sessionService.addMessage(session, "user", feedbackMessage);

                // Get updated history and call AI
                List<AiMessage> updatedHistory = sessionService.toAiMessages(session);
                currentAiResponse = aiClient.chat(updatedHistory.subList(0, updatedHistory.size() - 1),
                        feedbackMessage, systemPrompt, null, agentConfig.getMaxTokens());
                sessionService.addMessage(session, "assistant", currentAiResponse);

                // Post AI thinking comment
                postAiThinkingComment(owner, repo, issueNumber, currentAiResponse);

                // Parse new response and merge with previous changes
                ImplementationPlan previousPlan = currentPlan;
                ImplementationPlan newPlan = parseAiResponse(currentAiResponse);
                if (newPlan == null || !newPlan.hasFileChanges()) {
                    log.warn("AI failed to provide file changes after tool failure");
                    return currentPlan; // Return last valid plan
                }

                // Merge: preserve files that weren't updated
                currentPlan = mergeFileChanges(previousPlan, newPlan);
            }

            // Reached max tool executions
            if (toolExecutions >= maxToolExecutions) {
                log.warn("Reached maximum tool executions ({}) for follow-up validation", maxToolExecutions);
            }

            return currentPlan;

        } finally {
            // Clean up workspace
            if (workspaceDir != null) {
                toolExecutionService.cleanupWorkspace(workspaceDir);
            }
        }
    }

    /**
     * Handles a comment on an issue that mentions the bot.
     * This allows users to request changes or continue work after initial implementation.
     */
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
                repositoryClient.addReaction(owner, repo, commentId, "eyes");
            } catch (Exception e) {
                log.warn("Failed to add reaction to comment #{}: {}", commentId, e.getMessage());
            }

            // Update session status
            sessionService.setStatus(session, AgentSession.AgentSessionStatus.UPDATING);

            // Get current branch and default branch
            String branchName = session.getBranchName();
            String defaultBranch = repositoryClient.getDefaultBranch(owner, repo);
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

            // Post the AI's reasoning as a comment (excluding JSON)
            postAiThinkingComment(owner, repo, issueNumber, aiResponse);

            // Parse AI response
            ImplementationPlan plan = parseAiResponse(aiResponse);

            // Handle file requests - AI wants to see more files
            if (plan != null && plan.hasFileRequests()) {
                log.info("AI requesting {} additional files", plan.getRequestFiles().size());
                String fileContext = fetchSpecificFiles(owner, repo, workingBranch, plan.getRequestFiles());

                // Send files and ask AI to continue
                String filesMessage = "Here are the requested files:\n" + fileContext + "\n\nPlease continue.";
                sessionService.addMessage(session, "user", filesMessage);

                List<AiMessage> updatedHistory = sessionService.toAiMessages(session);
                aiResponse = aiClient.chat(updatedHistory.subList(0, updatedHistory.size() - 1), filesMessage,
                        systemPrompt, null, agentConfig.getMaxTokens());
                sessionService.addMessage(session, "assistant", aiResponse);

                // Post the follow-up AI reasoning as a comment
                postAiThinkingComment(owner, repo, issueNumber, aiResponse);

                plan = parseAiResponse(aiResponse);
            }

            if (plan == null || !plan.hasFileChanges()) {
                // AI responded but no code changes - the thinking comment was already posted
                sessionService.setStatus(session, AgentSession.AgentSessionStatus.PR_CREATED);
                return;
            }

            // Execute tool validation if requested and validation is enabled
            if (agentConfig.getValidation().isEnabled() && plan.hasToolRequest()) {
                plan = executeToolValidationLoop(session, plan, owner, repo, workingBranch,
                        issueNumber, systemPrompt, aiResponse);

                if (plan == null || !plan.hasFileChanges()) {
                    // Validation failed and couldn't be fixed
                    sessionService.setStatus(session, AgentSession.AgentSessionStatus.PR_CREATED);
                    repositoryClient.postComment(owner, repo, issueNumber,
                            "🤖 **AI Agent**: Validation failed and I couldn't fix the issues. " +
                            "Please check the tool output above and provide more guidance.");
                    return;
                }
            }

            // Apply the new changes
            if (branchName == null) {
                // Create a new branch if we don't have one
                branchName = agentConfig.getBranchPrefix() + "issue-" + issueNumber;
                repositoryClient.createBranch(owner, repo, branchName, defaultBranch);
                sessionService.setBranchName(session, branchName);
            }

            for (FileChange change : plan.getFileChanges()) {
                String commitMessage = String.format("agent: %s %s (issue #%d, follow-up)",
                        change.getOperation().name().toLowerCase(), change.getPath(), issueNumber);

                applyFileChange(owner, repo, branchName, change, commitMessage, session);

                // Record file change in session
                sessionService.addFileChange(session, change.getPath(), change.getOperation().name(), null);
            }

            // Create PR if we don't have one yet
            if (session.getPrNumber() == null) {
                String prTitle = String.format("AI Agent: %s (fixes #%d)", session.getIssueTitle(), issueNumber);
                String prBody = buildPrBody(issueNumber, plan);
                Long prNumber = repositoryClient.createPullRequest(owner, repo, prTitle, prBody,
                        branchName, defaultBranch);
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

            repositoryClient.postComment(owner, repo, issueNumber, updateComment);
            log.info("Successfully applied follow-up changes for issue #{}", issueNumber);

        } catch (Exception e) {
            log.error("Failed to handle comment on issue #{}: {}", issueNumber, e.getMessage(), e);

            try {
                repositoryClient.postComment(owner, repo, issueNumber,
                        String.format("🤖 **AI Agent**: Failed to process your request: `%s`\n\n" +
                                "Please try again or provide more details.",
                                e.getMessage()));
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
                String content = repositoryClient.getFileContent(owner, repo, path, ref);
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
     * Applies a single file change, supporting both diff-based and full content changes.
     */
    private void applyFileChange(String owner, String repo, String branchName,
                                  FileChange change, String commitMessage, AgentSession session) {
        String systemPrompt = promptService.getSystemPrompt(AGENT_PROMPT_NAME);
        applyFileChangeWithRetry(owner, repo, branchName, change, commitMessage, session, aiClient, systemPrompt);
    }

    /**
     * Applies a file change with retry capability.
     * If a diff fails, it fetches the current file content and asks the AI to regenerate the diff.
     *
     * @param owner         Repository owner
     * @param repo          Repository name
     * @param branchName    Target branch
     * @param change        The file change to apply
     * @param commitMessage Commit message
     * @param session       Agent session (optional, for AI retry)
     * @param aiClient      AI client (optional, for retry)
     * @param systemPrompt  System prompt (optional, for retry)
     */
    private void applyFileChangeWithRetry(String owner, String repo, String branchName,
                                           FileChange change, String commitMessage,
                                           AgentSession session, AiClient aiClient, String systemPrompt) {
        switch (change.getOperation()) {
            case CREATE -> repositoryClient.createOrUpdateFile(owner, repo, change.getPath(),
                    change.getContent(), commitMessage, branchName, null);
            case UPDATE -> {
                String sha = repositoryClient.getFileSha(owner, repo, change.getPath(), branchName);
                String newContent;

                if (change.isDiffBased()) {
                    // Apply diff to existing content
                    String originalContent = repositoryClient.getFileContent(owner, repo,
                            change.getPath(), branchName);
                    try {
                        newContent = diffApplyService.applyDiff(originalContent, change.getDiff());
                    } catch (DiffApplyService.DiffApplyException e) {
                        // If we have AI context, try to regenerate the diff
                        if (aiClient != null && session != null && systemPrompt != null) {
                            log.warn("Diff failed for {}, asking AI to regenerate with current file content", change.getPath());

                            String regeneratedContent = askAiToRegenerateDiff(
                                    aiClient, systemPrompt, session, change.getPath(),
                                    change.getDiff(), originalContent);

                            if (regeneratedContent != null) {
                                newContent = regeneratedContent;
                                log.info("AI successfully regenerated content for {}", change.getPath());
                            } else {
                                throw new DiffApplyService.DiffApplyException(
                                        "Failed to apply diff to file `" + change.getPath() + "` and AI could not regenerate: " + e.getMessage());
                            }
                        } else {
                            throw new DiffApplyService.DiffApplyException(
                                    "Failed to apply diff to file `" + change.getPath() + "`: " + e.getMessage());
                        }
                    }
                    log.debug("Applied diff to {}: {} chars -> {} chars",
                            change.getPath(), originalContent.length(), newContent.length());
                } else {
                    // Full content replacement
                    newContent = change.getContent();
                }

                repositoryClient.createOrUpdateFile(owner, repo, change.getPath(),
                        newContent, commitMessage, branchName, sha);
            }
            case DELETE -> {
                String sha = repositoryClient.getFileSha(owner, repo, change.getPath(), branchName);
                repositoryClient.deleteFile(owner, repo, change.getPath(),
                        commitMessage, branchName, sha);
            }
        }
    }

    /**
     * Asks the AI to regenerate a diff when the original diff failed to apply.
     * Provides the current file content so the AI can create a correct diff.
     *
     * @param aiClient       The AI client
     * @param systemPrompt   System prompt
     * @param session        Current session
     * @param filePath       Path of the file
     * @param failedDiff     The diff that failed to apply
     * @param currentContent Current content of the file
     * @return The new file content, or null if regeneration failed
     */
    private String askAiToRegenerateDiff(AiClient aiClient, String systemPrompt,
                                          AgentSession session, String filePath,
                                          String failedDiff, String currentContent) {
        try {
            String prompt = String.format("""
                    ## Diff Application Failed
                    
                    The diff I tried to apply to `%s` failed because the file content has changed.
                    
                    ### Current File Content:
                    ```
                    %s
                    ```
                    
                    ### The Diff That Failed:
                    ```
                    %s
                    ```
                    
                    Please provide the **complete new file content** that implements the intended change.
                    Output ONLY the file content, no JSON, no markdown code blocks, just the raw file content.
                    """, filePath,
                    currentContent.length() > 5000 ? currentContent.substring(0, 5000) + "\n... (truncated)" : currentContent,
                    failedDiff);

            List<AiMessage> history = sessionService.toAiMessages(session);
            String response = aiClient.chat(history, prompt, systemPrompt, null, agentConfig.getMaxTokens());

            if (response == null || response.isBlank()) {
                return null;
            }

            // Clean up the response - remove any markdown code blocks if present
            String content = response.strip();
            if (content.startsWith("```")) {
                // Remove first line (```java or similar)
                int firstNewline = content.indexOf('\n');
                if (firstNewline > 0) {
                    content = content.substring(firstNewline + 1);
                }
                // Remove closing ```
                if (content.endsWith("```")) {
                    content = content.substring(0, content.length() - 3).stripTrailing();
                }
            }

            // Validate that we got something reasonable
            if (content.length() < 10) {
                log.warn("AI returned very short content for {}: {} chars", filePath, content.length());
                return null;
            }

            return content;

        } catch (Exception e) {
            log.error("Failed to ask AI to regenerate diff for {}: {}", filePath, e.getMessage());
            return null;
        }
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
            return null; // Pure JSON, no thinking text
        }

        return aiResponse;
    }

    /**
     * Posts the AI's thinking/reasoning as a comment on the issue, excluding JSON content.
     * This provides transparency about what the AI is doing.
     */
    private void postAiThinkingComment(String owner, String repo, Long issueNumber, String aiResponse) {
        String thinking = extractNonJsonResponse(aiResponse);

        // Extract summary from the response if available
        ImplementationPlan plan = parseAiResponse(aiResponse);
        String summary = (plan != null && plan.getSummary() != null) ? plan.getSummary() : null;

        // If no thinking text and no plan data, nothing to post
        if ((thinking == null || thinking.isBlank()) && plan == null) {
            return;
        }

        StringBuilder comment = new StringBuilder();
        comment.append("🤖 **AI Agent Response**:\n\n");

        // Add thinking text if present
        if (thinking != null && !thinking.isBlank()) {
            comment.append(thinking);
            comment.append("\n\n");
        }

        // Add summary if present
        if (summary != null && !summary.isBlank()) {
            comment.append("📝 **Summary**: ").append(summary).append("\n\n");
        }

        // Add file request info if present
        if (plan != null && plan.hasFileRequests()) {
            comment.append("📁 **Requesting files**: ");
            comment.append(String.join(", ", plan.getRequestFiles().stream()
                    .map(f -> "`" + f + "`")
                    .toList()));
            comment.append("\n\n");
        }

        // Add file changes info if present
        if (plan != null && plan.hasFileChanges()) {
            comment.append("📄 **Planned file changes** (").append(plan.getFileChanges().size()).append("):\n");
            for (FileChange fc : plan.getFileChanges()) {
                comment.append("- `").append(fc.getPath()).append("` (").append(fc.getOperation()).append(")\n");
            }
            comment.append("\n");
        }

        // Add tool request info if present
        if (plan != null && plan.hasToolRequest()) {
            ImplementationPlan.ToolRequest toolReq = plan.getToolRequest();
            comment.append("🔧 **Will run**: `").append(toolReq.getTool());
            if (toolReq.getArgs() != null && !toolReq.getArgs().isEmpty()) {
                comment.append(" ").append(String.join(" ", toolReq.getArgs()));
            }
            comment.append("`\n");
        }

        // Only post if we have content
        String commentText = comment.toString().strip();
        if (commentText.equals("🤖 **AI Agent Response**:")) {
            return; // Nothing meaningful to post
        }

        try {
            repositoryClient.postComment(owner, repo, issueNumber, commentText);
        } catch (Exception e) {
            log.warn("Failed to post AI thinking comment on issue #{}: {}", issueNumber, e.getMessage());
        }
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
                String content = repositoryClient.getFileContent(owner, repo, path, ref);
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
            if (response == null) {
                log.warn("Parsed AI response is null");
                return null;
            }

            // Check if AI is requesting more files
            List<String> requestFiles = response.getRequestFiles();

            // Parse file changes if present
            List<FileChange> fileChanges = new ArrayList<>();
            if (response.getFileChanges() != null) {
                fileChanges = response.getFileChanges().stream()
                        .map(fc -> FileChange.builder()
                                .path(fc.getPath())
                                .content(fc.getContent() != null ? fc.getContent() : "")
                                .diff(fc.getDiff())
                                .operation(parseOperation(fc.getOperation()))
                                .build())
                        .toList();
            }

            // Parse tool request if present
            ImplementationPlan.ToolRequest toolRequest = null;
            if (response.getRunTool() != null && response.getRunTool().getTool() != null) {
                toolRequest = ImplementationPlan.ToolRequest.builder()
                        .tool(response.getRunTool().getTool())
                        .args(response.getRunTool().getArgs())
                        .build();
            }

            return ImplementationPlan.builder()
                    .summary(response.getSummary())
                    .requestFiles(requestFiles)
                    .fileChanges(fileChanges)
                    .toolRequest(toolRequest)
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
     * IMPORTANT: Only truncates if the JSON is actually incomplete (unbalanced brackets).
     */
    String repairTruncatedJson(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }

        // First, check if the JSON is already complete (balanced brackets)
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

        // If JSON is already balanced and complete, return as-is (do NOT truncate!)
        if (braces == 0 && brackets == 0 && !inString) {
            return json;
        }

        // JSON is truncated - try to repair it by finding last complete fileChange
        int lastCompleteObject = findLastCompleteFileChange(json);
        if (lastCompleteObject > 0 && lastCompleteObject < json.length() - 10) {
            json = json.substring(0, lastCompleteObject);

            // Recount brackets after truncation
            braces = 0;
            brackets = 0;
            inString = false;
            prevChar = 0;

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
        }

        // If still unbalanced, try to close the structures
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

    /**
     * Normalizes a branch reference by removing the "refs/heads/" prefix if present.
     * Gitea returns branch refs in issues as "refs/heads/main" but the API and git
     * commands expect just "main".
     *
     * @param ref The branch reference (e.g., "refs/heads/main" or "main")
     * @return The normalized branch name (e.g., "main"), or null if input is null
     */
    private String normalizeBranchRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        // Remove "refs/heads/" prefix if present
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        // Remove "refs/tags/" prefix if present (for tag refs)
        if (ref.startsWith("refs/tags/")) {
            return ref.substring("refs/tags/".length());
        }
        return ref;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiImplementationResponse {
        private String summary;
        private List<String> requestFiles;  // Files the AI wants to see
        private List<AiFileChange> fileChanges;
        private AiToolRequest runTool;  // Tool the AI wants to run for validation
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiFileChange {
        private String path;
        private String operation;
        private String content;  // Full content (for CREATE or full UPDATE)
        private String diff;     // Diff for UPDATE (preferred over content)
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AiToolRequest {
        private String tool;
        private List<String> args;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FileRequestResponse {
        private String reasoning;
        private List<String> requestedFiles;
    }
}

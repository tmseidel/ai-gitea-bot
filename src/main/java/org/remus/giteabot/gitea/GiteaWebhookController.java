package org.remus.giteabot.gitea;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.IssueImplementationService;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.BotConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.review.CodeReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/webhook")
public class GiteaWebhookController {

    private final CodeReviewService codeReviewService;
    private final IssueImplementationService issueImplementationService;
    private final BotConfigProperties botConfig;
    private final AgentConfigProperties agentConfig;

    public GiteaWebhookController(CodeReviewService codeReviewService,
                                  IssueImplementationService issueImplementationService,
                                  BotConfigProperties botConfig,
                                  AgentConfigProperties agentConfig) {
        this.codeReviewService = codeReviewService;
        this.issueImplementationService = issueImplementationService;
        this.botConfig = botConfig;
        this.agentConfig = agentConfig;
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(@RequestBody WebhookPayload payload,
                                                @RequestParam(name = "prompt", required = false) String promptName) {
        // Ignore events triggered by the bot itself to prevent infinite loops
        if (isBotUser(payload)) {
            log.debug("Ignoring webhook event from bot user '{}'", botConfig.getUsername());
            return ResponseEntity.ok("ignored");
        }

        // Handle issue assignment events (agent feature)
        if ("assigned".equals(payload.getAction()) && payload.getIssue() != null
                && payload.getPullRequest() == null && payload.getComment() == null) {
            return handleIssueAssigned(payload);
        }

        // Handle inline review comments (bot mention in code-level review comments)
        if (payload.getComment() != null && payload.getComment().getPath() != null
                && !payload.getComment().getPath().isBlank()) {
            return handleInlineReviewComment(payload, promptName);
        }

        // Handle issue_comment events (bot commands in PR comments)
        if (payload.getComment() != null && payload.getIssue() != null) {
            return handleCommentEvent(payload, promptName);
        }

        // Handle PR events
        if (payload.getPullRequest() == null) {
            log.debug("Ignoring non-PR webhook event");
            return ResponseEntity.ok("ignored");
        }

        String action = payload.getAction();

        // Handle review submitted events (inline comments submitted as part of a review)
        if ("reviewed".equals(action) && payload.getReview() != null) {
            return handleReviewSubmittedEvent(payload, promptName);
        }

        if ("closed".equals(action)) {
            log.info("PR #{} in {} was closed, cleaning up session",
                    payload.getPullRequest().getNumber(),
                    payload.getRepository().getFullName());
            codeReviewService.handlePrClosed(payload);
            return ResponseEntity.ok("session closed");
        }

        if (!"opened".equals(action) && !"synchronized".equals(action)) {
            log.debug("Ignoring PR action: {}", action);
            return ResponseEntity.ok("ignored");
        }

        log.info("Received PR webhook: action={}, PR #{} in {}, prompt={}",
                action,
                payload.getPullRequest().getNumber(),
                payload.getRepository().getFullName(),
                promptName);

        codeReviewService.reviewPullRequest(payload, promptName);

        return ResponseEntity.ok("review triggered");
    }

    private ResponseEntity<String> handleInlineReviewComment(WebhookPayload payload, String promptName) {
        if (!"created".equals(payload.getAction())) {
            log.debug("Ignoring inline comment action: {}", payload.getAction());
            return ResponseEntity.ok("ignored");
        }

        String commentBody = payload.getComment().getBody();
        if (commentBody == null || !commentBody.contains(botConfig.getAlias())) {
            log.debug("Ignoring inline comment without bot mention");
            return ResponseEntity.ok("ignored");
        }

        // Determine PR number from either issue or pullRequest
        Long prNumber = null;
        if (payload.getIssue() != null) {
            prNumber = payload.getIssue().getNumber();
        } else if (payload.getPullRequest() != null) {
            prNumber = payload.getPullRequest().getNumber();
        }

        if (prNumber == null) {
            log.debug("Ignoring inline comment: unable to determine PR number");
            return ResponseEntity.ok("ignored");
        }

        log.info("Received inline review comment #{} on file {} in {}, PR #{}",
                payload.getComment().getId(),
                payload.getComment().getPath(),
                payload.getRepository().getFullName(),
                prNumber);

        codeReviewService.handleInlineComment(payload, promptName);

        return ResponseEntity.ok("inline comment response triggered");
    }

    private ResponseEntity<String> handleReviewSubmittedEvent(WebhookPayload payload, String promptName) {
        log.info("Received review submitted event for PR #{} in {}, review type={}",
                payload.getPullRequest().getNumber(),
                payload.getRepository().getFullName(),
                payload.getReview().getType());

        codeReviewService.handleReviewSubmitted(payload, promptName);

        return ResponseEntity.ok("review comments processing triggered");
    }

    private ResponseEntity<String> handleCommentEvent(WebhookPayload payload, String promptName) {
        // Only process newly created comments
        if (!"created".equals(payload.getAction())) {
            log.debug("Ignoring comment action: {}", payload.getAction());
            return ResponseEntity.ok("ignored");
        }

        String commentBody = payload.getComment().getBody();
        if (commentBody == null || !commentBody.contains(botConfig.getAlias())) {
            log.debug("Ignoring comment without bot mention");
            return ResponseEntity.ok("ignored");
        }

        // Check if this is a PR comment or an issue comment
        if (payload.getIssue().getPullRequest() != null) {
            // It's a PR comment - route to code review service
            log.info("Received bot command in comment #{} on PR #{} in {}",
                    payload.getComment().getId(),
                    payload.getIssue().getNumber(),
                    payload.getRepository().getFullName());

            codeReviewService.handleBotCommand(payload, promptName);
            return ResponseEntity.ok("command received");
        } else {
            // It's an issue comment - check if agent is enabled and route accordingly
            if (!agentConfig.isEnabled()) {
                log.debug("Agent feature is disabled, ignoring issue comment");
                return ResponseEntity.ok("ignored");
            }

            // Check if repo is in the allowed list (if configured)
            String repoFullName = payload.getRepository().getFullName();
            if (!agentConfig.getAllowedRepos().isEmpty()
                    && !agentConfig.getAllowedRepos().contains(repoFullName)) {
                log.debug("Repository {} is not in the agent's allowed repos list, ignoring", repoFullName);
                return ResponseEntity.ok("ignored");
            }

            log.info("Received bot mention in comment #{} on issue #{} in {}",
                    payload.getComment().getId(),
                    payload.getIssue().getNumber(),
                    payload.getRepository().getFullName());

            issueImplementationService.handleIssueComment(payload);
            return ResponseEntity.ok("agent comment received");
        }
    }

    private ResponseEntity<String> handleIssueAssigned(WebhookPayload payload) {
        if (!agentConfig.isEnabled()) {
            log.debug("Agent feature is disabled, ignoring issue assignment");
            return ResponseEntity.ok("ignored");
        }

        // Check if the assignee is the bot (assignee is inside the issue object)
        WebhookPayload.Owner assignee = payload.getIssue().getAssignee();
        if (assignee == null
                || !botConfig.getUsername().equalsIgnoreCase(assignee.getLogin())) {
            log.debug("Issue not assigned to bot, ignoring");
            return ResponseEntity.ok("ignored");
        }

        // Check if repo is in the allowed list (if configured)
        String repoFullName = payload.getRepository().getFullName();
        if (!agentConfig.getAllowedRepos().isEmpty()
                && !agentConfig.getAllowedRepos().contains(repoFullName)) {
            log.info("Repository {} is not in the agent's allowed repos list, ignoring", repoFullName);
            return ResponseEntity.ok("ignored");
        }

        // Ignore issues that are actually PRs
        if (payload.getIssue().getPullRequest() != null) {
            log.debug("Ignoring assignment on PR issue");
            return ResponseEntity.ok("ignored");
        }

        log.info("Issue #{} in {} assigned to bot, triggering implementation agent",
                payload.getIssue().getNumber(), repoFullName);

        issueImplementationService.handleIssueAssigned(payload);

        return ResponseEntity.ok("agent triggered");
    }

    /**
     * Checks whether the webhook event was triggered by the bot's own Gitea user.
     * This prevents infinite loops where the bot reacts to its own comments.
     */
    private boolean isBotUser(WebhookPayload payload) {
        String botUsername = botConfig.getUsername();
        if (botUsername == null || botUsername.isBlank()) {
            return false;
        }

        // Check the sender field (present on most webhook events)
        if (payload.getSender() != null && botUsername.equalsIgnoreCase(payload.getSender().getLogin())) {
            return true;
        }

        // Check the comment author (defense-in-depth for comment events)
        return payload.getComment() != null
                && payload.getComment().getUser() != null
                && botUsername.equalsIgnoreCase(payload.getComment().getUser().getLogin());
    }
}

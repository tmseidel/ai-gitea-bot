package org.remus.giteabot.gitea;

import lombok.extern.slf4j.Slf4j;
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
    private final BotConfigProperties botConfig;

    public GiteaWebhookController(CodeReviewService codeReviewService, BotConfigProperties botConfig) {
        this.codeReviewService = codeReviewService;
        this.botConfig = botConfig;
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(@RequestBody WebhookPayload payload,
                                                @RequestParam(name = "prompt", required = false) String promptName) {
        // Ignore events triggered by the bot itself to prevent infinite loops
        if (isBotUser(payload)) {
            log.debug("Ignoring webhook event from bot user '{}'", botConfig.getUsername());
            return ResponseEntity.ok("ignored");
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

        // Only process comments on PRs (issue has pull_request field)
        if (payload.getIssue().getPullRequest() == null) {
            log.debug("Ignoring comment on non-PR issue");
            return ResponseEntity.ok("ignored");
        }

        String commentBody = payload.getComment().getBody();
        if (commentBody == null || !commentBody.contains(botConfig.getAlias())) {
            log.debug("Ignoring comment without bot mention");
            return ResponseEntity.ok("ignored");
        }

        log.info("Received bot command in comment #{} on PR #{} in {}",
                payload.getComment().getId(),
                payload.getIssue().getNumber(),
                payload.getRepository().getFullName());

        codeReviewService.handleBotCommand(payload, promptName);

        return ResponseEntity.ok("command received");
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

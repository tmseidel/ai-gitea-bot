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
}

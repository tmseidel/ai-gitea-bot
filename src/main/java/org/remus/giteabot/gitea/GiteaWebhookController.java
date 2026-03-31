package org.remus.giteabot.gitea;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.review.CodeReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/webhook")
public class GiteaWebhookController {

    private final CodeReviewService codeReviewService;

    public GiteaWebhookController(CodeReviewService codeReviewService) {
        this.codeReviewService = codeReviewService;
    }

    @PostMapping
    public ResponseEntity<String> handleWebhook(@RequestBody WebhookPayload payload,
                                                @RequestParam(name = "prompt", required = false) String promptName) {
        if (payload.getPullRequest() == null) {
            log.debug("Ignoring non-PR webhook event");
            return ResponseEntity.ok("ignored");
        }

        String action = payload.getAction();
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
}

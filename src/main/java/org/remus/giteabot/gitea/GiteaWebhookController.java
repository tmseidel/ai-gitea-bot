package org.remus.giteabot.gitea;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/webhook")
public class GiteaWebhookController {

    private final BotService botService;
    private final BotWebhookService botWebhookService;

    public GiteaWebhookController(BotService botService,
                                  BotWebhookService botWebhookService) {
        this.botService = botService;
        this.botWebhookService = botWebhookService;
    }

    /**
     * Per-bot webhook endpoint. Each bot has a unique webhook secret that serves as its URL path.
     * Uses the bot's own AI and Git integrations via {@link BotWebhookService}.
     */
    @PostMapping("/{webhookSecret}")
    public ResponseEntity<String> handleBotWebhook(@PathVariable String webhookSecret,
                                                    @RequestBody WebhookPayload payload) {
        return botService.findByWebhookSecret(webhookSecret)
                .map(bot -> {
                    if (!bot.isEnabled()) {
                        log.debug("Bot '{}' is disabled, ignoring webhook", bot.getName());
                        return ResponseEntity.ok("bot disabled");
                    }
                    botService.incrementWebhookCallCount(bot);
                    log.info("Webhook received for bot '{}' (provider={}, git={})",
                            bot.getName(),
                            bot.getAiIntegration().getProviderType(),
                            bot.getGitIntegration().getName());
                    return handleBotWebhookEvent(bot, payload);
                })
                .orElseGet(() -> {
                    log.warn("No bot found for webhook secret: {}...",
                            webhookSecret.substring(0, Math.min(8, webhookSecret.length())));
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Routes a webhook event for a specific persisted bot through its own AI and Git integrations.
     */
    private ResponseEntity<String> handleBotWebhookEvent(Bot bot, WebhookPayload payload) {
        // Ignore events triggered by the bot itself
        if (botWebhookService.isBotUser(bot, payload)) {
            log.debug("Ignoring webhook event from bot's own user '{}'", bot.getUsername());
            return ResponseEntity.ok("ignored");
        }

        String botAlias = botWebhookService.getBotAlias(bot);

        // Handle inline review comments
        if (payload.getComment() != null && payload.getComment().getPath() != null
                && !payload.getComment().getPath().isBlank()) {
            if ("created".equals(payload.getAction())
                    && payload.getComment().getBody() != null
                    && payload.getComment().getBody().contains(botAlias)) {
                botWebhookService.handleInlineComment(bot, payload);
                return ResponseEntity.ok("inline comment response triggered");
            }
            return ResponseEntity.ok("ignored");
        }

        // Handle issue/PR comments with bot mention
        if (payload.getComment() != null && payload.getIssue() != null) {
            if (!"created".equals(payload.getAction())) {
                return ResponseEntity.ok("ignored");
            }
            String body = payload.getComment().getBody();
            if (body == null || !body.contains(botAlias)) {
                return ResponseEntity.ok("ignored");
            }
            if (payload.getIssue().getPullRequest() != null) {
                botWebhookService.handleBotCommand(bot, payload);
                return ResponseEntity.ok("command received");
            }
            return ResponseEntity.ok("ignored");
        }

        // Handle PR events
        if (payload.getPullRequest() == null) {
            return ResponseEntity.ok("ignored");
        }

        String action = payload.getAction();

        if ("reviewed".equals(action) && payload.getReview() != null) {
            botWebhookService.handleReviewSubmitted(bot, payload);
            return ResponseEntity.ok("review comments processing triggered");
        }

        if ("closed".equals(action)) {
            botWebhookService.handlePrClosed(bot, payload);
            return ResponseEntity.ok("session closed");
        }

        if ("opened".equals(action) || "synchronized".equals(action)) {
            botWebhookService.reviewPullRequest(bot, payload);
            return ResponseEntity.ok("review triggered");
        }

        return ResponseEntity.ok("ignored");
    }
}

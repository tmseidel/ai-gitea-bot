package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.gitea.model.GiteaReview;
import org.remus.giteabot.gitea.model.GiteaReviewComment;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.session.ReviewSession;
import org.remus.giteabot.session.SessionService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Handles webhook events for persisted {@link Bot} entities using their
 * specific {@link AiIntegration} and {@link GitIntegration} configurations.
 * <p>
 * This is the bridge between the admin data model and the code-review / agent
 * services.  Each bot gets its own {@link AiClient} (via {@link AiClientFactory})
 * and its own {@link GiteaApiClient} (via {@link GiteaClientFactory}).
 */
@Slf4j
@Service
public class BotWebhookService {

    static final int MAX_DIFF_CHARS_FOR_CONTEXT = 60000;

    private final AiClientFactory aiClientFactory;
    private final GiteaClientFactory giteaClientFactory;
    private final SessionService sessionService;
    private final BotService botService;

    public BotWebhookService(AiClientFactory aiClientFactory,
                             GiteaClientFactory giteaClientFactory,
                             SessionService sessionService,
                             BotService botService) {
        this.aiClientFactory = aiClientFactory;
        this.giteaClientFactory = giteaClientFactory;
        this.sessionService = sessionService;
        this.botService = botService;
    }

    /**
     * Reviews a pull request using the bot's specific AI and Git integrations.
     */
    @Async
    public void reviewPullRequest(Bot bot, WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        String prTitle = payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest().getBody();

        log.info("[Bot '{}'] Starting code review for PR #{} '{}' in {}/{}",
                bot.getName(), prNumber, prTitle, owner, repo);

        try {
            AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
            GiteaApiClient giteaClient = createGiteaClient(bot);

            String diff = giteaClient.getPullRequestDiff(owner, repo, prNumber, null);
            if (diff == null || diff.isBlank()) {
                log.warn("[Bot '{}'] No diff found for PR #{}", bot.getName(), prNumber);
                return;
            }

            String systemPrompt = bot.getPrompt();
            String review = aiClient.reviewDiff(prTitle, prBody, diff, systemPrompt, null);

            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, null);
            sessionService.addMessage(session, "user", "Review PR #" + prNumber);
            sessionService.addMessage(session, "assistant", review);

            giteaClient.postReviewComment(owner, repo, prNumber, review, null);

            log.info("[Bot '{}'] Review posted for PR #{} in {}/{}",
                    bot.getName(), prNumber, owner, repo);
        } catch (Exception e) {
            log.error("[Bot '{}'] Failed to review PR #{} in {}/{}: {}",
                    bot.getName(), prNumber, owner, repo, e.getMessage(), e);
            botService.recordError(bot, e.getMessage());
        }
    }

    /**
     * Handles a bot-mention command in a PR comment.
     */
    @Async
    public void handleBotCommand(Bot bot, WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getIssue().getNumber();

        log.info("[Bot '{}'] Handling command in comment on PR #{} in {}/{}",
                bot.getName(), prNumber, owner, repo);

        try {
            AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
            GiteaApiClient giteaClient = createGiteaClient(bot);

            // Add reaction to show the bot is processing
            giteaClient.addReaction(owner, repo, payload.getComment().getId(), "eyes", null);

            String diff = giteaClient.getPullRequestDiff(owner, repo, prNumber, null);

            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, null);
            List<AiMessage> history = sessionService.toAiMessages(session);

            String userMessage = payload.getComment().getBody();
            if (diff != null && !diff.isBlank()) {
                String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                        ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n... (truncated)"
                        : diff;
                userMessage = "Current PR diff for context:\n```diff\n" + truncatedDiff + "\n```\n\n" + userMessage;
            }

            sessionService.addMessage(session, "user", payload.getComment().getBody());
            String response = aiClient.chat(history, userMessage, bot.getPrompt(), null);
            sessionService.addMessage(session, "assistant", response);

            giteaClient.postComment(owner, repo, prNumber, response, null);

            log.info("[Bot '{}'] Response posted for command on PR #{}", bot.getName(), prNumber);
        } catch (Exception e) {
            log.error("[Bot '{}'] Failed to handle command on PR #{}: {}",
                    bot.getName(), prNumber, e.getMessage(), e);
            botService.recordError(bot, e.getMessage());
        }
    }

    /**
     * Handles an inline review comment mentioning the bot.
     */
    @Async
    public void handleInlineComment(Bot bot, WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getIssue() != null
                ? payload.getIssue().getNumber()
                : payload.getPullRequest().getNumber();

        log.info("[Bot '{}'] Handling inline comment on PR #{} in {}/{}", bot.getName(), prNumber, owner, repo);

        try {
            AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
            GiteaApiClient giteaClient = createGiteaClient(bot);

            giteaClient.addReaction(owner, repo, payload.getComment().getId(), "eyes", null);

            String diff = giteaClient.getPullRequestDiff(owner, repo, prNumber, null);

            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, null);
            List<AiMessage> history = sessionService.toAiMessages(session);

            String filePath = payload.getComment().getPath();
            String commentBody = payload.getComment().getBody();
            String userMessage = "Inline comment on file `" + filePath + "`:\n" + commentBody;
            if (diff != null && !diff.isBlank()) {
                String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                        ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n... (truncated)"
                        : diff;
                userMessage = "Current PR diff for context:\n```diff\n" + truncatedDiff + "\n```\n\n" + userMessage;
            }

            sessionService.addMessage(session, "user", commentBody);
            String response = aiClient.chat(history, userMessage, bot.getPrompt(), null);
            sessionService.addMessage(session, "assistant", response);

            giteaClient.postComment(owner, repo, prNumber, response, null);

            log.info("[Bot '{}'] Inline comment response posted for PR #{}", bot.getName(), prNumber);
        } catch (Exception e) {
            log.error("[Bot '{}'] Failed to handle inline comment on PR #{}: {}",
                    bot.getName(), prNumber, e.getMessage(), e);
            botService.recordError(bot, e.getMessage());
        }
    }

    /**
     * Handles a review submitted event (responds to pending review comments).
     */
    @Async
    public void handleReviewSubmitted(Bot bot, WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();

        log.info("[Bot '{}'] Handling review submitted on PR #{} in {}/{}",
                bot.getName(), prNumber, owner, repo);

        try {
            AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
            GiteaApiClient giteaClient = createGiteaClient(bot);

            List<GiteaReview> reviews = giteaClient.getReviews(owner, repo, prNumber, null);
            if (reviews == null || reviews.isEmpty()) {
                return;
            }

            GiteaReview latestReview = reviews.getLast();
            List<GiteaReviewComment> comments = giteaClient.getReviewComments(
                    owner, repo, prNumber, latestReview.getId(), null);

            if (comments == null || comments.isEmpty()) {
                return;
            }

            String diff = giteaClient.getPullRequestDiff(owner, repo, prNumber, null);
            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, null);
            List<AiMessage> history = sessionService.toAiMessages(session);

            StringBuilder reviewComments = new StringBuilder("Review comments submitted:\n\n");
            for (GiteaReviewComment comment : comments) {
                reviewComments.append("**").append(comment.getPath())
                        .append("** (line ").append(comment.getLine()).append("):\n")
                        .append(comment.getBody()).append("\n\n");
            }

            String userMessage = reviewComments.toString();
            if (diff != null && !diff.isBlank()) {
                String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                        ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n... (truncated)"
                        : diff;
                userMessage = "Current PR diff for context:\n```diff\n" + truncatedDiff + "\n```\n\n" + userMessage;
            }

            sessionService.addMessage(session, "user", reviewComments.toString());
            String response = aiClient.chat(history, userMessage, bot.getPrompt(), null);
            sessionService.addMessage(session, "assistant", response);

            giteaClient.postComment(owner, repo, prNumber, response, null);

            log.info("[Bot '{}'] Review response posted for PR #{}", bot.getName(), prNumber);
        } catch (Exception e) {
            log.error("[Bot '{}'] Failed to handle review on PR #{}: {}",
                    bot.getName(), prNumber, e.getMessage(), e);
            botService.recordError(bot, e.getMessage());
        }
    }

    /**
     * Handles PR closed event by cleaning up the session.
     */
    public void handlePrClosed(Bot bot, WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();

        log.info("[Bot '{}'] PR #{} in {}/{} was closed, cleaning up session",
                bot.getName(), prNumber, owner, repo);
        sessionService.deleteSession(owner, repo, prNumber);
    }

    /**
     * Checks whether the webhook event was triggered by this bot's own user.
     */
    public boolean isBotUser(Bot bot, WebhookPayload payload) {
        String botUsername = bot.getUsername();
        if (botUsername == null || botUsername.isBlank()) {
            return false;
        }

        if (payload.getSender() != null && botUsername.equalsIgnoreCase(payload.getSender().getLogin())) {
            return true;
        }

        return payload.getComment() != null
                && payload.getComment().getUser() != null
                && botUsername.equalsIgnoreCase(payload.getComment().getUser().getLogin());
    }

    /**
     * Returns the bot alias used for @-mention detection.
     */
    public String getBotAlias(Bot bot) {
        return "@" + bot.getUsername();
    }

    private GiteaApiClient createGiteaClient(Bot bot) {
        GitIntegration gitIntegration = bot.getGitIntegration();
        RestClient restClient = giteaClientFactory.getClient(gitIntegration);
        return new GiteaApiClient(restClient, gitIntegration.getUrl());
    }
}

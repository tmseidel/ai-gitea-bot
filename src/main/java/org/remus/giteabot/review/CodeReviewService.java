package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.remus.giteabot.anthropic.AnthropicClient;
import org.remus.giteabot.anthropic.model.AnthropicRequest;
import org.remus.giteabot.config.BotConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.gitea.model.GiteaReview;
import org.remus.giteabot.gitea.model.GiteaReviewComment;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.session.ReviewSession;
import org.remus.giteabot.session.SessionService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class CodeReviewService {

    static final int MAX_DIFF_CHARS_FOR_CONTEXT = 60000;

    private final GiteaApiClient giteaApiClient;
    private final AnthropicClient anthropicClient;
    private final PromptService promptService;
    private final SessionService sessionService;
    private final BotConfigProperties botConfig;

    public CodeReviewService(GiteaApiClient giteaApiClient, AnthropicClient anthropicClient,
                             PromptService promptService, SessionService sessionService,
                             BotConfigProperties botConfig) {
        this.giteaApiClient = giteaApiClient;
        this.anthropicClient = anthropicClient;
        this.promptService = promptService;
        this.sessionService = sessionService;
        this.botConfig = botConfig;
    }

    @Async
    public void reviewPullRequest(WebhookPayload payload, String promptName) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        String prTitle = payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest().getBody();

        log.info("Starting code review for PR #{} '{}' in {}/{}, prompt={}", prNumber, prTitle, owner, repo, promptName);

        try {
            String giteaToken = promptService.resolveGiteaToken(promptName, null);
            String diff = giteaApiClient.getPullRequestDiff(owner, repo, prNumber, giteaToken);
            if (diff == null || diff.isBlank()) {
                log.warn("No diff found for PR #{} in {}/{}", prNumber, owner, repo);
                return;
            }

            String systemPrompt = promptService.getSystemPrompt(promptName);
            String modelOverride = promptService.resolveModel(promptName, null);

            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, promptName);

            String review;
            if (session.getMessages().isEmpty()) {
                // Initial review: use the chunked diff review for thoroughness
                review = anthropicClient.reviewDiff(prTitle, prBody, diff, systemPrompt, modelOverride);

                // Store a summary user message and the review in the session
                String userSummary = buildPrSummaryMessage(prTitle, prBody);
                sessionService.addMessage(session, "user", userSummary);
                sessionService.addMessage(session, "assistant", review);
            } else {
                // PR was updated: use conversation context with new diff
                String updateMessage = buildPrUpdateMessage(prTitle, diff);
                List<AnthropicRequest.Message> history = sessionService.toAnthropicMessages(session);

                review = anthropicClient.chat(history, updateMessage, systemPrompt, modelOverride);

                sessionService.addMessage(session, "user", updateMessage);
                sessionService.addMessage(session, "assistant", review);
            }

            String commentBody = formatReviewComment(review);
            giteaApiClient.postReviewComment(owner, repo, prNumber, commentBody, giteaToken);

            log.info("Code review completed for PR #{} in {}/{}", prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Code review failed for PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage(), e);
        }
    }

    @Async
    public void handleBotCommand(WebhookPayload payload, String promptName) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getIssue().getNumber();
        Long commentId = payload.getComment().getId();
        String commentBody = payload.getComment().getBody();

        log.info("Handling bot command in comment #{} for PR #{} in {}/{}", commentId, prNumber, owner, repo);

        try {
            String giteaToken = promptService.resolveGiteaToken(promptName, null);

            // Add eyes reaction to acknowledge the comment
            try {
                giteaApiClient.addReaction(owner, repo, commentId, "eyes", giteaToken);
            } catch (Exception e) {
                log.warn("Failed to add reaction to comment #{}: {}", commentId, e.getMessage());
            }

            String systemPrompt = promptService.getSystemPrompt(promptName);
            String modelOverride = promptService.resolveModel(promptName, null);

            // Get or create session
            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, promptName);

            // If session is empty, add context from the PR
            if (session.getMessages().isEmpty()) {
                String diff = giteaApiClient.getPullRequestDiff(owner, repo, prNumber, giteaToken);
                var prContext = buildPrContextString(payload, diff);
                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            // Send the comment as a new message in the conversation
            List<AnthropicRequest.Message> history = sessionService.toAnthropicMessages(session);
            String response = anthropicClient.chat(history, commentBody, systemPrompt, modelOverride);

            // Store messages in session
            sessionService.addMessage(session, "user", commentBody);
            sessionService.addMessage(session, "assistant", response);

            // Post the response as a comment on the PR
            String formattedResponse = formatBotResponse(response);
            giteaApiClient.postComment(owner, repo, prNumber, formattedResponse, giteaToken);

            log.info("Bot command handled for comment #{} on PR #{} in {}/{}", commentId, prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Failed to handle bot command for comment #{} on PR #{} in {}/{}: {}",
                    commentId, prNumber, owner, repo, e.getMessage(), e);
        }
    }

    private static @NonNull String buildPrContextString(WebhookPayload payload, String diff) {
        String prContext = "This is a pull request. " +
                "Title: " + payload.getIssue().getTitle() + "\n" +
                "Description: " + (payload.getIssue().getBody() != null ? payload.getIssue().getBody() : "N/A");
        if (diff != null && !diff.isBlank()) {
            // Truncate diff to avoid excessively large context
            String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                    ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(truncated)" : diff;
            prContext += "\n\nDiff:\n```diff\n" + truncatedDiff + "\n```";
        }
        return prContext;
    }

    @Async
    public void handleInlineComment(WebhookPayload payload, String promptName) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = resolvePrNumber(payload);
        Long commentId = payload.getComment().getId();
        String commentBody = payload.getComment().getBody();
        String filePath = payload.getComment().getPath();
        String diffHunk = payload.getComment().getDiffHunk();
        Integer line = payload.getComment().getLine();

        log.info("Handling inline comment #{} on file {} for PR #{} in {}/{}",
                commentId, filePath, prNumber, owner, repo);

        try {
            String giteaToken = promptService.resolveGiteaToken(promptName, null);

            // Add eyes reaction to acknowledge the comment
            try {
                giteaApiClient.addReaction(owner, repo, commentId, "eyes", giteaToken);
            } catch (Exception e) {
                log.warn("Failed to add reaction to inline comment #{}: {}", commentId, e.getMessage());
            }

            String systemPrompt = promptService.getSystemPrompt(promptName);
            String modelOverride = promptService.resolveModel(promptName, null);

            // Get or create session for conversation context
            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, promptName);

            // If session is empty, add PR context
            if (session.getMessages().isEmpty()) {
                String diff = giteaApiClient.getPullRequestDiff(owner, repo, prNumber, giteaToken);
                String prTitle = payload.getIssue() != null ? payload.getIssue().getTitle() : "";
                String prBody = payload.getIssue() != null ? payload.getIssue().getBody() : null;
                String prContext = "This is a pull request in " + owner + "/" + repo + ".";
                if (prTitle != null && !prTitle.isBlank()) {
                    prContext += " Title: " + prTitle;
                }
                if (prBody != null && !prBody.isBlank()) {
                    prContext += "\nDescription: " + prBody;
                }
                if (diff != null && !diff.isBlank()) {
                    String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                            ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(truncated)" : diff;
                    prContext += "\n\nDiff:\n```diff\n" + truncatedDiff + "\n```";
                }
                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            var formattedResponse = buildInlineCommentAndSend(filePath, diffHunk, commentBody, session, systemPrompt, modelOverride);
            if (line != null && line > 0) {
                try {
                    giteaApiClient.postInlineReviewComment(owner, repo, prNumber,
                            filePath, line, formattedResponse, giteaToken);
                } catch (Exception e) {
                    log.warn("Failed to post inline reply, falling back to regular comment: {}", e.getMessage());
                    giteaApiClient.postComment(owner, repo, prNumber, formattedResponse, giteaToken);
                }
            } else {
                giteaApiClient.postComment(owner, repo, prNumber, formattedResponse, giteaToken);
            }

            log.info("Inline comment handled for comment #{} on PR #{} in {}/{}",
                    commentId, prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Failed to handle inline comment #{} on PR #{} in {}/{}: {}",
                    commentId, prNumber, owner, repo, e.getMessage(), e);
        }
    }

    private String buildInlineCommentAndSend(String filePath, String diffHunk, String commentBody, ReviewSession session, String systemPrompt, String modelOverride) {
        // Build context message with file/code context
        String contextMessage = buildInlineCommentContext(filePath, diffHunk, commentBody);

        // Send to Claude
        List<AnthropicRequest.Message> history = sessionService.toAnthropicMessages(session);
        String response = anthropicClient.chat(history, contextMessage, systemPrompt, modelOverride);

        // Store in session
        sessionService.addMessage(session, "user", contextMessage);
        sessionService.addMessage(session, "assistant", response);

        // Reply inline at the same file/line if possible
        return formatBotResponse(response);
    }

    /**
     * Handles a review submitted event (action: "reviewed").
     * Gitea sends this when a user submits a review with inline comments.
     * The individual comments are NOT in the webhook payload, so we fetch them
     * from the Gitea API, filter for bot mentions, and respond to each.
     */
    @Async
    public void handleReviewSubmitted(WebhookPayload payload, String promptName) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();

        log.info("Handling review submitted event for PR #{} in {}/{}", prNumber, owner, repo);

        try {
            String giteaToken = promptService.resolveGiteaToken(promptName, null);
            String systemPrompt = promptService.getSystemPrompt(promptName);
            String modelOverride = promptService.resolveModel(promptName, null);

            // Fetch all reviews for the PR to find the latest one
            List<GiteaReview> reviews = giteaApiClient.getReviews(owner, repo, prNumber, giteaToken);
            if (reviews.isEmpty()) {
                log.warn("No reviews found for PR #{} in {}/{}", prNumber, owner, repo);
                return;
            }

            // Take the latest review (highest ID)
            GiteaReview latestReview = reviews.stream()
                    .reduce((a, b) -> (b.getId() != null && (a.getId() == null || b.getId() > a.getId())) ? b : a)
                    .orElse(null);

            if (latestReview == null || latestReview.getId() == null) {
                log.warn("No valid review found for PR #{} in {}/{}", prNumber, owner, repo);
                return;
            }

            log.info("Processing latest review #{} for PR #{} in {}/{}", latestReview.getId(), prNumber, owner, repo);

            // Fetch comments for this review
            List<GiteaReviewComment> comments = giteaApiClient.getReviewComments(
                    owner, repo, prNumber, latestReview.getId(), giteaToken);

            // Filter for comments that mention the bot
            String botAlias = botConfig.getAlias();
            List<GiteaReviewComment> botMentionComments = comments.stream()
                    .filter(c -> c.getBody() != null && c.getBody().contains(botAlias))
                    .toList();

            if (botMentionComments.isEmpty()) {
                log.debug("No bot-mentioning comments in review #{} for PR #{}", latestReview.getId(), prNumber);
                return;
            }

            log.info("Found {} bot-mentioning comment(s) in review #{} for PR #{}",
                    botMentionComments.size(), latestReview.getId(), prNumber);

            // Get or create session
            ReviewSession session = sessionService.getOrCreateSession(owner, repo, prNumber, promptName);

            // If session is empty, add PR context
            if (session.getMessages().isEmpty()) {
                String diff = giteaApiClient.getPullRequestDiff(owner, repo, prNumber, giteaToken);
                String prTitle = payload.getPullRequest().getTitle();
                String prBody = payload.getPullRequest().getBody();
                String prContext = "This is a pull request in " + owner + "/" + repo + ".";
                if (prTitle != null && !prTitle.isBlank()) {
                    prContext += " Title: " + prTitle;
                }
                if (prBody != null && !prBody.isBlank()) {
                    prContext += "\nDescription: " + prBody;
                }
                if (diff != null && !diff.isBlank()) {
                    String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                            ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(truncated)" : diff;
                    prContext += "\n\nDiff:\n```diff\n" + truncatedDiff + "\n```";
                }
                sessionService.addMessage(session, "user", prContext);
                sessionService.addMessage(session, "assistant",
                        "I've reviewed the pull request context. How can I help you?");
            }

            // Process each bot-mentioning comment
            for (GiteaReviewComment reviewComment : botMentionComments) {
                try {
                    processReviewComment(owner, repo, prNumber, reviewComment,
                            session, systemPrompt, modelOverride, giteaToken);
                } catch (Exception e) {
                    log.error("Failed to process review comment #{} on PR #{} in {}/{}: {}",
                            reviewComment.getId(), prNumber, owner, repo, e.getMessage(), e);
                }
            }

            log.info("Review submitted event handled for PR #{} in {}/{}", prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Failed to handle review submitted event for PR #{} in {}/{}: {}",
                    prNumber, owner, repo, e.getMessage(), e);
        }
    }

    private void processReviewComment(String owner, String repo, Long prNumber,
                                      GiteaReviewComment reviewComment, ReviewSession session,
                                      String systemPrompt, String modelOverride, String giteaToken) {
        Long commentId = reviewComment.getId();
        String filePath = reviewComment.getPath();
        String diffHunk = reviewComment.getDiffHunk();
        String commentBody = reviewComment.getBody();
        Integer line = reviewComment.getLine();

        log.info("Processing review comment #{} on file {} for PR #{}", commentId, filePath, prNumber);

        // Add eyes reaction to acknowledge
        try {
            giteaApiClient.addReaction(owner, repo, commentId, "eyes", giteaToken);
        } catch (Exception e) {
            log.warn("Failed to add reaction to review comment #{}: {}", commentId, e.getMessage());
        }

        // Build context message
        var formattedResponse = buildInlineCommentAndSend(filePath, diffHunk, commentBody, session, systemPrompt, modelOverride);
        if (line != null && line > 0) {
            try {
                giteaApiClient.postInlineReviewComment(owner, repo, prNumber,
                        filePath, line, formattedResponse, giteaToken);
            } catch (Exception e) {
                log.warn("Failed to post inline reply for review comment #{}, falling back to regular comment: {}",
                        commentId, e.getMessage());
                giteaApiClient.postComment(owner, repo, prNumber, formattedResponse, giteaToken);
            }
        } else {
            giteaApiClient.postComment(owner, repo, prNumber, formattedResponse, giteaToken);
        }
    }

    public void handlePrClosed(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();

        log.info("PR #{} in {}/{} was closed, deleting session", prNumber, owner, repo);
        sessionService.deleteSession(owner, repo, prNumber);
    }

    String formatReviewComment(String review) {
        return "## 🤖 AI Code Review\n\n" + review +
                "\n\n---\n*Automated review by Anthropic Gitea Bot*";
    }

    String formatBotResponse(String response) {
        return "## 🤖 Bot Response\n\n" + response +
                "\n\n---\n*Response by Anthropic Gitea Bot*";
    }

    private String buildPrSummaryMessage(String prTitle, String prBody) {
        StringBuilder sb = new StringBuilder();
        sb.append("I opened a pull request titled '").append(prTitle).append("'.");
        if (prBody != null && !prBody.isBlank()) {
            sb.append(" Description: ").append(prBody);
        }
        sb.append(" Please review it.");
        return sb.toString();
    }

    private String buildPrUpdateMessage(String prTitle, String diff) {
        // Truncate diff for conversation context to avoid excessively large messages
        String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(truncated)" : diff;
        return "The pull request '" + prTitle + "' has been updated with new changes. " +
                "Please review the updated diff:\n```diff\n" + truncatedDiff + "\n```";
    }

    String buildInlineCommentContext(String filePath, String diffHunk, String commentBody) {
        StringBuilder sb = new StringBuilder();
        sb.append("Someone left an inline review comment on file `").append(filePath).append("`.\n\n");
        if (diffHunk != null && !diffHunk.isBlank()) {
            sb.append("Code context (diff hunk):\n```diff\n").append(diffHunk).append("\n```\n\n");
        }
        sb.append("Their comment/question:\n").append(commentBody);
        return sb.toString();
    }

    Long resolvePrNumber(WebhookPayload payload) {
        if (payload.getIssue() != null && payload.getIssue().getNumber() != null) {
            return payload.getIssue().getNumber();
        }
        if (payload.getPullRequest() != null && payload.getPullRequest().getNumber() != null) {
            return payload.getPullRequest().getNumber();
        }
        return null;
    }
}

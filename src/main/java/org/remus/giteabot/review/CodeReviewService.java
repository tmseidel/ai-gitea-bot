package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.anthropic.AnthropicClient;
import org.remus.giteabot.anthropic.model.AnthropicRequest;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.session.ReviewSession;
import org.remus.giteabot.session.SessionService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class CodeReviewService {

    static final int MAX_DIFF_CHARS_FOR_CONTEXT = 60000;

    private final GiteaApiClient giteaApiClient;
    private final AnthropicClient anthropicClient;
    private final PromptService promptService;
    private final SessionService sessionService;

    public CodeReviewService(GiteaApiClient giteaApiClient, AnthropicClient anthropicClient,
                             PromptService promptService, SessionService sessionService) {
        this.giteaApiClient = giteaApiClient;
        this.anthropicClient = anthropicClient;
        this.promptService = promptService;
        this.sessionService = sessionService;
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
                String prContext = "This is a pull request. " +
                        "Title: " + payload.getIssue().getTitle() + "\n" +
                        "Description: " + (payload.getIssue().getBody() != null ? payload.getIssue().getBody() : "N/A");
                if (diff != null && !diff.isBlank()) {
                    // Truncate diff to avoid excessively large context
                    String truncatedDiff = diff.length() > MAX_DIFF_CHARS_FOR_CONTEXT
                            ? diff.substring(0, MAX_DIFF_CHARS_FOR_CONTEXT) + "\n...(truncated)" : diff;
                    prContext += "\n\nDiff:\n```diff\n" + truncatedDiff + "\n```";
                }
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
}

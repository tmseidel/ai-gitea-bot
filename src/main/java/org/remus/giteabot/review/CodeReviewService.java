package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.anthropic.AnthropicClient;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CodeReviewService {

    private final GiteaApiClient giteaApiClient;
    private final AnthropicClient anthropicClient;
    private final PromptService promptService;

    public CodeReviewService(GiteaApiClient giteaApiClient, AnthropicClient anthropicClient,
                             PromptService promptService) {
        this.giteaApiClient = giteaApiClient;
        this.anthropicClient = anthropicClient;
        this.promptService = promptService;
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

            String review = anthropicClient.reviewDiff(prTitle, prBody, diff, systemPrompt, modelOverride);

            String commentBody = formatReviewComment(review);
            giteaApiClient.postReviewComment(owner, repo, prNumber, commentBody, giteaToken);

            log.info("Code review completed for PR #{} in {}/{}", prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Code review failed for PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage(), e);
        }
    }

    String formatReviewComment(String review) {
        return "## 🤖 AI Code Review\n\n" + review +
                "\n\n---\n*Automated review by Anthropic Gitea Bot*";
    }
}

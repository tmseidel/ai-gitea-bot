package org.remus.giteabot.review;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.anthropic.AnthropicClient;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CodeReviewService {

    private final GiteaApiClient giteaApiClient;
    private final AnthropicClient anthropicClient;

    public CodeReviewService(GiteaApiClient giteaApiClient, AnthropicClient anthropicClient) {
        this.giteaApiClient = giteaApiClient;
        this.anthropicClient = anthropicClient;
    }

    public void reviewPullRequest(WebhookPayload payload) {
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();
        Long prNumber = payload.getPullRequest().getNumber();
        String prTitle = payload.getPullRequest().getTitle();
        String prBody = payload.getPullRequest().getBody();

        log.info("Starting code review for PR #{} '{}' in {}/{}", prNumber, prTitle, owner, repo);

        String diff = giteaApiClient.getPullRequestDiff(owner, repo, prNumber);
        if (diff == null || diff.isBlank()) {
            log.warn("No diff found for PR #{} in {}/{}", prNumber, owner, repo);
            return;
        }

        String review = anthropicClient.reviewDiff(prTitle, prBody, diff);

        String commentBody = formatReviewComment(review);
        giteaApiClient.postReviewComment(owner, repo, prNumber, commentBody);

        log.info("Code review completed for PR #{} in {}/{}", prNumber, owner, repo);
    }

    String formatReviewComment(String review) {
        return "## 🤖 AI Code Review\n\n" + review +
                "\n\n---\n*Automated review by Anthropic Gitea Bot*";
    }
}

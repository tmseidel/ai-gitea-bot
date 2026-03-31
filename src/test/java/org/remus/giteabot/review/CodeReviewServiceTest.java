package org.remus.giteabot.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.anthropic.AnthropicClient;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodeReviewServiceTest {

    @Mock
    private GiteaApiClient giteaApiClient;

    @Mock
    private AnthropicClient anthropicClient;

    @InjectMocks
    private CodeReviewService codeReviewService;

    @Test
    void reviewPullRequest_postsReview() {
        WebhookPayload payload = createTestPayload();

        when(giteaApiClient.getPullRequestDiff("testowner", "testrepo", 1L))
                .thenReturn("diff --git a/file.txt b/file.txt\n+new line");
        when(anthropicClient.reviewDiff(eq("Test PR"), eq("Test body"), anyString()))
                .thenReturn("Looks good!");

        codeReviewService.reviewPullRequest(payload);

        verify(giteaApiClient).postReviewComment(
                eq("testowner"), eq("testrepo"), eq(1L), contains("Looks good!"));
    }

    @Test
    void reviewPullRequest_emptyDiff_skipsReview() {
        WebhookPayload payload = createTestPayload();

        when(giteaApiClient.getPullRequestDiff("testowner", "testrepo", 1L))
                .thenReturn("");

        codeReviewService.reviewPullRequest(payload);

        verify(anthropicClient, never()).reviewDiff(anyString(), anyString(), anyString());
        verify(giteaApiClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void formatReviewComment_containsHeader() {
        String result = codeReviewService.formatReviewComment("some review text");
        assert result.contains("🤖 AI Code Review");
        assert result.contains("some review text");
        assert result.contains("Automated review by Anthropic Gitea Bot");
    }

    private WebhookPayload createTestPayload() {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("opened");

        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(1L);
        pr.setTitle("Test PR");
        pr.setBody("Test body");
        payload.setPullRequest(pr);

        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("testowner");

        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("testrepo");
        repository.setFullName("testowner/testrepo");
        repository.setOwner(owner);
        payload.setRepository(repository);

        return payload;
    }
}

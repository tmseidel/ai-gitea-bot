package org.remus.giteabot.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.anthropic.AnthropicClient;
import org.remus.giteabot.config.PromptService;
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

    @Mock
    private PromptService promptService;

    @InjectMocks
    private CodeReviewService codeReviewService;

    @Test
    void reviewPullRequest_postsReview() {
        WebhookPayload payload = createTestPayload();

        when(promptService.resolveGiteaToken(isNull(), isNull())).thenReturn(null);
        when(promptService.getSystemPrompt(isNull())).thenReturn("test system prompt");
        when(promptService.resolveModel(isNull(), isNull())).thenReturn(null);
        when(giteaApiClient.getPullRequestDiff("testowner", "testrepo", 1L, null))
                .thenReturn("diff --git a/file.txt b/file.txt\n+new line");
        when(anthropicClient.reviewDiff(eq("Test PR"), eq("Test body"), anyString(),
                eq("test system prompt"), isNull()))
                .thenReturn("Looks good!");

        codeReviewService.reviewPullRequest(payload, null);

        verify(giteaApiClient).postReviewComment(
                eq("testowner"), eq("testrepo"), eq(1L), contains("Looks good!"), isNull());
    }

    @Test
    void reviewPullRequest_emptyDiff_skipsReview() {
        WebhookPayload payload = createTestPayload();

        when(promptService.resolveGiteaToken(isNull(), isNull())).thenReturn(null);
        when(giteaApiClient.getPullRequestDiff("testowner", "testrepo", 1L, null))
                .thenReturn("");

        codeReviewService.reviewPullRequest(payload, null);

        verify(anthropicClient, never()).reviewDiff(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(giteaApiClient, never()).postReviewComment(anyString(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void reviewPullRequest_withPromptName_usesPromptConfig() {
        WebhookPayload payload = createTestPayload();

        when(promptService.resolveGiteaToken(eq("security"), isNull())).thenReturn("custom-token");
        when(promptService.getSystemPrompt("security")).thenReturn("You are a security reviewer.");
        when(promptService.resolveModel(eq("security"), isNull())).thenReturn("claude-opus-4-20250514");
        when(giteaApiClient.getPullRequestDiff("testowner", "testrepo", 1L, "custom-token"))
                .thenReturn("diff --git a/file.txt b/file.txt\n+new line");
        when(anthropicClient.reviewDiff(eq("Test PR"), eq("Test body"), anyString(),
                eq("You are a security reviewer."), eq("claude-opus-4-20250514")))
                .thenReturn("Security looks good!");

        codeReviewService.reviewPullRequest(payload, "security");

        verify(giteaApiClient).postReviewComment(
                eq("testowner"), eq("testrepo"), eq(1L), contains("Security looks good!"), eq("custom-token"));
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

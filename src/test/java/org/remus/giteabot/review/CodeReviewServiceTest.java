package org.remus.giteabot.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.anthropic.AnthropicClient;
import org.remus.giteabot.anthropic.model.AnthropicRequest;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.session.ReviewSession;
import org.remus.giteabot.session.SessionService;

import java.util.List;

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

    @Mock
    private SessionService sessionService;

    @InjectMocks
    private CodeReviewService codeReviewService;

    @Test
    void reviewPullRequest_postsReview() {
        WebhookPayload payload = createTestPayload();
        ReviewSession session = new ReviewSession("testowner", "testrepo", 1L, null);

        when(promptService.resolveGiteaToken(isNull(), isNull())).thenReturn(null);
        when(promptService.getSystemPrompt(isNull())).thenReturn("test system prompt");
        when(promptService.resolveModel(isNull(), isNull())).thenReturn(null);
        when(sessionService.getOrCreateSession("testowner", "testrepo", 1L, null)).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
        when(giteaApiClient.getPullRequestDiff("testowner", "testrepo", 1L, null))
                .thenReturn("diff --git a/file.txt b/file.txt\n+new line");
        when(anthropicClient.reviewDiff(eq("Test PR"), eq("Test body"), anyString(),
                eq("test system prompt"), isNull()))
                .thenReturn("Looks good!");

        codeReviewService.reviewPullRequest(payload, null);

        verify(giteaApiClient).postReviewComment(
                eq("testowner"), eq("testrepo"), eq(1L), contains("Looks good!"), isNull());
        verify(sessionService).getOrCreateSession("testowner", "testrepo", 1L, null);
        verify(sessionService, times(2)).addMessage(any(), anyString(), anyString());
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
        ReviewSession session = new ReviewSession("testowner", "testrepo", 1L, "security");

        when(promptService.resolveGiteaToken(eq("security"), isNull())).thenReturn("custom-token");
        when(promptService.getSystemPrompt("security")).thenReturn("You are a security reviewer.");
        when(promptService.resolveModel(eq("security"), isNull())).thenReturn("claude-opus-4-20250514");
        when(sessionService.getOrCreateSession("testowner", "testrepo", 1L, "security")).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
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
    void reviewPullRequest_existingSession_usesChat() {
        WebhookPayload payload = createTestPayload();
        ReviewSession session = new ReviewSession("testowner", "testrepo", 1L, null);
        session.addMessage("user", "Previous question");
        session.addMessage("assistant", "Previous answer");

        when(promptService.resolveGiteaToken(isNull(), isNull())).thenReturn(null);
        when(promptService.getSystemPrompt(isNull())).thenReturn("test prompt");
        when(promptService.resolveModel(isNull(), isNull())).thenReturn(null);
        when(sessionService.getOrCreateSession("testowner", "testrepo", 1L, null)).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
        when(sessionService.toAnthropicMessages(session)).thenReturn(List.of(
                AnthropicRequest.Message.builder().role("user").content("Previous question").build(),
                AnthropicRequest.Message.builder().role("assistant").content("Previous answer").build()
        ));
        when(giteaApiClient.getPullRequestDiff("testowner", "testrepo", 1L, null))
                .thenReturn("new diff content");
        when(anthropicClient.chat(anyList(), anyString(), eq("test prompt"), isNull()))
                .thenReturn("Updated review");

        codeReviewService.reviewPullRequest(payload, null);

        verify(anthropicClient).chat(anyList(), anyString(), eq("test prompt"), isNull());
        verify(anthropicClient, never()).reviewDiff(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void handleBotCommand_addsReactionAndResponds() {
        WebhookPayload payload = createCommentPayload("@claude_bot explain this");
        ReviewSession session = new ReviewSession("testowner", "testrepo", 1L, null);
        session.addMessage("user", "Initial context");
        session.addMessage("assistant", "Initial review");

        when(promptService.resolveGiteaToken(isNull(), isNull())).thenReturn(null);
        when(promptService.getSystemPrompt(isNull())).thenReturn("test prompt");
        when(promptService.resolveModel(isNull(), isNull())).thenReturn(null);
        when(sessionService.getOrCreateSession("testowner", "testrepo", 1L, null)).thenReturn(session);
        when(sessionService.addMessage(any(), anyString(), anyString())).thenReturn(session);
        when(sessionService.toAnthropicMessages(session)).thenReturn(List.of(
                AnthropicRequest.Message.builder().role("user").content("Initial context").build(),
                AnthropicRequest.Message.builder().role("assistant").content("Initial review").build()
        ));
        when(anthropicClient.chat(anyList(), eq("@claude_bot explain this"), eq("test prompt"), isNull()))
                .thenReturn("Here's my explanation");

        codeReviewService.handleBotCommand(payload, null);

        verify(giteaApiClient).addReaction("testowner", "testrepo", 42L, "eyes", null);
        verify(giteaApiClient).postComment(eq("testowner"), eq("testrepo"), eq(1L), contains("Here's my explanation"), isNull());
    }

    @Test
    void handlePrClosed_deletesSession() {
        WebhookPayload payload = createTestPayload();

        codeReviewService.handlePrClosed(payload);

        verify(sessionService).deleteSession("testowner", "testrepo", 1L);
    }

    @Test
    void formatReviewComment_containsHeader() {
        String result = codeReviewService.formatReviewComment("some review text");
        assert result.contains("🤖 AI Code Review");
        assert result.contains("some review text");
        assert result.contains("Automated review by Anthropic Gitea Bot");
    }

    @Test
    void formatBotResponse_containsHeader() {
        String result = codeReviewService.formatBotResponse("some response");
        assert result.contains("🤖 Bot Response");
        assert result.contains("some response");
        assert result.contains("Response by Anthropic Gitea Bot");
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

    private WebhookPayload createCommentPayload(String body) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("created");

        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        comment.setId(42L);
        comment.setBody(body);
        WebhookPayload.Owner commentUser = new WebhookPayload.Owner();
        commentUser.setLogin("testuser");
        comment.setUser(commentUser);
        payload.setComment(comment);

        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(1L);
        issue.setTitle("Test PR");
        issue.setBody("Test body");
        issue.setPullRequest(new WebhookPayload.IssuePullRequest());
        payload.setIssue(issue);

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

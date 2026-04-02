package org.remus.giteabot.gitea;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.BotConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.review.CodeReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GiteaWebhookController.class)
@ActiveProfiles("test")
class GiteaWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CodeReviewService codeReviewService;

    @MockitoBean
    private BotConfigProperties botConfigProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void handleWebhook_prOpened_triggersReview() throws Exception {
        WebhookPayload payload = createTestPayload("opened");

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(codeReviewService).reviewPullRequest(any(WebhookPayload.class), isNull());
    }

    @Test
    void handleWebhook_prSynchronized_triggersReview() throws Exception {
        WebhookPayload payload = createTestPayload("synchronized");

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(codeReviewService).reviewPullRequest(any(WebhookPayload.class), isNull());
    }

    @Test
    void handleWebhook_prClosed_closesSession() throws Exception {
        WebhookPayload payload = createTestPayload("closed");

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("session closed"));

        verify(codeReviewService).handlePrClosed(any(WebhookPayload.class));
    }

    @Test
    void handleWebhook_noPullRequest_ignored() throws Exception {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("push");

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(codeReviewService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void handleWebhook_withPromptParam_passesPromptName() throws Exception {
        WebhookPayload payload = createTestPayload("opened");

        mockMvc.perform(post("/api/webhook")
                        .param("prompt", "security")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(codeReviewService).reviewPullRequest(any(WebhookPayload.class), eq("security"));
    }

    @Test
    void handleWebhook_commentWithBotMention_triggersCommand() throws Exception {
        when(botConfigProperties.getAlias()).thenReturn("@claude_bot");

        String payload = """
                {
                    "action": "created",
                    "comment": {
                        "id": 42,
                        "body": "@claude_bot please explain this code",
                        "user": {"login": "testuser"}
                    },
                    "issue": {
                        "number": 1,
                        "title": "Test PR",
                        "pull_request": {}
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testowner/testrepo",
                        "owner": {"login": "testowner"}
                    }
                }
                """;

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("command received"));

        verify(codeReviewService).handleBotCommand(any(WebhookPayload.class), isNull());
    }

    @Test
    void handleWebhook_commentWithoutBotMention_ignored() throws Exception {
        when(botConfigProperties.getAlias()).thenReturn("@claude_bot");

        String payload = """
                {
                    "action": "created",
                    "comment": {
                        "id": 42,
                        "body": "just a regular comment",
                        "user": {"login": "testuser"}
                    },
                    "issue": {
                        "number": 1,
                        "title": "Test PR",
                        "pull_request": {}
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testowner/testrepo",
                        "owner": {"login": "testowner"}
                    }
                }
                """;

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(codeReviewService, never()).handleBotCommand(any(), any());
    }

    @Test
    void handleWebhook_commentOnNonPrIssue_ignored() throws Exception {
        when(botConfigProperties.getAlias()).thenReturn("@claude_bot");

        String payload = """
                {
                    "action": "created",
                    "comment": {
                        "id": 42,
                        "body": "@claude_bot help",
                        "user": {"login": "testuser"}
                    },
                    "issue": {
                        "number": 1,
                        "title": "Not a PR"
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testowner/testrepo",
                        "owner": {"login": "testowner"}
                    }
                }
                """;

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(codeReviewService, never()).handleBotCommand(any(), any());
    }

    @Test
    void handleWebhook_inlineCommentWithBotMention_triggersInlineHandler() throws Exception {
        when(botConfigProperties.getAlias()).thenReturn("@claude_bot");

        String payload = """
                {
                    "action": "created",
                    "comment": {
                        "id": 55,
                        "body": "@claude_bot explain this code",
                        "user": {"login": "testuser"},
                        "path": "src/main/java/Foo.java",
                        "diff_hunk": "@@ -10,7 +10,7 @@\\n some code context",
                        "line": 15
                    },
                    "issue": {
                        "number": 3,
                        "title": "Refactor PR",
                        "pull_request": {}
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testowner/testrepo",
                        "owner": {"login": "testowner"}
                    }
                }
                """;

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("inline comment response triggered"));

        verify(codeReviewService).handleInlineComment(any(WebhookPayload.class), isNull());
        verify(codeReviewService, never()).handleBotCommand(any(), any());
    }

    @Test
    void handleWebhook_inlineCommentWithoutBotMention_ignored() throws Exception {
        when(botConfigProperties.getAlias()).thenReturn("@claude_bot");

        String payload = """
                {
                    "action": "created",
                    "comment": {
                        "id": 55,
                        "body": "just a regular inline comment",
                        "user": {"login": "testuser"},
                        "path": "src/main/java/Foo.java",
                        "line": 15
                    },
                    "issue": {
                        "number": 3,
                        "title": "Refactor PR",
                        "pull_request": {}
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testowner/testrepo",
                        "owner": {"login": "testowner"}
                    }
                }
                """;

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(codeReviewService, never()).handleInlineComment(any(), any());
    }

    @Test
    void handleWebhook_inlineCommentViaPullRequest_triggersInlineHandler() throws Exception {
        when(botConfigProperties.getAlias()).thenReturn("@claude_bot");

        String payload = """
                {
                    "action": "created",
                    "comment": {
                        "id": 56,
                        "body": "@claude_bot what does this do?",
                        "user": {"login": "testuser"},
                        "path": "src/main/java/Bar.java",
                        "diff_hunk": "@@ -1,5 +1,5 @@\\n code",
                        "line": 3,
                        "pull_request_review_id": 10
                    },
                    "pull_request": {
                        "number": 7,
                        "title": "Feature PR"
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testowner/testrepo",
                        "owner": {"login": "testowner"}
                    }
                }
                """;

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("inline comment response triggered"));

        verify(codeReviewService).handleInlineComment(any(WebhookPayload.class), isNull());
    }

    @Test
    void handleWebhook_reviewSubmitted_triggersReviewHandler() throws Exception {
        String payload = """
                {
                    "action": "reviewed",
                    "number": 2,
                    "pull_request": {
                        "id": 2,
                        "number": 2,
                        "title": "Test PR"
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testowner/testrepo",
                        "owner": {"login": "testowner"}
                    },
                    "sender": {
                        "login": "tom"
                    },
                    "review": {
                        "type": "pull_request_review_comment",
                        "content": ""
                    }
                }
                """;

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("review comments processing triggered"));

        verify(codeReviewService).handleReviewSubmitted(any(WebhookPayload.class), isNull());
    }

    @Test
    void handleWebhook_reviewSubmittedWithPrompt_passesPromptName() throws Exception {
        String payload = """
                {
                    "action": "reviewed",
                    "number": 2,
                    "pull_request": {
                        "id": 2,
                        "number": 2,
                        "title": "Test PR"
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testowner/testrepo",
                        "owner": {"login": "testowner"}
                    },
                    "sender": {
                        "login": "tom"
                    },
                    "review": {
                        "type": "pull_request_review_comment",
                        "content": ""
                    }
                }
                """;

        mockMvc.perform(post("/api/webhook")
                        .param("prompt", "security")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("review comments processing triggered"));

        verify(codeReviewService).handleReviewSubmitted(any(WebhookPayload.class), eq("security"));
    }

    private WebhookPayload createTestPayload(String action) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction(action);

        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(1L);
        pr.setTitle("Test PR");
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

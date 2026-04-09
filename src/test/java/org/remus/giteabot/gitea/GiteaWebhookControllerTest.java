package org.remus.giteabot.gitea;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotService;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
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
    private BotService botService;

    @MockitoBean
    private BotWebhookService botWebhookService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void handleBotWebhook_botFoundAndEnabled_prOpened_triggersReview() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        WebhookPayload payload = createTestPayload("opened");

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(botService).incrementWebhookCallCount(bot);
        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBotWebhook_botFoundAndEnabled_prSynchronized_triggersReview() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        WebhookPayload payload = createTestPayload("synchronized");

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(botWebhookService).reviewPullRequest(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBotWebhook_botFoundAndEnabled_prClosed_closesSession() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        WebhookPayload payload = createTestPayload("closed");

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("session closed"));

        verify(botWebhookService).handlePrClosed(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBotWebhook_botDisabled_returnsBotDisabled() throws Exception {
        Bot bot = createTestBot();
        bot.setEnabled(false);
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        WebhookPayload payload = createTestPayload("opened");

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("bot disabled"));

        verify(botWebhookService, never()).reviewPullRequest(any(), any());
        verify(botService, never()).incrementWebhookCallCount(any());
    }

    @Test
    void handleBotWebhook_botNotFound_returns404() throws Exception {
        when(botService.findByWebhookSecret("unknown-secret")).thenReturn(Optional.empty());

        WebhookPayload payload = createTestPayload("opened");

        mockMvc.perform(post("/api/webhook/unknown-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isNotFound());

        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void handleBotWebhook_commentWithBotMention_triggersCommand() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(botWebhookService.getBotAlias(bot)).thenReturn("@ai_bot");

        String payload = """
                {
                    "action": "created",
                    "comment": {
                        "id": 42,
                        "body": "@ai_bot please explain this code",
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

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("command received"));

        verify(botWebhookService).handleBotCommand(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBotWebhook_commentWithoutBotMention_ignored() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(botWebhookService.getBotAlias(bot)).thenReturn("@ai_bot");

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

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(botWebhookService, never()).handleBotCommand(any(), any());
    }

    @Test
    void handleBotWebhook_inlineCommentWithBotMention_triggersInlineHandler() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(botWebhookService.getBotAlias(bot)).thenReturn("@ai_bot");

        String payload = """
                {
                    "action": "created",
                    "comment": {
                        "id": 55,
                        "body": "@ai_bot explain this code",
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

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("inline comment response triggered"));

        verify(botWebhookService).handleInlineComment(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBotWebhook_reviewSubmitted_triggersReviewHandler() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        String payload = """
                {
                    "action": "reviewed",
                    "pull_request": {
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

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("review comments processing triggered"));

        verify(botWebhookService).handleReviewSubmitted(eq(bot), any(WebhookPayload.class));
    }

    @Test
    void handleBotWebhook_botSender_ignored() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));
        when(botWebhookService.isBotUser(eq(bot), any())).thenReturn(true);

        String payload = """
                {
                    "action": "opened",
                    "pull_request": {
                        "number": 1,
                        "title": "Test PR"
                    },
                    "repository": {
                        "name": "testrepo",
                        "full_name": "testowner/testrepo",
                        "owner": {"login": "testowner"}
                    },
                    "sender": {
                        "login": "ai_bot"
                    }
                }
                """;

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void handleBotWebhook_noPullRequest_ignored() throws Exception {
        Bot bot = createTestBot();
        when(botService.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        WebhookPayload payload = new WebhookPayload();
        payload.setAction("push");

        mockMvc.perform(post("/api/webhook/test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(botWebhookService, never()).reviewPullRequest(any(), any());
    }

    private Bot createTestBot() {
        Bot bot = new Bot();
        bot.setId(1L);
        bot.setName("Test Bot");
        bot.setUsername("ai_bot");
        bot.setWebhookSecret("test-secret");
        bot.setEnabled(true);

        AiIntegration ai = new AiIntegration();
        ai.setId(1L);
        ai.setName("Test AI");
        ai.setProviderType("anthropic");
        ai.setApiUrl("http://localhost:8081");
        ai.setModel("claude-sonnet-4-20250514");
        ai.setMaxTokens(4096);
        ai.setMaxDiffCharsPerChunk(120000);
        ai.setMaxDiffChunks(8);
        ai.setRetryTruncatedChunkChars(60000);
        ai.setCreatedAt(Instant.now());
        ai.setUpdatedAt(Instant.now());
        bot.setAiIntegration(ai);

        GitIntegration git = new GitIntegration();
        git.setId(1L);
        git.setName("Test Git");
        git.setProviderType("gitea");
        git.setUrl("http://localhost:3000");
        git.setToken("test-token");
        git.setCreatedAt(Instant.now());
        git.setUpdatedAt(Instant.now());
        bot.setGitIntegration(git);

        return bot;
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

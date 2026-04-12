package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.agent.DiffApplyService;
import org.remus.giteabot.agent.session.AgentSessionService;
import org.remus.giteabot.agent.validation.ToolExecutionService;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.config.PromptService;
import org.remus.giteabot.config.ReviewConfigProperties;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.session.SessionService;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class BotWebhookServiceTest {

    @Mock
    private AiClientFactory aiClientFactory;

    @Mock
    private GiteaClientFactory giteaClientFactory;

    @Mock
    private PromptService promptService;

    @Mock
    private SessionService sessionService;

    @Mock
    private AgentConfigProperties agentConfig;

    @Mock
    private AgentSessionService agentSessionService;

    @Mock
    private ToolExecutionService toolExecutionService;

    @Mock
    private DiffApplyService diffApplyService;

    @Mock
    private BotService botService;

    private BotWebhookService botWebhookService;

    @BeforeEach
    void setUp() {
        botWebhookService = new BotWebhookService(aiClientFactory, giteaClientFactory,
                promptService, sessionService, agentConfig, new ReviewConfigProperties(),
                agentSessionService, toolExecutionService, diffApplyService, botService);
    }

    @Test
    void isBotUser_senderMatchesBotUsername_returnsTrue() {
        Bot bot = createBot("test-bot", "ai_bot");
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("ai_bot");
        payload.setSender(sender);

        assertTrue(botWebhookService.isBotUser(bot, payload));
    }

    @Test
    void isBotUser_senderDoesNotMatch_returnsFalse() {
        Bot bot = createBot("test-bot", "ai_bot");
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("human_user");
        payload.setSender(sender);

        assertFalse(botWebhookService.isBotUser(bot, payload));
    }

    @Test
    void isBotUser_nullUsername_returnsFalse() {
        Bot bot = createBot("test-bot", null);
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin("human_user");
        payload.setSender(sender);

        assertFalse(botWebhookService.isBotUser(bot, payload));
    }

    @Test
    void isBotUser_commentUserMatchesBotUsername_returnsTrue() {
        Bot bot = createBot("test-bot", "ai_bot");
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Comment comment = new WebhookPayload.Comment();
        WebhookPayload.Owner user = new WebhookPayload.Owner();
        user.setLogin("ai_bot");
        comment.setUser(user);
        payload.setComment(comment);

        assertTrue(botWebhookService.isBotUser(bot, payload));
    }

    @Test
    void getBotAlias_returnsMentionFormat() {
        Bot bot = createBot("test-bot", "ai_bot");
        assertEquals("@ai_bot", botWebhookService.getBotAlias(bot));
    }

    @Test
    void getBotAlias_nullUsername_returnsEmpty() {
        Bot bot = createBot("test-bot", null);
        assertEquals("", botWebhookService.getBotAlias(bot));
    }

    private Bot createBot(String name, String username) {
        Bot bot = new Bot();
        bot.setName(name);
        bot.setUsername(username);
        return bot;
    }
}

package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpringAiChatModelClientTest {

    private ChatModel mockChatModel() {
        return mock(ChatModel.class);
    }

    private SpringAiChatModelClient createClient(ChatModel chatModel) {
        return new SpringAiChatModelClient(chatModel, "test-model", 1024,
                5000, 4, 2000);
    }

    private ChatResponse mockChatResponse(String text) {
        AssistantMessage output = new AssistantMessage(text);
        Generation generation = new Generation(output);
        return new ChatResponse(List.of(generation));
    }

    @Test
    void reviewDiff_delegatesToChatModel() {
        ChatModel chatModel = mockChatModel();
        when(chatModel.call(any(Prompt.class))).thenReturn(mockChatResponse("Looks good!"));

        SpringAiChatModelClient client = createClient(chatModel);
        String result = client.reviewDiff("Test PR", "body", "some diff");

        assertTrue(result.contains("Looks good!"));
        verify(chatModel, atLeastOnce()).call(any(Prompt.class));
    }

    @Test
    void chat_delegatesToChatModel() {
        ChatModel chatModel = mockChatModel();
        when(chatModel.call(any(Prompt.class))).thenReturn(mockChatResponse("AI response"));

        SpringAiChatModelClient client = createClient(chatModel);
        List<AiMessage> history = List.of(
                AiMessage.builder().role("user").content("Hello").build(),
                AiMessage.builder().role("assistant").content("Hi!").build()
        );

        String result = client.chat(history, "How are you?", "system prompt", null);
        assertEquals("AI response", result);
    }

    @Test
    void chat_withMaxTokensOverride() {
        ChatModel chatModel = mockChatModel();
        when(chatModel.call(any(Prompt.class))).thenReturn(mockChatResponse("response"));

        SpringAiChatModelClient client = createClient(chatModel);
        String result = client.chat(List.of(), "message", "prompt", null, 8192);

        assertEquals("response", result);
    }

    @Test
    void reviewDiff_handlesEmptyResponse() {
        ChatModel chatModel = mockChatModel();
        // Return response with null output text
        ChatResponse emptyResponse = new ChatResponse(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(emptyResponse);

        SpringAiChatModelClient client = createClient(chatModel);
        String result = client.reviewDiff("Test PR", "body", "diff");

        // When the response is empty, the client returns a fallback message
        assertTrue(result.contains("Unable to generate"));
    }

    @Test
    void isPromptTooLongError_detectsAnthropicError() {
        SpringAiChatModelClient client = createClient(mockChatModel());

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"prompt is too long: 208154 tokens > 200000 maximum\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsOpenAiContextLengthError() {
        SpringAiChatModelClient client = createClient(mockChatModel());

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens.\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsTooManyTokens() {
        SpringAiChatModelClient client = createClient(mockChatModel());

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"too many tokens in the request\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsOllamaTooLong() {
        SpringAiChatModelClient client = createClient(mockChatModel());

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"input is too long\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsLlamaCppTokenLimit() {
        SpringAiChatModelClient client = createClient(mockChatModel());

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":\"token limit exceeded\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_ignoresUnrelatedErrors() {
        SpringAiChatModelClient client = createClient(mockChatModel());

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"invalid api key\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertFalse(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_handlesNullBody() {
        SpringAiChatModelClient client = createClient(mockChatModel());

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                null,
                StandardCharsets.UTF_8);

        assertFalse(client.isPromptTooLongError(ex));
    }

    @Test
    void chat_convertsMessageRolesCorrectly() {
        ChatModel chatModel = mockChatModel();
        when(chatModel.call(any(Prompt.class))).thenReturn(mockChatResponse("response"));

        SpringAiChatModelClient client = createClient(chatModel);
        List<AiMessage> history = List.of(
                AiMessage.builder().role("user").content("question").build(),
                AiMessage.builder().role("assistant").content("answer").build(),
                AiMessage.builder().role("system").content("instruction").build()
        );

        String result = client.chat(history, "follow-up", "system", null);
        assertEquals("response", result);
        verify(chatModel).call(any(Prompt.class));
    }
}

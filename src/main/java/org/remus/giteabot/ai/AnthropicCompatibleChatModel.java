package org.remus.giteabot.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ChatModel} implementation for the Anthropic Messages API.
 * <p>
 * This bridges the gap where Spring AI's built-in {@code AnthropicApi} is not yet
 * binary-compatible with Spring Framework 7 (Spring Boot 4).  It uses a plain
 * {@link RestClient} to call the {@code /v1/messages} endpoint.
 */
@Slf4j
public class AnthropicCompatibleChatModel implements ChatModel {

    private final RestClient restClient;
    private final String defaultModel;
    private final int defaultMaxTokens;

    public AnthropicCompatibleChatModel(RestClient restClient, String defaultModel, int defaultMaxTokens) {
        this.restClient = restClient;
        this.defaultModel = defaultModel;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String model = defaultModel;
        int maxTokens = defaultMaxTokens;

        ChatOptions options = prompt.getOptions();
        if (options != null) {
            if (options.getModel() != null && !options.getModel().isBlank()) {
                model = options.getModel();
            }
            if (options.getMaxTokens() != null && options.getMaxTokens() > 0) {
                maxTokens = options.getMaxTokens();
            }
        }

        String systemPrompt = null;
        List<RequestMessage> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            String role = message.getMessageType().getValue();
            if ("system".equals(role)) {
                systemPrompt = message.getText();
            } else {
                messages.add(new RequestMessage(role, message.getText()));
            }
        }

        MessagesRequest request = MessagesRequest.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .messages(messages)
                .build();

        MessagesResponse response = restClient.post()
                .uri("/v1/messages")
                .body(request)
                .retrieve()
                .body(MessagesResponse.class);

        return toSpringAiResponse(response);
    }

    private ChatResponse toSpringAiResponse(MessagesResponse response) {
        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            return new ChatResponse(List.of());
        }

        String text = response.getContent().stream()
                .filter(block -> "text".equals(block.getType()))
                .map(MessagesResponse.ContentBlock::getText)
                .findFirst()
                .orElse("");

        AssistantMessage assistantMessage = new AssistantMessage(text);
        Generation generation = new Generation(assistantMessage);

        return new ChatResponse(List.of(generation));
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class MessagesRequest {
        private String model;
        @JsonProperty("max_tokens")
        private int maxTokens;
        private String system;
        private List<RequestMessage> messages;
    }

    record RequestMessage(String role, String content) {}

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class MessagesResponse {
        private String id;
        private String type;
        private String role;
        private List<ContentBlock> content;
        private String model;
        @JsonProperty("stop_reason")
        private String stopReason;
        private Usage usage;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class ContentBlock {
            private String type;
            private String text;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Usage {
            @JsonProperty("input_tokens")
            private int inputTokens;
            @JsonProperty("output_tokens")
            private int outputTokens;
        }
    }
}

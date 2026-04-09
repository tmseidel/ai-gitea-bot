package org.remus.giteabot.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ChatModel} implementation for OpenAI-compatible APIs (OpenAI, llama.cpp).
 * <p>
 * This bridges the gap where Spring AI's built-in {@code OpenAiApi} is not yet
 * binary-compatible with Spring Framework 7 (Spring Boot 4).  It uses a plain
 * {@link RestClient} to call the standard {@code /v1/chat/completions} endpoint.
 */
@Slf4j
public class OpenAiCompatibleChatModel implements ChatModel {

    private final RestClient restClient;
    private final String defaultModel;
    private final int defaultMaxTokens;

    public OpenAiCompatibleChatModel(RestClient restClient, String defaultModel, int defaultMaxTokens) {
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

        List<RequestMessage> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            messages.add(new RequestMessage(message.getMessageType().getValue(), message.getText()));
        }

        CompletionRequest request = CompletionRequest.builder()
                .model(model)
                .maxCompletionTokens(maxTokens)
                .messages(messages)
                .build();

        CompletionResponse response = restClient.post()
                .uri("/v1/chat/completions")
                .body(request)
                .retrieve()
                .body(CompletionResponse.class);

        return toSpringAiResponse(response);
    }

    private ChatResponse toSpringAiResponse(CompletionResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return new ChatResponse(List.of());
        }

        CompletionResponse.Choice choice = response.getChoices().getFirst();
        String text = (choice.getMessage() != null) ? choice.getMessage().getContent() : null;

        AssistantMessage assistantMessage = new AssistantMessage(text != null ? text : "");
        Generation generation = new Generation(assistantMessage);

        return new ChatResponse(List.of(generation));
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class CompletionRequest {
        private String model;
        @JsonProperty("max_completion_tokens")
        private int maxCompletionTokens;
        private List<RequestMessage> messages;
    }

    record RequestMessage(String role, String content) {}

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CompletionResponse {
        private String id;
        private String model;
        private List<Choice> choices;
        private Usage usage;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Choice {
            private int index;
            private ChoiceMessage message;
            @JsonProperty("finish_reason")
            private String finishReason;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class ChoiceMessage {
            private String role;
            private String content;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        static class Usage {
            @JsonProperty("prompt_tokens")
            private int promptTokens;
            @JsonProperty("completion_tokens")
            private int completionTokens;
            @JsonProperty("total_tokens")
            private int totalTokens;
        }
    }
}

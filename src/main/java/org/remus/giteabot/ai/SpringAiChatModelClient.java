package org.remus.giteabot.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI client implementation that delegates to Spring AI's {@link ChatModel}.
 * This adapter replaces the provider-specific implementations (Anthropic, OpenAI,
 * Ollama, llama.cpp) with a single unified implementation powered by Spring AI.
 * <p>
 * The diff chunking, retry logic, and message building from {@link AbstractAiClient}
 * are preserved. Only the provider-specific API call logic is replaced.
 */
@Slf4j
public class SpringAiChatModelClient extends AbstractAiClient {

    private final ChatModel chatModel;

    public SpringAiChatModelClient(ChatModel chatModel, String model, int maxTokens,
                                   int maxDiffCharsPerChunk, int maxDiffChunks,
                                   int retryTruncatedChunkChars) {
        super(model, maxTokens, maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
        this.chatModel = chatModel;
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userMessage));

        return doCall(messages, effectiveModel, maxTokens, "review");
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> conversationMessages) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        for (AiMessage m : conversationMessages) {
            messages.add(toSpringAiMessage(m));
        }

        return doCall(messages, effectiveModel, maxTokens, "chat");
    }

    @Override
    protected boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("prompt is too long")
                || normalized.contains("maximum context length")
                || normalized.contains("too many tokens")
                || normalized.contains("max_completion_tokens")
                || normalized.contains("too long")
                || normalized.contains("context length")
                || normalized.contains("maximum context")
                || normalized.contains("token limit")
                || normalized.contains("exceeds")
                || normalized.contains("maximum");
    }

    private String doCall(List<Message> messages, String effectiveModel, int maxTokens, String context) {
        ChatOptions options = ChatOptions.builder()
                .model(effectiveModel)
                .maxTokens(maxTokens)
                .build();

        Prompt prompt = new Prompt(messages, options);
        ChatResponse response = chatModel.call(prompt);

        return extractText(response, context);
    }

    private String extractText(ChatResponse response, String context) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            log.warn("Empty response from AI provider");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = response.getResult().getOutput().getText();

        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            log.info("AI {} response: {} prompt tokens, {} completion tokens",
                    context,
                    usage.getPromptTokens(),
                    usage.getCompletionTokens());
        }

        return result;
    }

    private Message toSpringAiMessage(AiMessage aiMessage) {
        return switch (aiMessage.getRole()) {
            case "assistant" -> new AssistantMessage(aiMessage.getContent());
            case "system" -> new SystemMessage(aiMessage.getContent());
            default -> new UserMessage(aiMessage.getContent());
        };
    }
}

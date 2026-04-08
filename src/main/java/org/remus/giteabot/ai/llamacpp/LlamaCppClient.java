package org.remus.giteabot.ai.llamacpp;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AbstractAiClient;
import org.remus.giteabot.ai.AiMessage;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI client implementation for llama.cpp server.
 * Uses the OpenAI-compatible /v1/chat/completions endpoint.
 * <p>
 * Supports GBNF grammar constraints for structured JSON output, which significantly
 * improves reliability for the agent feature compared to unconstrained generation.
 * <p>
 * See: https://github.com/ggerganov/llama.cpp/blob/master/examples/server/README.md
 */
@Slf4j
public class LlamaCppClient extends AbstractAiClient {

    private final RestClient restClient;

    /**
     * GBNF grammar for the agent's JSON response format.
     * This grammar constrains the model to output valid JSON matching the expected schema:
     * - fileChanges: array of file modifications
     * - runTool: optional tool invocation
     * - message: optional message to the user
     * - done: boolean indicating completion
     */
    private static final String AGENT_JSON_GRAMMAR = """
            root ::= "{" ws root-content "}" ws
            root-content ::= (file-changes-field ws)? (run-tool-field ws)? (message-field ws)? done-field
            
            file-changes-field ::= "\\"fileChanges\\"" ws ":" ws file-changes-array ","?
            file-changes-array ::= "[" ws (file-change ("," ws file-change)*)? "]"
            file-change ::= "{" ws file-change-content "}"
            file-change-content ::= path-field "," ws operation-field "," ws content-field
            path-field ::= "\\"path\\"" ws ":" ws string
            operation-field ::= "\\"operation\\"" ws ":" ws operation-value
            operation-value ::= "\\"CREATE\\"" | "\\"MODIFY\\"" | "\\"DELETE\\""
            content-field ::= "\\"content\\"" ws ":" ws string
            
            run-tool-field ::= "\\"runTool\\"" ws ":" ws run-tool-object ","?
            run-tool-object ::= "{" ws tool-content "}" | "null"
            tool-content ::= tool-name-field "," ws tool-args-field
            tool-name-field ::= "\\"name\\"" ws ":" ws string
            tool-args-field ::= "\\"arguments\\"" ws ":" ws string
            
            message-field ::= "\\"message\\"" ws ":" ws string ","?
            
            done-field ::= "\\"done\\"" ws ":" ws boolean
            
            string ::= "\\"" ([^"\\\\] | "\\\\" ["\\\\/bfnrt] | "\\\\u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])* "\\""
            boolean ::= "true" | "false"
            ws ::= [ \\t\\n\\r]*
            """;

    /**
     * Stop sequences to prevent models from generating chat template tokens.
     * Different models use different chat formats, so we include common ones.
     */
    private static final List<String> STOP_SEQUENCES = List.of(
            "<|im_start|>",
            "<|im_end|>",
            "<|end|>",
            "<|eot_id|>",
            "[INST]",
            "[/INST]",
            "<<SYS>>",
            "<</SYS>>",
            "<|user|>",
            "<|assistant|>"
    );

    public LlamaCppClient(RestClient restClient, String model, int maxTokens,
                          int maxDiffCharsPerChunk, int maxDiffChunks,
                          int retryTruncatedChunkChars) {
        super(model, maxTokens, maxDiffCharsPerChunk, maxDiffChunks, retryTruncatedChunkChars);
        this.restClient = restClient;
    }

    @Override
    protected String sendReviewRequest(String systemPrompt, String effectiveModel,
                                       int maxTokens, String userMessage) {
        List<LlamaCppRequest.Message> messages = new ArrayList<>();
        messages.add(LlamaCppRequest.Message.builder().role("system").content(systemPrompt).build());
        messages.add(LlamaCppRequest.Message.builder().role("user").content(userMessage).build());

        String grammar = shouldUseJsonGrammar(systemPrompt) ? AGENT_JSON_GRAMMAR : null;
        return doRequest(effectiveModel, messages, maxTokens, "review", grammar);
    }

    @Override
    protected String sendChatRequest(String systemPrompt, String effectiveModel,
                                     int maxTokens, List<AiMessage> conversationMessages) {
        List<LlamaCppRequest.Message> messages = new ArrayList<>();
        messages.add(LlamaCppRequest.Message.builder().role("system").content(systemPrompt).build());

        for (AiMessage m : conversationMessages) {
            messages.add(LlamaCppRequest.Message.builder()
                    .role(m.getRole())
                    .content(m.getContent())
                    .build());
        }

        String grammar = shouldUseJsonGrammar(systemPrompt) ? AGENT_JSON_GRAMMAR : null;
        return doRequest(effectiveModel, messages, maxTokens, "chat", grammar);
    }

    @Override
    protected boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("context length")
                || normalized.contains("too long")
                || normalized.contains("maximum context")
                || normalized.contains("token limit")
                || normalized.contains("exceeds");
    }

    /**
     * Detects whether the system prompt is requesting JSON output for the agent.
     * If so, we enable GBNF grammar constraints for reliable structured responses.
     */
    private boolean shouldUseJsonGrammar(String systemPrompt) {
        if (systemPrompt == null) {
            return false;
        }
        String lower = systemPrompt.toLowerCase(Locale.ROOT);
        // Detect if the prompt asks for JSON output (typically the agent prompt)
        return lower.contains("respond with a json")
                || lower.contains("output json")
                || (lower.contains("output format") && lower.contains("json"))
                || lower.contains("\"filechanges\"")
                || lower.contains("\"runtool\"");
    }

    private String doRequest(String model, List<LlamaCppRequest.Message> messages,
                             int maxTokens, String context, String grammar) {
        LlamaCppRequest.LlamaCppRequestBuilder requestBuilder = LlamaCppRequest.builder()
                .model(model)
                .messages(messages)
                .maxTokens(maxTokens)
                .stream(false)
                .stop(STOP_SEQUENCES)
                .temperature(0.7); // Balanced temperature for detailed but coherent responses

        if (grammar != null) {
            requestBuilder.grammar(grammar);
            log.info("llama.cpp {} request: GBNF grammar enabled for structured JSON output", context);
        }

        LlamaCppRequest request = requestBuilder.build();

        log.debug("llama.cpp request to /v1/chat/completions: model={}, messages={}, maxTokens={}",
                model, messages.size(), maxTokens);

        LlamaCppResponse response = restClient.post()
                .uri("/v1/chat/completions")
                .body(request)
                .retrieve()
                .body(LlamaCppResponse.class);

        return extractText(response, context);
    }

    private String extractText(LlamaCppResponse response, String context) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            log.warn("Empty response from llama.cpp server");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        LlamaCppResponse.Choice firstChoice = response.getChoices().getFirst();
        if (firstChoice == null
                || firstChoice.getMessage() == null
                || firstChoice.getMessage().getContent() == null) {
            log.warn("Empty message in llama.cpp response");
            return "Unable to generate " + context + " - empty response from AI.";
        }

        String result = firstChoice.getMessage().getContent();

        if (response.getUsage() != null) {
            log.info("llama.cpp {} response: {} prompt tokens, {} completion tokens",
                    context,
                    response.getUsage().getPromptTokens(),
                    response.getUsage().getCompletionTokens());
        }

        return result;
    }
}


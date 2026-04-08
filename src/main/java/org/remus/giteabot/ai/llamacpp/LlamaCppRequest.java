package org.remus.giteabot.ai.llamacpp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Request model for llama.cpp server's OpenAI-compatible chat completions endpoint.
 * Supports the grammar field for structured JSON output constraints.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlamaCppRequest {

    private String model;

    private List<Message> messages;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /**
     * Temperature for response generation (0.0 = deterministic, higher = more creative).
     */
    private Double temperature;

    /**
     * GBNF grammar string for constraining output format.
     * See: https://github.com/ggerganov/llama.cpp/blob/master/grammars/README.md
     * When set, the model output will be constrained to match this grammar.
     */
    private String grammar;

    /**
     * Alternative to grammar: JSON schema for structured output.
     * llama.cpp can convert this to a grammar internally.
     */
    @JsonProperty("json_schema")
    private Object jsonSchema;

    /**
     * Whether to stream the response. We always set this to false.
     */
    private Boolean stream;

    /**
     * Stop sequences - generation stops when any of these strings is encountered.
     * Used to prevent models from generating chat template tokens.
     */
    private List<String> stop;

    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }
}


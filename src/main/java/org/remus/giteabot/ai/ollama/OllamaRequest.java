package org.remus.giteabot.ai.ollama;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OllamaRequest {

    private String model;

    private List<Message> messages;

    private boolean stream;

    private Options options;

    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    @Builder
    public static class Options {
        /**
         * Maximum number of tokens to generate (num_predict in Ollama API).
         */
        private Integer numPredict;
    }
}

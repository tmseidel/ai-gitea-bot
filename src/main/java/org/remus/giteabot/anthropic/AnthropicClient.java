package org.remus.giteabot.anthropic;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.anthropic.model.AnthropicRequest;
import org.remus.giteabot.anthropic.model.AnthropicResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Service
public class AnthropicClient {

    private static final String SYSTEM_PROMPT = """
            You are an experienced software engineer performing a code review.
            Analyze the provided pull request diff and provide a constructive review.
            Focus on:
            - Potential bugs or logic errors
            - Security concerns
            - Performance issues
            - Code style and best practices
            - Suggestions for improvement
            
            Format your review as clear, actionable feedback.
            If the changes look good, say so briefly.
            Do not repeat the diff back. Be concise but thorough.
            """;

    private final RestClient anthropicRestClient;
    private final String model;
    private final int maxTokens;

    public AnthropicClient(@Qualifier("anthropicRestClient") RestClient anthropicRestClient,
                           @Value("${anthropic.model}") String model,
                           @Value("${anthropic.max-tokens}") int maxTokens) {
        this.anthropicRestClient = anthropicRestClient;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public String reviewDiff(String prTitle, String prBody, String diff) {
        log.info("Requesting code review from Anthropic model={}", model);

        String userMessage = buildUserMessage(prTitle, prBody, diff);

        AnthropicRequest request = AnthropicRequest.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(SYSTEM_PROMPT)
                .messages(List.of(
                        AnthropicRequest.Message.builder()
                                .role("user")
                                .content(userMessage)
                                .build()
                ))
                .build();

        AnthropicResponse response = anthropicRestClient.post()
                .uri("/v1/messages")
                .body(request)
                .retrieve()
                .body(AnthropicResponse.class);

        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            log.warn("Empty response from Anthropic API");
            return "Unable to generate review — empty response from AI.";
        }

        String review = response.getContent().stream()
                .filter(block -> "text".equals(block.getType()))
                .map(AnthropicResponse.ContentBlock::getText)
                .reduce("", (a, b) -> a + b);

        log.info("Review received: {} input tokens, {} output tokens",
                response.getUsage().getInputTokens(),
                response.getUsage().getOutputTokens());

        return review;
    }

    String buildUserMessage(String prTitle, String prBody, String diff) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please review the following pull request.\n\n");
        sb.append("**Title:** ").append(prTitle).append("\n");
        if (prBody != null && !prBody.isBlank()) {
            sb.append("**Description:** ").append(prBody).append("\n");
        }
        sb.append("\n**Diff:**\n```diff\n").append(diff).append("\n```");
        return sb.toString();
    }
}

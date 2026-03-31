package org.remus.giteabot.anthropic;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.anthropic.model.AnthropicRequest;
import org.remus.giteabot.anthropic.model.AnthropicResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class AnthropicClient {

    static final String DEFAULT_SYSTEM_PROMPT = """
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
    private final int maxDiffCharsPerChunk;
    private final int maxDiffChunks;
    private final int retryTruncatedChunkChars;

    public AnthropicClient(@Qualifier("anthropicRestClient") RestClient anthropicRestClient,
                           @Value("${anthropic.model}") String model,
                           @Value("${anthropic.max-tokens}") int maxTokens,
                           @Value("${anthropic.max-diff-chars-per-chunk:120000}") int maxDiffCharsPerChunk,
                           @Value("${anthropic.max-diff-chunks:8}") int maxDiffChunks,
                           @Value("${anthropic.retry-truncated-chunk-chars:60000}") int retryTruncatedChunkChars) {
        this.anthropicRestClient = anthropicRestClient;
        this.model = model;
        this.maxTokens = maxTokens;
        this.maxDiffCharsPerChunk = maxDiffCharsPerChunk;
        this.maxDiffChunks = maxDiffChunks;
        this.retryTruncatedChunkChars = retryTruncatedChunkChars;
    }

    public String reviewDiff(String prTitle, String prBody, String diff) {
        return reviewDiff(prTitle, prBody, diff, null, null);
    }

    public String reviewDiff(String prTitle, String prBody, String diff, String systemPrompt, String modelOverride) {
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : model;
        String effectivePrompt = (systemPrompt != null && !systemPrompt.isBlank()) ? systemPrompt : DEFAULT_SYSTEM_PROMPT;

        log.info("Requesting code review from Anthropic model={}", effectiveModel);
        ChunkingResult chunkingResult = splitDiffIntoChunks(diff);
        List<String> reviews = new ArrayList<>();
        int failedChunks = 0;
        Exception lastException = null;

        for (int i = 0; i < chunkingResult.chunks().size(); i++) {
            String chunk = chunkingResult.chunks().get(i);
            int chunkNumber = i + 1;
            int totalChunks = chunkingResult.chunks().size();

            try {
                String review = reviewSingleChunk(prTitle, prBody, chunk, chunkNumber, totalChunks, false,
                        effectivePrompt, effectiveModel);

                if (totalChunks > 1) {
                    reviews.add("### Diff chunk " + chunkNumber + "/" + totalChunks + "\n" + review);
                } else {
                    reviews.add(review);
                }
            } catch (Exception e) {
                failedChunks++;
                lastException = e;
                log.warn("Review failed for chunk {}/{}: {}", chunkNumber, totalChunks, e.getMessage());
                if (totalChunks > 1) {
                    reviews.add("### Diff chunk " + chunkNumber + "/" + totalChunks
                            + "\n_Review for this chunk failed: " + e.getMessage() + "_");
                }
            }
        }

        if (failedChunks > 0 && failedChunks == chunkingResult.chunks().size()) {
            throw new RuntimeException("All " + failedChunks + " chunk(s) failed during review", lastException);
        }

        if (failedChunks > 0) {
            reviews.add("**Note:** " + failedChunks + " of " + chunkingResult.chunks().size()
                    + " diff chunk(s) could not be reviewed due to API errors.");
        }

        if (chunkingResult.wasTruncated()) {
            reviews.add("**Warning:** review is incomplete because the diff was truncated after " + maxDiffChunks + " chunks.");
        }

        return String.join("\n\n", reviews);
    }

    private String reviewSingleChunk(String prTitle, String prBody, String diffChunk, int chunkNumber, int totalChunks,
                                     boolean isRetry, String systemPrompt, String effectiveModel) {
        try {
            return reviewSingleChunkInternal(prTitle, prBody, diffChunk, chunkNumber, totalChunks, isRetry,
                    systemPrompt, effectiveModel);
        } catch (HttpClientErrorException.BadRequest e) {
            if (isPromptTooLongError(e) && !isRetry && diffChunk.length() > retryTruncatedChunkChars) {
                log.warn("Prompt too long for chunk {}/{} (chars={}), retrying with truncated chunk (chars={})",
                        chunkNumber,
                        totalChunks,
                        diffChunk.length(),
                        retryTruncatedChunkChars);
                String truncatedChunk = truncateDiff(diffChunk, retryTruncatedChunkChars);
                return reviewSingleChunk(prTitle, prBody, truncatedChunk, chunkNumber, totalChunks, true,
                        systemPrompt, effectiveModel);
            }
            throw e;
        }
    }

    /**
     * Performs the actual API call for a single diff chunk.
     * Package-private so it can be overridden in tests.
     */
    String reviewSingleChunkInternal(String prTitle, String prBody, String diffChunk,
                                     int chunkNumber, int totalChunks, boolean isRetry,
                                     String systemPrompt, String effectiveModel) {
        String userMessage = buildUserMessage(prTitle, prBody, diffChunk, chunkNumber, totalChunks, isRetry);

        AnthropicRequest request = AnthropicRequest.builder()
                .model(effectiveModel)
                .maxTokens(maxTokens)
                .system(systemPrompt)
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
            return "Unable to generate review - empty response from AI.";
        }

        String review = response.getContent().stream()
                .filter(block -> "text".equals(block.getType()))
                .map(AnthropicResponse.ContentBlock::getText)
                .reduce("", (a, b) -> a + b);

        if (response.getUsage() != null) {
            log.info("Review received for chunk {}/{}: {} input tokens, {} output tokens",
                    chunkNumber,
                    totalChunks,
                    response.getUsage().getInputTokens(),
                    response.getUsage().getOutputTokens());
        }

        return review;
    }

    ChunkingResult splitDiffIntoChunks(String diff) {
        if (diff == null || diff.isBlank()) {
            return new ChunkingResult(List.of(""), false);
        }

        List<String> chunks = new ArrayList<>();
        boolean truncated = false;
        String remaining = diff;

        while (!remaining.isEmpty() && chunks.size() < maxDiffChunks) {
            if (remaining.length() <= maxDiffCharsPerChunk) {
                chunks.add(remaining);
                remaining = "";
                break;
            }

            int splitIndex = findSplitIndex(remaining, maxDiffCharsPerChunk);
            chunks.add(remaining.substring(0, splitIndex));
            remaining = remaining.substring(splitIndex);
        }

        if (!remaining.isEmpty()) {
            truncated = true;
        }

        return new ChunkingResult(chunks, truncated);
    }

    int findSplitIndex(String text, int maxChars) {
        int candidate = text.lastIndexOf('\n', maxChars);
        if (candidate > 0) {
            return candidate;
        }
        return maxChars;
    }

    String buildUserMessage(String prTitle, String prBody, String diff) {
        return buildUserMessage(prTitle, prBody, diff, 1, 1, false);
    }

    String buildUserMessage(String prTitle, String prBody, String diff, int chunkNumber, int totalChunks, boolean isRetry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please review the following pull request.\n\n");
        sb.append("**Title:** ").append(prTitle).append("\n");
        if (prBody != null && !prBody.isBlank()) {
            sb.append("**Description:** ").append(prBody).append("\n");
        }
        if (totalChunks > 1) {
            sb.append("**Diff chunk:** ").append(chunkNumber).append("/").append(totalChunks).append("\n");
        }
        if (isRetry) {
            sb.append("**Note:** The diff for this chunk was truncated to fit model limits.\n");
        }
        sb.append("\n**Diff:**\n```diff\n").append(diff).append("\n```");
        return sb.toString();
    }

    String truncateDiff(String diffChunk, int maxChars) {
        if (diffChunk.length() <= maxChars) {
            return diffChunk;
        }
        return diffChunk.substring(0, maxChars) + "\n\n# ... truncated due to model input limit ...";
    }

    boolean isPromptTooLongError(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains("prompt is too long") || normalized.contains("maximum");
    }

    record ChunkingResult(List<String> chunks, boolean wasTruncated) {}
}

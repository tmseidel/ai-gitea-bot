package org.remus.giteabot.gitea;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.gitea.model.GiteaReview;
import org.remus.giteabot.gitea.model.GiteaReviewComment;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class GiteaApiClient {

    private final RestClient giteaRestClient;
    private final String giteaUrl;
    private final String defaultGiteaToken;
    private final ConcurrentMap<String, RestClient> clientCache = new ConcurrentHashMap<>();

    public GiteaApiClient(@Qualifier("giteaRestClient") RestClient giteaRestClient,
                           @Value("${gitea.url}") String giteaUrl,
                           @Value("${gitea.token}") String defaultGiteaToken) {
        this.giteaRestClient = giteaRestClient;
        this.giteaUrl = giteaUrl;
        this.defaultGiteaToken = defaultGiteaToken;
    }

    public String getPullRequestDiff(String owner, String repo, Long pullNumber, String tokenOverride) {
        log.info("Fetching diff for PR #{} in {}/{}", pullNumber, owner, repo);
        return getClient(tokenOverride).get()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}.diff", owner, repo, pullNumber)
                .header("Accept", "text/plain")
                .retrieve()
                .body(String.class);
    }

    public void postReviewComment(String owner, String repo, Long pullNumber, String body, String tokenOverride) {
        log.info("Posting review comment on PR #{} in {}/{}", pullNumber, owner, repo);
        getClient(tokenOverride).post()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews", owner, repo, pullNumber)
                .body(new ReviewRequest(body, "COMMENT"))
                .retrieve()
                .toBodilessEntity();
        log.info("Review comment posted successfully");
    }

    public void postComment(String owner, String repo, Long issueNumber, String body, String tokenOverride) {
        log.info("Posting comment on issue/PR #{} in {}/{}", issueNumber, owner, repo);
        getClient(tokenOverride).post()
                .uri("/api/v1/repos/{owner}/{repo}/issues/{index}/comments", owner, repo, issueNumber)
                .body(new CommentRequest(body))
                .retrieve()
                .toBodilessEntity();
        log.info("Comment posted successfully");
    }

    public void addReaction(String owner, String repo, Long commentId, String reaction, String tokenOverride) {
        log.info("Adding '{}' reaction to comment #{} in {}/{}", reaction, commentId, owner, repo);
        getClient(tokenOverride).post()
                .uri("/api/v1/repos/{owner}/{repo}/issues/comments/{id}/reactions", owner, repo, commentId)
                .body(new ReactionRequest(reaction))
                .retrieve()
                .toBodilessEntity();
    }

    public void postInlineReviewComment(String owner, String repo, Long pullNumber,
                                        String filePath, int line, String body, String tokenOverride) {
        log.info("Posting inline review comment on PR #{} in {}/{} at {}:{}", pullNumber, owner, repo, filePath, line);
        var request = new InlineReviewRequest("", "COMMENT",
                List.of(new InlineReviewComment(body, line, filePath)));
        getClient(tokenOverride).post()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews", owner, repo, pullNumber)
                .body(request)
                .retrieve()
                .toBodilessEntity();
        log.info("Inline review comment posted successfully");
    }

    public List<GiteaReview> getReviews(String owner, String repo, Long pullNumber, String tokenOverride) {
        log.info("Fetching reviews for PR #{} in {}/{}", pullNumber, owner, repo);
        List<GiteaReview> reviews = getClient(tokenOverride).get()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews", owner, repo, pullNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return reviews != null ? reviews : List.of();
    }

    public List<GiteaReviewComment> getReviewComments(String owner, String repo, Long pullNumber,
                                                      Long reviewId, String tokenOverride) {
        log.info("Fetching comments for review #{} on PR #{} in {}/{}", reviewId, pullNumber, owner, repo);
        List<GiteaReviewComment> comments = getClient(tokenOverride).get()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments",
                        owner, repo, pullNumber, reviewId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return comments != null ? comments : List.of();
    }

    private RestClient getClient(String tokenOverride) {
        if (tokenOverride != null && !tokenOverride.isBlank()) {
            return clientCache.computeIfAbsent(tokenOverride, token ->
                    RestClient.builder()
                            .baseUrl(giteaUrl)
                            .defaultHeader("Authorization", "token " + token)
                            .defaultHeader("Accept", "application/json")
                            .build());
        }
        return giteaRestClient;
    }

    record ReviewRequest(String body, String event) {}
    record CommentRequest(String body) {}
    record ReactionRequest(String content) {}
    record InlineReviewRequest(String body, String event, List<InlineReviewComment> comments) {}
    record InlineReviewComment(String body, @com.fasterxml.jackson.annotation.JsonProperty("new_position") int newPosition, String path) {}
}

package org.remus.giteabot.gitea;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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
}

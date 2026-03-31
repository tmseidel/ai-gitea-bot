package org.remus.giteabot.gitea;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
public class GiteaApiClient {

    private final RestClient giteaRestClient;

    public GiteaApiClient(@Qualifier("giteaRestClient") RestClient giteaRestClient) {
        this.giteaRestClient = giteaRestClient;
    }

    public String getPullRequestDiff(String owner, String repo, Long pullNumber) {
        log.info("Fetching diff for PR #{} in {}/{}", pullNumber, owner, repo);
        return giteaRestClient.get()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}.diff", owner, repo, pullNumber)
                .header("Accept", "text/plain")
                .retrieve()
                .body(String.class);
    }

    public void postReviewComment(String owner, String repo, Long pullNumber, String body) {
        log.info("Posting review comment on PR #{} in {}/{}", pullNumber, owner, repo);
        record CommentRequest(String body) {}
        giteaRestClient.post()
                .uri("/api/v1/repos/{owner}/{repo}/pulls/{index}/reviews", owner, repo, pullNumber)
                .body(new ReviewRequest(body, "COMMENT"))
                .retrieve()
                .toBodilessEntity();
        log.info("Review comment posted successfully");
    }

    record ReviewRequest(String body, String event) {}
}

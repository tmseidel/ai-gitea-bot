package org.remus.giteabot.gitea;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.gitea.model.GiteaReview;
import org.remus.giteabot.gitea.model.GiteaReviewComment;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;
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

    // ---- Repository operations for the issue implementation agent ----

    @SuppressWarnings("unchecked")
    public String getDefaultBranch(String owner, String repo, String tokenOverride) {
        log.info("Fetching default branch for {}/{}", owner, repo);
        Map<String, Object> repoInfo = getClient(tokenOverride).get()
                .uri("/api/v1/repos/{owner}/{repo}", owner, repo)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (repoInfo != null && repoInfo.containsKey("default_branch")) {
            return (String) repoInfo.get("default_branch");
        }
        return "main";
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getRepositoryTree(String owner, String repo, String ref, String tokenOverride) {
        log.info("Fetching repository tree for {}/{} at ref={}", owner, repo, ref);
        Map<String, Object> result = getClient(tokenOverride).get()
                .uri("/api/v1/repos/{owner}/{repo}/git/trees/{ref}?recursive=true", owner, repo, ref)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.containsKey("tree")) {
            return (List<Map<String, Object>>) result.get("tree");
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public String getFileContent(String owner, String repo, String path, String ref, String tokenOverride) {
        log.info("Fetching file content for {}/{}/{} at ref={}", owner, repo, path, ref);
        Map<String, Object> result = getClient(tokenOverride).get()
                .uri("/api/v1/repos/{owner}/{repo}/contents/{path}?ref={ref}", owner, repo, path, ref)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.containsKey("content")) {
            String base64Content = (String) result.get("content");
            return new String(Base64.getMimeDecoder().decode(base64Content));
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    public String getFileSha(String owner, String repo, String path, String ref, String tokenOverride) {
        log.info("Fetching file SHA for {}/{}/{} at ref={}", owner, repo, path, ref);
        Map<String, Object> result = getClient(tokenOverride).get()
                .uri("/api/v1/repos/{owner}/{repo}/contents/{path}?ref={ref}", owner, repo, path, ref)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (result != null && result.containsKey("sha")) {
            return (String) result.get("sha");
        }
        return null;
    }

    public void createBranch(String owner, String repo, String branchName, String fromRef, String tokenOverride) {
        log.info("Creating branch '{}' from '{}' in {}/{}", branchName, fromRef, owner, repo);
        getClient(tokenOverride).post()
                .uri("/api/v1/repos/{owner}/{repo}/branches", owner, repo)
                .body(new CreateBranchRequest(branchName, fromRef))
                .retrieve()
                .toBodilessEntity();
        log.info("Branch '{}' created successfully", branchName);
    }

    public void createOrUpdateFile(String owner, String repo, String path, String content,
                                   String message, String branch, String sha, String tokenOverride) {
        log.info("Creating/updating file {} on branch '{}' in {}/{}", path, branch, owner, repo);
        String base64Content = Base64.getEncoder().encodeToString(content.getBytes());

        if (sha != null) {
            getClient(tokenOverride).put()
                    .uri("/api/v1/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                    .body(new UpdateFileRequest(base64Content, message, branch, sha))
                    .retrieve()
                    .toBodilessEntity();
        } else {
            getClient(tokenOverride).post()
                    .uri("/api/v1/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                    .body(new CreateFileRequest(base64Content, message, branch))
                    .retrieve()
                    .toBodilessEntity();
        }
        log.info("File {} committed successfully", path);
    }

    public void deleteFile(String owner, String repo, String path, String message,
                           String branch, String sha, String tokenOverride) {
        log.info("Deleting file {} on branch '{}' in {}/{}", path, branch, owner, repo);
        getClient(tokenOverride).method(org.springframework.http.HttpMethod.DELETE)
                .uri("/api/v1/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                .body(new DeleteFileRequest(message, branch, sha))
                .retrieve()
                .toBodilessEntity();
        log.info("File {} deleted successfully", path);
    }

    @SuppressWarnings("unchecked")
    public Long createPullRequest(String owner, String repo, String title, String body,
                                  String head, String base, String tokenOverride) {
        log.info("Creating pull request '{}' in {}/{} from {} to {}", title, owner, repo, head, base);
        Map<String, Object> result = getClient(tokenOverride).post()
                .uri("/api/v1/repos/{owner}/{repo}/pulls", owner, repo)
                .body(new CreatePullRequest(title, body, head, base))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Long prNumber = null;
        if (result != null && result.containsKey("number")) {
            prNumber = ((Number) result.get("number")).longValue();
        }
        log.info("Pull request created: #{}", prNumber);
        return prNumber;
    }

    public void deleteBranch(String owner, String repo, String branchName, String tokenOverride) {
        log.info("Deleting branch '{}' in {}/{}", branchName, owner, repo);
        try {
            getClient(tokenOverride).delete()
                    .uri("/api/v1/repos/{owner}/{repo}/branches/{branch}", owner, repo, branchName)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Branch '{}' deleted successfully", branchName);
        } catch (Exception e) {
            log.warn("Failed to delete branch '{}': {}", branchName, e.getMessage());
        }
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
    record CreateBranchRequest(@com.fasterxml.jackson.annotation.JsonProperty("new_branch_name") String newBranchName,
                               @com.fasterxml.jackson.annotation.JsonProperty("old_branch_name") String oldBranchName) {}
    record CreateFileRequest(String content, String message, String branch) {}
    record UpdateFileRequest(String content, String message, String branch, String sha) {}
    record CreatePullRequest(String title, String body, String head, String base) {}
    record DeleteFileRequest(String message, String branch, String sha) {}
}

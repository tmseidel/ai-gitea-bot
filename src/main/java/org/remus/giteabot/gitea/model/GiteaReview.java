package org.remus.giteabot.gitea.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * API response model for a Gitea pull request review.
 * Returned by GET /repos/{owner}/{repo}/pulls/{index}/reviews
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GiteaReview {

    private Long id;

    private String body;

    private String state;

    private GiteaUser user;

    @JsonProperty("submitted_at")
    private String submittedAt;

    @JsonProperty("comments_count")
    private Integer commentsCount;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GiteaUser {
        private Long id;
        private String login;
    }
}


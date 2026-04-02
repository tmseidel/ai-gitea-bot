package org.remus.giteabot.gitea.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * API response model for a Gitea pull request review comment.
 * Returned by GET /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GiteaReviewComment {

    private Long id;

    private String body;

    private String path;

    @JsonProperty("diff_hunk")
    private String diffHunk;

    private Integer line;

    @JsonProperty("old_line_num")
    private Integer oldLineNum;

    @JsonProperty("new_line_num")
    private Integer newLineNum;

    private GiteaReview.GiteaUser user;
}


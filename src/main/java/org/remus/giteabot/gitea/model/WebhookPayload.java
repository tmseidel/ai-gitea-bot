package org.remus.giteabot.gitea.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookPayload {

    private String action;

    private Long number;

    @JsonProperty("pull_request")
    private PullRequest pullRequest;

    private Repository repository;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PullRequest {
        private Long id;
        private Long number;
        private String title;
        private String body;
        private String state;

        @JsonProperty("diff_url")
        private String diffUrl;

        private Head head;
        private Head base;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Head {
        private String ref;
        private String sha;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repository {
        private Long id;
        private String name;

        @JsonProperty("full_name")
        private String fullName;

        private Owner owner;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Owner {
        private String login;
    }
}

package org.remus.giteabot.agent.session;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.remus.giteabot.session.ConversationMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Represents an agent coding session for implementing an issue.
 * Tracks the conversation history, file changes made, and the resulting PR.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "agent_sessions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"repoOwner", "repoName", "issueNumber"}))
public class AgentSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String repoOwner;

    @Column(nullable = false)
    private String repoName;

    @Column(nullable = false)
    private Long issueNumber;

    /**
     * The title of the issue being implemented.
     */
    private String issueTitle;

    /**
     * The name of the branch created for this implementation.
     */
    private String branchName;

    /**
     * The PR number if a pull request was created (null if not yet created).
     */
    private Long prNumber;

    /**
     * Current status of the agent session.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentSessionStatus status = AgentSessionStatus.IN_PROGRESS;

    /**
     * AI conversation history for context continuity.
     * Using Set to avoid MultipleBagFetchException with Hibernate.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_session_id")
    private Set<ConversationMessage> messages = new HashSet<>();

    /**
     * File changes made during this session.
     * Using Set to avoid MultipleBagFetchException with Hibernate.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_session_id")
    private Set<AgentFileChange> fileChanges = new HashSet<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public AgentSession(String repoOwner, String repoName, Long issueNumber, String issueTitle) {
        this.repoOwner = repoOwner;
        this.repoName = repoName;
        this.issueNumber = issueNumber;
        this.issueTitle = issueTitle;
        this.status = AgentSessionStatus.IN_PROGRESS;
    }

    public void addMessage(String role, String content) {
        messages.add(new ConversationMessage(role, content));
    }

    public void addFileChange(String path, String operation, String commitSha) {
        fileChanges.add(new AgentFileChange(path, operation, commitSha));
    }

    public enum AgentSessionStatus {
        /**
         * Agent is currently working on the issue.
         */
        IN_PROGRESS,

        /**
         * Agent has created a PR and is waiting for feedback.
         */
        PR_CREATED,

        /**
         * Agent is making additional changes based on feedback.
         */
        UPDATING,

        /**
         * PR was merged, session is complete.
         */
        COMPLETED,

        /**
         * Agent failed to implement the issue.
         */
        FAILED
    }
}



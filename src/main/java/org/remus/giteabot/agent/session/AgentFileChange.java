package org.remus.giteabot.agent.session;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a file change made by the agent during issue implementation.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "agent_file_changes")
public class AgentFileChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The path of the file that was changed.
     */
    @Column(nullable = false)
    private String path;

    /**
     * The operation performed: CREATE, UPDATE, or DELETE.
     */
    @Column(nullable = false)
    private String operation;

    /**
     * The commit SHA for this file change (if available).
     */
    private String commitSha;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public AgentFileChange(String path, String operation, String commitSha) {
        this.path = path;
        this.operation = operation;
        this.commitSha = commitSha;
    }
}


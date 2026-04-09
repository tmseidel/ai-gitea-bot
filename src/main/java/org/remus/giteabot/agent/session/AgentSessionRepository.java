package org.remus.giteabot.agent.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {

    @Query("SELECT s FROM AgentSession s LEFT JOIN FETCH s.messages LEFT JOIN FETCH s.fileChanges " +
           "WHERE s.repoOwner = :owner AND s.repoName = :repo AND s.issueNumber = :issueNumber")
    Optional<AgentSession> findByRepoOwnerAndRepoNameAndIssueNumber(
            @Param("owner") String repoOwner,
            @Param("repo") String repoName,
            @Param("issueNumber") Long issueNumber);

    @Query("SELECT s FROM AgentSession s LEFT JOIN FETCH s.messages LEFT JOIN FETCH s.fileChanges " +
           "WHERE s.repoOwner = :owner AND s.repoName = :repo AND s.prNumber = :prNumber")
    Optional<AgentSession> findByRepoOwnerAndRepoNameAndPrNumber(
            @Param("owner") String repoOwner,
            @Param("repo") String repoName,
            @Param("prNumber") Long prNumber);

    void deleteByRepoOwnerAndRepoNameAndIssueNumber(String repoOwner, String repoName, Long issueNumber);
}


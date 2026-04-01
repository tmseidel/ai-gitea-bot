package org.remus.giteabot.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReviewSessionRepository extends JpaRepository<ReviewSession, Long> {

    @Query("SELECT s FROM ReviewSession s LEFT JOIN FETCH s.messages WHERE s.repoOwner = :owner AND s.repoName = :repo AND s.prNumber = :prNumber")
    Optional<ReviewSession> findByRepoOwnerAndRepoNameAndPrNumber(@Param("owner") String repoOwner,
                                                                   @Param("repo") String repoName,
                                                                   @Param("prNumber") Long prNumber);

    void deleteByRepoOwnerAndRepoNameAndPrNumber(String repoOwner, String repoName, Long prNumber);
}

package org.remus.giteabot.session;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.anthropic.model.AnthropicRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class SessionService {

    private final ReviewSessionRepository repository;

    public SessionService(ReviewSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ReviewSession getOrCreateSession(String owner, String repo, Long prNumber, String promptName) {
        Optional<ReviewSession> existing = repository.findByRepoOwnerAndRepoNameAndPrNumber(owner, repo, prNumber);
        if (existing.isPresent()) {
            log.info("Reusing existing session for PR #{} in {}/{}", prNumber, owner, repo);
            return existing.get();
        }

        log.info("Creating new session for PR #{} in {}/{}", prNumber, owner, repo);
        ReviewSession session = new ReviewSession(owner, repo, prNumber, promptName);
        return repository.save(session);
    }

    @Transactional(readOnly = true)
    public Optional<ReviewSession> getSession(String owner, String repo, Long prNumber) {
        return repository.findByRepoOwnerAndRepoNameAndPrNumber(owner, repo, prNumber);
    }

    @Transactional
    public ReviewSession addMessage(ReviewSession session, String role, String content) {
        session.addMessage(role, content);
        return repository.save(session);
    }

    @Transactional
    public void deleteSession(String owner, String repo, Long prNumber) {
        log.info("Deleting session for PR #{} in {}/{}", prNumber, owner, repo);
        repository.deleteByRepoOwnerAndRepoNameAndPrNumber(owner, repo, prNumber);
    }

    /**
     * Converts stored conversation messages to Anthropic API message format.
     */
    public List<AnthropicRequest.Message> toAnthropicMessages(ReviewSession session) {
        return session.getMessages().stream()
                .map(m -> AnthropicRequest.Message.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .build())
                .toList();
    }
}

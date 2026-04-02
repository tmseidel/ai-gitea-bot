package org.remus.giteabot.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.anthropic.model.AnthropicRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private ReviewSessionRepository repository;

    @InjectMocks
    private SessionService sessionService;

    @Test
    void getOrCreateSession_createsNewSession() {
        when(repository.findByRepoOwnerAndRepoNameAndPrNumber("owner", "repo", 1L))
                .thenReturn(Optional.empty());
        when(repository.save(any(ReviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewSession session = sessionService.getOrCreateSession("owner", "repo", 1L, "default");

        assertEquals("owner", session.getRepoOwner());
        assertEquals("repo", session.getRepoName());
        assertEquals(1L, session.getPrNumber());
        assertEquals("default", session.getPromptName());
        verify(repository).save(any(ReviewSession.class));
    }

    @Test
    void getOrCreateSession_reusesExistingSession() {
        ReviewSession existing = new ReviewSession("owner", "repo", 1L, "default");
        when(repository.findByRepoOwnerAndRepoNameAndPrNumber("owner", "repo", 1L))
                .thenReturn(Optional.of(existing));

        ReviewSession session = sessionService.getOrCreateSession("owner", "repo", 1L, "default");

        assertSame(existing, session);
        verify(repository, never()).save(any());
    }

    @Test
    void addMessage_appendsToSession() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        when(repository.save(any(ReviewSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        sessionService.addMessage(session, "user", "Hello");

        assertEquals(1, session.getMessages().size());
        assertEquals("user", session.getMessages().getFirst().getRole());
        assertEquals("Hello", session.getMessages().getFirst().getContent());
        verify(repository).save(session);
    }

    @Test
    void deleteSession_deletesFromRepository() {
        sessionService.deleteSession("owner", "repo", 1L);

        verify(repository).deleteByRepoOwnerAndRepoNameAndPrNumber("owner", "repo", 1L);
    }

    @Test
    void toAnthropicMessages_convertsCorrectly() {
        ReviewSession session = new ReviewSession("owner", "repo", 1L, null);
        session.addMessage("user", "Review this PR");
        session.addMessage("assistant", "Looks good!");

        List<AnthropicRequest.Message> messages = sessionService.toAnthropicMessages(session);

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getRole());
        assertEquals("Review this PR", messages.get(0).getContent());
        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("Looks good!", messages.get(1).getContent());
    }

    @Test
    void getSession_returnsOptional() {
        when(repository.findByRepoOwnerAndRepoNameAndPrNumber("owner", "repo", 1L))
                .thenReturn(Optional.empty());

        Optional<ReviewSession> result = sessionService.getSession("owner", "repo", 1L);

        assertTrue(result.isEmpty());
    }
}

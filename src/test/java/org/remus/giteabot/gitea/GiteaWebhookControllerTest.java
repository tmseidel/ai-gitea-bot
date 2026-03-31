package org.remus.giteabot.gitea;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.review.CodeReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GiteaWebhookController.class)
@ActiveProfiles("test")
class GiteaWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CodeReviewService codeReviewService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void handleWebhook_prOpened_triggersReview() throws Exception {
        WebhookPayload payload = createTestPayload("opened");

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(codeReviewService).reviewPullRequest(any(WebhookPayload.class), isNull());
    }

    @Test
    void handleWebhook_prSynchronized_triggersReview() throws Exception {
        WebhookPayload payload = createTestPayload("synchronized");

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(codeReviewService).reviewPullRequest(any(WebhookPayload.class), isNull());
    }

    @Test
    void handleWebhook_prClosed_ignored() throws Exception {
        WebhookPayload payload = createTestPayload("closed");

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(codeReviewService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void handleWebhook_noPullRequest_ignored() throws Exception {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction("push");

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored"));

        verify(codeReviewService, never()).reviewPullRequest(any(), any());
    }

    @Test
    void handleWebhook_withPromptParam_passesPromptName() throws Exception {
        WebhookPayload payload = createTestPayload("opened");

        mockMvc.perform(post("/api/webhook")
                        .param("prompt", "security")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        verify(codeReviewService).reviewPullRequest(any(WebhookPayload.class), eq("security"));
    }

    private WebhookPayload createTestPayload(String action) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction(action);

        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(1L);
        pr.setTitle("Test PR");
        payload.setPullRequest(pr);

        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("testowner");

        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("testrepo");
        repository.setFullName("testowner/testrepo");
        repository.setOwner(owner);
        payload.setRepository(repository);

        return payload;
    }
}

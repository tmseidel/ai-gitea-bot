package org.remus.giteabot.integration;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test that starts the full Spring Boot application with mocked Gitea and Anthropic HTTP servers.
 * Tests the complete webhook flow: receive webhook → fetch diff → call Anthropic → post review.
 * Uses a large exemplary diff to validate the full pipeline.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookIntegrationTest {

    @TempDir
    static Path tempPromptDir;

    static HttpServer giteaServer;
    static HttpServer anthropicServer;
    static int giteaPort;
    static int anthropicPort;

    static final List<String> giteaReviewBodies = Collections.synchronizedList(new ArrayList<>());
    static CountDownLatch reviewPostedLatch;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws IOException {
        // Start mock servers before Spring context
        giteaServer = HttpServer.create(new InetSocketAddress(0), 0);
        anthropicServer = HttpServer.create(new InetSocketAddress(0), 0);

        giteaPort = giteaServer.getAddress().getPort();
        anthropicPort = anthropicServer.getAddress().getPort();

        setupGiteaServer();
        setupAnthropicServer();

        giteaServer.start();
        anthropicServer.start();

        // Write prompt files
        Files.writeString(tempPromptDir.resolve("default.md"),
                "You are a code reviewer. Be concise.");
        Files.writeString(tempPromptDir.resolve("security.md"),
                "You are a security-focused code reviewer. Focus exclusively on security vulnerabilities.");

        registry.add("gitea.url", () -> "http://localhost:" + giteaPort);
        registry.add("gitea.token", () -> "test-token");
        registry.add("anthropic.api.url", () -> "http://localhost:" + anthropicPort);
        registry.add("anthropic.api.key", () -> "test-api-key");
        registry.add("anthropic.max-diff-chars-per-chunk", () -> "50000");
        registry.add("anthropic.max-diff-chunks", () -> "4");
        registry.add("prompts.dir", tempPromptDir::toString);
        registry.add("prompts.definitions.default.file", () -> "default.md");
        registry.add("prompts.definitions.security.file", () -> "security.md");
        registry.add("prompts.definitions.security.model", () -> "claude-opus-4-20250514");
    }

    @BeforeEach
    void setUp() {
        giteaReviewBodies.clear();
        reviewPostedLatch = new CountDownLatch(1);
    }

    @AfterAll
    static void tearDown() {
        if (giteaServer != null) giteaServer.stop(0);
        if (anthropicServer != null) anthropicServer.stop(0);
    }

    private static void setupGiteaServer() {
        // Mock endpoint: GET /api/v1/repos/{owner}/{repo}/pulls/{index}.diff
        giteaServer.createContext("/api/v1/repos/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("GET".equals(method) && path.endsWith(".diff")) {
                // Return a large exemplary diff
                String diff = generateLargeDiff();
                byte[] response = diff.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } else if ("POST".equals(method) && path.endsWith("/reviews")) {
                // Capture the review comment
                String body = new String(exchange.getRequestBody().readAllBytes());
                giteaReviewBodies.add(body);
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
                reviewPostedLatch.countDown();
            } else {
                exchange.sendResponseHeaders(404, 0);
                exchange.getResponseBody().close();
            }
        });
    }

    private static void setupAnthropicServer() {
        anthropicServer.createContext("/v1/messages", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                exchange.getResponseBody().close();
                return;
            }

            String requestBody = new String(exchange.getRequestBody().readAllBytes());

            // Generate a mock review response
            String reviewText = "The code changes look reasonable. Here are some observations:\\n\\n"
                    + "1. **Naming**: Variable names are clear and descriptive.\\n"
                    + "2. **Error handling**: Consider adding null checks.\\n"
                    + "3. **Performance**: The algorithm complexity looks acceptable.";

            // Verify the request contains expected fields
            if (requestBody.contains("security")) {
                reviewText = "**Security Review**\\n\\n"
                        + "1. No SQL injection vulnerabilities detected.\\n"
                        + "2. Input validation appears adequate.\\n"
                        + "3. No hardcoded credentials found.";
            }

            String response = """
                    {
                        "id": "msg_test123",
                        "type": "message",
                        "role": "assistant",
                        "content": [{"type": "text", "text": "%s"}],
                        "model": "claude-sonnet-4-20250514",
                        "stop_reason": "end_turn",
                        "usage": {"input_tokens": 1000, "output_tokens": 200}
                    }
                    """.formatted(reviewText);

            byte[] responseBytes = response.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
    }

    @Test
    void webhookTriggersReview_defaultPrompt() throws Exception {
        String webhookPayload = createWebhookPayload("opened");

        long startTime = System.currentTimeMillis();
        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookPayload))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));
        long webhookResponseTime = System.currentTimeMillis() - startTime;

        // Webhook should respond quickly (async processing)
        assertTrue(webhookResponseTime < 2000, "Webhook should respond within 2 seconds, took " + webhookResponseTime + "ms");

        // Wait for async review to be posted
        boolean posted = reviewPostedLatch.await(10, TimeUnit.SECONDS);
        assertTrue(posted, "Review should be posted within 10 seconds");
        assertFalse(giteaReviewBodies.isEmpty(), "A review should have been posted to Gitea");

        String reviewBody = giteaReviewBodies.getFirst();
        assertTrue(reviewBody.contains("body"), "Review should contain a body field");
    }

    @Test
    void webhookTriggersReview_withPromptParam() throws Exception {
        String webhookPayload = createWebhookPayload("opened");

        mockMvc.perform(post("/api/webhook")
                        .param("prompt", "security")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookPayload))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        boolean posted = reviewPostedLatch.await(10, TimeUnit.SECONDS);
        assertTrue(posted, "Review should be posted within 10 seconds");
        assertFalse(giteaReviewBodies.isEmpty(), "A review should have been posted to Gitea");
    }

    @Test
    void webhookIgnoresClosedPR() throws Exception {
        String webhookPayload = createWebhookPayload("closed");

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookPayload))
                .andExpect(status().isOk())
                .andExpect(content().string("session closed"));
    }

    @Test
    void webhookHandlesSynchronizedAction() throws Exception {
        String webhookPayload = createWebhookPayload("synchronized");

        mockMvc.perform(post("/api/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookPayload))
                .andExpect(status().isOk())
                .andExpect(content().string("review triggered"));

        boolean posted = reviewPostedLatch.await(10, TimeUnit.SECONDS);
        assertTrue(posted, "Review should be posted within 10 seconds");
    }

    private String createWebhookPayload(String action) {
        return """
                {
                    "action": "%s",
                    "number": 42,
                    "pull_request": {
                        "id": 1,
                        "number": 42,
                        "title": "Add user authentication module",
                        "body": "This PR adds JWT-based authentication with password hashing and session management.",
                        "state": "open",
                        "diff_url": "http://localhost:%d/api/v1/repos/testowner/testrepo/pulls/42.diff",
                        "head": {"ref": "feature/auth", "sha": "abc123"},
                        "base": {"ref": "main", "sha": "def456"}
                    },
                    "repository": {
                        "id": 1,
                        "name": "testrepo",
                        "full_name": "testowner/testrepo",
                        "owner": {"login": "testowner"}
                    }
                }
                """.formatted(action, giteaPort);
    }

    /**
     * Generates a large, realistic diff simulating multiple file changes in an authentication module.
     */
    private static String generateLargeDiff() {
        StringBuilder diff = new StringBuilder();

        // File 1: New AuthenticationService.java
        diff.append("""
                diff --git a/src/main/java/com/example/auth/AuthenticationService.java b/src/main/java/com/example/auth/AuthenticationService.java
                new file mode 100644
                index 0000000..1234567
                --- /dev/null
                +++ b/src/main/java/com/example/auth/AuthenticationService.java
                @@ -0,0 +1,120 @@
                +package com.example.auth;
                +
                +import java.security.SecureRandom;
                +import java.time.Instant;
                +import java.time.temporal.ChronoUnit;
                +import java.util.Base64;
                +import java.util.Map;
                +import java.util.concurrent.ConcurrentHashMap;
                +
                +import org.springframework.stereotype.Service;
                +
                +@Service
                +public class AuthenticationService {
                +
                +    private final Map<String, UserSession> activeSessions = new ConcurrentHashMap<>();
                +    private final UserRepository userRepository;
                +    private final PasswordEncoder passwordEncoder;
                +    private final JwtTokenProvider tokenProvider;
                +
                +    public AuthenticationService(UserRepository userRepository,
                +                                 PasswordEncoder passwordEncoder,
                +                                 JwtTokenProvider tokenProvider) {
                +        this.userRepository = userRepository;
                +        this.passwordEncoder = passwordEncoder;
                +        this.tokenProvider = tokenProvider;
                +    }
                +
                +    public AuthResult authenticate(String username, String password) {
                +        User user = userRepository.findByUsername(username)
                +                .orElseThrow(() -> new AuthenticationException("Invalid credentials"));
                +
                +        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
                +            throw new AuthenticationException("Invalid credentials");
                +        }
                +
                +        if (user.isLocked()) {
                +            throw new AuthenticationException("Account is locked");
                +        }
                +
                +        String token = tokenProvider.generateToken(user);
                +        String refreshToken = generateRefreshToken();
                +
                +        UserSession session = new UserSession(
                +                user.getId(),
                +                token,
                +                refreshToken,
                +                Instant.now().plus(30, ChronoUnit.MINUTES),
                +                Instant.now().plus(7, ChronoUnit.DAYS)
                +        );
                +
                +        activeSessions.put(refreshToken, session);
                +
                +        return new AuthResult(token, refreshToken, session.tokenExpiry());
                +    }
                +
                +    public AuthResult refreshToken(String refreshToken) {
                +        UserSession session = activeSessions.get(refreshToken);
                +        if (session == null || session.refreshExpiry().isBefore(Instant.now())) {
                +            throw new AuthenticationException("Invalid or expired refresh token");
                +        }
                +
                +        User user = userRepository.findById(session.userId())
                +                .orElseThrow(() -> new AuthenticationException("User not found"));
                +
                +        String newToken = tokenProvider.generateToken(user);
                +        String newRefreshToken = generateRefreshToken();
                +
                +        activeSessions.remove(refreshToken);
                +
                +        UserSession newSession = new UserSession(
                +                user.getId(),
                +                newToken,
                +                newRefreshToken,
                +                Instant.now().plus(30, ChronoUnit.MINUTES),
                +                Instant.now().plus(7, ChronoUnit.DAYS)
                +        );
                +        activeSessions.put(newRefreshToken, newSession);
                +
                +        return new AuthResult(newToken, newRefreshToken, newSession.tokenExpiry());
                +    }
                +
                +    public void logout(String refreshToken) {
                +        activeSessions.remove(refreshToken);
                +    }
                +
                +    public void logoutAll(Long userId) {
                +        activeSessions.entrySet().removeIf(e -> e.getValue().userId().equals(userId));
                +    }
                +
                +    private String generateRefreshToken() {
                +        byte[] bytes = new byte[32];
                +        new SecureRandom().nextBytes(bytes);
                +        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                +    }
                +
                +    public record AuthResult(String token, String refreshToken, Instant expiresAt) {}
                +
                +    public record UserSession(Long userId, String token, String refreshToken,
                +                              Instant tokenExpiry, Instant refreshExpiry) {}
                +}
                """);

        // File 2: New JwtTokenProvider.java
        diff.append("""
                diff --git a/src/main/java/com/example/auth/JwtTokenProvider.java b/src/main/java/com/example/auth/JwtTokenProvider.java
                new file mode 100644
                index 0000000..2345678
                --- /dev/null
                +++ b/src/main/java/com/example/auth/JwtTokenProvider.java
                @@ -0,0 +1,80 @@
                +package com.example.auth;
                +
                +import javax.crypto.SecretKey;
                +import java.time.Instant;
                +import java.time.temporal.ChronoUnit;
                +import java.util.Date;
                +
                +import org.springframework.beans.factory.annotation.Value;
                +import org.springframework.stereotype.Component;
                +
                +@Component
                +public class JwtTokenProvider {
                +
                +    private final String secretKey;
                +    private final long tokenValidityMinutes;
                +
                +    public JwtTokenProvider(
                +            @Value("${jwt.secret}") String secretKey,
                +            @Value("${jwt.validity-minutes:30}") long tokenValidityMinutes) {
                +        this.secretKey = secretKey;
                +        this.tokenValidityMinutes = tokenValidityMinutes;
                +    }
                +
                +    public String generateToken(User user) {
                +        Instant now = Instant.now();
                +        Instant expiry = now.plus(tokenValidityMinutes, ChronoUnit.MINUTES);
                +
                +        // Build JWT token with claims
                +        return buildJwt(user.getId(), user.getUsername(), user.getRole(), now, expiry);
                +    }
                +
                +    public TokenClaims validateToken(String token) {
                +        try {
                +            // Parse and validate the JWT token
                +            return parseJwt(token);
                +        } catch (Exception e) {
                +            throw new AuthenticationException("Invalid or expired token: " + e.getMessage());
                +        }
                +    }
                +
                +    private String buildJwt(Long userId, String username, String role,
                +                            Instant issuedAt, Instant expiry) {
                +        // Simplified JWT building - in production use a proper JWT library
                +        return String.format("jwt_%d_%s_%s_%d_%d",
                +                userId, username, role,
                +                issuedAt.toEpochMilli(), expiry.toEpochMilli());
                +    }
                +
                +    private TokenClaims parseJwt(String token) {
                +        if (token == null || !token.startsWith("jwt_")) {
                +            throw new AuthenticationException("Invalid token format");
                +        }
                +        String[] parts = token.split("_");
                +        if (parts.length < 6) {
                +            throw new AuthenticationException("Invalid token structure");
                +        }
                +        long expiry = Long.parseLong(parts[5]);
                +        if (Instant.ofEpochMilli(expiry).isBefore(Instant.now())) {
                +            throw new AuthenticationException("Token expired");
                +        }
                +        return new TokenClaims(Long.parseLong(parts[1]), parts[2], parts[3]);
                +    }
                +
                +    public record TokenClaims(Long userId, String username, String role) {}
                +}
                """);

        // File 3: Modified UserController.java
        diff.append("""
                diff --git a/src/main/java/com/example/controller/UserController.java b/src/main/java/com/example/controller/UserController.java
                index 3456789..4567890 100644
                --- a/src/main/java/com/example/controller/UserController.java
                +++ b/src/main/java/com/example/controller/UserController.java
                @@ -1,25 +1,85 @@
                 package com.example.controller;
                 
                -import org.springframework.web.bind.annotation.GetMapping;
                -import org.springframework.web.bind.annotation.RequestMapping;
                -import org.springframework.web.bind.annotation.RestController;
                +import com.example.auth.AuthenticationService;
                +import com.example.auth.AuthenticationService.AuthResult;
                +import jakarta.validation.Valid;
                +import jakarta.validation.constraints.NotBlank;
                +import jakarta.validation.constraints.Size;
                +import org.springframework.http.HttpStatus;
                +import org.springframework.http.ResponseEntity;
                +import org.springframework.web.bind.annotation.*;
                 
                 @RestController
                -@RequestMapping("/api/users")
                +@RequestMapping("/api/auth")
                 public class UserController {
                 
                -    @GetMapping
                -    public String getUsers() {
                -        return "users list";
                +    private final AuthenticationService authService;
                +
                +    public UserController(AuthenticationService authService) {
                +        this.authService = authService;
                +    }
                +
                +    @PostMapping("/login")
                +    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
                +        AuthResult result = authService.authenticate(request.username(), request.password());
                +        return ResponseEntity.ok(new LoginResponse(
                +                result.token(),
                +                result.refreshToken(),
                +                result.expiresAt().toEpochMilli()
                +        ));
                +    }
                +
                +    @PostMapping("/refresh")
                +    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
                +        AuthResult result = authService.refreshToken(request.refreshToken());
                +        return ResponseEntity.ok(new LoginResponse(
                +                result.token(),
                +                result.refreshToken(),
                +                result.expiresAt().toEpochMilli()
                +        ));
                +    }
                +
                +    @PostMapping("/logout")
                +    @ResponseStatus(HttpStatus.NO_CONTENT)
                +    public void logout(@RequestBody RefreshRequest request) {
                +        authService.logout(request.refreshToken());
                     }
                +
                +    public record LoginRequest(
                +            @NotBlank @Size(min = 3, max = 50) String username,
                +            @NotBlank @Size(min = 8, max = 100) String password
                +    ) {}
                +
                +    public record RefreshRequest(
                +            @NotBlank String refreshToken
                +    ) {}
                +
                +    public record LoginResponse(String token, String refreshToken, long expiresAt) {}
                 }
                """);

        // File 4: New SecurityConfig.java
        diff.append("""
                diff --git a/src/main/java/com/example/config/SecurityConfig.java b/src/main/java/com/example/config/SecurityConfig.java
                new file mode 100644
                index 0000000..5678901
                --- /dev/null
                +++ b/src/main/java/com/example/config/SecurityConfig.java
                @@ -0,0 +1,65 @@
                +package com.example.config;
                +
                +import com.example.auth.JwtTokenProvider;
                +import jakarta.servlet.FilterChain;
                +import jakarta.servlet.ServletException;
                +import jakarta.servlet.http.HttpServletRequest;
                +import jakarta.servlet.http.HttpServletResponse;
                +import org.springframework.context.annotation.Bean;
                +import org.springframework.context.annotation.Configuration;
                +import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                +import org.springframework.security.web.SecurityFilterChain;
                +import org.springframework.web.filter.OncePerRequestFilter;
                +
                +import java.io.IOException;
                +
                +@Configuration
                +public class SecurityConfig {
                +
                +    private final JwtTokenProvider tokenProvider;
                +
                +    public SecurityConfig(JwtTokenProvider tokenProvider) {
                +        this.tokenProvider = tokenProvider;
                +    }
                +
                +    @Bean
                +    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                +        return http
                +                .csrf(csrf -> csrf.disable())
                +                .authorizeHttpRequests(auth -> auth
                +                        .requestMatchers("/api/auth/**").permitAll()
                +                        .requestMatchers("/actuator/**").permitAll()
                +                        .anyRequest().authenticated()
                +                )
                +                .addFilterBefore(jwtAuthFilter(), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                +                .build();
                +    }
                +
                +    @Bean
                +    public OncePerRequestFilter jwtAuthFilter() {
                +        return new OncePerRequestFilter() {
                +            @Override
                +            protected void doFilterInternal(HttpServletRequest request,
                +                                           HttpServletResponse response,
                +                                           FilterChain filterChain)
                +                    throws ServletException, IOException {
                +                String header = request.getHeader("Authorization");
                +                if (header != null && header.startsWith("Bearer ")) {
                +                    String token = header.substring(7);
                +                    try {
                +                        var claims = tokenProvider.validateToken(token);
                +                        // Set security context with authenticated user
                +                        request.setAttribute("userId", claims.userId());
                +                        request.setAttribute("username", claims.username());
                +                    } catch (Exception e) {
                +                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                +                        return;
                +                    }
                +                }
                +                filterChain.doFilter(request, response);
                +            }
                +        };
                +    }
                +}
                """);

        // File 5: Test file changes
        diff.append("""
                diff --git a/src/test/java/com/example/auth/AuthenticationServiceTest.java b/src/test/java/com/example/auth/AuthenticationServiceTest.java
                new file mode 100644
                index 0000000..6789012
                --- /dev/null
                +++ b/src/test/java/com/example/auth/AuthenticationServiceTest.java
                @@ -0,0 +1,90 @@
                +package com.example.auth;
                +
                +import org.junit.jupiter.api.BeforeEach;
                +import org.junit.jupiter.api.Test;
                +import org.junit.jupiter.api.extension.ExtendWith;
                +import org.mockito.Mock;
                +import org.mockito.junit.jupiter.MockitoExtension;
                +
                +import java.util.Optional;
                +
                +import static org.junit.jupiter.api.Assertions.*;
                +import static org.mockito.Mockito.*;
                +
                +@ExtendWith(MockitoExtension.class)
                +class AuthenticationServiceTest {
                +
                +    @Mock
                +    private UserRepository userRepository;
                +    @Mock
                +    private PasswordEncoder passwordEncoder;
                +    @Mock
                +    private JwtTokenProvider tokenProvider;
                +
                +    private AuthenticationService authService;
                +
                +    @BeforeEach
                +    void setUp() {
                +        authService = new AuthenticationService(userRepository, passwordEncoder, tokenProvider);
                +    }
                +
                +    @Test
                +    void authenticate_validCredentials_returnsTokens() {
                +        User user = new User(1L, "testuser", "hashedpw", "USER", false);
                +        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
                +        when(passwordEncoder.matches("password123", "hashedpw")).thenReturn(true);
                +        when(tokenProvider.generateToken(user)).thenReturn("jwt_token");
                +
                +        var result = authService.authenticate("testuser", "password123");
                +
                +        assertNotNull(result.token());
                +        assertNotNull(result.refreshToken());
                +        assertNotNull(result.expiresAt());
                +    }
                +
                +    @Test
                +    void authenticate_invalidPassword_throwsException() {
                +        User user = new User(1L, "testuser", "hashedpw", "USER", false);
                +        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
                +        when(passwordEncoder.matches("wrongpw", "hashedpw")).thenReturn(false);
                +
                +        assertThrows(AuthenticationException.class,
                +                () -> authService.authenticate("testuser", "wrongpw"));
                +    }
                +
                +    @Test
                +    void authenticate_lockedAccount_throwsException() {
                +        User user = new User(1L, "testuser", "hashedpw", "USER", true);
                +        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
                +        when(passwordEncoder.matches("password123", "hashedpw")).thenReturn(true);
                +
                +        assertThrows(AuthenticationException.class,
                +                () -> authService.authenticate("testuser", "password123"));
                +    }
                +
                +    @Test
                +    void authenticate_unknownUser_throwsException() {
                +        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());
                +
                +        assertThrows(AuthenticationException.class,
                +                () -> authService.authenticate("unknown", "password"));
                +    }
                +
                +    @Test
                +    void refreshToken_validToken_returnsNewTokens() {
                +        User user = new User(1L, "testuser", "hashedpw", "USER", false);
                +        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
                +        when(passwordEncoder.matches("password123", "hashedpw")).thenReturn(true);
                +        when(tokenProvider.generateToken(user)).thenReturn("jwt_token");
                +
                +        var authResult = authService.authenticate("testuser", "password123");
                +        var refreshResult = authService.refreshToken(authResult.refreshToken());
                +
                +        assertNotNull(refreshResult.token());
                +        assertNotEquals(authResult.refreshToken(), refreshResult.refreshToken());
                +    }
                +}
                """);

        // File 6: Database migration
        diff.append("""
                diff --git a/src/main/resources/db/migration/V2__add_auth_tables.sql b/src/main/resources/db/migration/V2__add_auth_tables.sql
                new file mode 100644
                index 0000000..7890123
                --- /dev/null
                +++ b/src/main/resources/db/migration/V2__add_auth_tables.sql
                @@ -0,0 +1,35 @@
                +-- Authentication tables
                +CREATE TABLE IF NOT EXISTS users (
                +    id BIGSERIAL PRIMARY KEY,
                +    username VARCHAR(50) NOT NULL UNIQUE,
                +    password_hash VARCHAR(255) NOT NULL,
                +    role VARCHAR(20) NOT NULL DEFAULT 'USER',
                +    is_locked BOOLEAN NOT NULL DEFAULT FALSE,
                +    failed_login_attempts INT NOT NULL DEFAULT 0,
                +    last_login_at TIMESTAMP,
                +    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                +    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                +);
                +
                +CREATE TABLE IF NOT EXISTS refresh_tokens (
                +    id BIGSERIAL PRIMARY KEY,
                +    user_id BIGINT NOT NULL REFERENCES users(id),
                +    token VARCHAR(255) NOT NULL UNIQUE,
                +    expires_at TIMESTAMP NOT NULL,
                +    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                +);
                +
                +CREATE INDEX idx_users_username ON users(username);
                +CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);
                +CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
                +
                +-- Audit log for authentication events
                +CREATE TABLE IF NOT EXISTS auth_audit_log (
                +    id BIGSERIAL PRIMARY KEY,
                +    user_id BIGINT REFERENCES users(id),
                +    event_type VARCHAR(50) NOT NULL,
                +    ip_address VARCHAR(45),
                +    user_agent TEXT,
                +    success BOOLEAN NOT NULL,
                +    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                +);
                """);

        return diff.toString();
    }
}

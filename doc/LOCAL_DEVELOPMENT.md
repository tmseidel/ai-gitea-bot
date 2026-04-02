# Local Development

This guide covers building, testing, and running the bot locally for development.

## Prerequisites

- **Java 21** or later
- **Maven 3.9+**
- **Docker** and **Docker Compose** (for the local Gitea instance)

## Build & Test

```bash
mvn clean package       # Compile and package (includes tests)
mvn test                # Run tests only
mvn clean package -DskipTests   # Package without running tests
```

## Running Natively

```bash
mvn spring-boot:run
```

This starts the bot on `http://localhost:8080` using the default profile:
- **H2 in-memory database** (no external database needed)
- Reads configuration from `src/main/resources/application.properties`

### Configuration for Local Development

The key environment variables to set:

```bash
export GITEA_URL=http://localhost:3000
export GITEA_TOKEN=your-local-gitea-token
export ANTHROPIC_API_KEY=your-api-key
```

Or edit `src/main/resources/application.properties` directly for local development.

## Local Gitea Instance

A pre-configured Gitea instance is provided under `systemtest/` for local testing.

### Starting Gitea

```bash
docker compose -f systemtest/docker-compose-local-gitea.yml up -d
```

This starts **Gitea 1.25.5** on `http://localhost:3000` with:
- Pre-configured test data (users, repos, PRs) in `systemtest/gitea/`
- Webhook delivery to the host enabled (`GITEA__webhook__ALLOWED_HOST_LIST=*`)
- `host.docker.internal` mapped to the host network

### Pre-configured Users

The local Gitea instance comes with existing test data in `systemtest/gitea/`. Log in to explore the existing setup or create new users as needed.

### Configuring the Webhook

1. Open `http://localhost:3000` in your browser
2. Navigate to a repository's **Settings → Webhooks → Add Webhook → Gitea**
3. Set the **Target URL** to: `http://host.docker.internal:8080/api/webhook`
4. Select events: **Pull Request**, **Issue Comment**, **Pull Request Review**, **Pull Request Comment**
5. Save the webhook

The `host.docker.internal` hostname allows the Gitea Docker container to reach the bot running natively on your host machine.

### Stopping Gitea

```bash
docker compose -f systemtest/docker-compose-local-gitea.yml down
```

The test data in `systemtest/gitea/` is persisted on disk and survives restarts.

## Test Profile

Tests use the `test` profile with `src/test/resources/application-test.properties`:
- H2 in-memory database
- Mock URLs for external services

Run tests with:

```bash
mvn test
```

## Project Structure

```
src/main/java/org/remus/giteabot/
├── config/       # Configuration classes, prompt service, bot config
├── gitea/        # Webhook controller, API client, payload models
│   └── model/    # WebhookPayload, GiteaReview, GiteaReviewComment
├── anthropic/    # Anthropic API client and request/response models
│   └── model/    # AnthropicRequest, AnthropicResponse
├── review/       # CodeReviewService (orchestration)
└── session/      # ReviewSession, ConversationMessage, SessionService
```

## Useful Endpoints

| Endpoint | Description |
|---|---|
| `POST /api/webhook` | Webhook receiver |
| `POST /api/webhook?prompt=security` | Webhook with specific prompt profile |
| `GET /actuator/health` | Health check |
| `GET /actuator/info` | Application info |


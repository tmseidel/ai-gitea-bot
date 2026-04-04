# Deployment

This guide covers deploying the AI Gitea Bot using Docker Compose.

## Prerequisites

- **Docker** and **Docker Compose** installed
- A **Gitea instance** with the bot user configured (see [Gitea Setup](GITEA_SETUP.md))
- An API key for your chosen AI provider (Anthropic, OpenAI) or a local Ollama instance

## Quick Start

### With Anthropic (default)

```bash
export GITEA_URL=https://your-gitea-instance.com
export GITEA_TOKEN=your-gitea-api-token
export AI_ANTHROPIC_API_KEY=your-anthropic-api-key

docker compose up --build -d
```

### With OpenAI

```bash
export GITEA_URL=https://your-gitea-instance.com
export GITEA_TOKEN=your-gitea-api-token
export AI_PROVIDER=openai
export AI_MODEL=gpt-4o
export AI_OPENAI_API_KEY=your-openai-api-key

docker compose up --build -d
```

### With Ollama (local LLM)

See [Using Ollama](OLLAMA.md) for a dedicated guide.

This starts:
- The bot application on port **8080**
- A **PostgreSQL 17** database for session persistence

## Docker Compose Template

Save the following as `docker-compose.yml` and adjust the values to your environment:

```yaml
services:
  app:
    image: ai-gitea-bot:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      GITEA_URL: https://your-gitea-instance.com
      GITEA_TOKEN: your-gitea-api-token
      AI_PROVIDER: anthropic           # "anthropic", "openai", or "ollama"
      AI_MODEL: claude-sonnet-4-20250514
      AI_MAX_TOKENS: 4096
      AI_ANTHROPIC_API_KEY: your-api-key
      BOT_USERNAME: "ai_bot"
      DATABASE_URL: jdbc:postgresql://db:5432/giteabot
      DATABASE_USERNAME: giteabot
      DATABASE_PASSWORD: change-me
    volumes:
      - ./prompts:/app/prompts:ro
    depends_on:
      db:
        condition: service_healthy
    restart: unless-stopped

  db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: giteabot
      POSTGRES_USER: giteabot
      POSTGRES_PASSWORD: change-me
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U giteabot"]
      interval: 5s
      timeout: 5s
      retries: 5
    restart: unless-stopped

volumes:
  pgdata:
```

> **Note:** Replace placeholders with your actual values. For sensitive values, consider using a `.env` file or Docker secrets.

## Environment Variables

### Required

| Variable | Description |
|---|---|
| `GITEA_URL` | URL of your Gitea instance (e.g., `https://gitea.example.com`) |
| `GITEA_TOKEN` | API token for the bot's Gitea user account |

### AI Provider Selection

| Variable | Default | Description |
|---|---|---|
| `AI_PROVIDER` | `anthropic` | AI provider to use: `anthropic`, `openai`, or `ollama` |
| `AI_MODEL` | `claude-sonnet-4-20250514` | Default model name for the selected provider |
| `AI_MAX_TOKENS` | `4096` | Max tokens per response |
| `AI_MAX_DIFF_CHARS_PER_CHUNK` | `120000` | Max characters per diff chunk |
| `AI_MAX_DIFF_CHUNKS` | `8` | Maximum number of chunks to review |
| `AI_RETRY_TRUNCATED_CHUNK_CHARS` | `60000` | Truncated chunk size on retry |

### Anthropic-Specific

| Variable | Default | Description |
|---|---|---|
| `AI_ANTHROPIC_API_URL` | `https://api.anthropic.com` | Anthropic API base URL |
| `AI_ANTHROPIC_API_KEY` | | Anthropic API key |
| `AI_ANTHROPIC_API_VERSION` | `2023-06-01` | Anthropic API version |

### OpenAI-Specific

| Variable | Default | Description |
|---|---|---|
| `AI_OPENAI_API_URL` | `https://api.openai.com` | OpenAI API base URL (also works with compatible APIs) |
| `AI_OPENAI_API_KEY` | | OpenAI API key |

### Ollama-Specific

| Variable | Default | Description |
|---|---|---|
| `AI_OLLAMA_API_URL` | `http://localhost:11434` | Ollama API base URL |

### Optional — Bot

| Variable | Default | Description |
|---|---|---|
| `BOT_USERNAME` | `ai_bot` | Gitea username of the bot account (mention alias is derived as `@ai_bot`) |

### Optional — Database

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://db:5432/giteabot` | JDBC connection URL |
| `DATABASE_USERNAME` | `giteabot` | Database username |
| `DATABASE_PASSWORD` | `giteabot` | Database password |

## Configurable Prompts

System prompts sent to the AI are customizable via markdown files. Place them in the `prompts/` directory, which is mounted as a read-only volume:

```yaml
volumes:
  - ./prompts:/app/prompts:ro
```

Prompt files can be edited on the host without rebuilding the Docker image.

### Defining Prompt Profiles

Add properties to your configuration to define named prompts:

```properties
# Default prompt — used when no ?prompt= parameter is provided
prompts.definitions.default.file=default.md

# Security-focused review with a more capable model
prompts.definitions.security.file=security-review.md
prompts.definitions.security.model=claude-opus-4-20250514

# Team-specific review using a separate Gitea service account
prompts.definitions.team-a.file=team-a-review.md
prompts.definitions.team-a.gitea-token=team-a-specific-token
```

Select the prompt via the webhook URL: `http://<bot-host>:8080/api/webhook?prompt=security`

## Dockerfile Details

The Dockerfile uses a **multi-stage build**:

1. **Build stage** (`eclipse-temurin:21-jdk-alpine`): Compiles the application with Maven
2. **Runtime stage** (`eclipse-temurin:21-jre-alpine`): Runs the JAR as a non-root user

Key features:
- Maven dependency layer caching for fast rebuilds
- Non-root `appuser` for security
- Health check via `/actuator/health` (interval: 30s, start period: 30s)
- JVM tuning: `UseContainerSupport` and `MaxRAMPercentage=75.0`

## Database

- PostgreSQL 17 (Alpine) is included in the Docker Compose setup
- Data is persisted in the `pgdata` Docker volume
- Schema is automatically managed by Hibernate (`ddl-auto=update`)
- The database stores review sessions and conversation history so the bot maintains context across PR updates

## Health Check

The bot exposes a health endpoint:

```
GET http://<bot-host>:8080/actuator/health
```

Use this for load balancer health checks or container orchestration.

## Stopping

```bash
docker compose down        # Stop containers (data preserved in pgdata volume)
docker compose down -v     # Stop and remove volumes (deletes all session data)
```


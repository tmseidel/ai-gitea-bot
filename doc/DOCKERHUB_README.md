# Anthropic Gitea Bot

AI-powered code review bot that connects your Gitea instance with the Anthropic Claude API.

## Features

- **Automatic PR Reviews** — Reviews diffs when Pull Requests are opened or updated
- **Interactive Bot Commands** — Mention `@claude_bot` in PR comments to ask questions
- **Inline Review Comments** — Mention the bot in code-level review comments for context-aware answers
- **Session Management** — Maintains conversation history per PR for follow-up interactions
- **Configurable Prompts** — Define multiple review profiles via markdown files
- **Smart Diff Chunking** — Splits large diffs into chunks with retry on token limits

## Quick Start

```bash
docker run -d \
  -p 8080:8080 \
  -e GITEA_URL=https://your-gitea-instance.com \
  -e GITEA_TOKEN=your-gitea-api-token \
  -e ANTHROPIC_API_KEY=your-anthropic-api-key \
  -e DATABASE_URL=jdbc:postgresql://your-db-host:5432/giteabot \
  -e DATABASE_USERNAME=giteabot \
  -e DATABASE_PASSWORD=your-db-password \
  -v ./prompts:/app/prompts:ro \
  anthropic-gitea-bot
```

Or with Docker Compose (includes PostgreSQL):

```yaml
# docker-compose.yml
services:
  app:
    image: anthropic-gitea-bot:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      GITEA_URL: https://your-gitea-instance.com
      GITEA_TOKEN: your-gitea-api-token
      ANTHROPIC_API_KEY: your-anthropic-api-key
      ANTHROPIC_MODEL: claude-sonnet-4-20250514
      ANTHROPIC_MAX_TOKENS: 4096
      BOT_ALIAS: "@claude_bot"
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

Then run:

```bash
docker compose up -d
```

## Environment Variables

### Required

| Variable | Description |
|---|---|
| `GITEA_URL` | URL of your Gitea instance |
| `GITEA_TOKEN` | API token for the bot's Gitea user account |
| `ANTHROPIC_API_KEY` | Anthropic API key for Claude |

### Optional

| Variable | Default | Description |
|---|---|---|
| `ANTHROPIC_MODEL` | `claude-sonnet-4-20250514` | Claude model for reviews |
| `ANTHROPIC_MAX_TOKENS` | `4096` | Max tokens per response |
| `BOT_ALIAS` | `@claude_bot` | Mention alias the bot responds to |
| `DATABASE_URL` | `jdbc:postgresql://db:5432/giteabot` | JDBC connection URL |
| `DATABASE_USERNAME` | `giteabot` | Database username |
| `DATABASE_PASSWORD` | `giteabot` | Database password |
| `PROMPTS_DIR` | `/app/prompts` | Directory for prompt markdown files |

## Gitea Webhook Setup

1. In your Gitea repository or organization, go to **Settings → Webhooks → Add Webhook → Gitea**
2. Set **Target URL** to `http://<bot-host>:8080/api/webhook`
3. Select events: **Pull Request**, **Issue Comment**, **Pull Request Review**, **Pull Request Comment**
4. Save the webhook

To use a specific prompt profile: `http://<bot-host>:8080/api/webhook?prompt=security`

## Volumes

| Path | Description |
|---|---|
| `/app/prompts` | System prompt markdown files (mount read-only) |

## Health Check

```
GET http://<host>:8080/actuator/health
```

Built-in health check runs every 30s with a 30s start period.

## Source Code & Documentation

- [GitHub Repository](https://github.com/your-org/anthropic-gitea-bot)
- [Architecture](https://github.com/your-org/anthropic-gitea-bot/blob/main/doc/ARCHITECTURE.md)
- [Gitea Setup Guide](https://github.com/your-org/anthropic-gitea-bot/blob/main/doc/GITEA_SETUP.md)
- [Deployment Guide](https://github.com/your-org/anthropic-gitea-bot/blob/main/doc/DEPLOYMENT.md)

## License

MIT



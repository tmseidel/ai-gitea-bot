# AI Code Review Bot

AI-powered code review bot for **Gitea, GitHub, GitLab, and Bitbucket** with a **web-based management UI**. Supports multiple AI providers — Anthropic Claude, OpenAI, Ollama (local LLMs), and llama.cpp.

## Features

- **Web-Based Management** — Configure bots, AI providers, and Git connections through a browser UI
- **Multi-Bot Support** — Create multiple bots with different AI providers and prompts
- **Multiple Git Providers** — Gitea, GitHub, GitHub Enterprise, GitLab, and Bitbucket Cloud support
- **Automatic PR Reviews** — Reviews diffs when Pull Requests are opened or updated
- **Multiple AI Providers** — Anthropic, OpenAI, Ollama, and llama.cpp support
- **Interactive Bot Commands** — Mention the bot in PR comments to ask questions
- **Inline Review Comments** — Context-aware answers to code-level review comments
- **Session Management** — Maintains conversation history per PR
- **Smart Diff Chunking** — Splits large diffs into chunks with retry on token limits
- **Issue Implementation Agent** — Assign the bot to an issue for autonomous code generation
- **Encrypted Secrets** — API keys and tokens are encrypted at rest

## Quick Start

```bash
docker compose up -d
```

Then:
1. Navigate to `http://localhost:8080`
2. Create your admin account
3. Configure AI and Git integrations via the web UI
4. Create a bot and configure webhooks in your Git provider

## Docker Compose

```yaml
services:
  app:
    image: tmseidel/ai-gitea-bot:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DATABASE_URL: jdbc:postgresql://db:5432/giteabot
      DATABASE_USERNAME: giteabot
      DATABASE_PASSWORD: change-me
      APP_ENCRYPTION_KEY: your-secure-encryption-key
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

## Supported AI Providers

| Provider | Default API URL | Suggested Models |
|----------|-----------------|------------------|
| **Anthropic** | `https://api.anthropic.com` | claude-opus-4-6, claude-sonnet-4-6, claude-haiku-4-5-20251001 |
| **OpenAI** | `https://api.openai.com` | gpt-5.4, gpt-5.3-codex, gpt-5.1-codex-max, gpt-5-codex |
| **Ollama** | `http://localhost:11434` | User-configured local models |
| **llama.cpp** | `http://localhost:8081` | User-configured GGUF models |

All AI configuration (API URLs, keys, models) is managed through the web UI — no environment variables needed.

## Supported Git Providers

| Provider | Description |
|----------|-------------|
| **Gitea** | Self-hosted Gitea instances |
| **GitHub** | github.com |
| **GitHub Enterprise** | Self-hosted GitHub Enterprise Server |

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_ENCRYPTION_KEY` | *(random)* | Encryption key for API keys/tokens. Set for persistence across restarts. |
| `DATABASE_URL` | `jdbc:postgresql://db:5432/giteabot` | JDBC connection URL |
| `DATABASE_USERNAME` | `giteabot` | Database username |
| `DATABASE_PASSWORD` | | Database password |

### Agent Configuration (Optional)

| Variable | Default | Description |
|----------|---------|-------------|
| `AGENT_MAX_FILES` | `20` | Maximum files the agent can modify per issue |
| `AGENT_MAX_TOKENS` | `32768` | Maximum tokens for AI responses in agent mode |
| `AGENT_BRANCH_PREFIX` | `ai-agent/` | Prefix for branches created by the agent |

## Webhook Setup

Each bot gets a unique webhook URL displayed in the web UI:
- **Gitea**: `/api/webhook/abc123-...`
- **GitHub**: `/api/github-webhook/abc123-...`

### For Gitea

1. Go to your repository → **Settings → Webhooks → Add Webhook → Gitea**
2. Set **Target URL** to your bot's webhook URL
3. Select events: **Pull Request**, **Issue Comment**, **Pull Request Review**, **Pull Request Comment**
4. Save the webhook

### For GitHub

1. Go to your repository → **Settings → Webhooks → Add webhook**
2. Set **Payload URL** to your bot's webhook URL
3. Set **Content type** to `application/json`
4. Select events: **Pull requests**, **Issue comments**, **Pull request reviews**, **Pull request review comments**
5. Save the webhook

## Volumes

| Path | Description |
|------|-------------|
| `/app/prompts` | System prompt templates (optional, mount read-only) |

## Health Check

```
GET http://<host>:8080/actuator/health
```

Built-in health check runs every 30s with a 30s start period.

## Source Code & Documentation

- [GitHub Repository](https://github.com/tmseidel/anthropic-gitea-bot)
- [User Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/USER_GUIDE.md)
- [Architecture](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/ARCHITECTURE.md)
- [Gitea Setup Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/GITEA_SETUP.md)
- [GitHub Setup Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/GITHUB_SETUP.md)
- [Deployment Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/DEPLOYMENT.md)

## License

MIT

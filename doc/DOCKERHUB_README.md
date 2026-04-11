# AI-Git-Bot

> **Half Bot, half Agent** — The intelligent Gateway between Git platforms and AI providers. 🤖🧠

AI-Git-Bot is a lightweight, self-hostable **Gateway application** for AI-powered code reviews and autonomous issue implementation. Connects **Gitea, GitHub, GitHub Enterprise, GitLab, and Bitbucket Cloud** with **Anthropic Claude, OpenAI, Ollama (local LLMs), and llama.cpp** — all managed through a **web-based UI**.

## Features

- **Gateway Architecture** — Central hub connecting any Git platform with any AI provider
- **Web-Based Management** — Configure bots, AI providers, and Git connections through a browser UI
- **Multi-Bot Support** — Create multiple bots with different AI providers, prompts, and personas
- **Multiple Git Providers** — Gitea, GitHub, GitHub Enterprise, GitLab, and Bitbucket Cloud support
- **Multiple AI Providers** — Anthropic, OpenAI, Ollama, and llama.cpp support
- **Automatic PR Reviews** — Reviews diffs when Pull Requests are opened or updated
- **Interactive Bot Commands** — Mention the bot in PR comments to ask questions
- **Inline Review Comments** — Context-aware answers to code-level review comments
- **Issue Implementation Agent** — Assign the bot to an issue for autonomous code generation
- **AI-Driven Code Validation** — Agent validates generated code with build tools (Maven, Gradle, npm, etc.)
- **Session Management** — Maintains conversation history per PR
- **Smart Diff Chunking** — Splits large diffs into chunks with retry on token limits
- **Encrypted Secrets** — API keys and tokens are encrypted at rest (AES-256-GCM)
- **Self-Host Friendly** — Run everything on-premise with local LLMs for compliance requirements

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
    image: tmseidel/ai-git-bot:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DATABASE_URL: jdbc:postgresql://db:5432/giteabot
      DATABASE_USERNAME: giteabot
      DATABASE_PASSWORD: change-me
      APP_ENCRYPTION_KEY: your-secure-encryption-key
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
| **GitLab** | gitlab.com and self-managed GitLab CE/EE |
| **Bitbucket Cloud** | bitbucket.org |

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

Each bot gets a unique webhook URL displayed in the web UI. The same URL format works for all Git providers:

- `/api/webhook/{webhook-secret}`

### Supported Events per Platform

| Event | Gitea | GitHub | GitLab | Bitbucket |
|-------|-------|--------|--------|-----------|
| Pull Request | ✅ | ✅ | ✅ Merge request events | ✅ PR: Created/Updated |
| Comments | ✅ Issue Comment | ✅ Issue comments | ✅ Comments | ✅ PR: Comment created |
| Issues (Agent) | ✅ | ✅ | ✅ Issues events | — |

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
- [Agent Documentation](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/AGENT.md)
- [Gitea Setup Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/GITEA_SETUP.md)
- [GitHub Setup Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/GITHUB_SETUP.md)
- [GitLab Setup Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/GITLAB_SETUP.md)
- [Bitbucket Setup Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/BITBUCKET_SETUP.md)
- [Deployment Guide](https://github.com/tmseidel/anthropic-gitea-bot/blob/main/doc/DEPLOYMENT.md)

## License

MIT

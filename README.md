# AI Gitea Bot

![AI Gitea Bot](doc/screenshot_small.png)

A Spring Boot application that connects your Gitea instance with AI providers to deliver automated, AI-powered code reviews on Pull Requests. The bot supports **multiple AI providers** — Anthropic Claude, OpenAI, and Ollama (local LLMs) — and can review new PRs, respond to questions in comments, and answer inline review comments while maintaining conversation context across interactions.

## Features

### 🔍 Automatic PR Code Reviews

When a Pull Request is opened or updated, the bot automatically reviews the diff and posts feedback as a review comment. Large diffs are intelligently split into chunks with automatic retry on token limits.

<img src="doc/screenshot_initial_code_review.png" alt="Initial Code Review" width="600"/>

### 💬 Interactive Bot Commands

Mention the bot (e.g., `@ai_bot`) in any PR comment to ask questions or request additional analysis. The bot acknowledges with 👀 and responds using the full conversation history.

<img src="doc/screenshot_code_review_with_comment.png" alt="Code Review with Comment" width="600"/>

### 📝 Inline Review Comment Responses

Mention the bot in an inline review comment on a specific code line. The bot includes the file context and diff hunk when generating its answer and replies directly inline.

<img src="doc/screenshot_code_review_with_inline_comment.png" alt="Code Review with Inline Comment" width="600"/>

### 🔌 Multiple AI Providers

Choose the AI provider that fits your needs:

| Provider | Use Case |
|---|---|
| **Anthropic** | Claude models via Anthropic API (default) |
| **OpenAI** | GPT models via OpenAI API or compatible endpoints |
| **Ollama** | Run open-source LLMs locally — no API key needed |
| **llama.cpp** | High-performance local inference with GBNF grammar support |

### More Features

- **Issue Implementation Agent** — Assign the bot to an issue and it will autonomously implement changes and create a PR (see [Agent Documentation](doc/AGENT.md))
- **Session Management** — Maintains conversation history per PR, persisted in a database, enabling context-aware follow-up reviews
- **Configurable System Prompts** — Define multiple review profiles (security audit, performance review, etc.) via markdown files, selectable per webhook
- **Per-Prompt Overrides** — Each prompt profile can override the AI model and Gitea API token
- **Review Submitted Handling** — Processes inline comments submitted as part of a Gitea review by fetching them from the API
- **Health Endpoint** — `/actuator/health` for monitoring and orchestration

## Docker

The bot is available as a Docker image on [Docker Hub](https://hub.docker.com/r/tmseidel/anthropic-gitea-bot).

```yaml
services:
  app:
    image: tmseidel/anthropic-gitea-bot:latest
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      GITEA_URL: ${GITEA_URL:-https://your-gitea-instance.com}
      GITEA_TOKEN: ${GITEA_TOKEN}
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
      ANTHROPIC_MODEL: ${ANTHROPIC_MODEL:-claude-sonnet-4-20250514}
      BOT_ALIAS: ${BOT_ALIAS:-@claude_bot}
      DATABASE_URL: jdbc:postgresql://db:5432/giteabot
      DATABASE_USERNAME: ${DATABASE_USERNAME:-giteabot}
      DATABASE_PASSWORD: ${DATABASE_PASSWORD:-giteabot}
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
      POSTGRES_USER: ${DATABASE_USERNAME:-giteabot}
      POSTGRES_PASSWORD: ${DATABASE_PASSWORD:-giteabot}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DATABASE_USERNAME:-giteabot}"]
      interval: 5s
      timeout: 5s
      retries: 5
    restart: unless-stopped

volumes:
  pgdata:
```


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

### With Ollama (local)

```bash
export GITEA_TOKEN=your-gitea-api-token

docker compose -f systemtest/docker-compose-ollama.yml up --build -d
```

This compose file provisions the local Ollama services only. Start the bot and your Gitea server separately, then see [Using Ollama](doc/OLLAMA.md) for details on configuring the bot to use Ollama.

> **Note:** The issue implementation agent is not recommended with Ollama due to JSON output limitations. Set `AGENT_ENABLED=false` when using local LLMs. Code reviews work well with any provider.

### With llama.cpp (local with grammar support)

```bash
# Start llama.cpp server with Qwen2.5-Coder 7B
docker compose -f systemtest/docker-compose-llamacpp.yml up -d

# Configure and start the bot
export AI_PROVIDER=llamacpp
export AI_MODEL=qwen2.5-coder-7b-instruct
export AI_LLAMACPP_API_URL=http://localhost:8081
export GITEA_TOKEN=your-gitea-api-token

docker compose up -d
```

llama.cpp supports GBNF grammar constraints, making the agent feature more reliable than Ollama. See [Using llama.cpp](doc/LLAMACPP.md) for details.

## Architecture Overview

```mermaid
graph LR
    Gitea["Gitea Instance"]
    Bot["AI Gitea Bot<br/>(Spring Boot)"]
    AI["AI Provider<br/>(Anthropic / OpenAI / Ollama)"]
    DB["PostgreSQL"]

    Gitea -- "Webhooks" --> Bot
    Bot -- "Fetch diff, post reviews" --> Gitea
    Bot -- "AI review requests" --> AI
    Bot -- "Session persistence" --> DB
```

The bot receives webhooks from Gitea, fetches PR diffs, sends them to the configured AI provider for review, and posts the results back. Conversation sessions are persisted in a database to maintain context across PR updates and follow-up interactions.

➡️ See [Architecture Documentation](doc/ARCHITECTURE.md) for detailed component diagrams and request flows.

## Documentation

| Document | Description |
|---|---|
| [Architecture](doc/ARCHITECTURE.md) | Component diagrams, request flows, webhook routing |
| [Agent](doc/AGENT.md) | Autonomous issue implementation agent setup and usage |
| [Gitea Setup](doc/GITEA_SETUP.md) | Bot user creation, permissions, API tokens, webhook configuration |
| [Deployment](doc/DEPLOYMENT.md) | Docker Compose deployment, environment variables, prompt configuration |
| [Using Ollama](doc/OLLAMA.md) | Running with local LLMs via Ollama |
| [Using llama.cpp](doc/LLAMACPP.md) | Running with llama.cpp and GBNF grammar support |
| [Local Development](doc/LOCAL_DEVELOPMENT.md) | Building, testing, local Gitea instance, project structure |
| [Contributing](CONTRIBUTING.md) | Contribution guidelines, coding conventions |
| [Code of Conduct](CODE_OF_CONDUCT.md) | Community standards |

## License

[MIT](LICENSE)
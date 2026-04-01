# Anthropic Gitea Bot

A bot that integrates a Gitea instance with the Anthropic API to provide automated AI-powered code reviews on Pull Requests.

## Features

- **Automated PR Code Reviews** — receives Gitea webhooks when PRs are opened or updated and posts AI-generated reviews
- **Agentic Session Management** — maintains conversation sessions per PR, persisted in a database, enabling context-aware follow-up reviews
- **Interactive Bot Commands** — mention the bot (e.g., `@claude_bot`) in PR comments to ask questions or request additional analysis; the bot reacts with 👀 and responds in context
- **Configurable System Prompts** — define multiple review profiles via markdown files, selectable per webhook
- **Per-Prompt Overrides** — each prompt definition can override the Claude model and Gitea API token
- **Smart Diff Chunking** — automatically splits large diffs into reviewable chunks with retry on token limits
- **Anthropic Claude Integration** — uses the Anthropic Messages API for intelligent, context-aware feedback
- **Health Endpoint** — `/actuator/health` for monitoring and orchestration

## Quick Start

### Docker Compose

```bash
export GITEA_URL=https://your-gitea-instance.com
export GITEA_TOKEN=your-gitea-api-token
export ANTHROPIC_API_KEY=your-anthropic-api-key

docker compose up --build -d
```

This starts the bot along with a PostgreSQL database for session persistence.

### Local Development

```bash
mvn spring-boot:run       # Start the application (uses H2 in-memory database)
mvn test                  # Run tests
mvn clean package         # Build jar
```

Requires Java 21+.

## Configuration

### Core Settings

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `gitea.url` | `GITEA_URL` | `http://localhost:3000` | Gitea instance URL |
| `gitea.token` | `GITEA_TOKEN` | — | Gitea API token |
| `anthropic.api.url` | `ANTHROPIC_API_URL` | `https://api.anthropic.com` | Anthropic API base URL |
| `anthropic.api.key` | `ANTHROPIC_API_KEY` | — | Anthropic API key |
| `anthropic.model` | `ANTHROPIC_MODEL` | `claude-sonnet-4-20250514` | Default model for reviews |
| `anthropic.max-tokens` | `ANTHROPIC_MAX_TOKENS` | `4096` | Max tokens per review response |
| `anthropic.max-diff-chars-per-chunk` | `ANTHROPIC_MAX_DIFF_CHARS_PER_CHUNK` | `120000` | Max characters per diff chunk |
| `anthropic.max-diff-chunks` | `ANTHROPIC_MAX_DIFF_CHUNKS` | `8` | Maximum number of diff chunks to review |
| `anthropic.retry-truncated-chunk-chars` | `ANTHROPIC_RETRY_TRUNCATED_CHUNK_CHARS` | `60000` | Truncated chunk size on retry |

### Bot Settings

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `bot.alias` | `BOT_ALIAS` | `@claude_bot` | The mention alias the bot responds to in PR comments |

### Database Settings

The bot uses a database to persist review sessions and conversation history. In Docker, a PostgreSQL database is provided. For local development, an H2 in-memory database is used by default.

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `spring.datasource.url` | `DATABASE_URL` | `jdbc:h2:mem:giteabot` (local) / `jdbc:postgresql://db:5432/giteabot` (Docker) | Database JDBC URL |
| `spring.datasource.username` | `DATABASE_USERNAME` | `sa` (local) / `giteabot` (Docker) | Database username |
| `spring.datasource.password` | `DATABASE_PASSWORD` | — | Database password |

### Configurable Prompts

System prompts sent to Claude are customizable via markdown files. Define multiple review profiles for different use cases (e.g., general review, security audit, performance review).

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `prompts.dir` | `PROMPTS_DIR` | `prompts` (`/app/prompts` in Docker) | Directory containing prompt markdown files |
| `prompts.definitions.<name>.file` | — | — | Markdown filename for the named prompt |
| `prompts.definitions.<name>.model` | — | — | Optional Claude model override for this prompt |
| `prompts.definitions.<name>.gitea-token` | — | — | Optional Gitea token override for this prompt |

**Example configuration:**

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

**Creating prompt files:** Place markdown files in the prompts directory. The file content is sent as-is as the system prompt to Claude. See `prompts/default.md` for an example.

**Docker volume mount:** Prompt files are mounted as a read-only volume so they can be edited without rebuilding the image:

```yaml
volumes:
  - ./prompts:/app/prompts:ro
```

## Gitea Webhook Setup

1. In your Gitea repository, go to **Settings → Webhooks → Add Webhook → Gitea**
2. Set the **Target URL** to `http://<bot-host>:8080/api/webhook`
3. To use a specific prompt profile, append the query parameter: `http://<bot-host>:8080/api/webhook?prompt=security`
4. Select **Pull Request Events** and **Issue Comment** events
5. Save the webhook

The bot will automatically:
- **Review PRs** when they are opened or updated, maintaining a conversation session per PR
- **Respond to commands** when mentioned (e.g., `@claude_bot explain the auth changes`) in PR comments
- **Clean up sessions** when PRs are closed or merged

### Bot Commands

Mention the bot alias in any PR comment to interact with it:

```
@claude_bot please explain the authentication changes
@claude_bot are there any security concerns with this approach?
@claude_bot suggest improvements for the error handling
```

The bot will:
1. React with 👀 to acknowledge the comment
2. Use the existing conversation context from the PR session
3. Post a response as a new comment on the PR

The bot alias is configurable via the `BOT_ALIAS` environment variable (default: `@claude_bot`).

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed architecture diagrams and component descriptions.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on how to contribute to this project.

## Code of Conduct

This project follows a Code of Conduct to ensure a welcoming and inclusive community. See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## License

[MIT](LICENSE)

# Anthropic Gitea Bot

A bot that integrates a Gitea instance with the Anthropic API to provide automated AI-powered code reviews on Pull Requests.

## Features

- **Automated PR Code Reviews** — receives Gitea webhooks when PRs are opened or updated and posts AI-generated reviews
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

### Local Development

```bash
mvn spring-boot:run       # Start the application
mvn test                  # Run tests
mvn clean package         # Build jar
```

Requires Java 21+.

## Configuration

| Property | Environment Variable | Default | Description |
|---|---|---|---|
| `gitea.url` | `GITEA_URL` | `http://localhost:3000` | Gitea instance URL |
| `gitea.token` | `GITEA_TOKEN` | — | Gitea API token |
| `anthropic.api.url` | `ANTHROPIC_API_URL` | `https://api.anthropic.com` | Anthropic API base URL |
| `anthropic.api.key` | `ANTHROPIC_API_KEY` | — | Anthropic API key |
| `anthropic.model` | `ANTHROPIC_MODEL` | `claude-sonnet-4-20250514` | Model to use for reviews |
| `anthropic.max-tokens` | `ANTHROPIC_MAX_TOKENS` | `4096` | Max tokens for the review response |

## Gitea Webhook Setup

1. In your Gitea repository, go to **Settings → Webhooks → Add Webhook → Gitea**
2. Set the **Target URL** to `http://<bot-host>:8080/api/webhook`
3. Select **Pull Request Events**
4. Save the webhook

The bot will automatically review PRs when they are opened or updated.

## License

[MIT](LICENSE)

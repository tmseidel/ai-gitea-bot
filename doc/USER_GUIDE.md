# User Guide

## Overview

AI Gitea Bot provides a web-based management interface for creating and managing AI-powered code review bots. Each bot connects an AI provider (Anthropic, OpenAI, Ollama, or llama.cpp) with a Git provider (Gitea) and has its own unique webhook URL.

All AI and Gitea configuration is managed exclusively through the web UI and stored in the database. There are no environment variables for AI providers, Gitea connections, or bot usernames.

## Getting Started

### Initial Setup

1. Start the application and navigate to `http://your-server:8080`
2. On first visit, you'll be redirected to the setup page
3. Create your administrator account with a username and password (minimum 8 characters)
4. After account creation, you'll be redirected to the login page

### Logging In

Navigate to `http://your-server:8080/login` and enter your administrator credentials.

## Dashboard

The dashboard (`/dashboard`) provides an overview of all your bots and key statistics:

- **Total Bots**: Number of configured bots
- **Active Bots**: Number of enabled bots
- **Total Webhook Calls**: Sum of all webhook calls across all bots
- **AI Tokens**: Total tokens sent to and received from AI providers

The bot table shows each bot's name, status, integrations, and recent activity.

## Managing AI Integrations

AI Integrations define connections to AI providers. Navigate to **AI Integrations** from the dashboard or navbar.

### Creating an AI Integration

1. Click **New Integration**
2. Fill in the form:
   - **Name**: A descriptive name (e.g., "Anthropic Production")
   - **Provider Type**: Select the AI provider (anthropic, openai, ollama, llamacpp)
   - **API URL**: The API endpoint URL
     - Anthropic: `https://api.anthropic.com`
     - OpenAI: `https://api.openai.com`
     - Ollama: `http://localhost:11434`
     - llama.cpp: `http://localhost:8081`
   - **API Key**: Your API key (encrypted at rest; not needed for Ollama)
   - **API Version**: API version string (Anthropic only, e.g., `2023-06-01`)
   - **Model**: The AI model to use (e.g., `claude-sonnet-4-20250514`, `gpt-4o`, `llama3`)
   - **Max Tokens**: Maximum tokens per AI response (default: 4096)
   - **Max Diff Chars Per Chunk**: Maximum characters per diff chunk (default: 120000)
   - **Max Diff Chunks**: Maximum number of diff chunks to process (default: 8)
   - **Retry Truncated Chunk Chars**: Truncated chunk size for retries (default: 60000)
3. Click **Save**

### Editing an AI Integration

Click the **Edit** button on the integration's row. When editing, leave the API Key field blank to keep the existing encrypted value.

### Deleting an AI Integration

Click the **Delete** button on the integration's row. You'll be asked to confirm. Note: You cannot delete an integration that is in use by a bot.

## Managing Git Integrations

Git Integrations define connections to Git providers. Navigate to **Git Integrations** from the dashboard or navbar.

### Creating a Git Integration

1. Click **New Integration**
2. Fill in the form:
   - **Name**: A descriptive name (e.g., "Gitea Production")
   - **Provider Type**: Select the Git provider (currently only "gitea")
   - **URL**: The Git server URL (e.g., `http://gitea:3000`)
   - **Token**: Your Git API token (encrypted at rest)
3. Click **Save**

### Managing Git Integrations

Edit and delete operations work the same as AI Integrations.

## Managing Bots

Bots are the core entities that connect an AI provider with a Git provider. Navigate to **Bots** from the dashboard or navbar.

### Creating a Bot

1. Click **New Bot**
2. Fill in the form:
   - **Name**: A unique name for the bot (e.g., "Code Reviewer")
   - **Username**: The Git username the bot uses (e.g., "ai_bot"). This is used to detect and ignore the bot's own actions.
   - **Custom Prompt**: Optional system prompt for the AI. Leave blank to use the default code review prompt.
   - **AI Integration**: Select an AI integration from the dropdown
   - **Git Integration**: Select a Git integration from the dropdown
   - **Enabled**: Whether the bot is active
   - **Agent Enabled**: Whether the AI agent feature (issue implementation) is active for this bot
3. Click **Save**

### Webhook URL

After creating a bot, a unique webhook URL is generated (e.g., `/api/webhook/abc123-def456-...`). Configure this URL in your Gitea repository's webhook settings:

1. In Gitea, go to your repository → Settings → Webhooks
2. Add a new webhook
3. Set the **Target URL** to: `http://your-bot-server:8080/api/webhook/{webhook-secret}`
4. Set **Content Type** to `application/json`
5. Select events: Pull Request events, Issue Comment events, and Issue events (if agent is enabled)

### Bot Statistics

The dashboard shows per-bot statistics:
- **Webhook Calls**: Total number of webhook requests received
- **Last Webhook**: Timestamp of the most recent webhook call
- **Last Error**: If the last operation failed, the error message and timestamp are displayed

## Security

### Data Encryption

Sensitive data (API keys, Git tokens) is encrypted at rest using AES-256-GCM encryption. Set the `APP_ENCRYPTION_KEY` environment variable to a secure value for production deployments. If not set, a random key is generated at startup (data won't survive restarts).

### Authentication

The web UI is protected by Spring Security with form-based authentication. The API webhook endpoints (`/api/**`) remain unauthenticated to allow Gitea to send webhooks.

## Configuration Reference

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_ENCRYPTION_KEY` | (random) | Encryption key for sensitive data |
| `DATABASE_URL` | H2 in-memory | Database JDBC URL |
| `DATABASE_USERNAME` | `sa` | Database username |
| `DATABASE_PASSWORD` | (empty) | Database password |

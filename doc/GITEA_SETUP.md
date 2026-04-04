# Gitea Instance Setup

This guide walks you through preparing your Gitea instance to work with the AI Gitea Bot.

## 1. Create the Bot User

The bot needs its own Gitea user account to post reviews and comments.

1. Log in to your Gitea instance as an **administrator**
2. Navigate to **Site Administration → User Accounts → Create User Account**
3. Fill in the details:
   - **Username:** `ai_bot` (or any name you prefer — must match the `BOT_USERNAME` setting)
   - **Email:** `ai_bot@noreply.localhost`
   - **Password:** Choose a strong password
4. Click **Create User Account**

> **Tip:** If self-registration is enabled, you can also register the bot user through the normal registration flow.

## 2. Add the Bot to Organizations and Repositories

The bot needs read/write access to repositories where it should perform code reviews.

### Option A: Organization-wide Access

1. Navigate to the organization's **Settings → Members**
2. Click **Invite Member**
3. Search for `ai_bot` and add it
4. Assign the **Write** role (or create a team with appropriate permissions)

### Option B: Per-Repository Access

1. Navigate to the repository's **Settings → Collaborators**
2. Search for `ai_bot`
3. Add with **Write** access

The bot needs at minimum:
- **Read** access to pull requests and code
- **Write** access to issues/comments (for posting reviews and reactions)

## 3. Create an API Token

The bot authenticates against the Gitea API using a personal access token.

1. Log in as the `ai_bot` user
2. Go to **Settings → Applications**
3. Under **Manage Access Tokens**, click **Generate New Token**
4. Give it a descriptive name, e.g., `ai-gitea-bot`
5. Select the following **permissions**:
   - ✅ `write:issue` — Post review comments, add reactions
   - ✅ `write:repository` — Read diffs, post pull request reviews
   - ✅ `write:user` — Needed for reading user context
6. Click **Generate Token**
7. **Copy the token immediately** — it will not be shown again

Use this token as the `GITEA_TOKEN` environment variable when deploying the bot.

## 4. Configure Webhooks

Webhooks tell Gitea to notify the bot when pull request events occur.

### Repository-level Webhook

1. In the repository, go to **Settings → Webhooks → Add Webhook → Gitea**
2. Configure:
   - **Target URL:** `http://<bot-host>:8080/api/webhook`
   - **HTTP Method:** POST
   - **Content Type:** application/json
   - **Secret:** (leave empty or set a shared secret)
3. Under **Trigger On**, select **Custom Events**, then enable:
   - ✅ **Pull Request**
   - ✅ **Issue Comment**
   - ✅ **Pull Request Review**
   - ✅ **Pull Request Comment**
4. Click **Add Webhook**

### Organization-level Webhook

To cover all repositories in an organization at once:

1. Go to the organization's **Settings → Webhooks**
2. Follow the same configuration as above

### Using Prompt Profiles

To use a specific prompt profile for a webhook, append the `prompt` query parameter:

```
http://<bot-host>:8080/api/webhook?prompt=security
```

This allows different repositories or organizations to use different review styles.

## 5. Configure the Bot Username

The `BOT_USERNAME` environment variable (default: `ai_bot`) must match the username of the bot account in Gitea. The mention alias (e.g., `@ai_bot`) is derived automatically.

If your bot user is named `my_review_bot`, set:

```bash
export BOT_USERNAME=my_review_bot
```

## Verification

After setup, create a test pull request. The bot should:

1. Automatically post an AI-generated code review
2. Respond when mentioned in PR comments (e.g., `@ai_bot explain this`)
3. Respond to inline review comments mentioning the bot
4. React with 👀 to acknowledge commands

Check the bot's application logs for troubleshooting if reviews don't appear.

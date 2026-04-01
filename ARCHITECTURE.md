# Architecture

This document describes the high-level architecture of the Anthropic Gitea Bot, including component responsibilities and request flows.

## System Overview

```mermaid
graph LR
    Gitea["Gitea Instance"]
    Bot["Anthropic Gitea Bot"]
    Anthropic["Anthropic API"]
    DB["PostgreSQL Database"]

    Gitea -- "Webhook (PR/Comment event)" --> Bot
    Bot -- "Fetch PR diff" --> Gitea
    Bot -- "Post review/comment" --> Gitea
    Bot -- "Add reaction" --> Gitea
    Bot -- "Review diff / Chat" --> Anthropic
    Anthropic -- "Review text" --> Bot
    Bot -- "Persist/Load session" --> DB
```

The bot sits between a Gitea instance and the Anthropic Claude API. When a pull request is opened or updated, Gitea sends a webhook to the bot. The bot fetches the diff, sends it to Claude for review, and posts the review back as a PR comment. Conversation sessions are persisted in a database so the bot maintains context across PR updates and comment interactions.

## Component Diagram

```mermaid
graph TD
    subgraph "Spring Boot Application"
        Controller["GiteaWebhookController<br/><i>REST endpoint</i>"]
        ReviewService["CodeReviewService<br/><i>Orchestration</i>"]
        SessionService["SessionService<br/><i>Session lifecycle</i>"]
        PromptService["PromptService<br/><i>Prompt resolution</i>"]
        GiteaClient["GiteaApiClient<br/><i>Gitea REST calls</i>"]
        AnthropicClient["AnthropicClient<br/><i>Claude API calls</i>"]
        AppConfig["AppConfig<br/><i>RestClient beans</i>"]
        PromptConfig["PromptConfigProperties<br/><i>Prompt definitions</i>"]
        BotConfig["BotConfigProperties<br/><i>Bot alias config</i>"]
        SessionRepo["ReviewSessionRepository<br/><i>JPA repository</i>"]
    end

    subgraph "External"
        Gitea["Gitea"]
        Anthropic["Anthropic API"]
        PromptFiles["Prompt Files<br/><i>prompts/*.md</i>"]
        DB["Database<br/><i>PostgreSQL / H2</i>"]
    end

    Controller --> ReviewService
    Controller --> BotConfig
    ReviewService --> PromptService
    ReviewService --> GiteaClient
    ReviewService --> AnthropicClient
    ReviewService --> SessionService
    SessionService --> SessionRepo
    SessionRepo --> DB
    PromptService --> PromptConfig
    PromptService --> PromptFiles
    GiteaClient --> Gitea
    AnthropicClient --> Anthropic
    AppConfig --> GiteaClient
    AppConfig --> AnthropicClient
```

## Components

### GiteaWebhookController

- **Package:** `org.remus.giteabot.gitea`
- **Endpoint:** `POST /api/webhook?prompt={name}`
- Receives Gitea webhook payloads for pull request and issue comment events
- Filters PR events for `opened`, `synchronized`, and `closed` actions
- Detects bot mentions (configurable alias) in PR comments and delegates to command handling
- Delegates to `CodeReviewService` asynchronously

### CodeReviewService

- **Package:** `org.remus.giteabot.review`
- Orchestrates the full review flow:
  1. Resolves prompt configuration (system prompt, model override, token override)
  2. Creates or reuses a session for the PR
  3. Fetches the PR diff from Gitea
  4. Sends the diff (or conversation) to Claude for review
  5. Stores messages in the session for future context
  6. Posts the review comment back to the PR
- Handles bot commands from PR comments:
  1. Adds an 👀 reaction to acknowledge the comment
  2. Sends the comment in the context of the existing conversation
  3. Posts the response as a new PR comment
- Handles PR close/merge by deleting the session
- Runs asynchronously via `@Async`

### SessionService

- **Package:** `org.remus.giteabot.session`
- Manages the lifecycle of review sessions:
  - Creates new sessions when PRs are opened
  - Retrieves existing sessions for PR updates and comment interactions
  - Stores conversation messages (user/assistant pairs)
  - Deletes sessions when PRs are closed or merged
- Converts stored messages to Anthropic API format for multi-turn conversations

### ReviewSession / ConversationMessage

- **Package:** `org.remus.giteabot.session`
- JPA entities persisted in the database
- `ReviewSession` stores: repo owner, repo name, PR number, prompt name, timestamps
- `ConversationMessage` stores: role (user/assistant), content, timestamp
- Sessions are uniquely identified by (repoOwner, repoName, prNumber)

### PromptService

- **Package:** `org.remus.giteabot.config`
- Resolves named prompt definitions from configuration
- Loads system prompt content from markdown files on disk
- Falls back to the `default` definition, then to a hardcoded built-in prompt
- Resolves per-prompt model and Gitea token overrides

### AnthropicClient

- **Package:** `org.remus.giteabot.anthropic`
- Sends review requests to the Anthropic Messages API
- Supports single-shot diff reviews with chunking
- Supports multi-turn conversations via the `chat()` method for session-based interactions
- Retries with truncated input when prompts exceed model limits
- Supports system prompt and model overrides per request

### GiteaApiClient

- **Package:** `org.remus.giteabot.gitea`
- Fetches PR diffs from the Gitea API
- Posts review comments and regular comments back to PRs
- Adds emoji reactions to comments (e.g., 👀 for acknowledgment)
- Supports per-request token overrides with cached `RestClient` instances

### BotConfigProperties

- **Package:** `org.remus.giteabot.config`
- Configures the bot mention alias (default: `@claude_bot`)
- The alias is used to detect bot commands in PR comments

### AppConfig

- **Package:** `org.remus.giteabot.config`
- Configures `RestClient` beans for Gitea and Anthropic API communication

### PromptConfigProperties

- **Package:** `org.remus.giteabot.config`
- Maps `prompts.*` configuration properties to named `PromptConfig` definitions
- Each definition specifies a markdown file and optional model/token overrides

## Request Flows

### PR Review Flow

```mermaid
sequenceDiagram
    participant Gitea
    participant Controller as WebhookController
    participant Review as CodeReviewService
    participant Session as SessionService
    participant DB as Database
    participant Prompt as PromptService
    participant GiteaAPI as GiteaApiClient
    participant Claude as AnthropicClient

    Gitea->>Controller: POST /api/webhook (PR opened)
    Controller->>Review: reviewPullRequest(payload, promptName)
    Review->>Prompt: resolveGiteaToken(promptName)
    Prompt-->>Review: token
    Review->>GiteaAPI: getPullRequestDiff(owner, repo, pr, token)
    GiteaAPI->>Gitea: GET .diff
    Gitea-->>GiteaAPI: diff content
    GiteaAPI-->>Review: diff
    Review->>Session: getOrCreateSession(owner, repo, pr)
    Session->>DB: find or create
    DB-->>Session: session
    Session-->>Review: session (new)
    Review->>Prompt: getSystemPrompt(promptName)
    Review->>Claude: reviewDiff(title, body, diff, prompt, model)
    Claude-->>Review: review text
    Review->>Session: addMessage("user", summary)
    Review->>Session: addMessage("assistant", review)
    Session->>DB: persist messages
    Review->>GiteaAPI: postReviewComment(owner, repo, pr, review)
    GiteaAPI->>Gitea: POST review
```

### PR Update Flow (Synchronized)

```mermaid
sequenceDiagram
    participant Gitea
    participant Controller as WebhookController
    participant Review as CodeReviewService
    participant Session as SessionService
    participant DB as Database
    participant Claude as AnthropicClient

    Gitea->>Controller: POST /api/webhook (PR synchronized)
    Controller->>Review: reviewPullRequest(payload, promptName)
    Review->>Session: getOrCreateSession(owner, repo, pr)
    Session->>DB: find existing
    DB-->>Session: session (with history)
    Session-->>Review: session (has messages)
    Review->>Session: toAnthropicMessages(session)
    Session-->>Review: conversation history
    Review->>Claude: chat(history, updateMessage, prompt, model)
    Claude-->>Review: updated review
    Review->>Session: addMessage("user", update)
    Review->>Session: addMessage("assistant", review)
    Review->>Gitea: postReviewComment(review)
```

### Bot Command Flow

```mermaid
sequenceDiagram
    participant User
    participant Gitea
    participant Controller as WebhookController
    participant Review as CodeReviewService
    participant Session as SessionService
    participant DB as Database
    participant GiteaAPI as GiteaApiClient
    participant Claude as AnthropicClient

    User->>Gitea: Comment: "@claude_bot explain this"
    Gitea->>Controller: POST /api/webhook (issue_comment)
    Controller->>Review: handleBotCommand(payload)
    Review->>GiteaAPI: addReaction(commentId, "eyes")
    GiteaAPI->>Gitea: POST 👀 reaction
    Review->>Session: getOrCreateSession(owner, repo, pr)
    Session->>DB: find existing
    DB-->>Session: session (with history)
    Review->>Session: toAnthropicMessages(session)
    Session-->>Review: conversation history
    Review->>Claude: chat(history, comment, prompt, model)
    Claude-->>Review: response
    Review->>Session: addMessage("user", comment)
    Review->>Session: addMessage("assistant", response)
    Review->>GiteaAPI: postComment(owner, repo, pr, response)
    GiteaAPI->>Gitea: POST comment
```

### PR Close/Merge Flow

```mermaid
sequenceDiagram
    participant Gitea
    participant Controller as WebhookController
    participant Review as CodeReviewService
    participant Session as SessionService
    participant DB as Database

    Gitea->>Controller: POST /api/webhook (PR closed)
    Controller->>Review: handlePrClosed(payload)
    Review->>Session: deleteSession(owner, repo, pr)
    Session->>DB: DELETE session + messages
```

## Diff Chunking Flow

```mermaid
flowchart TD
    A[Receive full diff] --> B{Diff size > max chunk chars?}
    B -- No --> C[Send as single chunk]
    B -- Yes --> D[Split at newline boundaries]
    D --> E{More chunks && under limit?}
    E -- Yes --> D
    E -- No --> F[Review each chunk]
    F --> G{API returns 'prompt too long'?}
    G -- No --> H[Collect review]
    G -- Yes --> I[Truncate and retry]
    I --> H
    H --> J[Combine all chunk reviews]
```

## Prompt Resolution Flow

```mermaid
flowchart TD
    A["Webhook arrives with ?prompt=name"] --> B{name provided?}
    B -- No --> C[Look up 'default' definition]
    B -- Yes --> D[Look up named definition]
    D --> E{Definition found?}
    E -- No --> C
    E -- Yes --> F[Load markdown file from prompts.dir]
    C --> G{Default definition exists?}
    G -- No --> H[Use hardcoded built-in prompt]
    G -- Yes --> F
    F --> I{File readable?}
    I -- No --> H
    I -- Yes --> J[Return file content as system prompt]
```

## Docker Deployment

```mermaid
graph LR
    subgraph "Docker Compose"
        subgraph "App Container"
            App["app.jar<br/>(Spring Boot)"]
            Prompts["/app/prompts/<br/>Mounted volume"]
        end
        subgraph "DB Container"
            Postgres["PostgreSQL 17<br/>(Session storage)"]
            PGData["pgdata volume"]
        end
    end

    Host["Host filesystem<br/>./prompts/"] -- "bind mount :ro" --> Prompts
    App -- reads --> Prompts
    App -- "JDBC" --> Postgres
    Postgres -- stores --> PGData
```

- The `prompts/` directory is baked into the image with a default prompt
- At runtime, the host's `./prompts/` directory is bind-mounted as read-only
- Prompt files can be edited on the host without rebuilding the image
- PostgreSQL persists review sessions and conversation history
- Session data survives container restarts via the `pgdata` volume

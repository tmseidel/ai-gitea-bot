# Architecture

This document describes the high-level architecture of the Anthropic Gitea Bot, including component responsibilities and request flows.

## System Overview

```mermaid
graph LR
    Gitea["Gitea Instance"]
    Bot["Anthropic Gitea Bot"]
    Anthropic["Anthropic API"]
    DB["PostgreSQL Database"]

    Gitea -- "Webhook (PR/Comment/Review event)" --> Bot
    Bot -- "Fetch PR diff" --> Gitea
    Bot -- "Post review/comment" --> Gitea
    Bot -- "Fetch reviews & comments" --> Gitea
    Bot -- "Add reaction" --> Gitea
    Bot -- "Review diff / Chat" --> Anthropic
    Anthropic -- "Review text" --> Bot
    Bot -- "Persist/Load session" --> DB
```

The bot sits between a Gitea instance and the Anthropic Claude API. When a pull request is opened or updated, Gitea sends a webhook to the bot. The bot fetches the diff, sends it to Claude for review, and posts the review back as a PR comment. Conversation sessions are persisted in a database so the bot maintains context across PR updates and comment interactions.

The bot also responds to inline review comments and submitted reviews containing bot mentions by fetching the relevant review data from the Gitea API and posting context-aware replies.

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
    ReviewService --> BotConfig
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
- Receives Gitea webhook payloads for pull request, issue comment, and review comment events
- Routes events based on payload structure:
  - **Inline review comments** (`comment.path` set): delegates to `handleInlineComment()`
  - **Issue/PR comments** (`comment` + `issue` set): delegates to `handleBotCommand()`
  - **Review submitted** (`action: "reviewed"` + `review` set): delegates to `handleReviewSubmitted()`
  - **PR lifecycle** (`opened`, `synchronized`, `closed`): delegates to `reviewPullRequest()` or `handlePrClosed()`
- Filters comments for bot mention (configurable alias) before processing
- Delegates to `CodeReviewService` asynchronously

### CodeReviewService

- **Package:** `org.remus.giteabot.review`
- Orchestrates all review and interaction flows:
  - **`reviewPullRequest()`**: Initial review or follow-up review on PR update. Fetches diff, sends to Claude, posts review comment.
  - **`handleBotCommand()`**: Responds to bot mentions in regular PR comments. Acknowledges with 👀 reaction, sends conversation to Claude, posts response.
  - **`handleInlineComment()`**: Responds to bot mentions in inline code review comments. Includes file path and diff hunk context. Replies inline at the same file/line, falls back to regular comment.
  - **`handleReviewSubmitted()`**: Handles review submission events where the individual comments are not in the webhook payload. Fetches reviews and their comments from the Gitea API, filters for bot mentions, and processes each matching comment.
- Manages session lifecycle (create, reuse, enrich with PR context)
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
- Posts review comments, regular comments, and inline review comments back to PRs
- Fetches reviews and review comments for a PR (used when processing submitted reviews)
- Adds emoji reactions to comments (e.g., 👀 for acknowledgment)
- Supports per-request token overrides with cached `RestClient` instances

### WebhookPayload Model

- **Package:** `org.remus.giteabot.gitea.model`
- Deserializes Gitea webhook payloads with support for:
  - PR events (`pullRequest`, `action`)
  - Issue comments (`comment`, `issue`)
  - Inline review comments (`comment.path`, `comment.diffHunk`, `comment.line`, `comment.pullRequestReviewId`)
  - Review submitted events (`review.id`, `review.type`, `review.content`)
  - Sender information (`sender`)

### BotConfigProperties

- **Package:** `org.remus.giteabot.config`
- Configures the bot mention alias (default: `@claude_bot`)
- Used by both the webhook controller (for filtering) and the code review service (for review comment filtering)

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

### Inline Review Comment Flow

```mermaid
sequenceDiagram
    participant User
    participant Gitea
    participant Controller as WebhookController
    participant Review as CodeReviewService
    participant Session as SessionService
    participant GiteaAPI as GiteaApiClient
    participant Claude as AnthropicClient

    User->>Gitea: Inline comment on code: "@claude_bot explain this"
    Gitea->>Controller: POST /api/webhook (comment with path)
    Controller->>Review: handleInlineComment(payload)
    Review->>GiteaAPI: addReaction(commentId, "eyes")
    Review->>Session: getOrCreateSession(owner, repo, pr)
    Review->>Claude: chat(history, fileContext + diffHunk + question)
    Claude-->>Review: response
    Review->>GiteaAPI: postInlineReviewComment(file, line, response)
    Note right of GiteaAPI: Falls back to postComment() on error
```

### Review Submitted Flow

```mermaid
sequenceDiagram
    participant User
    participant Gitea
    participant Controller as WebhookController
    participant Review as CodeReviewService
    participant GiteaAPI as GiteaApiClient
    participant Claude as AnthropicClient

    User->>Gitea: Submit review with inline comments
    Gitea->>Controller: POST /api/webhook (action: "reviewed")
    Controller->>Review: handleReviewSubmitted(payload)
    Review->>GiteaAPI: getReviews(owner, repo, pr)
    GiteaAPI-->>Review: list of reviews
    Review->>Review: find latest review (highest ID)
    Review->>GiteaAPI: getReviewComments(owner, repo, pr, reviewId)
    GiteaAPI-->>Review: list of comments
    Review->>Review: filter for bot mentions
    loop For each bot-mentioning comment
        Review->>GiteaAPI: addReaction(commentId, "eyes")
        Review->>Claude: chat(history, fileContext + question)
        Claude-->>Review: response
        Review->>GiteaAPI: postInlineReviewComment(file, line, response)
    end
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

## Webhook Routing Flow

```mermaid
flowchart TD
    A["Webhook received"] --> B{comment with path?}
    B -- Yes --> C["handleInlineReviewComment()<br/>Bot mention in code-level comment"]
    B -- No --> D{comment + issue?}
    D -- Yes --> E["handleCommentEvent()<br/>Bot mention in PR comment"]
    D -- No --> F{pullRequest present?}
    F -- No --> G["ignored"]
    F -- Yes --> H{action = reviewed?}
    H -- Yes --> I["handleReviewSubmittedEvent()<br/>Fetch & process review comments"]
    H -- No --> J{action = closed?}
    J -- Yes --> K["handlePrClosed()"]
    J -- No --> L{action = opened/synchronized?}
    L -- Yes --> M["reviewPullRequest()"]
    L -- No --> G
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


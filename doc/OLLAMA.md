# Using Ollama

This guide covers running the AI Code Review Bot with [Ollama](https://ollama.com) for fully local, private AI-powered code reviews — no external API keys required.

## ⚠️ Important: Agent Compatibility

**The issue implementation agent has limited support with Ollama.**

The agent feature requires the AI to produce structured JSON output with specific fields (`fileChanges`, `runTool`, etc.). 

### Automatic JSON Mode

The bot **automatically enables Ollama's JSON mode** when it detects that the system prompt requests JSON output (e.g., the agent prompt). This significantly improves reliability compared to earlier versions.

However, local LLMs may still:
- Produce incomplete or malformed JSON responses
- Struggle with complex multi-file implementations
- Return simpler JSON than requested

### Recommendations

| Model Size | Agent Compatibility |
|------------|---------------------|
| 1-7B parameters | ❌ **Not recommended** — often fails even with JSON mode |
| 14-16B parameters | ⚠️ **May work** — better results with JSON mode |
| 32B+ parameters | ✅ **Best chance** — JSON mode helps significantly |

For reliable agent usage, use **Anthropic Claude or OpenAI GPT-4**. Ollama works well for **code reviews** (PR comments), which only require natural language responses.

If you want to disable the agent when using smaller Ollama models:

```bash
export AGENT_ENABLED=false
```

See [Agent Documentation](AGENT.md#ollama-limitations) for more details.

## Overview

Ollama lets you run open-source LLMs locally. The bot connects to Ollama's `/api/chat` endpoint, sending diffs and conversation history just like it would to Anthropic or OpenAI, but everything stays on your machine.

## Quick Start

A ready-to-use Docker Compose environment is provided in `systemtest/`:

```bash
export GITEA_TOKEN=your-gitea-api-token

docker compose -f systemtest/docker-compose-ollama.yml up --build -d
```

This starts:
- **Ollama** on port `11434` with a small LLM (`llama3.2:1b` by default)

The `ollama-pull` service automatically downloads the configured model on first start. You can then run the bot separately with the main `docker-compose.yml`, setting `AI_PROVIDER=ollama`.

**Important:** When using Ollama, disable the agent feature:

```bash
export AI_PROVIDER=ollama
export AI_MODEL=llama3.2:1b
export AI_OLLAMA_API_URL=http://localhost:11434
export AGENT_ENABLED=false  # Recommended for Ollama

docker compose up -d
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `AI_PROVIDER` | `ollama` | Must be `ollama` to use the Ollama backend |
| `AI_MODEL` | `llama3.2:1b` | Ollama model name (must be pulled first) |
| `AI_OLLAMA_API_URL` | `http://localhost:11434` | Ollama API base URL |
| `AI_MAX_TOKENS` | `2048` | Max tokens per response |
| `AI_MAX_DIFF_CHARS_PER_CHUNK` | `30000` | Max characters per diff chunk (smaller for local models) |
| `AI_MAX_DIFF_CHUNKS` | `4` | Maximum number of chunks to review |

### Using with an Existing Docker Compose

Add Ollama settings to your existing `docker-compose.yml`:

```yaml
services:
  app:
    environment:
      AI_PROVIDER: ollama
      AI_MODEL: llama3.2:1b
      AI_OLLAMA_API_URL: http://ollama:11434
      # ... other settings
    depends_on:
      ollama:
        condition: service_healthy

  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
    healthcheck:
      test: ["CMD-SHELL", "ollama list || exit 1"]
      interval: 10s
      timeout: 5s
      start_period: 30s
      retries: 5
    restart: unless-stopped

  ollama-pull:
    image: ollama/ollama:latest
    entrypoint: ["sh", "-c", "sleep 5 && ollama pull ${AI_MODEL:-llama3.2:1b}"]
    environment:
      OLLAMA_HOST: http://ollama:11434
    depends_on:
      ollama:
        condition: service_healthy

volumes:
  ollama_data:
```

### Using with a Host-Installed Ollama

If Ollama is already installed on your host machine:

```bash
# Pull a model
ollama pull llama3.2:1b

# Start the bot pointing to host Ollama
export AI_PROVIDER=ollama
export AI_MODEL=llama3.2:1b
export AI_OLLAMA_API_URL=http://localhost:11434
export AGENT_ENABLED=false  # Agent not recommended with Ollama

mvn spring-boot:run
```

## Recommended Models

### For Code Reviews (PR Comments)

Any model works for code reviews since they only require natural language output:

| Model | Size | Notes |
|---|---|---|
| `llama3.2:1b` | ~1.3 GB | Smallest, fastest — good for testing |
| `llama3.2:3b` | ~2.0 GB | Better quality, still fast |
| `codellama:7b` | ~3.8 GB | Code-focused, better for reviews |
| `deepseek-coder-v2:16b` | ~8.9 GB | High quality code reviews |
| `qwen2.5-coder:7b` | ~4.7 GB | Strong code understanding |

### For Agent (Issue Implementation) — Experimental

The agent requires structured JSON output. **Most local models fail at this**, but larger models have better success rates:

| Model | Size | Agent Compatibility |
|---|---|---|
| `llama3.2:1b` - `llama3.2:3b` | 1-2 GB | ❌ **Not recommended** — fails to produce JSON |
| `codellama:7b` - `codellama:13b` | 4-7 GB | ⚠️ **Unreliable** — sometimes works, often fails |
| `deepseek-coder-v2:16b` | ~8.9 GB | ⚠️ **May work** — better instruction following |
| `qwen2.5-coder:14b` | ~9 GB | ⚠️ **May work** — good at structured output |
| `qwen2.5-coder:32b` | ~20 GB | ✅ **Best chance** — strong instruction following |
| `codellama:70b` | ~40 GB | ✅ **Best chance** — largest, most capable |
| `deepseek-coder:33b` | ~19 GB | ✅ **Best chance** — good JSON compliance |

**Important notes:**
- Even "Best chance" models may occasionally fail to produce valid JSON
- Larger models require significantly more RAM/VRAM (32b needs ~24GB+, 70b needs ~48GB+)
- For production agent usage, **Anthropic Claude or OpenAI GPT-4 is strongly recommended**
- If you want to experiment with the agent on Ollama, start with `qwen2.5-coder:32b` or `deepseek-coder:33b`

### Trying Agent with Larger Models

If you want to test the agent with a larger Ollama model:

```bash
# Pull a larger model (requires significant RAM/VRAM)
ollama pull qwen2.5-coder:32b

# Run with agent enabled
export AI_PROVIDER=ollama
export AI_MODEL=qwen2.5-coder:32b
export AGENT_ENABLED=true  # Enable at your own risk

docker compose up -d
```

Monitor the logs for JSON parsing errors. If you see frequent failures, disable the agent:
```bash
export AGENT_ENABLED=false
```

Choose a model based on your available memory and quality requirements. Smaller models are faster but may produce lower-quality reviews.

### Pulling a Model

```bash
# Via CLI
ollama pull llama3.2:1b

# Or via API
curl http://localhost:11434/api/pull -d '{"name": "llama3.2:1b"}'
```

## Tuning for Local Models

Local models typically have smaller context windows and are slower than cloud APIs. Adjust these settings for best results:

```properties
# Smaller chunks to fit in context window
ai.max-diff-chars-per-chunk=30000

# Fewer chunks to reduce processing time
ai.max-diff-chunks=4

# Smaller retry size
ai.retry-truncated-chunk-chars=15000

# Lower max tokens for faster responses
ai.max-tokens=2048
```

## GPU Acceleration

For significantly better performance, use Ollama with GPU support:

```yaml
ollama:
  image: ollama/ollama:latest
  deploy:
    resources:
      reservations:
        devices:
          - driver: nvidia
            count: 1
            capabilities: [gpu]
```

This requires the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html).

## Troubleshooting

### Agent JSON Parsing Errors

If you see errors like:
```
ERROR: Failed to parse AI response as JSON: Unexpected character ('@' (code 64))
```

This means the AI returned raw code instead of valid JSON. The bot automatically enables Ollama's JSON mode for agent requests, but smaller models may still fail to produce valid structured output.

**Solutions:**
1. **Use a larger model** (32B+ parameters work best):
   ```bash
   ollama pull qwen2.5-coder:32b
   export AI_MODEL=qwen2.5-coder:32b
   ```
2. **Disable the agent** when using smaller models:
   ```bash
   export AGENT_ENABLED=false
   ```
3. **Use a cloud provider** (Anthropic Claude, OpenAI GPT-4) for reliable agent functionality

You can verify JSON mode is active by checking the logs for:
```
INFO: Ollama chat request: JSON mode enabled for structured output
```

### Model Not Found

If you see errors about the model not being found, pull it first:

```bash
docker compose exec ollama ollama pull llama3.2:1b
```

### Slow Responses

- Use a smaller model (e.g., `llama3.2:1b`)
- Reduce `AI_MAX_DIFF_CHARS_PER_CHUNK` and `AI_MAX_DIFF_CHUNKS`
- Enable GPU acceleration
- Increase container memory limits

### Connection Refused

Ensure the Ollama service is healthy before the bot starts. The provided Docker Compose uses health checks to handle this automatically.

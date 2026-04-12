package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes external tools (e.g. build / test commands) requested by the AI agent.
 * <p>
 * The list of allowed tools is configured via
 * {@link AgentConfigProperties.ValidationConfig#getAvailableTools()}.
 */
@Slf4j
@Service
public class ToolExecutionService {

    private final AgentConfigProperties agentConfig;

    public ToolExecutionService(AgentConfigProperties agentConfig) {
        this.agentConfig = agentConfig;
    }

    /**
     * Returns the list of tools that the AI agent is allowed to invoke.
     */
    public List<String> getAvailableTools() {
        return agentConfig.getValidation().getAvailableTools();
    }

    /**
     * Executes a tool command in the given workspace directory.
     *
     * @param workspaceDir The workspace directory
     * @param tool         The tool to execute (must be in {@link #getAvailableTools()})
     * @param arguments    The arguments to pass to the tool
     * @return The execution result
     */
    public ToolResult executeTool(Path workspaceDir, String tool, List<String> arguments) {
        List<String> availableTools = getAvailableTools();
        if (!availableTools.contains(tool)) {
            return new ToolResult(false, -1,
                    "Tool '" + tool + "' is not available. Available tools: "
                            + String.join(", ", availableTools),
                    "");
        }

        String[] command = new String[1 + (arguments != null ? arguments.size() : 0)];
        command[0] = tool;
        if (arguments != null) {
            for (int i = 0; i < arguments.size(); i++) {
                command[i + 1] = arguments.get(i);
            }
        }

        log.info("Executing tool: {} {}", tool,
                arguments != null ? String.join(" ", arguments) : "");

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workspaceDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int timeoutSeconds = agentConfig.getValidation().getToolTimeoutSeconds();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new ToolResult(false, -1, "",
                        "Tool execution timed out after " + timeoutSeconds + " seconds");
            }

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;

            log.info("Tool {} with exit code {}",
                    success ? "succeeded" : "failed", exitCode);

            String outputStr = output.toString();
            if (outputStr.length() > 10_000) {
                outputStr = outputStr.substring(0, 10_000) + "\n... (output truncated)";
            }

            return new ToolResult(success, exitCode, outputStr, "");

        } catch (IOException e) {
            log.error("Failed to execute tool: {}", e.getMessage());
            return new ToolResult(false, -1, "",
                    "Failed to execute tool: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult(false, -1, "",
                    "Tool execution interrupted");
        }
    }
}

package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.DiffApplyService;
import org.remus.giteabot.agent.model.FileChange;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes tools requested by the AI agent for validation purposes.
 * The AI decides which tools to run based on the file tree and available tools.
 */
@Slf4j
@Service
public class ToolExecutionService {

    private final AgentConfigProperties agentConfig;
    private final DiffApplyService diffApplyService;
    private final String giteaUrl;
    private final String giteaToken;

    public ToolExecutionService(AgentConfigProperties agentConfig,
                                DiffApplyService diffApplyService,
                                @Value("${gitea.url}") String giteaUrl,
                                @Value("${gitea.token}") String giteaToken) {
        this.agentConfig = agentConfig;
        this.diffApplyService = diffApplyService;
        this.giteaUrl = giteaUrl;
        this.giteaToken = giteaToken;
    }

    /**
     * Returns the list of available tools for the AI to use.
     */
    public List<String> getAvailableTools() {
        return agentConfig.getValidation().getAvailableTools();
    }

    /**
     * Prepares a workspace by cloning the repository and applying file changes.
     * Returns the result containing either the workspace path or an error message.
     *
     * @param owner       Repository owner
     * @param repo        Repository name
     * @param branch      The branch to clone
     * @param fileChanges The file changes to apply
     * @return WorkspaceResult containing the path or error details
     */
    public WorkspaceResult prepareWorkspace(String owner, String repo, String branch,
                                  List<FileChange> fileChanges) {
        try {
            // Create temp directory for clone
            Path tempDir = Files.createTempDirectory("agent-validation-");
            log.info("Cloning repository to {} for validation", tempDir);

            // Clone using git command
            String cloneUrl = buildCloneUrl(owner, repo);
            CommandResult cloneResult = runCommand(tempDir.getParent().toFile(),
                    new String[]{"git", "clone", "--depth", "1", "--branch", branch, cloneUrl, tempDir.getFileName().toString()},
                    60);

            if (!cloneResult.success()) {
                log.error("Failed to clone repository: {}", cloneResult.output());
                deleteDirectory(tempDir);
                return WorkspaceResult.failure("Failed to clone repository: " + cloneResult.output());
            }

            // Apply file changes
            for (FileChange change : fileChanges) {
                Path filePath = tempDir.resolve(change.getPath());

                switch (change.getOperation()) {
                    case CREATE -> {
                        Files.createDirectories(filePath.getParent());
                        Files.writeString(filePath, change.getContent());
                    }
                    case UPDATE -> {
                        Files.createDirectories(filePath.getParent());
                        String newContent;
                        if (change.isDiffBased()) {
                            // Apply diff to existing file content
                            String originalContent = Files.exists(filePath)
                                    ? Files.readString(filePath)
                                    : "";
                            try {
                                newContent = diffApplyService.applyDiff(originalContent, change.getDiff());
                                log.debug("Applied diff to {}: {} chars -> {} chars",
                                        change.getPath(), originalContent.length(), newContent.length());
                            } catch (DiffApplyService.DiffApplyException e) {
                                log.error("Failed to apply diff to {}: {}", change.getPath(), e.getMessage());
                                return WorkspaceResult.failure("Failed to apply diff to " + change.getPath() + ": " + e.getMessage());
                            }
                        } else {
                            // Full content replacement
                            newContent = change.getContent();
                        }
                        Files.writeString(filePath, newContent);
                    }
                    case DELETE -> Files.deleteIfExists(filePath);
                }
            }

            return WorkspaceResult.success(tempDir);

        } catch (IOException e) {
            log.error("Failed to prepare workspace: {}", e.getMessage());
            return WorkspaceResult.failure("Failed to prepare workspace: " + e.getMessage());
        }
    }

    /**
     * Result of workspace preparation.
     */
    public record WorkspaceResult(
            boolean success,
            Path workspacePath,
            String error
    ) {
        public static WorkspaceResult success(Path path) {
            return new WorkspaceResult(true, path, null);
        }

        public static WorkspaceResult failure(String error) {
            return new WorkspaceResult(false, null, error);
        }
    }

    /**
     * Executes a tool command in the given workspace directory.
     *
     * @param workspaceDir The workspace directory
     * @param tool         The tool to execute (must be in availableTools)
     * @param arguments    The arguments to pass to the tool
     * @return The execution result
     */
    public ToolResult executeTool(Path workspaceDir, String tool, List<String> arguments) {
        // Validate tool is allowed
        List<String> availableTools = getAvailableTools();
        if (!availableTools.contains(tool)) {
            return new ToolResult(false, -1,
                    "Tool '" + tool + "' is not available. Available tools: " + String.join(", ", availableTools),
                    "");
        }

        // Build command
        String[] command = new String[1 + (arguments != null ? arguments.size() : 0)];
        command[0] = tool;
        if (arguments != null) {
            for (int i = 0; i < arguments.size(); i++) {
                command[i + 1] = arguments.get(i);
            }
        }

        log.info("Executing tool: {} {}", tool, arguments != null ? String.join(" ", arguments) : "");

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workspaceDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Capture output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // Wait for process with timeout
            int timeoutSeconds = agentConfig.getValidation().getToolTimeoutSeconds();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new ToolResult(false, -1, "",
                        "Tool execution timed out after " + timeoutSeconds + " seconds");
            }

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;

            log.info("Tool {} with exit code {}", success ? "succeeded" : "failed", exitCode);

            // Truncate output if too long
            String outputStr = output.toString();
            if (outputStr.length() > 10000) {
                outputStr = outputStr.substring(0, 10000) + "\n... (output truncated)";
            }

            return new ToolResult(success, exitCode, outputStr, "");

        } catch (IOException e) {
            log.error("Failed to execute tool: {}", e.getMessage());
            return new ToolResult(false, -1, "", "Failed to execute tool: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult(false, -1, "", "Tool execution interrupted");
        }
    }

    /**
     * Cleans up a workspace directory.
     */
    public void cleanupWorkspace(Path workspaceDir) {
        if (workspaceDir != null) {
            try {
                deleteDirectory(workspaceDir);
                log.debug("Cleaned up workspace: {}", workspaceDir);
            } catch (IOException e) {
                log.warn("Failed to clean up workspace {}: {}", workspaceDir, e.getMessage());
            }
        }
    }

    private String buildCloneUrl(String owner, String repo) {
        // Preserve the original protocol (http or https)
        String protocol = giteaUrl.startsWith("https://") ? "https" : "http";
        String baseUrl = giteaUrl.replaceFirst("https?://", "");
        return String.format("%s://%s@%s/%s/%s.git", protocol, giteaToken, baseUrl, owner, repo);
    }

    private CommandResult runCommand(File workDir, String[] command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Capture output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false, "Command timed out after " + timeoutSeconds + " seconds");
            }

            boolean success = process.exitValue() == 0;
            return new CommandResult(success, output.toString());
        } catch (IOException | InterruptedException e) {
            log.error("Failed to run command: {}", e.getMessage());
            return new CommandResult(false, "Exception: " + e.getMessage());
        }
    }

    private record CommandResult(boolean success, String output) {}

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                log.warn("Failed to delete {}: {}", path, e.getMessage());
                            }
                        });
            }
        }
    }

    /**
     * Result of a tool execution.
     */
    public record ToolResult(
            boolean success,
            int exitCode,
            String output,
            String error
    ) {
        /**
         * Formats the result for sending to the AI.
         */
        public String formatForAi() {
            StringBuilder sb = new StringBuilder();
            sb.append("Exit code: ").append(exitCode).append("\n");
            if (!error.isEmpty()) {
                sb.append("Error: ").append(error).append("\n");
            }
            if (!output.isEmpty()) {
                sb.append("Output:\n```\n").append(output).append("```\n");
            }
            return sb.toString();
        }
    }
}


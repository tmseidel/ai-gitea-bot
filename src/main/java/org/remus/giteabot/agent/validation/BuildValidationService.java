package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.model.FileChange;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Validates code by cloning the repository, applying changes, and running a build.
 * This catches real compilation errors including missing imports, wrong method signatures, etc.
 */
@Slf4j
@Service
public class BuildValidationService {

    private final AgentConfigProperties agentConfig;
    private final String giteaUrl;
    private final String giteaToken;

    public BuildValidationService(AgentConfigProperties agentConfig,
                                  @Value("${gitea.url}") String giteaUrl,
                                  @Value("${gitea.token}") String giteaToken) {
        this.agentConfig = agentConfig;
        this.giteaUrl = giteaUrl;
        this.giteaToken = giteaToken;
    }

    /**
     * Validates file changes by cloning the repo, applying them, and running the build.
     *
     * @param owner       Repository owner
     * @param repo        Repository name
     * @param branch      The branch to clone
     * @param fileChanges The file changes to apply and validate
     * @return Build result containing success status and any error messages
     */
    public BuildResult validateWithBuild(String owner, String repo, String branch,
                                         List<FileChange> fileChanges) {
        if (!agentConfig.getValidation().isBuildEnabled()) {
            log.debug("Build validation is disabled");
            return new BuildResult(true, "", "Build validation disabled");
        }

        Path tempDir = null;
        try {
            // Create temp directory for clone
            tempDir = Files.createTempDirectory("agent-build-");
            log.info("Cloning repository to {} for build validation", tempDir);

            // Clone using git command (more reliable than JGit in Docker)
            String cloneUrl = buildCloneUrl(owner, repo);
            boolean cloneSuccess = runCommand(tempDir.getParent().toFile(),
                    new String[]{"git", "clone", "--depth", "1", "--branch", branch, cloneUrl, tempDir.getFileName().toString()},
                    60);

            if (!cloneSuccess) {
                return new BuildResult(false, "", "Failed to clone repository");
            }

            // Apply file changes
            for (FileChange change : fileChanges) {
                Path filePath = tempDir.resolve(change.getPath());

                switch (change.getOperation()) {
                    case CREATE, UPDATE -> {
                        Files.createDirectories(filePath.getParent());
                        Files.writeString(filePath, change.getContent());
                    }
                    case DELETE -> Files.deleteIfExists(filePath);
                }
            }

            // Detect build system and run build
            BuildSystem buildSystem = detectBuildSystem(tempDir);
            log.info("Detected build system: {}", buildSystem);

            return runBuild(tempDir, buildSystem);

        } catch (IOException e) {
            log.error("IO error during build validation: {}", e.getMessage());
            return new BuildResult(false, "", "IO error: " + e.getMessage());
        } finally {
            // Clean up temp directory
            if (tempDir != null) {
                try {
                    deleteDirectory(tempDir);
                } catch (IOException e) {
                    log.warn("Failed to clean up temp directory {}: {}", tempDir, e.getMessage());
                }
            }
        }
    }

    private String buildCloneUrl(String owner, String repo) {
        // Build authenticated clone URL
        // https://token@gitea.example.com/owner/repo.git
        String baseUrl = giteaUrl.replaceFirst("https?://", "");
        return String.format("https://%s@%s/%s/%s.git", giteaToken, baseUrl, owner, repo);
    }

    private boolean runCommand(File workDir, String[] command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Drain output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Just consume output
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            log.error("Failed to run command: {}", e.getMessage());
            return false;
        }
    }

    private BuildSystem detectBuildSystem(Path projectDir) {
        if (Files.exists(projectDir.resolve("pom.xml"))) {
            return BuildSystem.MAVEN;
        } else if (Files.exists(projectDir.resolve("build.gradle")) ||
                   Files.exists(projectDir.resolve("build.gradle.kts"))) {
            return BuildSystem.GRADLE;
        } else if (Files.exists(projectDir.resolve("package.json"))) {
            return BuildSystem.NPM;
        }
        return BuildSystem.UNKNOWN;
    }

    private BuildResult runBuild(Path projectDir, BuildSystem buildSystem) {
        String[] command = switch (buildSystem) {
            case MAVEN -> new String[]{"mvn", "compile", "-q", "-B"};
            case GRADLE -> new String[]{"./gradlew", "compileJava", "-q"};
            case NPM -> new String[]{"npm", "run", "build"};
            case UNKNOWN -> null;
        };

        if (command == null) {
            log.warn("Unknown build system, skipping build validation");
            return new BuildResult(true, "", "Unknown build system, validation skipped");
        }

        try {
            log.info("Running build command: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectDir.toFile());
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
            int timeoutSeconds = agentConfig.getValidation().getBuildTimeoutSeconds();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new BuildResult(false, output.toString(), "Build timed out after " + timeoutSeconds + " seconds");
            }

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;

            log.info("Build {} with exit code {}", success ? "succeeded" : "failed", exitCode);

            return new BuildResult(success, output.toString(),
                    success ? "Build successful" : "Build failed with exit code " + exitCode);

        } catch (IOException | InterruptedException e) {
            log.error("Failed to run build: {}", e.getMessage());
            return new BuildResult(false, "", "Failed to run build: " + e.getMessage());
        }
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted((a, b) -> b.compareTo(a)) // Reverse order for deletion
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", path, e.getMessage());
                        }
                    });
        }
    }

    public record BuildResult(
            boolean success,
            String output,
            String message
    ) {
        /**
         * Extracts a concise error summary suitable for sending to the AI.
         */
        public String getErrorSummary() {
            if (success) {
                return "";
            }

            // Extract compilation errors from output
            StringBuilder errors = new StringBuilder();
            String[] lines = output.split("\n");
            boolean inError = false;
            int errorCount = 0;

            for (String line : lines) {
                // Maven error patterns
                if (line.contains("[ERROR]") || line.contains("error:") ||
                    line.contains("cannot find symbol") || line.contains("cannot be applied")) {
                    errors.append(line).append("\n");
                    inError = true;
                    errorCount++;
                } else if (inError && (line.startsWith("  ") || line.contains("symbol:") ||
                           line.contains("location:"))) {
                    errors.append(line).append("\n");
                } else if (inError && !line.trim().isEmpty() && !line.startsWith(" ")) {
                    inError = false;
                }

                // Limit output size
                if (errorCount > 20) {
                    errors.append("... (truncated, more errors)\n");
                    break;
                }
            }

            if (errors.isEmpty()) {
                return output.length() > 2000 ? output.substring(0, 2000) + "..." : output;
            }

            return errors.toString();
        }
    }

    enum BuildSystem {
        MAVEN, GRADLE, NPM, UNKNOWN
    }
}




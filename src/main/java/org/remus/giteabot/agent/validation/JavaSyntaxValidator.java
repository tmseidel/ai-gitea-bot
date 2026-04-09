package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.tools.*;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validates Java source file syntax using the Java Compiler API.
 * This performs syntax checking without requiring full compilation dependencies.
 */
@Slf4j
@Component
public class JavaSyntaxValidator implements SyntaxValidator {

    private static final List<String> JAVA_EXTENSIONS = List.of(".java");

    @Override
    public boolean canValidate(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase();
        return JAVA_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    @Override
    public List<ValidationError> validate(String filePath, String content) {
        List<ValidationError> errors = new ArrayList<>();

        if (content == null || content.isBlank()) {
            errors.add(new ValidationError(filePath, "File content is empty"));
            return errors;
        }

        try {
            // Get the system Java compiler
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                log.warn("Java compiler not available, skipping syntax validation for {}", filePath);
                return errors; // Return empty - can't validate without compiler
            }

            // Create a diagnostic collector to capture errors
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

            // Create an in-memory file object
            JavaFileObject sourceFile = new InMemoryJavaFileObject(filePath, content);

            // Create a compilation task (parse only, don't generate bytecode)
            StringWriter output = new StringWriter();
            StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ENGLISH, null);

            // We use -proc:none to disable annotation processing and -Xlint:none to reduce noise
            JavaCompiler.CompilationTask task = compiler.getTask(
                    output,
                    fileManager,
                    diagnostics,
                    List.of("-proc:none", "-Xlint:none", "-implicit:none"),
                    null,
                    List.of(sourceFile)
            );

            // Run the compilation (will fail due to missing dependencies, but catches syntax errors)
            boolean success = task.call();

            // Collect syntax errors (filter out "cannot find symbol" type errors)
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    String message = diagnostic.getMessage(Locale.ENGLISH);

                    // Filter out dependency-related errors, keep syntax errors
                    if (isSyntaxError(message)) {
                        errors.add(new ValidationError(
                                filePath,
                                (int) diagnostic.getLineNumber(),
                                (int) diagnostic.getColumnNumber(),
                                message,
                                "ERROR"
                        ));
                    }
                }
            }

            fileManager.close();

        } catch (Exception e) {
            log.warn("Failed to validate Java syntax for {}: {}", filePath, e.getMessage());
            // Don't add error - validation infrastructure failure shouldn't block
        }

        return errors;
    }

    /**
     * Determines if an error message indicates a syntax error rather than a missing dependency.
     */
    private boolean isSyntaxError(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();

        // Non-syntax errors we want to ignore first (check these before catching generic "expected")
        if (lower.contains("cannot find symbol") ||
            lower.contains("package") && lower.contains("does not exist") ||
            lower.contains("cannot be applied") ||
            lower.contains("incompatible types") ||
            lower.contains("cannot access") ||
            lower.contains("should be declared in a file named") ||
            lower.contains("is public, should be declared")) {
            return false;
        }

        // Syntax errors we want to catch
        if (lower.contains("';' expected") ||
            lower.contains("'(' expected") ||
            lower.contains("')' expected") ||
            lower.contains("'{' expected") ||
            lower.contains("'}' expected") ||
            lower.contains("illegal start of") ||
            lower.contains("unclosed") ||
            lower.contains("reached end of file while parsing") ||
            lower.contains("not a statement") ||
            lower.contains("orphaned")) {
            return true;
        }

        // For generic "expected" messages, only report if not already filtered above
        if (lower.contains("expected")) {
            return true;
        }

        // Default: treat as syntax error to be safe
        return true;
    }

    /**
     * In-memory implementation of JavaFileObject for compiling source strings.
     */
    private static class InMemoryJavaFileObject extends SimpleJavaFileObject {
        private final String content;

        InMemoryJavaFileObject(String name, String content) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}


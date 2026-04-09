## Plan: Improve Agent Code Quality

The AI agent can generate code that doesn't compile. This plan outlines mechanisms to improve output quality and catch errors before creating PRs.

### Overview

The current implementation has a "generate and commit" approach without validation. We need to add:
1. Pre-commit validation
2. Iterative refinement on errors
3. Better context gathering
4. Multi-step planning

### Steps

1. **Add compilation/validation step before commit** – After generating code, run a validation check (e.g., `mvn compile`, `npm run build`) on the generated files. This requires:
   - Detecting the project type from build files (pom.xml, package.json, etc.)
   - Cloning the repo or using Gitea's API to create a test environment
   - Running the appropriate build command
   
   *Implementation*: Add `CodeValidator` service that can shell out to build tools or use language-specific APIs.

2. **Implement iterative error correction** – If validation fails, send the error output back to the AI with the context:
   - "The code you generated has the following compilation errors: [errors]. Please fix them."
   - Limit to N iterations (e.g., 3) to prevent infinite loops
   - Track iteration count in AgentSession
   
   *Implementation*: Add `maxValidationRetries` config, loop in IssueImplementationService until valid or max retries.

3. **Fetch more file context** – Currently limited to files mentioned in the issue. Should also:
   - Include files that import/depend on files being modified
   - Include interface/abstract class definitions
   - Parse import statements in generated code to fetch dependencies
   
   *Implementation*: Enhance `fetchRelevantFileContents()` to analyze dependencies.

4. **Add multi-step planning phase** – Before generating code:
   - Step 1: AI analyzes issue and outputs a plan (which files to modify, what changes)
   - Step 2: User/system reviews plan
   - Step 3: AI generates code based on approved plan
   
   *Implementation*: Add `planOnly` mode, store plan in AgentSession, require explicit continuation.

5. **Use structured output mode** – If the AI provider supports it:
   - Use JSON mode or tool-use to enforce output structure
   - Define strict schema for file changes
   - Reject malformed responses early
   
   *Implementation*: Add provider-specific structured output handling in AiClient.

6. **Add syntax validation per file type** – Before committing, validate each file:
   - Java: Parse with JavaParser or ECJ
   - JavaScript/TypeScript: Use ESLint or TypeScript compiler API
   - JSON/YAML: Parse to validate syntax
   - Markdown: Basic structure check
   
   *Implementation*: Add `SyntaxValidator` with pluggable validators per file extension.

7. **Include existing tests context** – If tests exist for modified code:
   - Include test files in context
   - Ask AI to update tests
   - Run tests after code generation
   
   *Implementation*: Add test file detection and inclusion in context gathering.

8. **Draft PR mode** – Instead of creating a regular PR:
   - Create as draft PR
   - Run CI pipeline
   - Only convert to ready-for-review if CI passes
   - Post CI status back to issue
   
   *Implementation*: Use Gitea's draft PR API, add CI status polling.

### Configuration Options to Add

```properties
# Validation settings
agent.validation.enabled=true
agent.validation.max-retries=3
agent.validation.timeout-seconds=300

# Planning mode
agent.planning.require-approval=false
agent.planning.plan-only-first=false

# Draft PR mode
agent.pr.create-as-draft=true
agent.pr.wait-for-ci=false

# Context gathering
agent.context.include-dependencies=true
agent.context.include-tests=true
agent.context.max-files=30
```

### Priority Order

1. **High Impact, Lower Effort**:
   - Iterative error correction (step 2)
   - Syntax validation per file (step 6)
   - Draft PR mode (step 8)

2. **High Impact, Higher Effort**:
   - Compilation/validation step (step 1)
   - Multi-step planning (step 4)

3. **Medium Impact**:
   - Better context gathering (step 3)
   - Include tests (step 7)
   - Structured output (step 5)

### Quick Win: Iterative Error Correction

The fastest improvement would be to add iterative error correction using syntax parsing only (no build tools required):

```java
// Pseudo-code for iterative refinement
for (int attempt = 0; attempt < maxRetries; attempt++) {
    ImplementationPlan plan = parseAiResponse(aiResponse);
    
    List<String> errors = validateSyntax(plan.getFileChanges());
    if (errors.isEmpty()) {
        // Code is valid, proceed with commit
        break;
    }
    
    // Send errors back to AI
    String errorFeedback = buildErrorFeedback(plan, errors);
    sessionService.addMessage(session, "user", errorFeedback);
    
    aiResponse = aiClient.chat(history, errorFeedback, systemPrompt, null, maxTokens);
    sessionService.addMessage(session, "assistant", aiResponse);
}
```

### Recommended First Implementation

Start with step 6 (syntax validation) combined with step 2 (iterative correction):
- Use lightweight parsers that don't require build tools
- Fall back gracefully if parser not available for file type
- Limit retries to prevent cost explosion


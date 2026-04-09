package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSyntaxValidatorTest {

    private final JavaSyntaxValidator validator = new JavaSyntaxValidator();

    @Test
    void canValidate_javaFiles_returnsTrue() {
        assertThat(validator.canValidate("src/Main.java")).isTrue();
        assertThat(validator.canValidate("Test.java")).isTrue();
        assertThat(validator.canValidate("com/example/MyClass.java")).isTrue();
    }

    @Test
    void canValidate_nonJavaFiles_returnsFalse() {
        assertThat(validator.canValidate("script.py")).isFalse();
        assertThat(validator.canValidate("config.json")).isFalse();
        assertThat(validator.canValidate("README.md")).isFalse();
        assertThat(validator.canValidate(null)).isFalse();
    }

    @Test
    void validate_validJavaClass_returnsNoErrors() {
        String content = """
                package com.example;
                
                public class HelloWorld {
                    public static void main(String[] args) {
                        System.out.println("Hello, World!");
                    }
                }
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("HelloWorld.java", content);

        assertThat(errors).isEmpty();
    }

    @Test
    void validate_missingSemicolon_returnsError() {
        String content = """
                public class Test {
                    public void method() {
                        int x = 5
                    }
                }
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("Test.java", content);

        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).message()).containsIgnoringCase("expected");
    }

    @Test
    void validate_unclosedBrace_returnsError() {
        String content = """
                public class Test {
                    public void method() {
                        if (true) {
                            System.out.println("oops");
                        // missing closing brace
                    }
                }
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("Test.java", content);

        assertThat(errors).isNotEmpty();
    }

    @Test
    void validate_emptyContent_returnsError() {
        List<SyntaxValidator.ValidationError> errors = validator.validate("Empty.java", "");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).contains("empty");
    }

    @Test
    void validate_nullContent_returnsError() {
        List<SyntaxValidator.ValidationError> errors = validator.validate("Null.java", null);
        assertThat(errors).hasSize(1);
    }

    @Test
    void validate_validInterface_returnsNoErrors() {
        String content = """
                package com.example;
                
                public interface MyService {
                    void doSomething();
                    String getResult(int id);
                }
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("MyService.java", content);

        assertThat(errors).isEmpty();
    }

    @Test
    void validate_validRecord_returnsNoErrors() {
        String content = """
                package com.example;
                
                public record Person(String name, int age) {
                    public String greeting() {
                        return "Hello, " + name;
                    }
                }
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("Person.java", content);

        assertThat(errors).isEmpty();
    }

    @Test
    void validate_missingImportIsNotSyntaxError() {
        // Missing imports cause "cannot find symbol" errors which we ignore
        // because they're dependency issues, not syntax issues
        String content = """
                package com.example;
                
                public class Test {
                    private List<String> items;
                    
                    public void process() {
                        System.out.println(items.size());
                    }
                }
                """;

        List<SyntaxValidator.ValidationError> errors = validator.validate("Test.java", content);

        // Should be empty - missing import is not a syntax error
        assertThat(errors).isEmpty();
    }
}


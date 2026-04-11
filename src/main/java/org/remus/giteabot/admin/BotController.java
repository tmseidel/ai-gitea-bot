package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequestMapping("/bots")
public class BotController {

    private final BotService botService;
    private final AiIntegrationService aiIntegrationService;
    private final GitIntegrationService gitIntegrationService;

    // Prompt template definitions (name -> description)
    private static final Map<String, String> PROMPT_TEMPLATE_NAMES = new LinkedHashMap<>();

    static {
        PROMPT_TEMPLATE_NAMES.put("default", "Default (concise code review)");
        PROMPT_TEMPLATE_NAMES.put("local-llm", "Local LLM (detailed, structured review)");
    }

    private Map<String, String> loadPromptTemplates() {
        Map<String, String> templates = new LinkedHashMap<>();
        for (String templateName : PROMPT_TEMPLATE_NAMES.keySet()) {
            String content = loadPromptTemplate("prompts/" + templateName + ".md");
            if (!content.isEmpty()) {
                templates.put(PROMPT_TEMPLATE_NAMES.get(templateName), content);
            }
        }
        return templates;
    }

    private String loadPromptTemplate(String resourcePath) {
        try {
            // First try classpath (packaged JAR)
            Resource resource = new ClassPathResource(resourcePath);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
                }
            }
            // Fallback: try to load from file system (for development/Docker volume)
            java.nio.file.Path path = java.nio.file.Paths.get(resourcePath);
            if (java.nio.file.Files.exists(path)) {
                return java.nio.file.Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Could not load prompt template: {}", resourcePath, e);
        }
        return "";
    }

    public BotController(BotService botService,
                         AiIntegrationService aiIntegrationService,
                         GitIntegrationService gitIntegrationService) {
        this.botService = botService;
        this.aiIntegrationService = aiIntegrationService;
        this.gitIntegrationService = gitIntegrationService;
    }

    @GetMapping
    public String list(Model model) {
        List<Bot> bots = botService.findAll();
        model.addAttribute("bots", bots);
        model.addAttribute("activeNav", "bots");
        return "bots/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("bot", new Bot());
        model.addAttribute("aiIntegrations", aiIntegrationService.findAll());
        model.addAttribute("gitIntegrations", gitIntegrationService.findAll());
        model.addAttribute("promptTemplates", loadPromptTemplates());
        model.addAttribute("activeNav", "bots");
        return "bots/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return botService.findById(id)
                .map(bot -> {
                    model.addAttribute("bot", bot);
                    model.addAttribute("aiIntegrations", aiIntegrationService.findAll());
                    model.addAttribute("gitIntegrations", gitIntegrationService.findAll());
                    model.addAttribute("promptTemplates", loadPromptTemplates());
                    model.addAttribute("activeNav", "bots");
                    return "bots/form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Bot not found");
                    return "redirect:/bots";
                });
    }

    @PostMapping("/save")
    public String save(@ModelAttribute Bot bot,
                       @RequestParam Long aiIntegrationId,
                       @RequestParam Long gitIntegrationId,
                       RedirectAttributes redirectAttributes) {
        try {
            AiIntegration aiIntegration = aiIntegrationService.findById(aiIntegrationId)
                    .orElseThrow(() -> new IllegalArgumentException("AI Integration not found"));
            GitIntegration gitIntegration = gitIntegrationService.findById(gitIntegrationId)
                    .orElseThrow(() -> new IllegalArgumentException("Git Integration not found"));

            bot.setAiIntegration(aiIntegration);
            bot.setGitIntegration(gitIntegration);
            botService.save(bot);
            redirectAttributes.addFlashAttribute("success", "Bot saved successfully");
        } catch (Exception e) {
            log.error("Failed to save Bot", e);
            redirectAttributes.addFlashAttribute("error", "Failed to save: " + e.getMessage());
        }
        return "redirect:/bots";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            botService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Bot deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete Bot", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return "redirect:/bots";
    }
}

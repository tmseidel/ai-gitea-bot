package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "bot")
public class BotConfigProperties {

    /**
     * The mention alias the bot responds to in PR comments (e.g., "@claude_bot").
     * Users mention this alias followed by a command to interact with the bot.
     */
    private String alias = "@claude_bot";
}

package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "bot")
public class BotConfigProperties {

    /**
     * The Gitea login username of the bot account (e.g., "ai_bot").
     * Used to ignore webhooks triggered by the bot's own actions (preventing infinite loops)
     * and to derive the mention alias ("@ai_bot") the bot responds to in PR comments.
     */
    private String username = "ai_bot";

    /**
     * Returns the mention alias derived from the username (e.g., "@ai_bot").
     */
    public String getAlias() {
        return "@" + username;
    }
}

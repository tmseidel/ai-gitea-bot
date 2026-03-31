package org.remus.giteabot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    @Bean
    public RestClient giteaRestClient(@Value("${gitea.url}") String giteaUrl,
                                      @Value("${gitea.token}") String giteaToken) {
        return RestClient.builder()
                .baseUrl(giteaUrl)
                .defaultHeader("Authorization", "token " + giteaToken)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Bean
    public RestClient anthropicRestClient(@Value("${anthropic.api.url}") String anthropicUrl,
                                          @Value("${anthropic.api.key}") String anthropicKey,
                                          @Value("${anthropic.api.version}") String anthropicVersion) {
        return RestClient.builder()
                .baseUrl(anthropicUrl)
                .defaultHeader("x-api-key", anthropicKey)
                .defaultHeader("anthropic-version", anthropicVersion)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}

package com.commitgotchi.character.image;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CharacterAssetWebConfig implements WebMvcConfigurer {

    private static final String CHARACTER_ASSET_LOCATION = "classpath:/character-assets/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/character-assets/**")
                .addResourceLocations(CHARACTER_ASSET_LOCATION);
    }
}

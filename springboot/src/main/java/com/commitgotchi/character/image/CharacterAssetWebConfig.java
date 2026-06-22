package com.commitgotchi.character.image;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Configuration
public class CharacterAssetWebConfig implements WebMvcConfigurer {

    private static final String DEFAULT_IMAGE_NAME = "default_image1.png";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/character-assets/**")
                .addResourceLocations(docsResourceLocation());
    }

    private String docsResourceLocation() {
        String location = resolveDocsDirectory().toUri().toString();
        return location.endsWith("/") ? location : location + "/";
    }

    private Path resolveDocsDirectory() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                workingDirectory.resolve("docs"),
                workingDirectory.resolve("../docs")
        );
        return candidates.stream()
                .map(Path::normalize)
                .filter(candidate -> Files.isRegularFile(candidate.resolve(DEFAULT_IMAGE_NAME)))
                .findFirst()
                .orElse(workingDirectory.resolve("../docs").normalize());
    }
}

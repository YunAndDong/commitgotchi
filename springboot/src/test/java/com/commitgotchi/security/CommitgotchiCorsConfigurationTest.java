package com.commitgotchi.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CommitgotchiCorsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CommitgotchiCorsConfiguration.class);

    @Test
    void parsesTrimmedExactOriginsAndDropsEmptyCsvEntries() {
        assertThat(CommitgotchiCorsConfiguration.parseAllowedOrigins(
                " http://localhost:5173, ,https://app.example.com:8443 ", false
        )).isEqualTo(List.of("http://localhost:5173", "https://app.example.com:8443"));
    }

    @Test
    void rejectsWildcardPatternsAndNonOriginUriPartsInEveryProfile() {
        for (String invalid : List.of(
                "*",
                "https://*.example.com",
                "https://app.example.com/",
                "https://user@app.example.com",
                "https://app.example.com/path",
                "https://app.example.com?query=1",
                "https://app.example.com#fragment",
                "https://app.example.com:",
                "https://app.example.com:99999",
                "http://app.example.com",
                "ftp://app.example.com"
        )) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CommitgotchiCorsConfiguration.parseAllowedOrigins(invalid, false));
        }
    }

    @Test
    void acceptsChromeExtensionOriginAlongsideWebOriginsInEveryProfile() {
        assertThat(CommitgotchiCorsConfiguration.parseAllowedOrigins(
                "http://localhost:5173,chrome-extension://llnclajenonklpnohgleabfmpaijbgie", false
        )).containsExactly("http://localhost:5173", "chrome-extension://llnclajenonklpnohgleabfmpaijbgie");

        assertThat(CommitgotchiCorsConfiguration.parseAllowedOrigins(
                "https://app.example.com,chrome-extension://llnclajenonklpnohgleabfmpaijbgie", true
        )).containsExactly("https://app.example.com", "chrome-extension://llnclajenonklpnohgleabfmpaijbgie");
    }

    @Test
    void rejectsMalformedChromeExtensionOrigins() {
        for (String invalid : List.of(
                "chrome-extension://",
                "chrome-extension://llnclajenonklpnohgleabfmpaijbgie:8080",
                "chrome-extension://llnclajenonklpnohgleabfmpaijbgie/popup.html",
                "chrome-extension://*",
                "chrome-extension://not-a-valid-extension-id",
                "chrome-extension://llnclajenonklpnohgleabfmpaijbgie?x=1",
                "chrome-extension://llnclajenonklpnohgleabfmpaijbgie#frag"
        )) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CommitgotchiCorsConfiguration.parseAllowedOrigins(invalid, false));
        }
    }

    @Test
    void productionRequiresAtLeastOneHttpsOrigin() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CommitgotchiCorsConfiguration.parseAllowedOrigins("", true));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CommitgotchiCorsConfiguration.parseAllowedOrigins(
                        "http://localhost:5173", true
                ));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CommitgotchiCorsConfiguration.parseAllowedOrigins(
                        "chrome-extension://llnclajenonklpnohgleabfmpaijbgie", true
                ));
        assertThat(CommitgotchiCorsConfiguration.parseAllowedOrigins(
                "https://app.example.com", true
        )).containsExactly("https://app.example.com");
    }

    @Test
    void productionApplicationContextFailsFastForUnsafeAllowlist() {
        contextRunner.withPropertyValues(
                "spring.profiles.active=prod",
                "commitgotchi.cors.allowed-origins=http://app.example.com"
        ).run(context -> assertThat(context).hasFailed());

        contextRunner.withPropertyValues(
                "spring.profiles.active=prod",
                "commitgotchi.cors.allowed-origins=https://app.example.com"
        ).run(context -> assertThat(context).hasNotFailed());
    }
}

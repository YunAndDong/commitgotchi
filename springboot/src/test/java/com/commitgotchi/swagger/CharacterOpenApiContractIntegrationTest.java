package com.commitgotchi.swagger;

import com.commitgotchi.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CharacterOpenApiContractIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void documentsGameCharacterEndpointsAsBearerProtectedContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.paths['/api/game/state'].get").exists())
                .andExpect(jsonPath("$.paths['/api/game/state'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/game/state'].get.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/game/characters'].post").exists())
                .andExpect(jsonPath("$.paths['/api/game/characters'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/game/characters'].post.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/game/characters'].post.requestBody.content['application/json']"
                        + ".schema['$ref']").value("#/components/schemas/CharacterCreateRequest"))
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}'].get.parameters.length()").value(1))
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}'].get.parameters[0].name").value("id"))
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}'].patch.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}'].patch.parameters.length()").value(1))
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}'].patch.parameters[0].name").value("id"))
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}'].patch.requestBody.content['application/json']"
                        + ".schema['$ref']").value("#/components/schemas/CharacterUpdateRequest"))
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}'].delete.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}'].delete.parameters.length()").value(1))
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}'].delete.parameters[0].name").value("id"))
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}/active'].patch.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}/active'].patch.parameters.length()").value(1))
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}/active'].patch.parameters[0].name").value("id"))
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}/retry-image'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}/retry-image'].post.parameters.length()").value(1))
                .andExpect(jsonPath("$.paths['/api/game/characters/{id}/retry-image'].post.parameters[0].name").value("id"))
                .andExpect(content().string(not(containsString("AuthPrincipal"))));
    }

    @Test
    void documentsCharacterInputValidationAndExamples() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CharacterCreateRequest.required.length()").value(3))
                .andExpect(jsonPath("$.components.schemas.CharacterCreateRequest.required",
                        containsInAnyOrder("name", "keyword", "personality")))
                .andExpect(jsonPath("$.components.schemas.CharacterCreateRequest.properties.name.maxLength").value(40))
                .andExpect(jsonPath("$.components.schemas.CharacterCreateRequest.properties.name.example")
                        .value("Commit Buddy"))
                .andExpect(jsonPath("$.components.schemas.CharacterCreateRequest.properties.keyword.maxLength").value(120))
                .andExpect(jsonPath("$.components.schemas.CharacterCreateRequest.properties.keyword.example")
                        .value("green study slime"))
                .andExpect(jsonPath("$.components.schemas.CharacterCreateRequest.properties.personality.maxLength").value(500))
                .andExpect(jsonPath("$.components.schemas.CharacterCreateRequest.properties.personality.example")
                        .value("Kind but precise"))
                .andExpect(jsonPath("$.components.schemas.CharacterUpdateRequest.required.length()").value(3))
                .andExpect(jsonPath("$.components.schemas.CharacterUpdateRequest.required",
                        containsInAnyOrder("name", "keyword", "personality")))
                .andExpect(jsonPath("$.components.schemas.CharacterUpdateRequest.properties.name.maxLength").value(40))
                .andExpect(jsonPath("$.components.schemas.CharacterUpdateRequest.properties.name.example")
                        .value("Commit Buddy"))
                .andExpect(jsonPath("$.components.schemas.CharacterUpdateRequest.properties.name.description",
                        containsString("표시 이름")))
                .andExpect(jsonPath("$.components.schemas.CharacterUpdateRequest.properties.keyword.maxLength").value(120))
                .andExpect(jsonPath("$.components.schemas.CharacterUpdateRequest.properties.keyword.example")
                        .value("green study slime"))
                .andExpect(jsonPath("$.components.schemas.CharacterUpdateRequest.properties.keyword.description",
                        containsString("디자인 키워드")))
                .andExpect(jsonPath("$.components.schemas.CharacterUpdateRequest.properties.personality.maxLength").value(500))
                .andExpect(jsonPath("$.components.schemas.CharacterUpdateRequest.properties.personality.example")
                        .value("Kind but precise"))
                .andExpect(jsonPath("$.components.schemas.CharacterUpdateRequest.properties.personality.description",
                        containsString("성격 설명")));
    }
}

package com.commitgotchi.codex;

import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "commitgotchi.internal-api.secret=test-internal-secret")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CodexReviewApiIntegrationTest extends PostgresIntegrationTest {

    private static final String SPRITE_META = """
            {"columns":3,"rows":1,"frameMap":{"joy":[0,0],"sad":[0,1],"angry":[0,2]},"transparent":true}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void reviewsExposeAverageMyReviewAndPaginatedOtherReviews() throws Exception {
        AdminTestFixture.ProvisionedUser viewer = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser firstReviewer = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser secondReviewer = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        long characterId = insertCodexCharacter("review target");
        insertRaisedCharacter(viewer.id(), characterId, "viewer target");
        insertRaisedCharacter(firstReviewer.id(), characterId, "first target");
        insertRaisedCharacter(secondReviewer.id(), characterId, "second target");

        createReview(firstReviewer.bearer(), characterId, 4, "단단한 성장감이 좋아요")
                .andExpect(status().isOk());
        createReview(secondReviewer.bearer(), characterId, 2, "초반은 조금 느려요")
                .andExpect(status().isOk());
        createReview(viewer.bearer(), characterId, 5, "내가 키운 커밋고치라 애착이 커요")
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/codex/characters/{id}/reviews?page=0&size=1", characterId)
                        .header("Authorization", viewer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.characterId").value(characterId))
                .andExpect(jsonPath("$.averageStars").value(3.7))
                .andExpect(jsonPath("$.totalReviews").value(3))
                .andExpect(jsonPath("$.raisedByMe").value(true))
                .andExpect(jsonPath("$.canReview").value(false))
                .andExpect(jsonPath("$.myReview.stars").value(5))
                .andExpect(jsonPath("$.myReview.mine").value(true))
                .andExpect(jsonPath("$.reviews", hasSize(1)))
                .andExpect(jsonPath("$.reviews[0].mine").value(false))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void unraisedUserCannotReviewAndCanRaiseCatalogCharacter() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        long characterId = insertCodexCharacter("raise target");

        mockMvc.perform(get("/api/codex/characters/{id}/reviews", characterId)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.raisedByMe").value(false))
                .andExpect(jsonPath("$.canReview").value(false))
                .andExpect(jsonPath("$.myReview").value(nullValue()));

        createReview(user.bearer(), characterId, 5, "아직 키운 적 없는 리뷰")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/codex/characters/{id}/raise", characterId)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userCharacterId").isNumber())
                .andExpect(jsonPath("$.created").value(true));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM user_character
                WHERE user_id = ?
                  AND character_id = ?
                  AND is_active = true
                  AND deleted_at IS NULL
                """, Integer.class, user.id(), characterId)).isEqualTo(1);

        mockMvc.perform(get("/api/codex/characters/{id}/reviews", characterId)
                        .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.raisedByMe").value(true))
                .andExpect(jsonPath("$.canReview").value(true));
    }

    @Test
    void userCanUpdateAndDeleteOwnCodexReviewOnly() throws Exception {
        AdminTestFixture.ProvisionedUser reviewer = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        AdminTestFixture.ProvisionedUser otherUser = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        long characterId = insertCodexCharacter("editable review target");
        insertRaisedCharacter(reviewer.id(), characterId, "reviewer target");
        insertRaisedCharacter(otherUser.id(), characterId, "other target");

        createReview(reviewer.bearer(), characterId, 5, "처음 느낌도 좋아요")
                .andExpect(status().isOk());
        long reviewId = findReviewId(reviewer.id(), characterId);

        mockMvc.perform(patch("/api/codex/characters/{characterId}/reviews/{reviewId}", characterId, reviewId)
                        .header("Authorization", reviewer.bearer())
                        .contentType("application/json")
                        .content("""
                                {"stars":2,"text":"수정한 느낌은 더 솔직해요"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageStars").value(2.0))
                .andExpect(jsonPath("$.totalReviews").value(1))
                .andExpect(jsonPath("$.canReview").value(false))
                .andExpect(jsonPath("$.myReview.id").value(reviewId))
                .andExpect(jsonPath("$.myReview.stars").value(2))
                .andExpect(jsonPath("$.myReview.text").value("수정한 느낌은 더 솔직해요"));

        mockMvc.perform(delete("/api/codex/characters/{characterId}/reviews/{reviewId}", characterId, reviewId)
                        .header("Authorization", otherUser.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(delete("/api/codex/characters/{characterId}/reviews/{reviewId}", characterId, reviewId)
                        .header("Authorization", reviewer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageStars").value(0.0))
                .andExpect(jsonPath("$.totalReviews").value(0))
                .andExpect(jsonPath("$.canReview").value(true))
                .andExpect(jsonPath("$.myReview").value(nullValue()));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM codex_character_reviews
                WHERE id = ?
                """, Integer.class, reviewId)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions createReview(
            String bearer,
            long characterId,
            int stars,
            String text
    ) throws Exception {
        return mockMvc.perform(post("/api/codex/characters/{id}/reviews", characterId)
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("""
                        {"stars":%d,"text":"%s"}
                        """.formatted(stars, text)));
    }

    private long insertCodexCharacter(String designKeyword) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO characters (
                    personality,
                    design_keyword,
                    sprite_sheet_url,
                    sprite_meta,
                    image_status
                )
                VALUES (
                    'reviewable',
                    ?,
                    '/character-assets/default_image1.png',
                    ?::jsonb,
                    'READY'
                )
                RETURNING id
                """, Long.class, designKeyword, SPRITE_META);
    }

    private void insertRaisedCharacter(long userId, long characterId, String name) {
        jdbcTemplate.update("""
                INSERT INTO user_character (
                    user_id,
                    character_id,
                    name,
                    is_active
                )
                VALUES (?, ?, ?, true)
                """, userId, characterId, name);
    }

    private long findReviewId(long userId, long characterId) {
        return jdbcTemplate.queryForObject("""
                SELECT id
                FROM codex_character_reviews
                WHERE user_id = ?
                  AND character_id = ?
                """, Long.class, userId, characterId);
    }

    private String uniqueEmail() {
        return "codex-review-" + UUID.randomUUID() + "@example.com";
    }
}

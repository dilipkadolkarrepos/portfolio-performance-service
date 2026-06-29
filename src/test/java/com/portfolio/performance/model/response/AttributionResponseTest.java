package com.portfolio.performance.model.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.performance.model.enums.AttributionStatus;
import com.portfolio.performance.model.enums.PricingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AttributionResponse} and its nested {@link GroupContributionResult}.
 *
 * <p>Two concerns are verified:
 * <ol>
 *   <li>Lombok builder — every field survives construction and is retrievable via getters.</li>
 *   <li>Jackson serialization — the JSON output contains the required keys and formats,
 *       with particular attention to {@code status}, {@code degraded}, {@code warnings},
 *       and the ISO-8601 UTC rendering of {@code processedAt}.</li>
 * </ol>
 */
class AttributionResponseTest {

    // ------------------------------------------------------------------
    // Shared fixtures
    // ------------------------------------------------------------------

    private static final LocalDate VALUATION_DATE   = LocalDate.of(2026, 6, 30);
    private static final Instant   PROCESSED_AT     = Instant.parse("2026-06-30T10:45:00Z");

    /** Group with a primary return — weight 50 %, contribution = 0.50 × 1.25 = 0.625. */
    private static final GroupContributionResult GROUP_EQUITIES = GroupContributionResult.builder()
            .groupName("US Equities")
            .contributionPct(new BigDecimal("0.625000"))
            .pricingMode(PricingMode.PRIMARY)
            .build();

    /** Group that fell back to secondary pricing — weight 30 %, contribution = 0.30 × 0.45 = 0.135. */
    private static final GroupContributionResult GROUP_FIXED_INCOME = GroupContributionResult.builder()
            .groupName("Fixed Income")
            .contributionPct(new BigDecimal("0.135000"))
            .pricingMode(PricingMode.FALLBACK_USED)
            .build();

    /** Group with no pricing available — contribution forced to zero. */
    private static final GroupContributionResult GROUP_ALTERNATIVES = GroupContributionResult.builder()
            .groupName("Alternatives")
            .contributionPct(new BigDecimal("0.000000"))
            .pricingMode(PricingMode.UNAVAILABLE)
            .build();

    private AttributionResponse response;

    @BeforeEach
    void buildResponse() {
        response = AttributionResponse.builder()
                .requestId("REQ-2026-001")
                .portfolioId("PF-GLOBAL-42")
                .valuationDate(VALUATION_DATE)
                .totalContributionPct(new BigDecimal("0.760000"))
                .groupContributions(List.of(GROUP_EQUITIES, GROUP_FIXED_INCOME, GROUP_ALTERNATIVES))
                .status(AttributionStatus.REVIEW_REQUIRED)
                .degraded(true)
                .warnings(List.of(
                        "Fixed Income: returnPct null — fallback applied (0.45%)",
                        "Alternatives: no returnPct or fallbackReturnPct — contribution set to 0"
                ))
                .processedAt(PROCESSED_AT)
                .build();
    }

    // ------------------------------------------------------------------
    // 1.  Lombok builder tests
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Lombok builder — field population")
    class BuilderTests {

        @Test
        @DisplayName("Top-level scalar fields are populated correctly")
        void topLevelScalars_arePopulated() {
            assertAll("top-level AttributionResponse fields",
                    () -> assertNotNull(response,                            "response must not be null"),
                    () -> assertEquals("REQ-2026-001",    response.getRequestId()),
                    () -> assertEquals("PF-GLOBAL-42",    response.getPortfolioId()),
                    () -> assertEquals(VALUATION_DATE,    response.getValuationDate()),
                    () -> assertEquals(0, new BigDecimal("0.760000")
                                              .compareTo(response.getTotalContributionPct())),
                    () -> assertEquals(AttributionStatus.REVIEW_REQUIRED, response.getStatus()),
                    () -> assertTrue(response.isDegraded(),                 "degraded must be true"),
                    () -> assertEquals(PROCESSED_AT,      response.getProcessedAt())
            );
        }

        @Test
        @DisplayName("groupContributions list contains exactly three elements")
        void groupContributions_containsThreeElements() {
            assertNotNull(response.getGroupContributions());
            assertEquals(3, response.getGroupContributions().size());
        }

        @Test
        @DisplayName("warnings list contains exactly two entries")
        void warnings_containsTwoEntries() {
            assertNotNull(response.getWarnings());
            assertEquals(2, response.getWarnings().size());
        }

        @Test
        @DisplayName("Group 1 — PRIMARY pricing mode and correct contribution")
        void group1_primaryPricingMode() {
            GroupContributionResult g = response.getGroupContributions().get(0);
            assertAll("US Equities contribution result",
                    () -> assertEquals("US Equities",               g.getGroupName()),
                    () -> assertEquals(0, new BigDecimal("0.625000").compareTo(g.getContributionPct())),
                    () -> assertEquals(PricingMode.PRIMARY,         g.getPricingMode())
            );
        }

        @Test
        @DisplayName("Group 2 — FALLBACK_USED pricing mode and correct contribution")
        void group2_fallbackUsedPricingMode() {
            GroupContributionResult g = response.getGroupContributions().get(1);
            assertAll("Fixed Income contribution result",
                    () -> assertEquals("Fixed Income",              g.getGroupName()),
                    () -> assertEquals(0, new BigDecimal("0.135000").compareTo(g.getContributionPct())),
                    () -> assertEquals(PricingMode.FALLBACK_USED,   g.getPricingMode())
            );
        }

        @Test
        @DisplayName("Group 3 — UNAVAILABLE pricing mode and zero contribution")
        void group3_unavailablePricingMode() {
            GroupContributionResult g = response.getGroupContributions().get(2);
            assertAll("Alternatives contribution result",
                    () -> assertEquals("Alternatives",              g.getGroupName()),
                    () -> assertEquals(0, new BigDecimal("0.000000").compareTo(g.getContributionPct())),
                    () -> assertEquals(PricingMode.UNAVAILABLE,     g.getPricingMode())
            );
        }
    }

    // ------------------------------------------------------------------
    // 2.  Jackson serialization tests
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Jackson serialization — JSON structure and formatting")
    class JacksonSerializationTests {

        private ObjectMapper mapper;
        private JsonNode      root;

        @BeforeEach
        void serialize() throws Exception {
            // JavaTimeModule required in plain unit tests — Spring Boot auto-registers it,
            // but there is no application context here.
            mapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            String json = mapper.writeValueAsString(response);
            root = mapper.readTree(json);
        }

        // -- Required top-level keys --

        @Test
        @DisplayName("JSON contains 'status' key")
        void json_containsStatusKey() {
            assertTrue(root.has("status"), "JSON must contain a 'status' field");
            assertFalse(root.get("status").isNull(), "'status' must not be null");
        }

        @Test
        @DisplayName("JSON contains 'degraded' key")
        void json_containsDegradedKey() {
            assertTrue(root.has("degraded"), "JSON must contain a 'degraded' field");
        }

        @Test
        @DisplayName("JSON contains 'warnings' key")
        void json_containsWarningsKey() {
            assertTrue(root.has("warnings"), "JSON must contain a 'warnings' field");
            assertTrue(root.get("warnings").isArray(), "'warnings' must be a JSON array");
        }

        // -- Correct enum / boolean values --

        @Test
        @DisplayName("'status' serializes to its enum name string")
        void json_status_isEnumNameString() {
            assertEquals("REVIEW_REQUIRED", root.get("status").asText());
        }

        @Test
        @DisplayName("'degraded' serializes as boolean true")
        void json_degraded_isBooleanTrue() {
            assertTrue(root.get("degraded").isBoolean(), "'degraded' must be a JSON boolean");
            assertTrue(root.get("degraded").asBoolean(), "'degraded' value must be true");
        }

        @Test
        @DisplayName("'warnings' array has two elements matching the builder input")
        void json_warnings_hasTwoElements() {
            JsonNode warnings = root.get("warnings");
            assertEquals(2, warnings.size(), "expected two warning strings");
            assertEquals(
                    "Fixed Income: returnPct null — fallback applied (0.45%)",
                    warnings.get(0).asText()
            );
        }

        // -- ISO-8601 UTC format for processedAt --

        @Test
        @DisplayName("'processed_at' key is present in JSON")
        void json_containsProcessedAtKey() {
            assertTrue(root.has("processed_at"),
                    "JSON must contain a 'processed_at' field (snake_case via @JsonProperty)");
        }

        @Test
        @DisplayName("'processed_at' serializes in ISO-8601 UTC format ending with 'Z'")
        void json_processedAt_isIso8601UtcString() {
            String processedAt = root.get("processed_at").asText();
            // Must be a string, not a numeric epoch
            assertFalse(root.get("processed_at").isNumber(),
                    "'processed_at' must not be serialized as a numeric timestamp");
            assertTrue(processedAt.endsWith("Z"),
                    "'processed_at' must end with 'Z' to indicate UTC: " + processedAt);
        }

        @Test
        @DisplayName("'processed_at' round-trips back to the original Instant value")
        void json_processedAt_roundTripsToOriginalInstant() {
            // The @JsonFormat pattern drops sub-second precision — compare at second granularity.
            String processedAt = root.get("processed_at").asText();
            assertEquals("2026-06-30T10:45:00Z", processedAt,
                    "processedAt must serialize to the exact ISO-8601 UTC string");
        }

        // -- Snake_case key mapping --

        @Test
        @DisplayName("Top-level fields use snake_case keys from @JsonProperty")
        void json_topLevelKeys_areSnakeCase() {
            assertAll("snake_case key presence",
                    () -> assertTrue(root.has("request_id"),            "expected 'request_id'"),
                    () -> assertTrue(root.has("portfolio_id"),          "expected 'portfolio_id'"),
                    () -> assertTrue(root.has("valuation_date"),        "expected 'valuation_date'"),
                    () -> assertTrue(root.has("total_contribution_pct"),"expected 'total_contribution_pct'"),
                    () -> assertTrue(root.has("group_contributions"),   "expected 'group_contributions'"),
                    () -> assertTrue(root.has("processed_at"),          "expected 'processed_at'")
            );
        }

        @Test
        @DisplayName("First group in 'group_contributions' uses snake_case keys")
        void json_groupContribution_keysAreSnakeCase() {
            JsonNode firstGroup = root.get("group_contributions").get(0);
            assertAll("GroupContributionResult snake_case keys",
                    () -> assertTrue(firstGroup.has("group_name"),       "expected 'group_name'"),
                    () -> assertTrue(firstGroup.has("contribution_pct"), "expected 'contribution_pct'"),
                    () -> assertTrue(firstGroup.has("pricing_mode"),     "expected 'pricing_mode'")
            );
        }
    }
}

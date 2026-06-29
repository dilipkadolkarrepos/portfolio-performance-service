package com.portfolio.performance.model.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AttributionRequest} and its nested {@link GroupInput} objects.
 *
 * <p>Two concerns are verified independently:
 * <ol>
 *   <li>Lombok builder — all non-null fields survive the round-trip through the builder.</li>
 *   <li>Jackson deserialization — the canonical snake_case JSON payload maps correctly
 *       to every Java field, including nullable {@code returnPct} / {@code fallbackReturnPct}.</li>
 * </ol>
 */
class AttributionRequestTest {

    // ------------------------------------------------------------------
    // Shared fixtures
    // ------------------------------------------------------------------

    /** Three groups that together sum to exactly 100 % weight. */
    private static final GroupInput GROUP_EQUITIES = GroupInput.builder()
            .groupName("US Equities")
            .weightPct(new BigDecimal("50.00"))
            .returnPct(new BigDecimal("1.25"))
            .fallbackReturnPct(null)
            .build();

    /** One group with a fallback return — primary pricing unavailable. */
    private static final GroupInput GROUP_FIXED_INCOME = GroupInput.builder()
            .groupName("Fixed Income")
            .weightPct(new BigDecimal("30.00"))
            .returnPct(null)
            .fallbackReturnPct(new BigDecimal("0.45"))
            .build();

    /** One group where both return fields are null — pricing fully unavailable. */
    private static final GroupInput GROUP_ALTERNATIVES = GroupInput.builder()
            .groupName("Alternatives")
            .weightPct(new BigDecimal("20.00"))
            .returnPct(null)
            .fallbackReturnPct(null)
            .build();

    private static final LocalDate VALUATION_DATE = LocalDate.of(2026, 6, 30);

    /** Fully-populated {@link AttributionRequest} built via the Lombok builder. */
    private AttributionRequest request;

    @BeforeEach
    void buildRequest() {
        request = AttributionRequest.builder()
                .requestId("REQ-2026-001")
                .portfolioId("PF-GLOBAL-42")
                .valuationDate(VALUATION_DATE)
                .groups(List.of(GROUP_EQUITIES, GROUP_FIXED_INCOME, GROUP_ALTERNATIVES))
                .currency("USD")
                .requestedBy("analyst@example.com")
                .build();
    }

    // ------------------------------------------------------------------
    // 1. Lombok builder tests
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Lombok builder — field population")
    class BuilderTests {

        @Test
        @DisplayName("Top-level fields are populated correctly")
        void topLevelFields_arePopulated() {
            assertAll("top-level AttributionRequest fields",
                    () -> assertNotNull(request,                       "request must not be null"),
                    () -> assertEquals("REQ-2026-001",  request.getRequestId()),
                    () -> assertEquals("PF-GLOBAL-42",  request.getPortfolioId()),
                    () -> assertEquals(VALUATION_DATE,  request.getValuationDate()),
                    () -> assertEquals("USD",           request.getCurrency()),
                    () -> assertEquals("analyst@example.com", request.getRequestedBy())
            );
        }

        @Test
        @DisplayName("Groups list contains exactly three elements")
        void groups_containsThreeElements() {
            assertNotNull(request.getGroups(), "groups list must not be null");
            assertEquals(3, request.getGroups().size(), "expected exactly three groups");
        }

        @Test
        @DisplayName("Group 1 — US Equities has primary return and no fallback")
        void group1_usEquities_primaryPricingOnly() {
            GroupInput g = request.getGroups().get(0);
            assertAll("US Equities group",
                    () -> assertEquals("US Equities",           g.getGroupName()),
                    () -> assertEquals(new BigDecimal("50.00"), g.getWeightPct()),
                    () -> assertEquals(new BigDecimal("1.25"),  g.getReturnPct()),
                    () -> assertNull(g.getFallbackReturnPct(),  "fallbackReturnPct must be null when primary is present")
            );
        }

        @Test
        @DisplayName("Group 2 — Fixed Income has null returnPct and a valid fallbackReturnPct")
        void group2_fixedIncome_fallbackPricingUsed() {
            GroupInput g = request.getGroups().get(1);
            assertAll("Fixed Income group",
                    () -> assertEquals("Fixed Income",          g.getGroupName()),
                    () -> assertEquals(new BigDecimal("30.00"), g.getWeightPct()),
                    () -> assertNull(g.getReturnPct(),          "returnPct must be null for delayed-pricing group"),
                    () -> assertEquals(new BigDecimal("0.45"),  g.getFallbackReturnPct())
            );
        }

        @Test
        @DisplayName("Group 3 — Alternatives has both return fields null (pricing unavailable)")
        void group3_alternatives_pricingUnavailable() {
            GroupInput g = request.getGroups().get(2);
            assertAll("Alternatives group",
                    () -> assertEquals("Alternatives",          g.getGroupName()),
                    () -> assertEquals(new BigDecimal("20.00"), g.getWeightPct()),
                    () -> assertNull(g.getReturnPct(),          "returnPct must be null"),
                    () -> assertNull(g.getFallbackReturnPct(),  "fallbackReturnPct must be null")
            );
        }
    }

    // ------------------------------------------------------------------
    // 2. Jackson deserialization tests
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Jackson deserialization — snake_case JSON payload")
    class JacksonDeserializationTests {

        private ObjectMapper mapper;

        /**
         * Sample JSON mirrors the canonical POST body as it would arrive from a client.
         * Keys use snake_case to exercise the {@code @JsonProperty} mappings on every field.
         */
        private static final String SAMPLE_JSON = """
                {
                  "request_id"     : "REQ-2026-001",
                  "portfolio_id"   : "PF-GLOBAL-42",
                  "valuation_date" : "2026-06-30",
                  "groups" : [
                    {
                      "group_name"         : "US Equities",
                      "weight_pct"         : 50.00,
                      "return_pct"         : 1.25,
                      "fallback_return_pct": null
                    },
                    {
                      "group_name"         : "Fixed Income",
                      "weight_pct"         : 30.00,
                      "return_pct"         : null,
                      "fallback_return_pct": 0.45
                    },
                    {
                      "group_name"         : "Alternatives",
                      "weight_pct"         : 20.00,
                      "return_pct"         : null,
                      "fallback_return_pct": null
                    }
                  ],
                  "currency"     : "USD",
                  "requested_by" : "analyst@example.com"
                }
                """;

        @BeforeEach
        void configureMapper() {
            // JavaTimeModule is required for LocalDate in a plain unit test
            // (Spring Boot auto-configures this in integration tests, but not here).
            mapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        @Test
        @DisplayName("JSON deserializes without throwing an exception")
        void json_deserializesWithoutException() {
            assertDoesNotThrow(
                    () -> mapper.readValue(SAMPLE_JSON, AttributionRequest.class),
                    "ObjectMapper must not throw for well-formed attribution JSON"
            );
        }

        @Test
        @DisplayName("Top-level scalar fields map to the correct Java values")
        void json_topLevelFields_mappedCorrectly() throws Exception {
            AttributionRequest parsed = mapper.readValue(SAMPLE_JSON, AttributionRequest.class);

            assertAll("deserialized top-level fields",
                    () -> assertEquals("REQ-2026-001",         parsed.getRequestId()),
                    () -> assertEquals("PF-GLOBAL-42",         parsed.getPortfolioId()),
                    () -> assertEquals(LocalDate.of(2026,6,30),parsed.getValuationDate()),
                    () -> assertEquals("USD",                  parsed.getCurrency()),
                    () -> assertEquals("analyst@example.com",  parsed.getRequestedBy())
            );
        }

        @Test
        @DisplayName("Groups array deserializes to a list of three GroupInput objects")
        void json_groups_deserializeToThreeElements() throws Exception {
            AttributionRequest parsed = mapper.readValue(SAMPLE_JSON, AttributionRequest.class);

            assertNotNull(parsed.getGroups(), "groups must not be null after deserialization");
            assertEquals(3, parsed.getGroups().size(), "expected three deserialized groups");
        }

        @Test
        @DisplayName("Group with primary return — returnPct populated, fallbackReturnPct null")
        void json_group1_primaryReturnMapped() throws Exception {
            GroupInput g = mapper.readValue(SAMPLE_JSON, AttributionRequest.class)
                    .getGroups().get(0);

            assertAll("deserialized US Equities group",
                    () -> assertEquals("US Equities",                g.getGroupName()),
                    () -> assertEquals(0, new BigDecimal("50.00").compareTo(g.getWeightPct())),
                    () -> assertEquals(0, new BigDecimal("1.25").compareTo(g.getReturnPct())),
                    () -> assertNull(g.getFallbackReturnPct())
            );
        }

        @Test
        @DisplayName("Group with fallback return — returnPct null, fallbackReturnPct populated")
        void json_group2_fallbackReturnMapped() throws Exception {
            GroupInput g = mapper.readValue(SAMPLE_JSON, AttributionRequest.class)
                    .getGroups().get(1);

            assertAll("deserialized Fixed Income group",
                    () -> assertEquals("Fixed Income",              g.getGroupName()),
                    () -> assertEquals(0, new BigDecimal("30.00").compareTo(g.getWeightPct())),
                    () -> assertNull(g.getReturnPct()),
                    () -> assertEquals(0, new BigDecimal("0.45").compareTo(g.getFallbackReturnPct()))
            );
        }

        @Test
        @DisplayName("Group with no pricing — both returnPct and fallbackReturnPct null")
        void json_group3_pricingUnavailableMapped() throws Exception {
            GroupInput g = mapper.readValue(SAMPLE_JSON, AttributionRequest.class)
                    .getGroups().get(2);

            assertAll("deserialized Alternatives group",
                    () -> assertEquals("Alternatives",              g.getGroupName()),
                    () -> assertEquals(0, new BigDecimal("20.00").compareTo(g.getWeightPct())),
                    () -> assertNull(g.getReturnPct()),
                    () -> assertNull(g.getFallbackReturnPct())
            );
        }
    }
}

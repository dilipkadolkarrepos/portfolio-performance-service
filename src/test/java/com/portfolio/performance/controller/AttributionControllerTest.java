package com.portfolio.performance.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.performance.exception.InvalidAttributionInputException;
import com.portfolio.performance.model.enums.AttributionStatus;
import com.portfolio.performance.model.enums.PricingMode;
import com.portfolio.performance.model.request.AttributionRequest;
import com.portfolio.performance.model.request.GroupInput;
import com.portfolio.performance.model.response.AttributionResponse;
import com.portfolio.performance.model.response.GroupContributionResult;
import com.portfolio.performance.service.AttributionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@code @WebMvcTest} slice for {@link AttributionController}.
 *
 * <p>{@link AttributionService} is mocked — no Spring Data, no H2, no calculator.
 * This test validates only the HTTP layer: routing, content-type negotiation,
 * Jakarta Bean Validation, JSON serialization of the response body, and
 * error-handler behaviour for business-rule failures.
 */
@WebMvcTest(AttributionController.class)
class AttributionControllerTest {

    private static final String ENDPOINT = "/api/performance/attribution";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AttributionService attributionService;

    // -----------------------------------------------------------------------
    // Shared fixtures
    // -----------------------------------------------------------------------

    private static final String     REQUEST_ID    = "REQ-CTRL-001";
    private static final String     PORTFOLIO_ID  = "PF-CTRL-42";
    private static final LocalDate  VALUATION_DATE = LocalDate.of(2026, 6, 30);
    private static final Instant    PROCESSED_AT  = Instant.parse("2026-06-30T10:45:00Z");

    private AttributionRequest  validRequest;
    private AttributionResponse validResponse;

    @BeforeEach
    void buildFixtures() {
        validRequest = AttributionRequest.builder()
                .requestId(REQUEST_ID)
                .portfolioId(PORTFOLIO_ID)
                .valuationDate(VALUATION_DATE)
                .groups(List.of(
                        GroupInput.builder()
                                .groupName("US Equities")
                                .weightPct(new BigDecimal("60.00"))
                                .returnPct(new BigDecimal("1.50"))
                                .build(),
                        GroupInput.builder()
                                .groupName("Fixed Income")
                                .weightPct(new BigDecimal("30.00"))
                                .returnPct(null)
                                .fallbackReturnPct(new BigDecimal("0.40"))
                                .build(),
                        GroupInput.builder()
                                .groupName("Alternatives")
                                .weightPct(new BigDecimal("10.00"))
                                .returnPct(new BigDecimal("0.05"))
                                .build()
                ))
                .currency("USD")
                .requestedBy("analyst@example.com")
                .build();

        validResponse = AttributionResponse.builder()
                .requestId(REQUEST_ID)
                .portfolioId(PORTFOLIO_ID)
                .valuationDate(VALUATION_DATE)
                .totalContributionPct(new BigDecimal("1.025000"))
                .groupContributions(List.of(
                        GroupContributionResult.builder()
                                .groupName("US Equities")
                                .contributionPct(new BigDecimal("0.900000"))
                                .pricingMode(PricingMode.PRIMARY)
                                .build(),
                        GroupContributionResult.builder()
                                .groupName("Fixed Income")
                                .contributionPct(new BigDecimal("0.120000"))
                                .pricingMode(PricingMode.FALLBACK_USED)
                                .build(),
                        GroupContributionResult.builder()
                                .groupName("Alternatives")
                                .contributionPct(new BigDecimal("0.005000"))
                                .pricingMode(PricingMode.PRIMARY)
                                .build()
                ))
                .status(AttributionStatus.VALID)
                .degraded(false)
                .warnings(List.of("Fallback pricing used for Fixed Income"))
                .processedAt(PROCESSED_AT)
                .build();
    }

    // -----------------------------------------------------------------------
    // Happy path — HTTP 200 and required JSON keys
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Happy path — HTTP 200 with complete response body")
    class HappyPath {

        @BeforeEach
        void stubService() {
            when(attributionService.processAttribution(any())).thenReturn(validResponse);
        }

        @Test
        @DisplayName("POST /attribution returns HTTP 200")
        void returns200() throws Exception {
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Response body contains 'request_id' matching the request")
        void responseBodyContainsRequestId() throws Exception {
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(jsonPath("$.request_id").value(REQUEST_ID));
        }

        @Test
        @DisplayName("Response body contains 'status' field")
        void responseBodyContainsStatus() throws Exception {
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(jsonPath("$.status").value("VALID"));
        }

        @Test
        @DisplayName("Response body contains boolean 'degraded' field")
        void responseBodyContainsDegraded() throws Exception {
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(jsonPath("$.degraded").value(false));
        }

        @Test
        @DisplayName("Response body contains 'warnings' array")
        void responseBodyContainsWarnings() throws Exception {
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(jsonPath("$.warnings").isArray())
                    .andExpect(jsonPath("$.warnings[0]").value("Fallback pricing used for Fixed Income"));
        }

        @Test
        @DisplayName("Response body contains 'group_contributions' array with three elements")
        void responseBodyContainsGroupContributions() throws Exception {
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(jsonPath("$.group_contributions").isArray())
                    .andExpect(jsonPath("$.group_contributions.length()").value(3))
                    .andExpect(jsonPath("$.group_contributions[0].group_name").value("US Equities"))
                    .andExpect(jsonPath("$.group_contributions[0].pricing_mode").value("PRIMARY"))
                    .andExpect(jsonPath("$.group_contributions[1].pricing_mode").value("FALLBACK_USED"));
        }

        @Test
        @DisplayName("Response body contains 'processed_at' in ISO-8601 UTC format")
        void responseBodyContainsProcessedAt() throws Exception {
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(jsonPath("$.processed_at").value("2026-06-30T10:45:00Z"));
        }

        @Test
        @DisplayName("Response body contains 'total_contribution_pct'")
        void responseBodyContainsTotalContributionPct() throws Exception {
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(jsonPath("$.total_contribution_pct").value(1.025000));
        }

        @Test
        @DisplayName("Content-Type of response is application/json")
        void responseContentTypeIsJson() throws Exception {
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }
    }

    // -----------------------------------------------------------------------
    // Bean Validation — HTTP 400 for constraint violations
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Bean Validation — HTTP 400 on constraint violations")
    class BeanValidation {

        @Test
        @DisplayName("Blank requestId → HTTP 400 Validation Failed")
        void blankRequestId_returns400() throws Exception {
            AttributionRequest bad = AttributionRequest.builder()
                    .requestId("")          // @NotBlank violated
                    .portfolioId(PORTFOLIO_ID)
                    .valuationDate(VALUATION_DATE)
                    .groups(validRequest.getGroups())
                    .currency("USD")
                    .requestedBy("tester")
                    .build();

            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Validation Failed"));
        }

        @Test
        @DisplayName("Null groups list → HTTP 400 Validation Failed")
        void nullGroups_returns400() throws Exception {
            AttributionRequest bad = AttributionRequest.builder()
                    .requestId(REQUEST_ID)
                    .portfolioId(PORTFOLIO_ID)
                    .valuationDate(VALUATION_DATE)
                    .groups(null)           // @NotEmpty violated
                    .currency("USD")
                    .requestedBy("tester")
                    .build();

            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Validation Failed"));
        }

        @Test
        @DisplayName("Missing body → HTTP 400 Malformed Request")
        void missingBody_returns400() throws Exception {
            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Malformed Request"));
        }
    }

    // -----------------------------------------------------------------------
    // InvalidAttributionInputException — HTTP 400 from GlobalExceptionHandler
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("InvalidAttributionInputException — HTTP 400 from exception handler")
    class InvalidInputHandling {

        @Test
        @DisplayName("Service throws InvalidAttributionInputException → HTTP 400 with error body")
        void serviceThrowsInvalidInput_returns400() throws Exception {
            when(attributionService.processAttribution(any()))
                    .thenThrow(new InvalidAttributionInputException(
                            "Total weight 95.00% is outside the allowed range of 99-101%"));

            mockMvc.perform(post(ENDPOINT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Invalid Attribution Input"))
                    .andExpect(jsonPath("$.message").value(
                            "Total weight 95.00% is outside the allowed range of 99-101%"));
        }
    }
}

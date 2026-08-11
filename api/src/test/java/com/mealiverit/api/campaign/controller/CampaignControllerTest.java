package com.mealiverit.api.campaign.controller;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mealiverit.api.campaign.dto.CampaignCreateRequest;
import com.mealiverit.api.campaign.dto.CampaignStatusUpdateRequest;
import com.mealiverit.entity.campaign.CampaignStatus;
import com.mealiverit.entity.coupon.DiscountType;
import com.mealiverit.entity.user.MembershipTier;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class CampaignControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 캠페인_생성_후_조회_상태전환_전체흐름() throws Exception {
        CampaignCreateRequest createRequest = new CampaignCreateRequest(
                "여름맞이 쿠폰", 100, MembershipTier.CORPORAL,
                DiscountType.FIXED, BigDecimal.valueOf(1000), BigDecimal.valueOf(10000),
                BigDecimal.valueOf(5000), 24);

        String createResponseBody = mockMvc.perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id", greaterThan(0)))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.remainingStock").value(100))
                .andExpect(jsonPath("$.data.coupon.discountType").value("FIXED"))
                .andReturn().getResponse().getContentAsString();

        Long campaignId = objectMapper.readTree(createResponseBody).path("data").path("id").asLong();

        mockMvc.perform(get("/api/campaigns/{id}", campaignId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("여름맞이 쿠폰"));

        mockMvc.perform(get("/api/campaigns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        CampaignStatusUpdateRequest openRequest = new CampaignStatusUpdateRequest(CampaignStatus.OPEN, null, null);
        mockMvc.perform(patch("/api/campaigns/{id}/status", campaignId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(openRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"));

        // READY -> OPEN을 다시 요청하면(이미 OPEN) 상태전이 거부
        mockMvc.perform(patch("/api/campaigns/{id}/status", campaignId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(openRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_INVALID_STATE_TRANSITION"));

        CampaignStatusUpdateRequest closeRequest = new CampaignStatusUpdateRequest(CampaignStatus.CLOSED, null, null);
        mockMvc.perform(patch("/api/campaigns/{id}/status", campaignId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(closeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
    }

    @Test
    void 존재하지_않는_캠페인_조회시_404() throws Exception {
        mockMvc.perform(get("/api/campaigns/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_FOUND"));
    }

    @Test
    void 이름이_비어있으면_400() throws Exception {
        CampaignCreateRequest invalidRequest = new CampaignCreateRequest(
                "", 100, null, DiscountType.FIXED, BigDecimal.valueOf(1000), null, null, 24);

        mockMvc.perform(post("/api/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }
}

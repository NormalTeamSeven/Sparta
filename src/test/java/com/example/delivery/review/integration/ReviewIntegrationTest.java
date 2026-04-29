package com.example.delivery.review.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.delivery.global.test.IntegrationTestSupport;
import com.example.delivery.order.domain.entity.OrderEntity;
import com.example.delivery.order.domain.entity.OrderStatus;
import com.example.delivery.order.domain.repository.OrderRepository;
import com.example.delivery.review.domain.repository.ReviewRepository;
import com.example.delivery.review.presentation.dto.request.ReqCreateReviewDto;
import com.example.delivery.review.presentation.dto.request.ReqUpdateReviewDto;
import com.example.delivery.store.domain.entity.StoreEntity;
import com.example.delivery.store.domain.repository.StoreRepository;
import com.example.delivery.user.domain.entity.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReviewIntegrationTest extends IntegrationTestSupport {

    @Autowired OrderRepository orderRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired ReviewRepository reviewRepository;

    private StoreEntity store;
    private String customerToken;
    private static final String CUSTOMER_USERNAME = "customer01";

    @BeforeEach
    void setUp() throws Exception {
        seedUser(CUSTOMER_USERNAME, UserRole.CUSTOMER);
        store = storeRepository.save(StoreEntity.builder()
                .ownerId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .areaId(UUID.randomUUID())
                .name("테스트 가게")
                .address("서울시 강남구")
                .phone("010-0000-0000")
                .minOrderAmount(10000)
                .build());
        customerToken = login(CUSTOMER_USERNAME, DEFAULT_PASSWORD);
    }

    private OrderEntity seedCompletedOrder(String customerId) {
        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .customerId(customerId)
                .storeId(store.getId())
                .totalPrice(15000)
                .build());
        ReflectionTestUtils.setField(order, "status", OrderStatus.COMPLETED);
        return orderRepository.save(order);
    }

    @Nested
    @DisplayName("리뷰 생성")
    class CreateReview {

        @Test
        @DisplayName("주문 완료 상태 + 본인 주문 → review DB 저장, store averageRating 반영")
        void success_dbVerified() throws Exception {
            // given
            OrderEntity order = seedCompletedOrder(CUSTOMER_USERNAME);
            ReqCreateReviewDto req = new ReqCreateReviewDto(5, "맛있었어요!");

            // when
            mockMvc.perform(post("/api/v1/orders/{orderId}/reviews", order.getOrderId())
                            .header("Authorization", "Bearer " + customerToken)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());

            // then — DB 교차 검증
            assertThat(reviewRepository.existsByOrderId(order.getOrderId())).isTrue();
            assertThat(storeRepository.findById(store.getId()).orElseThrow().getAverageRating())
                    .isEqualByComparingTo("5.0");
        }

        @Test
        @DisplayName("주문이 COMPLETED 아닌 상태 → 400")
        void fail_orderNotCompleted() throws Exception {
            // given — PENDING 상태 주문 (Order 기본값)
            OrderEntity order = orderRepository.save(OrderEntity.builder()
                    .customerId(CUSTOMER_USERNAME)
                    .storeId(store.getId())
                    .totalPrice(15000)
                    .build());
            ReqCreateReviewDto req = new ReqCreateReviewDto(5, "맛있었어요!");

            // when / then
            mockMvc.perform(post("/api/v1/orders/{orderId}/reviews", order.getOrderId())
                            .header("Authorization", "Bearer " + customerToken)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("동일 주문에 중복 리뷰 생성 → 409")
        void fail_duplicateReview() throws Exception {
            // given — 이미 리뷰가 존재하는 주문
            OrderEntity order = seedCompletedOrder(CUSTOMER_USERNAME);
            ReqCreateReviewDto req = new ReqCreateReviewDto(5, "맛있었어요!");

            mockMvc.perform(post("/api/v1/orders/{orderId}/reviews", order.getOrderId())
                            .header("Authorization", "Bearer " + customerToken)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated());

            // when — 같은 주문에 리뷰 재시도
            mockMvc.perform(post("/api/v1/orders/{orderId}/reviews", order.getOrderId())
                            .header("Authorization", "Bearer " + customerToken)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("본인 주문 아닌 경우 → 403")
        void fail_notMyOrder() throws Exception {
            // given — 다른 유저 주문
            seedUser("other01", UserRole.CUSTOMER);
            OrderEntity order = seedCompletedOrder("other01");
            ReqCreateReviewDto req = new ReqCreateReviewDto(5, "맛있었어요!");

            // when / then
            mockMvc.perform(post("/api/v1/orders/{orderId}/reviews", order.getOrderId())
                            .header("Authorization", "Bearer " + customerToken)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("리뷰 수정")
    class UpdateReview {

        @Test
        @DisplayName("본인 리뷰 수정 → DB 반영, store averageRating 재계산 확인")
        void success_dbVerified() throws Exception {
            // given — 리뷰 생성 후 수정
            OrderEntity order = seedCompletedOrder(CUSTOMER_USERNAME);
            ReqCreateReviewDto createReq = new ReqCreateReviewDto(5, "맛있었어요!");

            String createBody = mockMvc.perform(post("/api/v1/orders/{orderId}/reviews", order.getOrderId())
                            .header("Authorization", "Bearer " + customerToken)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createReq)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            UUID reviewId = UUID.fromString(
                    objectMapper.readTree(createBody).path("data").path("reviewId").asText());

            ReqUpdateReviewDto updateReq = new ReqUpdateReviewDto(3, "다시 생각해보니 보통이었어요");

            // when
            mockMvc.perform(patch("/api/v1/reviews/{reviewId}", reviewId)
                            .header("Authorization", "Bearer " + customerToken)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateReq)))
                    .andExpect(status().isOk());

            // then — DB 교차 검증
            assertThat(reviewRepository.findById(reviewId).orElseThrow().getRating()).isEqualTo(3);
            assertThat(storeRepository.findById(store.getId()).orElseThrow().getAverageRating())
                    .isEqualByComparingTo("3.0");
        }
    }
}
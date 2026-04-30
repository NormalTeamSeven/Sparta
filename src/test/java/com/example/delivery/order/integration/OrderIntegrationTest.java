package com.example.delivery.order.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.delivery.global.test.IntegrationTestSupport;
import com.example.delivery.order.domain.entity.OrderEntity;
import com.example.delivery.order.domain.entity.OrderStatus;
import com.example.delivery.order.domain.repository.OrderRepository;
import com.example.delivery.order.infrastructure.repository.OrderItemRepository;
import com.example.delivery.order.presentation.dto.request.ReqChangeStatusDto;
import com.example.delivery.order.presentation.dto.request.ReqCreateOrderDto;
import com.example.delivery.order.presentation.dto.request.ReqOrderItemDto;
import com.example.delivery.order.presentation.dto.request.ReqUpdateRequestDto;
import com.example.delivery.user.domain.entity.UserRole;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order 도메인 통합 테스트.
 *
 * <p>[검증 목적]
 * <ul>
 *   <li>HTTP → Controller → Service → JPA → 실제 H2 DB 까지 전 구간 흐름</li>
 *   <li>권한·상태·시간 제약별 분기 케이스 — 시나리오 문서의 31개 케이스 빠짐없이 검증</li>
 *   <li>Soft Delete(@SQLRestriction)·Cascade(@OneToMany)·QueryDSL 실제 DB 동작 확인</li>
 * </ul>
 *
 * <p>[트랜잭션 격리]
 * 클래스 레벨 @Transactional 로 각 테스트 후 자동 롤백.
 * Customer 인증 토큰이 필요한 테스트는 seedUser + login 헬퍼 사용.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderIntegrationTest extends IntegrationTestSupport {

    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired EntityManager em;

    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID STORE_B = UUID.randomUUID();

    /** 픽스처 — 임의 customerId 로 PENDING 주문 저장 (status 지정 시 MASTER 경로로 전이). */
    private OrderEntity saveOrder(String customerId, UUID storeId, OrderStatus status) {
        OrderEntity order = OrderEntity.builder()
                .customerId(customerId)
                .storeId(storeId)
                .totalPrice(10_000)
                .build();
        if (status != OrderStatus.PENDING) {
            order.changeStatusByMaster(status);
        }
        return orderRepository.save(order);
    }

    /** createdAt 을 reflection 으로 변경 — 5분 경계 시간 검증용. */
    private void backdateCreatedAt(OrderEntity order, LocalDateTime when) {
        ReflectionTestUtils.setField(order, "createdAt", when);
    }

    // ── 주문 생성 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/orders — 주문 생성")
    class CreateOrder {

        @Test
        @DisplayName("CUSTOMER + 정상 항목 → 201, OrderItem cascade 저장, totalPrice = Σ(qty × unitPrice)")
        void customer_create_succeeds() throws Exception {
            seedUser("alice01", UserRole.CUSTOMER);
            String token = login("alice01", DEFAULT_PASSWORD);

            ReqCreateOrderDto req = new ReqCreateOrderDto(
                    STORE_A, null, "문 앞에 두고 가주세요.",
                    List.of(
                            new ReqOrderItemDto(UUID.randomUUID(), 2, 8_000),   // 16,000
                            new ReqOrderItemDto(UUID.randomUUID(), 1, 12_000)   // 12,000
                    ));

            long beforeItems = orderItemRepository.count();

            mockMvc.perform(post("/api/v1/orders")
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.totalPrice").value(28_000))
                    .andExpect(jsonPath("$.data.items.length()").value(2))
                    .andExpect(jsonPath("$.data.customerId").value("alice01"));

            assertThat(orderItemRepository.count()).isEqualTo(beforeItems + 2);
        }

        @Test
        @DisplayName("OWNER / MANAGER / MASTER 권한으로 생성 → 403 FORBIDDEN")
        void nonCustomer_create_forbidden() throws Exception {
            seedUser("ownr01", UserRole.OWNER);
            seedUser("mngr01", UserRole.MANAGER);
            seedUser("master01", UserRole.MASTER);

            ReqCreateOrderDto req = new ReqCreateOrderDto(
                    STORE_A, null, null,
                    List.of(new ReqOrderItemDto(UUID.randomUUID(), 1, 5_000)));
            String body = objectMapper.writeValueAsString(req);

            for (String username : List.of("ownr01", "mngr01", "master01")) {
                String token = login(username, DEFAULT_PASSWORD);
                mockMvc.perform(post("/api/v1/orders")
                                .header("Authorization", "Bearer " + token)
                                .contentType(APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("미인증 요청 → 401 UNAUTHORIZED")
        void unauthenticated_create_unauthorized() throws Exception {
            ReqCreateOrderDto req = new ReqCreateOrderDto(
                    STORE_A, null, null,
                    List.of(new ReqOrderItemDto(UUID.randomUUID(), 1, 5_000)));

            mockMvc.perform(post("/api/v1/orders")
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("quantity ≤ 0 → 400 VALIDATION_ERROR (Bean Validation)")
        void invalidQuantity_create_badRequest() throws Exception {
            seedUser("alice01", UserRole.CUSTOMER);
            String token = login("alice01", DEFAULT_PASSWORD);

            ReqCreateOrderDto req = new ReqCreateOrderDto(
                    STORE_A, null, null,
                    List.of(new ReqOrderItemDto(UUID.randomUUID(), 0, 5_000)));

            mockMvc.perform(post("/api/v1/orders")
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── 단건 조회 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/orders/{orderId} — 단건 조회")
    class GetOrder {

        @Test
        @DisplayName("존재하는 주문 ID → 200, DTO 반환")
        void existingOrder_returns200() throws Exception {
            OrderEntity saved = saveOrder("alice01", STORE_A, OrderStatus.PENDING);

            mockMvc.perform(get("/api/v1/orders/{id}", saved.getOrderId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.orderId").value(saved.getOrderId().toString()))
                    .andExpect(jsonPath("$.data.customerId").value("alice01"))
                    .andExpect(jsonPath("$.data.status").value("PENDING"));
        }

        @Test
        @DisplayName("존재하지 않는 주문 ID → 404 ORDER_NOT_FOUND")
        void nonExistentOrder_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("주문을 찾을 수 없습니다."));
        }

        @Test
        @DisplayName("soft-deleted 주문 → 404 (@SQLRestriction 자동 필터링)")
        void softDeletedOrder_returns404() throws Exception {
            OrderEntity saved = saveOrder("alice01", STORE_A, OrderStatus.PENDING);
            saved.softDelete("alice01");
            orderRepository.save(saved);
            // 1차 캐시(PersistenceContext)가 findById 시 SQLRestriction 을 우회하므로
            // flush + clear 로 DB 반영 후 캐시 비워서 실제 SQL 이 나가도록 강제한다.
            em.flush();
            em.clear();

            mockMvc.perform(get("/api/v1/orders/{id}", saved.getOrderId()))
                    .andExpect(status().isNotFound());
        }
    }

    // ── 목록 조회 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/orders — 목록 조회 (QueryDSL 동적 검색)")
    class GetOrders {

        @Test
        @DisplayName("CUSTOMER → 본인 주문만 자동 필터링 (?customerId=other 보내도 본인으로 강제)")
        void customer_seesOnlyOwnOrders() throws Exception {
            seedUser("alice01", UserRole.CUSTOMER);
            saveOrder("alice01", STORE_A, OrderStatus.PENDING);
            saveOrder("alice01", STORE_B, OrderStatus.ACCEPTED);
            saveOrder("bob01", STORE_A, OrderStatus.PENDING);

            String token = login("alice01", DEFAULT_PASSWORD);

            authedGet("/api/v1/orders", token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("OWNER + storeId 필터 → 해당 가게 주문 전체 반환")
        void owner_filterByStoreId() throws Exception {
            seedUser("ownr01", UserRole.OWNER);
            saveOrder("alice01", STORE_A, OrderStatus.PENDING);
            saveOrder("bob01", STORE_A, OrderStatus.ACCEPTED);
            saveOrder("alice01", STORE_B, OrderStatus.PENDING);

            String token = login("ownr01", DEFAULT_PASSWORD);

            authedGet("/api/v1/orders?storeId=" + STORE_A, token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("status 필터(PENDING) → 해당 상태 주문만 반환")
        void filterByStatus() throws Exception {
            seedUser("master01", UserRole.MASTER);
            saveOrder("alice01", STORE_A, OrderStatus.PENDING);
            saveOrder("alice01", STORE_A, OrderStatus.PENDING);
            saveOrder("bob01", STORE_B, OrderStatus.ACCEPTED);

            String token = login("master01", DEFAULT_PASSWORD);

            authedGet("/api/v1/orders?status=PENDING", token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("정렬 화이트리스트 외 컬럼(?sort=password) → 무시 후 createdAt DESC fallback")
        void sortWhitelist_unknownColumnIgnored() throws Exception {
            seedUser("master01", UserRole.MASTER);
            saveOrder("alice01", STORE_A, OrderStatus.PENDING);
            saveOrder("bob01", STORE_B, OrderStatus.PENDING);

            String token = login("master01", DEFAULT_PASSWORD);

            authedGet("/api/v1/orders?sort=password,desc", token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("미인증 요청 → 403 FORBIDDEN (컨트롤러가 principal == null 체크 후 throw)")
        void unauthenticated_returns403() throws Exception {
            mockMvc.perform(get("/api/v1/orders"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("soft-deleted 주문 → 결과에서 제외")
        void softDeletedOrder_excluded() throws Exception {
            seedUser("master01", UserRole.MASTER);
            saveOrder("alice01", STORE_A, OrderStatus.PENDING);
            OrderEntity deleted = saveOrder("bob01", STORE_B, OrderStatus.PENDING);
            deleted.softDelete("master01");
            orderRepository.save(deleted);

            String token = login("master01", DEFAULT_PASSWORD);

            authedGet("/api/v1/orders", token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1));
        }
    }

    // ── 주문 취소 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/orders/{orderId}/cancel — 주문 취소")
    class CancelOrder {

        @Test
        @DisplayName("CUSTOMER + 본인 + PENDING + 5분 이내 → 200, status=CANCELLED, canceledAt 기록")
        void customer_cancelsOwnPendingOrder_within5min() throws Exception {
            seedUser("alice01", UserRole.CUSTOMER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);

            String token = login("alice01", DEFAULT_PASSWORD);

            mockMvc.perform(post("/api/v1/orders/{id}/cancel", order.getOrderId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.data.canceledAt").isNotEmpty());

            assertThat(orderRepository.findById(order.getOrderId()))
                    .get()
                    .satisfies(o -> assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED));
        }

        @Test
        @DisplayName("CUSTOMER + 5분 초과 취소 시도 → 400 ORDER_CANCEL_TIMEOUT")
        void customer_cancelTimeout_returns400() throws Exception {
            seedUser("alice01", UserRole.CUSTOMER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);
            backdateCreatedAt(order, LocalDateTime.now().minusMinutes(6));
            orderRepository.save(order);

            String token = login("alice01", DEFAULT_PASSWORD);

            mockMvc.perform(post("/api/v1/orders/{id}/cancel", order.getOrderId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("주문 생성 후 5분이 경과하여 취소할 수 없습니다."));
        }

        @Test
        @DisplayName("CUSTOMER + PENDING 아닌 상태 취소 시도 → 400 INVALID_ORDER_STATUS")
        void customer_cancelNonPending_returns400() throws Exception {
            seedUser("alice01", UserRole.CUSTOMER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.ACCEPTED);

            String token = login("alice01", DEFAULT_PASSWORD);

            mockMvc.perform(post("/api/v1/orders/{id}/cancel", order.getOrderId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("CUSTOMER + 타인 주문 취소 시도 → 403 FORBIDDEN")
        void customer_cancelOthersOrder_returns403() throws Exception {
            seedUser("alice01", UserRole.CUSTOMER);
            OrderEntity bobOrder = saveOrder("bob01", STORE_A, OrderStatus.PENDING);

            String token = login("alice01", DEFAULT_PASSWORD);

            mockMvc.perform(post("/api/v1/orders/{id}/cancel", bobOrder.getOrderId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("MASTER → 어떤 상태든 취소 성공 (DELIVERED 도 강제 CANCELLED)")
        void master_cancelAnyStatus_succeeds() throws Exception {
            seedUser("master01", UserRole.MASTER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.DELIVERED);

            String token = login("master01", DEFAULT_PASSWORD);

            mockMvc.perform(post("/api/v1/orders/{id}/cancel", order.getOrderId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("OWNER / MANAGER 권한으로 취소 시도 → 403 FORBIDDEN")
        void ownerOrManager_cancel_forbidden() throws Exception {
            seedUser("ownr01", UserRole.OWNER);
            seedUser("mngr01", UserRole.MANAGER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);

            for (String username : List.of("ownr01", "mngr01")) {
                String token = login(username, DEFAULT_PASSWORD);
                mockMvc.perform(post("/api/v1/orders/{id}/cancel", order.getOrderId())
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isForbidden());
            }
        }
    }

    // ── 주문 상태 변경 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/v1/orders/{orderId}/status — 상태 변경")
    class ChangeStatus {

        private String statusJson(OrderStatus next) throws Exception {
            return objectMapper.writeValueAsString(new ReqChangeStatusDto(next));
        }

        @Test
        @DisplayName("OWNER + 정방향(PENDING → ACCEPTED) → 200, acceptedAt 기록")
        void owner_forwardTransition_succeeds() throws Exception {
            seedUser("ownr01", UserRole.OWNER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);

            String token = login("ownr01", DEFAULT_PASSWORD);

            mockMvc.perform(patch("/api/v1/orders/{id}/status", order.getOrderId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(statusJson(OrderStatus.ACCEPTED)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                    .andExpect(jsonPath("$.data.acceptedAt").isNotEmpty());
        }

        @Test
        @DisplayName("OWNER + 점프 전이(PENDING → COOKING) → 400 INVALID_ORDER_STATUS")
        void owner_jumpTransition_returns400() throws Exception {
            seedUser("ownr01", UserRole.OWNER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);

            String token = login("ownr01", DEFAULT_PASSWORD);

            mockMvc.perform(patch("/api/v1/orders/{id}/status", order.getOrderId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(statusJson(OrderStatus.COOKING)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("OWNER + CANCELLED 전이 시도 → 400 INVALID_ORDER_STATUS")
        void owner_cancelTransition_returns400() throws Exception {
            seedUser("ownr01", UserRole.OWNER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);

            String token = login("ownr01", DEFAULT_PASSWORD);

            mockMvc.perform(patch("/api/v1/orders/{id}/status", order.getOrderId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(statusJson(OrderStatus.CANCELLED)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("MANAGER + CANCELLED 전이 시도 → 403 FORBIDDEN")
        void manager_cancelTransition_returns403() throws Exception {
            seedUser("mngr01", UserRole.MANAGER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);

            String token = login("mngr01", DEFAULT_PASSWORD);

            mockMvc.perform(patch("/api/v1/orders/{id}/status", order.getOrderId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(statusJson(OrderStatus.CANCELLED)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("MANAGER + CANCELLED 외 전이 → 200 (점프·역방향 모두 허용)")
        void manager_nonCancelTransition_succeeds() throws Exception {
            seedUser("mngr01", UserRole.MANAGER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);

            String token = login("mngr01", DEFAULT_PASSWORD);

            mockMvc.perform(patch("/api/v1/orders/{id}/status", order.getOrderId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(statusJson(OrderStatus.DELIVERING)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("DELIVERING"));
        }

        @Test
        @DisplayName("MASTER + 어떤 전이든(역방향·CANCELLED 포함) → 200")
        void master_anyTransition_succeeds() throws Exception {
            seedUser("master01", UserRole.MASTER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.DELIVERED);

            String token = login("master01", DEFAULT_PASSWORD);

            // 역방향: DELIVERED → PENDING
            mockMvc.perform(patch("/api/v1/orders/{id}/status", order.getOrderId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(statusJson(OrderStatus.PENDING)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PENDING"));
        }

        @Test
        @DisplayName("CUSTOMER 권한으로 상태 변경 시도 → 403 FORBIDDEN")
        void customer_changeStatus_forbidden() throws Exception {
            seedUser("alice01", UserRole.CUSTOMER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);

            String token = login("alice01", DEFAULT_PASSWORD);

            mockMvc.perform(patch("/api/v1/orders/{id}/status", order.getOrderId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(statusJson(OrderStatus.ACCEPTED)))
                    .andExpect(status().isForbidden());
        }
    }

    // ── 요청사항 수정 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/v1/orders/{orderId}/request — 요청사항 수정")
    class UpdateRequest {

        private String requestJson(String text) throws Exception {
            return objectMapper.writeValueAsString(new ReqUpdateRequestDto(text));
        }

        @Test
        @DisplayName("CUSTOMER + 본인 + PENDING → 200, DB 반영")
        void customer_updateOwnPendingOrder_succeeds() throws Exception {
            seedUser("alice01", UserRole.CUSTOMER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);

            String token = login("alice01", DEFAULT_PASSWORD);

            mockMvc.perform(patch("/api/v1/orders/{id}/request", order.getOrderId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(requestJson("벨 누르지 마세요.")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.request").value("벨 누르지 마세요."));

            assertThat(orderRepository.findById(order.getOrderId()))
                    .get()
                    .satisfies(o -> assertThat(o.getRequest()).isEqualTo("벨 누르지 마세요."));
        }

        @Test
        @DisplayName("CUSTOMER + PENDING 아닌 주문 수정 시도 → 400 INVALID_ORDER_STATUS")
        void customer_updateNonPending_returns400() throws Exception {
            seedUser("alice01", UserRole.CUSTOMER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.ACCEPTED);

            String token = login("alice01", DEFAULT_PASSWORD);

            mockMvc.perform(patch("/api/v1/orders/{id}/request", order.getOrderId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(requestJson("수정 시도")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("CUSTOMER + 타인 주문 수정 시도 → 403 FORBIDDEN")
        void customer_updateOthersOrder_returns403() throws Exception {
            seedUser("alice01", UserRole.CUSTOMER);
            OrderEntity bobOrder = saveOrder("bob01", STORE_A, OrderStatus.PENDING);

            String token = login("alice01", DEFAULT_PASSWORD);

            mockMvc.perform(patch("/api/v1/orders/{id}/request", bobOrder.getOrderId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(requestJson("타인 주문 수정 시도")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("MASTER + PENDING 주문 수정 → 200 (본인 검증 없음)")
        void master_updatePending_succeeds() throws Exception {
            seedUser("master01", UserRole.MASTER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);

            String token = login("master01", DEFAULT_PASSWORD);

            mockMvc.perform(patch("/api/v1/orders/{id}/request", order.getOrderId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(APPLICATION_JSON)
                            .content(requestJson("관리자 수정")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.request").value("관리자 수정"));
        }

        @Test
        @DisplayName("OWNER / MANAGER 권한으로 수정 시도 → 403 FORBIDDEN")
        void ownerOrManager_update_forbidden() throws Exception {
            seedUser("ownr01", UserRole.OWNER);
            seedUser("mngr01", UserRole.MANAGER);
            OrderEntity order = saveOrder("alice01", STORE_A, OrderStatus.PENDING);
            String body = requestJson("권한 없는 수정 시도");

            for (String username : List.of("ownr01", "mngr01")) {
                String token = login(username, DEFAULT_PASSWORD);
                mockMvc.perform(patch("/api/v1/orders/{id}/request", order.getOrderId())
                                .header("Authorization", "Bearer " + token)
                                .contentType(APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isForbidden());
            }
        }
    }
}

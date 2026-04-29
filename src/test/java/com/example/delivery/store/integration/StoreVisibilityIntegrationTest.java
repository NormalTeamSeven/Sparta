package com.example.delivery.store.integration;

import com.example.delivery.area.domain.entity.AreaEntity;
import com.example.delivery.area.domain.repository.AreaRepository;
import com.example.delivery.category.domain.entity.CategoryEntity;
import com.example.delivery.category.domain.repository.CategoryRepository;
import com.example.delivery.global.test.IntegrationTestSupport;
import com.example.delivery.store.domain.entity.StoreEntity;
import com.example.delivery.store.domain.repository.StoreRepository;
import com.example.delivery.user.domain.entity.UserEntity;
import com.example.delivery.user.domain.entity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Store Visibility 통합 테스트 — 단건 조회의 숨김 분기 + 숨김 토글 흐름 검증.
 *
 * GET /api/v1/stores/{id} 의 숨김 정책 + PATCH /api/v1/stores/{id}/hide 의 권한 매트릭스를 다룬다.
 * - 생성/수정/삭제 → {@code StoreCrudIntegrationTest}
 * - 목록/검색/정렬/페이지 사이즈 → {@code StoreSearchIntegrationTest}
 *
 * 정책 메모
 * - GET 단건은 SecurityConfig 의 anyRequest().permitAll() 로 익명 허용된다.
 *   다만 서비스 계층이 isHidden=true 인 가게에 한해 OWNER/MANAGER/MASTER 가 아니면
 *   StoreNotFoundException 으로 정규화하여 404 로 응답한다(정보 노출 회피).
 *
 * - PATCH /hide 는 컨트롤러의 @PreAuthorize("hasAnyRole('OWNER','MANAGER','MASTER')") 로 권한이 강제되며
 *   서비스 계층의 assertModifiable 이 OWNER 본인 / MANAGER / MASTER 만 통과시킨다(타 OWNER 차단).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StoreVisibilityIntegrationTest extends IntegrationTestSupport {

    @Autowired
    AreaRepository areaRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    StoreRepository storeRepository;

    // ────────────────────────────────────────────────
    // GET /api/v1/stores/{storeId} - 기본 단건 조회
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("미인증 상태에서도 가게 단건 조회 가능(권한 없음) → 200")
    void getOne_unauthenticated_200() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");

        mockMvc.perform(get("/api/v1/stores/{id}", store.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("광화문 한식당"))
                .andExpect(jsonPath("$.data.storeId").value(store.getId().toString()))
                .andExpect(jsonPath("$.data.isHidden").value(false));
    }

    @Test
    @DisplayName("존재하지 않는 storeId 단건 조회 → 404 STORE_NOT_FOUND")
    void getOne_notFound_404() throws Exception {
        mockMvc.perform(get("/api/v1/stores/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("가게를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("soft-deleted 가게 단건 조회 → 404 (살아있는 row 만 노출)")
    void getOne_softDeletedStore_404() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");
        store.softDelete("master01");
        storeRepository.save(store);

        mockMvc.perform(get("/api/v1/stores/{id}", store.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("가게를 찾을 수 없습니다."));
    }

    // ────────────────────────────────────────────────
    // GET /api/v1/stores/{storeId} - 숨김(hidden) 분기
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("숨김 가게는 미인증 사용자에게 미존재로 노출 → 404")
    void getOne_hiddenStore_unauthenticated_404() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedHiddenStore(
                UUID.randomUUID(), category.getId(), area.getId(), "비밀가게");

        mockMvc.perform(get("/api/v1/stores/{id}", store.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("가게를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("숨김 가게는 CUSTOMER 에게도 미존재로 노출 → 404")
    void getOne_hiddenStore_customer_404() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedHiddenStore(
                UUID.randomUUID(), category.getId(), area.getId(), "비밀가게");

        seedUser("cust01", UserRole.CUSTOMER);
        String token = login("cust01", DEFAULT_PASSWORD);

        mockMvc.perform(get("/api/v1/stores/{id}", store.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("숨김 가게는 본인 OWNER 가 그대로 조회 가능 → 200")
    void getOne_hiddenStore_byOwnerSelf_200() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedHiddenStore(
                owner.getId(), category.getId(), area.getId(), "비밀가게");

        String token = login("ownr01", DEFAULT_PASSWORD);

        mockMvc.perform(get("/api/v1/stores/{id}", store.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("비밀가게"))
                .andExpect(jsonPath("$.data.isHidden").value(true));
    }

    @Test
    @DisplayName("숨김 가게도 MANAGER 는 그대로 조회 가능 → 200")
    void getOne_hiddenStore_byManager_200() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedHiddenStore(
                UUID.randomUUID(), category.getId(), area.getId(), "비밀가게");

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        mockMvc.perform(get("/api/v1/stores/{id}", store.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("비밀가게"))
                .andExpect(jsonPath("$.data.isHidden").value(true));
    }

    @Test
    @DisplayName("숨김 가게도 MASTER 는 그대로 조회 가능 → 200")
    void getOne_hiddenStore_byMaster_200() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedHiddenStore(
                UUID.randomUUID(), category.getId(), area.getId(), "비밀가게");

        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        mockMvc.perform(get("/api/v1/stores/{id}", store.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("비밀가게"))
                .andExpect(jsonPath("$.data.isHidden").value(true));
    }

    // ────────────────────────────────────────────────
    // PATCH /api/v1/stores/{storeId}/hide - 숨김 토글
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("OWNER 본인이 자신의 가게 숨김 토글 → 200 + isHidden=true + 후속 GET 반영")
    void toggleHidden_byOwnerSelf_thenGetReflects() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedStore(owner.getId(), category.getId(), area.getId(), "광화문 한식당");

        String token = login("ownr01", DEFAULT_PASSWORD);

        // 토글: false → true
        mockMvc.perform(patch("/api/v1/stores/" + store.getId() + "/hide")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isHidden").value(true));

        // 본인 OWNER 의 후속 GET 은 isHidden=true 를 그대로 노출
        mockMvc.perform(get("/api/v1/stores/{id}", store.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isHidden").value(true));

        StoreEntity reloaded = storeRepository.findById(store.getId()).orElseThrow();
        assertThat(reloaded.isHidden()).isTrue();
    }

    @Test
    @DisplayName("숨김 가게를 다시 토글 → 200 + isHidden=false (복원)")
    void toggleHidden_twice_returnsToFalse() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedHiddenStore(owner.getId(), category.getId(), area.getId(), "광화문 한식당");

        String token = login("ownr01", DEFAULT_PASSWORD);

        // 한 번 더 토글: true → false
        mockMvc.perform(patch("/api/v1/stores/" + store.getId() + "/hide")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isHidden").value(false));

        StoreEntity reloaded = storeRepository.findById(store.getId()).orElseThrow();
        assertThat(reloaded.isHidden()).isFalse();
    }

    @Test
    @DisplayName("MANAGER 가 타인 가게 숨김 토글 → 200")
    void toggleHidden_byManager_200() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        mockMvc.perform(patch("/api/v1/stores/" + store.getId() + "/hide")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isHidden").value(true));
    }

    @Test
    @DisplayName("MASTER 가 타인 가게 숨김 토글 → 200")
    void toggleHidden_byMaster_200() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");

        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        mockMvc.perform(patch("/api/v1/stores/" + store.getId() + "/hide")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isHidden").value(true));
    }

    @Test
    @DisplayName("OWNER 가 타인 가게 숨김 토글 → 403 STORE_ACCESS_DENIED")
    void toggleHidden_byOtherOwner_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        UserEntity ownerA = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedStore(ownerA.getId(), category.getId(), area.getId(), "광화문 한식당");

        seedUser("ownr02", UserRole.OWNER);
        String tokenB = login("ownr02", DEFAULT_PASSWORD);

        mockMvc.perform(patch("/api/v1/stores/" + store.getId() + "/hide")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("해당 가게에 대한 권한이 없습니다."));

        StoreEntity reloaded = storeRepository.findById(store.getId()).orElseThrow();
        assertThat(reloaded.isHidden()).isFalse();
    }

    @Test
    @DisplayName("CUSTOMER 가 숨김 토글 시도 → 403 (PreAuthorize)")
    void toggleHidden_byCustomer_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");

        seedUser("cust01", UserRole.CUSTOMER);
        String token = login("cust01", DEFAULT_PASSWORD);

        mockMvc.perform(patch("/api/v1/stores/" + store.getId() + "/hide")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 상태 숨김 토글 → 403")
    void toggleHidden_unauthenticated_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");

        mockMvc.perform(patch("/api/v1/stores/" + store.getId() + "/hide"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("존재하지 않는 storeId 숨김 토글 → 404")
    void toggleHidden_storeNotFound_404() throws Exception {
        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        mockMvc.perform(patch("/api/v1/stores/" + UUID.randomUUID() + "/hide")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("가게를 찾을 수 없습니다."));
    }

    // ────────────────────────────────────────────────
    // private helpers (베이스 미수정 정책에 따라 클래스 로컬)
    // ────────────────────────────────────────────────

    private AreaEntity seedArea(String name, boolean isActive) {
        return areaRepository.save(AreaEntity.builder()
                .name(name)
                .city("서울특별시")
                .district("종로구")
                .isActive(isActive)
                .build());
    }

    private CategoryEntity seedCategory(String name) {
        return categoryRepository.save(CategoryEntity.builder().name(name).build());
    }

    private StoreEntity seedStore(UUID ownerId, UUID categoryId, UUID areaId, String name) {
        return storeRepository.save(StoreEntity.builder()
                .ownerId(ownerId)
                .categoryId(categoryId)
                .areaId(areaId)
                .name(name)
                .address("서울특별시 종로구 어딘가 1")
                .phone(null)
                .minOrderAmount(15000)
                .build());
    }

    private StoreEntity seedHiddenStore(UUID ownerId, UUID categoryId, UUID areaId, String name) {
        StoreEntity store = seedStore(ownerId, categoryId, areaId, name);
        store.toggleHidden(); // false → true
        return storeRepository.save(store);
    }
}

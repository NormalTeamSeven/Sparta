package com.example.delivery.area.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.delivery.area.domain.entity.AreaEntity;
import com.example.delivery.area.domain.repository.AreaRepository;
import com.example.delivery.category.domain.entity.CategoryEntity;
import com.example.delivery.category.domain.repository.CategoryRepository;
import com.example.delivery.global.test.IntegrationTestSupport;
import com.example.delivery.store.domain.entity.StoreEntity;
import com.example.delivery.store.domain.repository.StoreRepository;
import com.example.delivery.store.presentation.dto.request.ReqCreateStoreDto;
import com.example.delivery.user.domain.entity.UserEntity;
import com.example.delivery.user.domain.entity.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Area DELETE 정책 통합 테스트.
 *
 * 검증 포인트
 * 1) DELETE /api/v1/areas/{id} 의 권한 매트릭스: MASTER 만 200, 그 외(MANAGER/OWNER/CUSTOMER/미인증) 403
 * 2) 사용 중 Store 가 있으면 → 409 AREA_IN_USE
 * 3) 성공 시 deletedAt/deletedBy 가 채워지고, 후속 단건 조회는 404
 * 4) Soft-deleted areaId 로 Store 생성 시도 → 404 (RelatedAreaNotFoundException, AreaRepository.findByIdAndDeletedAtIsNull)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AreaDeleteGuardIntegrationTest extends IntegrationTestSupport {

    @Autowired
    AreaRepository areaRepository;
    @Autowired
    StoreRepository storeRepository;
    @Autowired
    CategoryRepository categoryRepository;

    // ────────────────────────────────────────────────
    // 권한 매트릭스
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("MASTER 가 지역 삭제 → 200 + soft delete 반영(deletedAt/deletedBy)")
    void delete_byMaster_softDeletes() throws Exception {
        AreaEntity area = seedArea("광화문", "서울특별시", "종로구", true);

        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        authedDelete("/api/v1/areas/" + area.getId(), token)
                .andExpect(status().isOk());

        // Repository.findById 는 살아있는 row 만 보므로 직접 단건 조회로는 보이지 않음
        assertThat(areaRepository.findById(area.getId())).isEmpty();
        assertThat(areaRepository.findByNameIncludingDeleted("광화문")).isPresent();
    }

    @Test
    @DisplayName("MANAGER 가 삭제 시도 → 403 (DELETE 는 MASTER 전용)")
    void delete_byManager_403() throws Exception {
        AreaEntity area = seedArea("광화문", "서울특별시", "종로구", true);

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        authedDelete("/api/v1/areas/" + area.getId(), token)
                .andExpect(status().isForbidden());

        // 트랜잭션 내 실제로 삭제되지 않았는지 확인
        assertThat(areaRepository.findById(area.getId())).isPresent();
    }

    @Test
    @DisplayName("CUSTOMER 가 삭제 시도 → 403")
    void delete_byCustomer_403() throws Exception {
        AreaEntity area = seedArea("광화문", "서울특별시", "종로구", true);

        seedUser("cust01", UserRole.CUSTOMER);
        String token = login("cust01", DEFAULT_PASSWORD);

        authedDelete("/api/v1/areas/" + area.getId(), token)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 상태 DELETE 요청 → 403 (anonymous 에 대한 @PreAuthorize 거부)")
    void delete_unauthenticated_403() throws Exception {
        AreaEntity area = seedArea("광화문", "서울특별시", "종로구", true);

        mockMvc.perform(delete("/api/v1/areas/" + area.getId()))
                .andExpect(status().isForbidden());

        assertThat(areaRepository.findById(area.getId())).isPresent();
    }

    // ────────────────────────────────────────────────
    // 사용 중 Store 가드
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("사용 중인 Store 가 있으면 → 409 AREA_IN_USE")
    void delete_areaInUse_409() throws Exception {
        AreaEntity area = seedArea("광화문", "서울특별시", "종로구", true);
        seedStoreOn(area.getId());

        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        authedDelete("/api/v1/areas/" + area.getId(), token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("해당 지역을 사용 중인 가게가 있어 삭제할 수 없습니다."));

        // 가드에 의해 실제로 삭제되지 않았어야 함
        assertThat(areaRepository.findById(area.getId())).isPresent();
    }

    // ────────────────────────────────────────────────
    // 삭제 후 후속 조회 / 재참조
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("삭제 성공 후 단건 조회 → 404")
    void getAfterDelete_404() throws Exception {
        AreaEntity area = seedArea("광화문", "서울특별시", "종로구", true);

        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        authedDelete("/api/v1/areas/" + area.getId(), token)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/areas/{id}", area.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("지역을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("Soft-deleted areaId 로 Store 생성 시도 → 404 AREA_NOT_FOUND")
    void createStoreWithDeletedArea_404() throws Exception {
        // given: 카테고리(살아있음) + 지역(soft-deleted) + OWNER 로그인
        CategoryEntity category = seedCategory("한식-IT");
        AreaEntity area = seedArea("광화문", "서울특별시", "종로구", true);
        area.softDelete("master01");
        areaRepository.save(area);

        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        String token = login(owner.getUsername().value(), DEFAULT_PASSWORD);

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), area.getId(),
                "광화문 분식", "서울 종로구 어딘가 1", null, 5000);

        // when & then
        authedPost("/api/v1/stores", token, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("지역을 찾을 수 없습니다."));
    }

    // ────────────────────────────────────────────────
    // private helpers
    // ────────────────────────────────────────────────

    private ResultActions authedPost(String url, String token, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private AreaEntity seedArea(String name, String city, String district, boolean isActive) {
        return areaRepository.save(AreaEntity.builder()
                .name(name)
                .city(city)
                .district(district)
                .isActive(isActive)
                .build());
    }

    private CategoryEntity seedCategory(String name) {
        return categoryRepository.save(CategoryEntity.builder().name(name).build());
    }

    /**
     * area 사용 중 가드 검증 전용 — Store 도메인의 입력 검증/권한 흐름은 별도 테스트에서 다루므로,
     * 여기서는 repository 직접 저장으로 store row 만 만든다.
     * (StoreEntity 는 ownerId/categoryId 에 FK 제약이 없는 단순 UUID 컬럼이므로 임의 값 허용)
     */
    private StoreEntity seedStoreOn(UUID areaId) {
        return storeRepository.save(StoreEntity.builder()
                .ownerId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .areaId(areaId)
                .name("사용중-가게")
                .address("서울특별시 종로구 어딘가 1")
                .phone(null)
                .minOrderAmount(5000)
                .build());
    }
}

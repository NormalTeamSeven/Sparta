package com.example.delivery.store.integration;

import com.example.delivery.area.domain.entity.AreaEntity;
import com.example.delivery.area.domain.repository.AreaRepository;
import com.example.delivery.category.domain.entity.CategoryEntity;
import com.example.delivery.category.domain.repository.CategoryRepository;
import com.example.delivery.global.test.IntegrationTestSupport;
import com.example.delivery.store.domain.entity.StoreEntity;
import com.example.delivery.store.domain.repository.StoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Store 검색/목록 통합 테스트 — keyword/categoryId/areaId 필터 + 정렬 화이트리스트 +
 * 페이지 사이즈 화이트리스트 + 숨김/비활성 지역 가게 제외 정책 검증.
 *
 * GET /api/v1/stores 의 검색·정렬·페이지네이션 흐름만 다룬다.
 * - 생성/수정/삭제 → {@code StoreCrudIntegrationTest}
 * - 단건 조회 숨김 분기 / 숨김 토글 → {@code StoreVisibilityIntegrationTest}
 *
 * 정책 메모
 * - 목록 조회는 SecurityConfig 의 anyRequest().permitAll() 로 익명 허용된다.
 * - 정렬 화이트리스트(StoreRepositoryCustomImpl.resolvePath): {@code createdAt, updatedAt, name, averageRating}
 *   화이트리스트 외 필드는 OrderSpecifier 변환 시 silently 드롭되며, 모든 필드가 무효일 경우
 *   {@code STORE.createdAt.desc()} 로 fallback 된다.
 * - 페이지 사이즈 화이트리스트(PageSizePolicy): {@code 10, 30, 50} 만 통과되며 그 외는 10 으로 보정된다.
 * - 검색 결과는 항상 살아있는 row 만 본다(STORE.deletedAt.isNull()) + 숨김 가게 제외(STORE.isHidden.isFalse())
 *   + 비활성 지역의 가게 제외(AREA.isActive.isTrue()).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StoreSearchIntegrationTest extends IntegrationTestSupport {

    @Autowired
    AreaRepository areaRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    StoreRepository storeRepository;

    // ────────────────────────────────────────────────
    // 필터 (keyword / categoryId / areaId)
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("키워드(가게명 부분일치) 필터 → 매칭 항목만 반환")
    void getAll_keywordFilter() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        seedStore(category.getId(), area.getId(), "광화문 한식당");
        seedStore(category.getId(), area.getId(), "광화문 분식당");
        seedStore(category.getId(), area.getId(), "강남 한식당");

        mockMvc.perform(get("/api/v1/stores")
                        .param("keyword", "광화문")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[?(@.name=='광화문 한식당')]").exists())
                .andExpect(jsonPath("$.data.content[?(@.name=='광화문 분식당')]").exists());
    }

    @Test
    @DisplayName("categoryId 필터 → 해당 카테고리 가게만 반환")
    void getAll_categoryFilter() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity korean = seedCategory("한식");
        CategoryEntity chinese = seedCategory("중식");

        seedStore(korean.getId(), area.getId(), "한식가게A");
        seedStore(korean.getId(), area.getId(), "한식가게B");
        seedStore(chinese.getId(), area.getId(), "중식가게");

        mockMvc.perform(get("/api/v1/stores")
                        .param("categoryId", korean.getId().toString())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("areaId 필터 → 해당 지역 가게만 반환")
    void getAll_areaFilter() throws Exception {
        AreaEntity gwanghwa = seedArea("광화문", true);
        AreaEntity gangnam = seedArea("강남역", true);
        CategoryEntity category = seedCategory("한식");

        seedStore(category.getId(), gwanghwa.getId(), "광화문가게A");
        seedStore(category.getId(), gangnam.getId(), "강남가게A");
        seedStore(category.getId(), gangnam.getId(), "강남가게B");

        mockMvc.perform(get("/api/v1/stores")
                        .param("areaId", gangnam.getId().toString())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("키워드 + 카테고리 + 지역 합성 필터 → 모든 조건 만족하는 1건만 반환")
    void getAll_compositeFilter() throws Exception {
        AreaEntity gwanghwa = seedArea("광화문", true);
        AreaEntity gangnam = seedArea("강남역", true);
        CategoryEntity korean = seedCategory("한식");
        CategoryEntity chinese = seedCategory("중식");

        seedStore(korean.getId(), gwanghwa.getId(), "광화문 한식당");
        seedStore(chinese.getId(), gwanghwa.getId(), "광화문 중식당");
        seedStore(korean.getId(), gangnam.getId(), "강남 한식당");

        mockMvc.perform(get("/api/v1/stores")
                        .param("keyword", "광화문")
                        .param("categoryId", korean.getId().toString())
                        .param("areaId", gwanghwa.getId().toString())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("광화문 한식당"));
    }

    @Test
    @DisplayName("필터 없이 조회 + 숨김 가게 / 비활성 지역의 가게는 제외")
    void getAll_excludesHiddenAndInactiveAreaStores() throws Exception {
        AreaEntity activeArea = seedArea("광화문", true);
        AreaEntity inactiveArea = seedArea("강남역", false);
        CategoryEntity category = seedCategory("한식");

        seedStore(category.getId(), activeArea.getId(), "노출가게");
        seedHiddenStore(category.getId(), activeArea.getId(), "숨김가게");
        seedStore(category.getId(), inactiveArea.getId(), "비활성지역가게");

        mockMvc.perform(get("/api/v1/stores")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("노출가게"));
    }

    // ────────────────────────────────────────────────
    // 페이지 사이즈 화이트리스트 (10 / 30 / 50)
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("페이지 사이즈 화이트리스트 10/30/50 → 그대로 통과")
    void getAll_allowedPageSizes_passThrough() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        seedStore(category.getId(), area.getId(), "가게");

        for (int size : new int[]{10, 30, 50}) {
            mockMvc.perform(get("/api/v1/stores")
                            .param("page", "0")
                            .param("size", String.valueOf(size)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.size").value(size));
        }
    }

    @Test
    @DisplayName("허용되지 않은 page size(7) → 10 으로 보정")
    void getAll_invalidSize_normalizedToTen() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        seedStore(category.getId(), area.getId(), "가게");

        mockMvc.perform(get("/api/v1/stores")
                        .param("page", "0")
                        .param("size", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(10));
    }

    // ────────────────────────────────────────────────
    // 정렬 화이트리스트 (createdAt / updatedAt / name / averageRating)
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("정렬 화이트리스트: averageRating,desc 통과 → 평균 평점 내림차순")
    void getAll_sortByAverageRatingDesc() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        seedStoreWithRating(category.getId(), area.getId(), "고평점가게", 5);
        seedStoreWithRating(category.getId(), area.getId(), "중평점가게", 3);
        seedStoreWithRating(category.getId(), area.getId(), "저평점가게", 1);

        mockMvc.perform(get("/api/v1/stores")
                        .param("sort", "averageRating,desc")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].name").value("고평점가게"))
                .andExpect(jsonPath("$.data.content[1].name").value("중평점가게"))
                .andExpect(jsonPath("$.data.content[2].name").value("저평점가게"));
    }

    @Test
    @DisplayName("정렬 화이트리스트: averageRating,asc 통과 → 평균 평점 오름차순")
    void getAll_sortByAverageRatingAsc() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        seedStoreWithRating(category.getId(), area.getId(), "고평점가게", 5);
        seedStoreWithRating(category.getId(), area.getId(), "저평점가게", 1);

        mockMvc.perform(get("/api/v1/stores")
                        .param("sort", "averageRating,asc")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].name").value("저평점가게"))
                .andExpect(jsonPath("$.data.content[1].name").value("고평점가게"));
    }

    @Test
    @DisplayName("정렬 비허용 필드는 무시되고 화이트리스트 필드만 적용")
    void getAll_mixedSortFields_dropsInvalidKeepsValid() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        seedStoreWithRating(category.getId(), area.getId(), "고평점가게", 5);
        seedStoreWithRating(category.getId(), area.getId(), "저평점가게", 1);

        // garbageField 는 화이트리스트에 없어 무시되고, averageRating,desc 만 적용된다
        mockMvc.perform(get("/api/v1/stores")
                        .param("sort", "garbageField,desc")
                        .param("sort", "averageRating,desc")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].name").value("고평점가게"))
                .andExpect(jsonPath("$.data.content[1].name").value("저평점가게"));
    }

    @Test
    @DisplayName("정렬: 비허용 필드만 입력 → 오류 없이 fallback 동작(200, 누락 없음)")
    void getAll_onlyInvalidSortField_fallbackOk() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        seedStore(category.getId(), area.getId(), "가게A");
        seedStore(category.getId(), area.getId(), "가게B");

        // 모든 sort 필드가 화이트리스트 미포함 → STORE.createdAt.desc() 로 fallback
        mockMvc.perform(get("/api/v1/stores")
                        .param("sort", "garbageField,desc")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
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

    private StoreEntity seedStore(UUID categoryId, UUID areaId, String name) {
        return storeRepository.save(StoreEntity.builder()
                .ownerId(UUID.randomUUID())
                .categoryId(categoryId)
                .areaId(areaId)
                .name(name)
                .address("서울특별시 종로구 어딘가 1")
                .phone(null)
                .minOrderAmount(15000)
                .build());
    }

    private StoreEntity seedHiddenStore(UUID categoryId, UUID areaId, String name) {
        StoreEntity store = seedStore(categoryId, areaId, name);
        store.toggleHidden(); // false → true
        return storeRepository.save(store);
    }

    /**
     * averageRating 정렬 검증 전용 — 가게 생성 후 ratings 리스트로 평균 평점을 세팅한다.
     * StoreEntity.recalculateAverageRating(...) 은 평균을 0.0 ~ 5.0 범위로 강제하므로
     * 단일 정수 rating(1~5)을 그대로 평균값으로 사용한다.
     */
    private StoreEntity seedStoreWithRating(UUID categoryId, UUID areaId, String name, int rating) {
        StoreEntity store = seedStore(categoryId, areaId, name);
        store.recalculateAverageRating(List.of(rating));
        return storeRepository.save(store);
    }
}

package com.example.delivery.store.integration;

import com.example.delivery.area.domain.entity.AreaEntity;
import com.example.delivery.area.domain.repository.AreaRepository;
import com.example.delivery.category.domain.entity.CategoryEntity;
import com.example.delivery.category.domain.repository.CategoryRepository;
import com.example.delivery.global.test.IntegrationTestSupport;
import com.example.delivery.store.domain.entity.StoreEntity;
import com.example.delivery.store.domain.repository.StoreRepository;
import com.example.delivery.store.presentation.dto.request.ReqCreateStoreDto;
import com.example.delivery.store.presentation.dto.request.ReqUpdateStoreDto;
import com.example.delivery.user.domain.entity.UserEntity;
import com.example.delivery.user.domain.entity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Store CRUD 통합 테스트 — 권한 매트릭스 + Validation + 연관 엔티티(Category/Area) 검증 +
 * DB 영속 + 예외 핸들러까지의 흐름 검증.
 *
 * 본 클래스는 POST(생성) / PATCH(정보 수정) / DELETE 흐름만 다룬다.
 * - 단건 조회 숨김 분기 / 숨김 토글 → {@code StoreVisibilityIntegrationTest}
 * - 목록 조회 / 정렬 / 페이지 사이즈 화이트리스트 → {@code StoreSearchIntegrationTest}
 *
 * 정책 메모
 * - SecurityConfig 에서 /api/v1/stores/** 는 anyRequest().permitAll() 에 해당하지만,
 *   변경 계열 엔드포인트는 컨트롤러의 @PreAuthorize 로 권한이 강제된다.
 *   따라서 미인증 POST/PATCH/DELETE 의 응답은 ExceptionTranslationFilter 가 아니라
 *   GlobalExceptionHandler.handleAccessDenied 를 통한 403 으로 정규화된다.
 *   (UserAuthSmokeIntegrationTest 의 401 흐름과 다른 점에 주의)
 *
 * - 가게 생성(POST) 권한       : OWNER 전용
 *   가게 정보 수정(PATCH)       : OWNER(본인) / MANAGER / MASTER
 *   가게 삭제(DELETE)           : OWNER(본인) / MASTER  (MANAGER 불가)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StoreCrudIntegrationTest extends IntegrationTestSupport {

    @Autowired
    AreaRepository areaRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    StoreRepository storeRepository;

    // ────────────────────────────────────────────────
    // POST /api/v1/stores
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("OWNER 가 가게 생성 → 201 + DB 반영 + createdBy 설정")
    void create_byOwner_201() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), area.getId(),
                "광화문 한식당", "서울 종로구 세종대로 1", "02-1234-5678", 15000);

        authedPost("/api/v1/stores", token, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("광화문 한식당"))
                .andExpect(jsonPath("$.data.ownerId").value(owner.getId().toString()))
                .andExpect(jsonPath("$.data.categoryId").value(category.getId().toString()))
                .andExpect(jsonPath("$.data.areaId").value(area.getId().toString()))
                .andExpect(jsonPath("$.data.minOrderAmount").value(15000))
                .andExpect(jsonPath("$.data.isHidden").value(false))
                .andExpect(jsonPath("$.data.createdBy").value("ownr01"));

        StoreEntity persisted = storeRepository
                .findByOwnerIdAndName(owner.getId(), "광화문 한식당").orElseThrow();
        assertThat(persisted.getOwnerId()).isEqualTo(owner.getId());
        assertThat(persisted.getCreatedBy()).isEqualTo("ownr01");
    }

    @Test
    @DisplayName("MASTER 가 가게 생성 시도 → 403 (POST 는 OWNER 전용)")
    void create_byMaster_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), area.getId(),
                "광화문 한식당", "서울 종로구 세종대로 1", null, 15000);

        authedPost("/api/v1/stores", token, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."));
    }

    @Test
    @DisplayName("CUSTOMER 가 가게 생성 시도 → 403")
    void create_byCustomer_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        seedUser("cust01", UserRole.CUSTOMER);
        String token = login("cust01", DEFAULT_PASSWORD);

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), area.getId(),
                "광화문 한식당", "서울 종로구 세종대로 1", null, 15000);

        authedPost("/api/v1/stores", token, body)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 상태에서 가게 생성 시도 → 403 (anonymous 에 대한 @PreAuthorize 거부)")
    void create_unauthenticated_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), area.getId(),
                "광화문 한식당", "서울 종로구 세종대로 1", null, 15000);

        unauthedPost("/api/v1/stores", body)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("동일 OWNER 가 동일 가게명으로 재생성 → 409 STORE_ALREADY_EXISTS")
    void create_duplicateNameForSameOwner_409() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        seedStore(owner.getId(), category.getId(), area.getId(), "광화문 한식당");

        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), area.getId(),
                "광화문 한식당", "서울 종로구 세종대로 99", null, 15000);

        authedPost("/api/v1/stores", token, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("해당 소유자가 이미 동일한 이름의 가게를 보유하고 있습니다."));
    }

    @Test
    @DisplayName("다른 OWNER 가 같은 가게명으로 등록 → 201 (동일 소유자 기준 unique)")
    void create_sameNameDifferentOwner_201() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        UserEntity ownerA = seedUser("ownr01", UserRole.OWNER);
        seedStore(ownerA.getId(), category.getId(), area.getId(), "광화문 한식당");

        seedUser("ownr02", UserRole.OWNER);
        String tokenB = login("ownr02", DEFAULT_PASSWORD);

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), area.getId(),
                "광화문 한식당", "서울 종로구 세종대로 22", null, 15000);

        authedPost("/api/v1/stores", tokenB, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("광화문 한식당"))
                .andExpect(jsonPath("$.data.createdBy").value("ownr02"));
    }

    @Test
    @DisplayName("존재하지 않는 categoryId 로 생성 → 404 CATEGORY_NOT_FOUND")
    void create_categoryNotFound_404() throws Exception {
        AreaEntity area = seedArea("광화문", true);

        seedUser("ownr01", UserRole.OWNER);
        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                UUID.randomUUID(), area.getId(),
                "광화문 한식당", "서울 종로구 세종대로 1", null, 15000);

        authedPost("/api/v1/stores", token, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("카테고리를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("존재하지 않는 areaId 로 생성 → 404 AREA_NOT_FOUND")
    void create_areaNotFound_404() throws Exception {
        CategoryEntity category = seedCategory("한식");

        seedUser("ownr01", UserRole.OWNER);
        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), UUID.randomUUID(),
                "광화문 한식당", "서울 종로구 세종대로 1", null, 15000);

        authedPost("/api/v1/stores", token, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("지역을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("비활성 area 로 생성 → 400 AREA_INACTIVE")
    void create_inactiveArea_400() throws Exception {
        AreaEntity area = seedArea("광화문", false);
        CategoryEntity category = seedCategory("한식");

        seedUser("ownr01", UserRole.OWNER);
        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), area.getId(),
                "광화문 한식당", "서울 종로구 세종대로 1", null, 15000);

        authedPost("/api/v1/stores", token, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("비활성화된 지역에는 가게를 등록하거나 이전할 수 없습니다."));
    }

    @Test
    @DisplayName("필수 필드 누락(name 빈 값) → 400 VALIDATION_ERROR + errors[name]")
    void create_blankName_400() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        seedUser("ownr01", UserRole.OWNER);
        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), area.getId(),
                "   ", "서울 종로구 세종대로 1", null, 15000);

        authedPost("/api/v1/stores", token, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field=='name')]").exists());
    }

    @Test
    @DisplayName("필수 필드 누락(minOrderAmount null) → 400 VALIDATION_ERROR")
    void create_nullMinOrderAmount_400() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        seedUser("ownr01", UserRole.OWNER);
        String token = login("ownr01", DEFAULT_PASSWORD);

        // record 직렬화 우회를 위해 raw JSON 사용 (minOrderAmount 키 자체를 누락)
        String raw = """
                {
                  "categoryId":"%s",
                  "areaId":"%s",
                  "name":"광화문 한식당",
                  "address":"서울 종로구 세종대로 1"
                }
                """.formatted(category.getId(), area.getId());

        mockMvc.perform(post("/api/v1/stores")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field=='minOrderAmount')]").exists());
    }

    @Test
    @DisplayName("phone 빈 문자열 입력 → 201 + DB 에는 phone null 로 저장")
    void create_blankPhone_storedAsNull() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), area.getId(),
                "광화문 한식당", "서울 종로구 세종대로 1", "", 15000);

        authedPost("/api/v1/stores", token, body)
                .andExpect(status().isCreated());

        StoreEntity persisted = storeRepository
                .findByOwnerIdAndName(owner.getId(), "광화문 한식당").orElseThrow();
        assertThat(persisted.getPhone()).isNull();
    }

    // ────────────────────────────────────────────────
    // PATCH /api/v1/stores/{storeId} (정보 수정)
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("OWNER 본인이 자신의 가게 수정 → 200 + DB 반영")
    void update_byOwnerSelf_200() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedStore(owner.getId(), category.getId(), area.getId(), "광화문 한식당");

        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqUpdateStoreDto body = new ReqUpdateStoreDto(
                category.getId(), area.getId(),
                "광화문 한식당(리뉴얼)", "서울 종로구 세종대로 11", "02-9999-9999", 20000);

        authedPatch("/api/v1/stores/" + store.getId(), token, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("광화문 한식당(리뉴얼)"))
                .andExpect(jsonPath("$.data.minOrderAmount").value(20000));

        StoreEntity reloaded = storeRepository.findById(store.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("광화문 한식당(리뉴얼)");
        assertThat(reloaded.getAddress()).isEqualTo("서울 종로구 세종대로 11");
        assertThat(reloaded.getPhone()).isEqualTo("02-9999-9999");
        assertThat(reloaded.getMinOrderAmount()).isEqualTo(20000);
    }

    @Test
    @DisplayName("MANAGER 가 타인 가게 수정 → 200")
    void update_byManager_200() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedStore(owner.getId(), category.getId(), area.getId(), "원래가게명");

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqUpdateStoreDto body = new ReqUpdateStoreDto(
                category.getId(), area.getId(),
                "관리자수정", "서울 종로구 세종대로 1", null, 10000);

        authedPatch("/api/v1/stores/" + store.getId(), token, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("관리자수정"));
    }

    @Test
    @DisplayName("MASTER 가 타인 가게 수정 → 200")
    void update_byMaster_200() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "원래가게명");

        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        ReqUpdateStoreDto body = new ReqUpdateStoreDto(
                category.getId(), area.getId(),
                "마스터수정", "서울 종로구 세종대로 1", null, 10000);

        authedPatch("/api/v1/stores/" + store.getId(), token, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("마스터수정"));
    }

    @Test
    @DisplayName("OWNER 가 타인의 가게 수정 시도 → 403 STORE_ACCESS_DENIED")
    void update_byOtherOwner_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        UserEntity ownerA = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedStore(ownerA.getId(), category.getId(), area.getId(), "원래가게명");

        seedUser("ownr02", UserRole.OWNER);
        String tokenB = login("ownr02", DEFAULT_PASSWORD);

        ReqUpdateStoreDto body = new ReqUpdateStoreDto(
                category.getId(), area.getId(),
                "타인수정시도", "서울 종로구 세종대로 1", null, 10000);

        authedPatch("/api/v1/stores/" + store.getId(), tokenB, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("해당 가게에 대한 권한이 없습니다."));

        StoreEntity reloaded = storeRepository.findById(store.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("원래가게명");
    }

    @Test
    @DisplayName("CUSTOMER 가 가게 수정 시도 → 403 (PreAuthorize)")
    void update_byCustomer_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");

        seedUser("cust01", UserRole.CUSTOMER);
        String token = login("cust01", DEFAULT_PASSWORD);

        ReqUpdateStoreDto body = new ReqUpdateStoreDto(
                category.getId(), area.getId(),
                "수정시도", "서울 종로구 세종대로 1", null, 10000);

        authedPatch("/api/v1/stores/" + store.getId(), token, body)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 상태 PATCH → 403 (anonymous 에 대한 @PreAuthorize 거부)")
    void update_unauthenticated_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");

        ReqUpdateStoreDto body = new ReqUpdateStoreDto(
                category.getId(), area.getId(),
                "수정시도", "서울 종로구 세종대로 1", null, 10000);

        mockMvc.perform(patch("/api/v1/stores/" + store.getId())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("존재하지 않는 storeId 수정 → 404")
    void update_storeNotFound_404() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        ReqUpdateStoreDto body = new ReqUpdateStoreDto(
                category.getId(), area.getId(),
                "어떤가게", "서울", null, 10000);

        authedPatch("/api/v1/stores/" + UUID.randomUUID(), token, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("가게를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("동일 OWNER 의 다른 가게와 동일한 이름으로 수정 → 409")
    void update_duplicateNameInSameOwner_409() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        UserEntity owner = seedUser("ownr01", UserRole.OWNER);

        seedStore(owner.getId(), category.getId(), area.getId(), "가게A");
        StoreEntity target = seedStore(owner.getId(), category.getId(), area.getId(), "가게B");

        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqUpdateStoreDto body = new ReqUpdateStoreDto(
                category.getId(), area.getId(),
                "가게A", "서울", null, 10000);

        authedPatch("/api/v1/stores/" + target.getId(), token, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("해당 소유자가 이미 동일한 이름의 가게를 보유하고 있습니다."));
    }

    @Test
    @DisplayName("같은 이름으로 수정(자기 자신) → 중복 검증 통과 200")
    void update_sameName_200() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedStore(owner.getId(), category.getId(), area.getId(), "광화문 한식당");

        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqUpdateStoreDto body = new ReqUpdateStoreDto(
                category.getId(), area.getId(),
                "광화문 한식당", "서울 종로구 세종대로 999", null, 18000);

        authedPatch("/api/v1/stores/" + store.getId(), token, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address").value("서울 종로구 세종대로 999"))
                .andExpect(jsonPath("$.data.minOrderAmount").value(18000));
    }

    @Test
    @DisplayName("비활성 area 로 수정 → 400 AREA_INACTIVE")
    void update_inactiveArea_400() throws Exception {
        AreaEntity activeArea = seedArea("광화문", true);
        AreaEntity inactiveArea = seedArea("강남역", false);
        CategoryEntity category = seedCategory("한식");

        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedStore(owner.getId(), category.getId(), activeArea.getId(), "광화문 한식당");

        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqUpdateStoreDto body = new ReqUpdateStoreDto(
                category.getId(), inactiveArea.getId(),
                "광화문 한식당", "서울 종로구 세종대로 1", null, 10000);

        authedPatch("/api/v1/stores/" + store.getId(), token, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("비활성화된 지역에는 가게를 등록하거나 이전할 수 없습니다."));
    }

    // ────────────────────────────────────────────────
    // DELETE /api/v1/stores/{storeId}
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("OWNER 본인이 자신의 가게 삭제 → 200 + soft delete 반영(deletedAt/deletedBy)")
    void delete_byOwnerSelf_softDeletes() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");

        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedStore(owner.getId(), category.getId(), area.getId(), "광화문 한식당");

        String token = login("ownr01", DEFAULT_PASSWORD);

        authedDelete("/api/v1/stores/" + store.getId(), token)
                .andExpect(status().isOk());

        // findById 는 살아있는 row 만 보므로(StoreRepositoryImpl.findByIdAndDeletedAtIsNull) 노출되지 않음
        assertThat(storeRepository.findById(store.getId())).isEmpty();
        // 동일 OWNER 기준 살아있는 가게도 더 이상 보이지 않음
        assertThat(storeRepository.findByOwnerIdAndName(owner.getId(), "광화문 한식당")).isEmpty();
    }

    @Test
    @DisplayName("MASTER 가 타인 가게 삭제 → 200")
    void delete_byMaster_200() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");

        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        authedDelete("/api/v1/stores/" + store.getId(), token)
                .andExpect(status().isOk());

        assertThat(storeRepository.findById(store.getId())).isEmpty();
    }

    @Test
    @DisplayName("MANAGER 가 가게 삭제 시도 → 403 (DELETE 는 OWNER/MASTER 전용)")
    void delete_byManager_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        authedDelete("/api/v1/stores/" + store.getId(), token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."));

        // 트랜잭션 내 실제로 삭제되지 않았는지 확인
        assertThat(storeRepository.findById(store.getId())).isPresent();
    }

    @Test
    @DisplayName("OWNER 가 타인의 가게 삭제 시도 → 403 STORE_ACCESS_DENIED")
    void delete_byOtherOwner_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        UserEntity ownerA = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedStore(ownerA.getId(), category.getId(), area.getId(), "광화문 한식당");

        seedUser("ownr02", UserRole.OWNER);
        String tokenB = login("ownr02", DEFAULT_PASSWORD);

        authedDelete("/api/v1/stores/" + store.getId(), tokenB)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("해당 가게에 대한 권한이 없습니다."));

        assertThat(storeRepository.findById(store.getId())).isPresent();
    }

    @Test
    @DisplayName("CUSTOMER 가 가게 삭제 시도 → 403 (PreAuthorize)")
    void delete_byCustomer_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");

        seedUser("cust01", UserRole.CUSTOMER);
        String token = login("cust01", DEFAULT_PASSWORD);

        authedDelete("/api/v1/stores/" + store.getId(), token)
                .andExpect(status().isForbidden());

        assertThat(storeRepository.findById(store.getId())).isPresent();
    }

    @Test
    @DisplayName("미인증 상태 DELETE 요청 → 403 (anonymous 에 대한 @PreAuthorize 거부)")
    void delete_unauthenticated_403() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        StoreEntity store = seedStore(UUID.randomUUID(), category.getId(), area.getId(), "광화문 한식당");

        mockMvc.perform(delete("/api/v1/stores/" + store.getId()))
                .andExpect(status().isForbidden());

        assertThat(storeRepository.findById(store.getId())).isPresent();
    }

    @Test
    @DisplayName("존재하지 않는 storeId 삭제 → 404 STORE_NOT_FOUND")
    void delete_storeNotFound_404() throws Exception {
        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        authedDelete("/api/v1/stores/" + UUID.randomUUID(), token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("가게를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("삭제 성공 후 단건 조회 → 404")
    void getAfterDelete_404() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedStore(owner.getId(), category.getId(), area.getId(), "광화문 한식당");

        String token = login("ownr01", DEFAULT_PASSWORD);

        authedDelete("/api/v1/stores/" + store.getId(), token)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/stores/{id}", store.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("가게를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("삭제 성공 후 동일 OWNER 가 같은 이름으로 재생성 → 201 (중복 검증은 살아있는 row 만)")
    void recreateAfterDelete_201() throws Exception {
        AreaEntity area = seedArea("광화문", true);
        CategoryEntity category = seedCategory("한식");
        UserEntity owner = seedUser("ownr01", UserRole.OWNER);
        StoreEntity store = seedStore(owner.getId(), category.getId(), area.getId(), "광화문 한식당");

        String token = login("ownr01", DEFAULT_PASSWORD);

        // given: 기존 가게 삭제
        authedDelete("/api/v1/stores/" + store.getId(), token)
                .andExpect(status().isOk());

        // when & then: 동일 OWNER 가 같은 이름으로 재생성 가능
        ReqCreateStoreDto body = new ReqCreateStoreDto(
                category.getId(), area.getId(),
                "광화문 한식당", "서울 종로구 세종대로 1", null, 15000);

        authedPost("/api/v1/stores", token, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("광화문 한식당"))
                .andExpect(jsonPath("$.data.createdBy").value("ownr01"));
    }

    // ────────────────────────────────────────────────
    // private helpers (베이스 미수정 정책에 따라 클래스 로컬)
    // ────────────────────────────────────────────────

    private ResultActions authedPost(String url, String token, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions unauthedPost(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

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
}

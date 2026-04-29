package com.example.delivery.area.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.delivery.area.domain.entity.AreaEntity;
import com.example.delivery.area.domain.repository.AreaRepository;
import com.example.delivery.area.presentation.dto.request.ReqCreateAreaDto;
import com.example.delivery.area.presentation.dto.request.ReqUpdateAreaDto;
import com.example.delivery.global.test.IntegrationTestSupport;
import com.example.delivery.user.domain.entity.UserRole;
import java.util.Optional;
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
 * Area CRUD 통합 테스트 — 권한 매트릭스 + Validation + DB 영속 + 예외 핸들러까지의 흐름 검증.
 *
 * 정책 메모
 * - SecurityConfig 에서 /api/v1/areas/** 는 anyRequest().permitAll() 에 해당하지만,
 *   변경 계열 엔드포인트는 컨트롤러의 @PreAuthorize 로 권한이 강제된다.
 *   따라서 미인증 POST/PATCH 의 응답은 ExceptionTranslationFilter 가 아니라
 *   GlobalExceptionHandler.handleAccessDenied 를 통한 403 으로 정규화된다.
 *   (UserAuthSmokeIntegrationTest 의 401 흐름과 다른 점에 주의)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AreaCrudIntegrationTest extends IntegrationTestSupport {

    @Autowired
    AreaRepository areaRepository;

    // ────────────────────────────────────────────────
    // POST /api/v1/areas
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("MANAGER 가 지역 생성 → 201 + DB 반영 + createdBy 설정")
    void create_byManager_201() throws Exception {
        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqCreateAreaDto body = new ReqCreateAreaDto("광화문", "서울특별시", "종로구", true);

        authedPost("/api/v1/areas", token, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("광화문"))
                .andExpect(jsonPath("$.data.city").value("서울특별시"))
                .andExpect(jsonPath("$.data.district").value("종로구"))
                .andExpect(jsonPath("$.data.isActive").value(true))
                .andExpect(jsonPath("$.data.createdBy").value("manager01"));

        Optional<AreaEntity> persisted = areaRepository.findByName("광화문");
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getCreatedBy()).isEqualTo("manager01");
    }

    @Test
    @DisplayName("MASTER 도 지역 생성 가능 → 201")
    void create_byMaster_201() throws Exception {
        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        ReqCreateAreaDto body = new ReqCreateAreaDto("강남역", "서울특별시", "강남구", true);

        authedPost("/api/v1/areas", token, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.createdBy").value("master01"));
    }

    @Test
    @DisplayName("CUSTOMER 가 지역 생성 시도 → 403 FORBIDDEN")
    void create_byCustomer_403() throws Exception {
        seedUser("cust01", UserRole.CUSTOMER);
        String token = login("cust01", DEFAULT_PASSWORD);

        ReqCreateAreaDto body = new ReqCreateAreaDto("강남역", "서울특별시", "강남구", true);

        authedPost("/api/v1/areas", token, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."));
    }

    @Test
    @DisplayName("OWNER 도 지역 생성 권한 없음 → 403")
    void create_byOwner_403() throws Exception {
        seedUser("ownr01", UserRole.OWNER);
        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqCreateAreaDto body = new ReqCreateAreaDto("강남역", "서울특별시", "강남구", true);

        authedPost("/api/v1/areas", token, body)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 상태에서 지역 생성 시도 → 403 (anonymous 에 대한 @PreAuthorize 거부)")
    void create_unauthenticated_403() throws Exception {
        ReqCreateAreaDto body = new ReqCreateAreaDto("강남역", "서울특별시", "강남구", true);

        unauthedPost("/api/v1/areas", body)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이미 존재하는 지역명으로 생성 → 409 AREA_ALREADY_EXISTS")
    void create_duplicateName_409() throws Exception {
        seedArea("강남역", "서울특별시", "강남구", true);

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqCreateAreaDto body = new ReqCreateAreaDto("강남역", "서울특별시", "강남구", true);

        authedPost("/api/v1/areas", token, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 존재하는 지역명입니다."));
    }

    @Test
    @DisplayName("이미 soft-delete 된 이름으로 재생성 → 400 AREA_ALREADY_DELETED")
    void create_softDeletedName_400() throws Exception {
        AreaEntity tobe = seedArea("강남역", "서울특별시", "강남구", true);
        tobe.softDelete("master01");
        areaRepository.save(tobe);

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqCreateAreaDto body = new ReqCreateAreaDto("강남역", "서울특별시", "강남구", true);

        authedPost("/api/v1/areas", token, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("삭제된 지역명입니다."));
    }

    @Test
    @DisplayName("필수 필드 누락(name 빈 값) → 400 VALIDATION_ERROR + errors[name]")
    void create_blankName_400() throws Exception {
        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqCreateAreaDto body = new ReqCreateAreaDto("   ", "서울특별시", "강남구", true);

        authedPost("/api/v1/areas", token, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field=='name')]").exists());
    }

    @Test
    @DisplayName("필수 필드 누락(isActive null) → 400 VALIDATION_ERROR")
    void create_nullIsActive_400() throws Exception {
        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        // record 직렬화 우회를 위해 raw JSON 사용
        String raw = """
                {"name":"강남역","city":"서울특별시","district":"강남구"}
                """;

        mockMvc.perform(post("/api/v1/areas")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(raw))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field=='isActive')]").exists());
    }

    // ────────────────────────────────────────────────
    // GET /api/v1/areas, GET /api/v1/areas/{id}
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("미인증 상태에서도 지역 단건 조회 가능(권한 없음) → 200")
    void getOne_unauthenticated_200() throws Exception {
        AreaEntity area = seedArea("광화문", "서울특별시", "종로구", true);

        mockMvc.perform(get("/api/v1/areas/{id}", area.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("광화문"))
                .andExpect(jsonPath("$.data.areaId").value(area.getId().toString()));
    }

    @Test
    @DisplayName("존재하지 않는 areaId 단건 조회 → 404 AREA_NOT_FOUND")
    void getOne_notFound_404() throws Exception {
        mockMvc.perform(get("/api/v1/areas/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("지역을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("키워드 부분일치 + 페이지네이션 → 매칭 항목만 반환")
    void getAll_keywordFilter_paginated() throws Exception {
        seedArea("광화문", "서울특별시", "종로구", true);
        seedArea("광교", "경기도", "수원시", true);
        seedArea("강남역", "서울특별시", "강남구", true);

        mockMvc.perform(get("/api/v1/areas")
                        .param("keyword", "광")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[?(@.name=='광화문')]").exists())
                .andExpect(jsonPath("$.data.content[?(@.name=='광교')]").exists());
    }

    @Test
    @DisplayName("허용되지 않은 page size(7) → 10 으로 보정")
    void getAll_invalidSize_normalizedToTen() throws Exception {
        seedArea("광화문", "서울특별시", "종로구", true);

        mockMvc.perform(get("/api/v1/areas")
                        .param("page", "0")
                        .param("size", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(10));
    }

    // ────────────────────────────────────────────────
    // PATCH /api/v1/areas/{id}
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("MANAGER 가 지역 수정 → 200 + DB 반영")
    void update_byManager_200() throws Exception {
        AreaEntity area = seedArea("광화문", "서울특별시", "종로구", true);

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqUpdateAreaDto body = new ReqUpdateAreaDto("광화문(개편)", "서울특별시", "종로구", false);

        authedPatch("/api/v1/areas/" + area.getId(), token, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("광화문(개편)"))
                .andExpect(jsonPath("$.data.isActive").value(false));

        AreaEntity reloaded = areaRepository.findById(area.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("광화문(개편)");
        assertThat(reloaded.isActive()).isFalse();
    }

    @Test
    @DisplayName("CUSTOMER 가 지역 수정 시도 → 403")
    void update_byCustomer_403() throws Exception {
        AreaEntity area = seedArea("광화문", "서울특별시", "종로구", true);

        seedUser("cust01", UserRole.CUSTOMER);
        String token = login("cust01", DEFAULT_PASSWORD);

        ReqUpdateAreaDto body = new ReqUpdateAreaDto("광화문2", "서울특별시", "종로구", true);

        authedPatch("/api/v1/areas/" + area.getId(), token, body)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("다른 살아있는 지역과 동일한 이름으로 수정 → 409")
    void update_duplicateName_409() throws Exception {
        AreaEntity target = seedArea("광화문", "서울특별시", "종로구", true);
        seedArea("강남역", "서울특별시", "강남구", true);

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqUpdateAreaDto body = new ReqUpdateAreaDto("강남역", "서울특별시", "종로구", true);

        authedPatch("/api/v1/areas/" + target.getId(), token, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 존재하는 지역명입니다."));
    }

    @Test
    @DisplayName("같은 이름으로 수정(자기 자신) → 중복 검증 통과 200")
    void update_sameName_200() throws Exception {
        AreaEntity area = seedArea("광화문", "서울특별시", "종로구", true);

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqUpdateAreaDto body = new ReqUpdateAreaDto("광화문", "서울특별시", "종로구", false);

        authedPatch("/api/v1/areas/" + area.getId(), token, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));
    }

    @Test
    @DisplayName("존재하지 않는 areaId 수정 → 404")
    void update_notFound_404() throws Exception {
        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqUpdateAreaDto body = new ReqUpdateAreaDto("강남역", "서울특별시", "강남구", true);

        authedPatch("/api/v1/areas/" + UUID.randomUUID(), token, body)
                .andExpect(status().isNotFound());
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

    private AreaEntity seedArea(String name, String city, String district, boolean isActive) {
        return areaRepository.save(AreaEntity.builder()
                .name(name)
                .city(city)
                .district(district)
                .isActive(isActive)
                .build());
    }
}

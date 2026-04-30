package com.example.delivery.category.integration;

import com.example.delivery.area.domain.entity.AreaEntity;
import com.example.delivery.area.presentation.dto.request.ReqCreateAreaDto;
import com.example.delivery.area.presentation.dto.request.ReqUpdateAreaDto;
import com.example.delivery.category.domain.entity.CategoryEntity;
import com.example.delivery.category.domain.repository.CategoryRepository;
import com.example.delivery.category.presentation.dto.request.ReqCreateCategoryDto;
import com.example.delivery.category.presentation.dto.request.ReqUpdateCategoryDto;
import com.example.delivery.global.test.IntegrationTestSupport;
import com.example.delivery.user.domain.entity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Category CRUD 통합 테스트 — 권한 매트릭스 + Validation + DB 영속 + 예외 핸들러까지의 흐름 검증.
 *
 * 정책 메모
 * - SecurityConfig 에서 /api/v1/categories/** 는 anyRequest().permitAll() 에 해당하지만,
 *   변경 계열 엔드포인트는 컨트롤러의 @PreAuthorize 로 권한이 강제된다.
 *   따라서 미인증 POST/PATCH 의 응답은 ExceptionTranslationFilter 가 아니라
 *   GlobalExceptionHandler.handleAccessDenied 를 통한 403 으로 정규화된다.
 *   (UserAuthSmokeIntegrationTest 의 401 흐름과 다른 점에 주의)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CategoryCrudIntegrationTest extends IntegrationTestSupport {

    @Autowired
    CategoryRepository categoryRepository;

    // ────────────────────────────────────────────────
    // POST /api/v1/categories
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("MANAGER 가 카테고리 생성 → 201 + DB 반영 + createdBy 설정")
    void create_byManager_201() throws Exception {
        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqCreateCategoryDto body = new ReqCreateCategoryDto("한식");

        authedPost("/api/v1/categories", token, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("한식"))
                .andExpect(jsonPath("$.data.createdBy").value("manager01"));

        Optional<CategoryEntity> persisted = categoryRepository.findByName("한식");
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getCreatedBy()).isEqualTo("manager01");
    }

    @Test
    @DisplayName("MASTER 도 카테고리 생성 가능 → 201")
    void create_byMaster_201() throws Exception {
        seedUser("master01", UserRole.MASTER);
        String token = login("master01", DEFAULT_PASSWORD);

        ReqCreateCategoryDto body = new ReqCreateCategoryDto("한식");

        authedPost("/api/v1/categories", token, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.createdBy").value("master01"));
    }

    @Test
    @DisplayName("CUSTOMER 가 카테고리 생성 시도 → 403 FORBIDDEN")
    void create_byCustomer_403() throws Exception {
        seedUser("cust01", UserRole.CUSTOMER);
        String token = login("cust01", DEFAULT_PASSWORD);

        ReqCreateCategoryDto body = new ReqCreateCategoryDto("한식");

        authedPost("/api/v1/categories", token, body)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."));
    }

    @Test
    @DisplayName("OWNER 도 카테고리 생성 권한 없음 → 403")
    void create_byOwner_403() throws Exception {
        seedUser("ownr01", UserRole.OWNER);
        String token = login("ownr01", DEFAULT_PASSWORD);

        ReqCreateCategoryDto body = new ReqCreateCategoryDto("한식");

        authedPost("/api/v1/categories", token, body)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 상태에서 카테고리 생성 시도 → 403 (anonymous 에 대한 @PreAuthorize 거부)")
    void create_unauthenticated_403() throws Exception {
        ReqCreateCategoryDto body = new ReqCreateCategoryDto("한식");

        unauthedPost("/api/v1/categories", body)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이미 존재하는 카테고리명으로 생성 → 409 CATEGORY_ALREADY_EXISTS")
    void create_duplicateName_409() throws Exception {
        seedCategory("한식");

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqCreateCategoryDto body = new ReqCreateCategoryDto("한식");

        authedPost("/api/v1/categories", token, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 존재하는 카테고리 이름입니다."));
    }

    @Test
    @DisplayName("이미 soft-delete 된 이름으로 재생성 → 400 CATEGORY_ALREADY_DELETED")
    void create_softDeletedName_400() throws Exception {
        CategoryEntity tobe = seedCategory("한식");
        tobe.softDelete("master01");
        categoryRepository.save(tobe);

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqCreateCategoryDto body = new ReqCreateCategoryDto("한식");

        authedPost("/api/v1/categories", token, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("삭제된 카테고리 이름입니다."));
    }

    @Test
    @DisplayName("필수 필드 누락(name 빈 값) → 400 VALIDATION_ERROR + errors[name]")
    void create_blankName_400() throws Exception {
        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqCreateCategoryDto body = new ReqCreateCategoryDto(" ");

        authedPost("/api/v1/categories", token, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field=='name')]").exists());
    }

    // ────────────────────────────────────────────────
    // GET /api/v1/categories, GET /api/v1/categories/{id}
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("미인증 상태에서도 카테고리 단건 조회 가능(권한 없음) → 200")
    void getOne_unauthenticated_200() throws Exception {
        CategoryEntity category = seedCategory("한식");

        mockMvc.perform(get("/api/v1/categories/{id}", category.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("한식"))
                .andExpect(jsonPath("$.data.categoryId").value(category.getId().toString()));
    }

    @Test
    @DisplayName("존재하지 않는 categoryId 단건 조회 → 404 CATEGORY_NOT_FOUND")
    void getOne_notFound_404() throws Exception {
        mockMvc.perform(get("/api/v1/categories/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("카테고리를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("키워드 부분일치 + 페이지네이션 → 매칭 항목만 반환")
    void getAll_keywordFilter_paginated() throws Exception {
        seedCategory("한식");
        seedCategory("중식");
        seedCategory("치킨");

        mockMvc.perform(get("/api/v1/categories")
                        .param("keyword", "식")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[?(@.name=='한식')]").exists())
                .andExpect(jsonPath("$.data.content[?(@.name=='중식')]").exists());
    }

    @Test
    @DisplayName("허용되지 않은 page size(7) → 10 으로 보정")
    void getAll_invalidSize_normalizedToTen() throws Exception {
        seedCategory("한식");

        mockMvc.perform(get("/api/v1/categories")
                        .param("page", "0")
                        .param("size", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(10));
    }

    // ────────────────────────────────────────────────
    // PATCH /api/v1/categories/{id}
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("MANAGER 가 카테고리 수정 → 200 + DB 반영")
    void update_byManager_200() throws Exception {
        CategoryEntity category = seedCategory("한식");

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqUpdateCategoryDto body = new ReqUpdateCategoryDto("한식(퓨전)");

        authedPatch("/api/v1/categories/" + category.getId(), token, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("한식(퓨전)"));

        CategoryEntity reloaded = categoryRepository.findById(category.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("한식(퓨전)");
    }

    @Test
    @DisplayName("CUSTOMER 가 카테고리 수정 시도 → 403")
    void update_byCustomer_403() throws Exception {
        CategoryEntity category = seedCategory("한식");

        seedUser("cust01", UserRole.CUSTOMER);
        String token = login("cust01", DEFAULT_PASSWORD);

        ReqUpdateCategoryDto body = new ReqUpdateCategoryDto("한식(퓨전)");

        authedPatch("/api/v1/categories/" + category.getId(), token, body)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("다른 살아있는 카테고리와 동일한 이름으로 수정 → 409")
    void update_duplicateName_409() throws Exception {
        CategoryEntity target = seedCategory("한식(퓨전)");
        seedCategory("한식");

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqUpdateCategoryDto body = new ReqUpdateCategoryDto("한식");

        authedPatch("/api/v1/categories/" + target.getId(), token, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 존재하는 카테고리 이름입니다."));
    }

    @Test
    @DisplayName("같은 이름으로 수정(자기 자신) → 중복 검증 통과 200")
    void update_sameName_200() throws Exception {
        CategoryEntity category = seedCategory("한식");

        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqUpdateCategoryDto body = new ReqUpdateCategoryDto("한식");

        authedPatch("/api/v1/categories/" + category.getId(), token, body)
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("존재하지 않는 categoryId 수정 → 404")
    void update_notFound_404() throws Exception {
        seedUser("manager01", UserRole.MANAGER);
        String token = login("manager01", DEFAULT_PASSWORD);

        ReqUpdateCategoryDto body = new ReqUpdateCategoryDto("한식");

        authedPatch("/api/v1/categories/" + UUID.randomUUID(), token, body)
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

    private CategoryEntity seedCategory(String name) {
        return categoryRepository.save(CategoryEntity.builder()
                .name(name)
                .build());
    }
}

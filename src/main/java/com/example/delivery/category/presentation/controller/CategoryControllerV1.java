package com.example.delivery.category.presentation.controller;

import com.example.delivery.category.application.service.CategoryServiceV1;
import com.example.delivery.category.presentation.dto.request.ReqCreateCategoryDto;
import com.example.delivery.category.presentation.dto.request.ReqUpdateCategoryDto;
import com.example.delivery.category.presentation.dto.response.ResCreateCategoryDto;
import com.example.delivery.category.presentation.dto.response.ResGetCategoryDto;
import com.example.delivery.global.common.response.ApiResponse;
import com.example.delivery.global.common.response.PageResponse;
import com.example.delivery.global.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Category", description = "카테고리 API")
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryControllerV1 {

    private final CategoryServiceV1 categoryService;

    @Operation(summary = "카테고리 생성", description = "MANAGER / MASTER 권한으로 카테고리를 생성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 본문 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "카테고리 이름 중복")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'MASTER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ResCreateCategoryDto> createCategory(@Valid @RequestBody ReqCreateCategoryDto request) {
        return ApiResponse.created(categoryService.createCategory(request));
    }

    @Operation(summary = "카테고리 목록 조회", description = "키워드로 필터링하여 카테고리 목록을 페이지네이션 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ApiResponse<PageResponse<ResGetCategoryDto>> getAllCategories(
            @Parameter(description = "검색 키워드 (이름 부분 일치)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "페이지 번호 (0부터 시작)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.ok(categoryService.getAllCategories(keyword, page, size));
    }

    @Operation(summary = "카테고리 단건 조회", description = "카테고리 ID로 단건 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "카테고리 없음")
    })
    @GetMapping("/{categoryId}")
    public ApiResponse<ResGetCategoryDto> getCategory(
            @Parameter(description = "카테고리 ID") @PathVariable UUID categoryId
    ) {
        return ApiResponse.ok(categoryService.getCategory(categoryId));
    }

    @Operation(summary = "카테고리 수정", description = "MANAGER / MASTER 권한으로 카테고리 이름을 수정합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 본문 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "카테고리 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "카테고리 이름 중복")
    })
    @PatchMapping("/{categoryId}")
    @PreAuthorize("hasAnyRole('MANAGER', 'MASTER')")
    public ApiResponse<ResGetCategoryDto> updateCategory(
            @Parameter(description = "카테고리 ID") @PathVariable UUID categoryId,
            @Valid @RequestBody ReqUpdateCategoryDto request
    ) {
        return ApiResponse.ok(categoryService.updateCategory(categoryId, request));
    }

    @Operation(summary = "카테고리 삭제(soft delete)",
            description = "MASTER 권한 전용. 사용 중인 가게가 있으면 삭제 거부됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "카테고리 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "사용 중인 가게가 있어 삭제 불가")
    })
    @DeleteMapping("/{categoryId}")
    @PreAuthorize("hasAnyRole('MASTER')")
    public ApiResponse<Void> deleteCategory(
            @Parameter(description = "카테고리 ID") @PathVariable UUID categoryId,
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        categoryService.deleteCategory(categoryId, userPrincipal.username());
        return ApiResponse.ok(null);
    }
}

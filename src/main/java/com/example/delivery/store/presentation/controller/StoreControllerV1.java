package com.example.delivery.store.presentation.controller;

import com.example.delivery.global.common.response.ApiResponse;
import com.example.delivery.global.common.response.PageResponse;
import com.example.delivery.global.infrastructure.security.UserPrincipal;
import com.example.delivery.store.application.service.StoreServiceV1;
import com.example.delivery.store.presentation.dto.request.ReqCreateStoreDto;
import com.example.delivery.store.presentation.dto.request.ReqUpdateStoreDto;
import com.example.delivery.store.presentation.dto.response.ResCreateStoreDto;
import com.example.delivery.store.presentation.dto.response.ResGetStoreDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Store", description = "가게 API")
@RestController
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
public class StoreControllerV1 {

    private final StoreServiceV1 storeService;

    @Operation(summary = "가게 생성", description = "OWNER 권한으로 신규 가게를 등록합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 본문 검증 실패 / 비활성 지역"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "OWNER 권한 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "카테고리/지역 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동일 소유자 내 가게명 중복")
    })
    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ResCreateStoreDto> createStore(
            @Valid @RequestBody ReqCreateStoreDto request,
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        return ApiResponse.created(storeService.createStore(request, userPrincipal.id()));
    }

    @Operation(summary = "가게 목록 조회",
            description = """
                    키워드/카테고리/지역으로 필터링하여 가게 목록을 페이지네이션 조회합니다.

                    정렬 가능 필드: `createdAt`, `updatedAt`, `name`, `averageRating`
                    예) `?sort=averageRating,desc&sort=name,asc` (멀티 정렬 가능, 화이트리스트 외 필드는 무시)
                    페이지 크기는 `10`, `30`, `50`만 허용되며 그 외 값은 `10`으로 보정됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ApiResponse<PageResponse<ResGetStoreDto>> getAllStores(
            @Parameter(description = "검색 키워드 (가게명/주소 부분 일치)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "카테고리 ID")
            @RequestParam(required = false) UUID categoryId,
            @Parameter(description = "지역 ID")
            @RequestParam(required = false) UUID areaId,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.ok(storeService.getAllStores(keyword, categoryId, areaId, pageable));
    }

    @Operation(summary = "가게 단건 조회",
            description = "가게 ID로 단건 조회. 숨김 가게는 OWNER(본인)/MANAGER/MASTER 만 조회 가능, 그 외는 404로 처리됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "가게 없음 또는 숨김 처리됨")
    })
    @GetMapping("/{storeId}")
    public ApiResponse<ResGetStoreDto> getStore(
            @Parameter(description = "가게 ID") @PathVariable UUID storeId,
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        return ApiResponse.ok(storeService.getStore(storeId, userPrincipal));
    }

    @Operation(summary = "가게 정보 수정",
            description = "OWNER(본인) / MANAGER / MASTER 가 가게 정보를 수정합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 본문 검증 실패 / 비활성 지역"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "가게/카테고리/지역 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "동일 소유자 내 가게명 중복")
    })
    @PatchMapping("/{storeId}")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'MASTER')")
    public ApiResponse<ResGetStoreDto> updateStore(
            @Parameter(description = "가게 ID") @PathVariable UUID storeId,
            @Valid @RequestBody ReqUpdateStoreDto request,
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        return ApiResponse.ok(storeService.updateStore(storeId, request, userPrincipal));
    }

    @Operation(summary = "가게 숨김 토글",
            description = "OWNER(본인) / MANAGER / MASTER 가 가게의 숨김 여부를 토글합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "토글 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "가게 없음")
    })
    @PatchMapping("/{storeId}/hide")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER', 'MASTER')")
    public ApiResponse<ResGetStoreDto> toggleHiddenStore(
            @Parameter(description = "가게 ID") @PathVariable UUID storeId,
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        return ApiResponse.ok(storeService.toggleHiddenStore(storeId, userPrincipal));
    }

    @Operation(summary = "가게 삭제(soft delete)",
            description = "OWNER(본인) / MASTER 만 삭제 가능합니다. (MANAGER 불가)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "가게 없음")
    })
    @DeleteMapping("/{storeId}")
    @PreAuthorize("hasAnyRole('OWNER', 'MASTER')")
    public ApiResponse<Void> deleteStore(
            @Parameter(description = "가게 ID") @PathVariable UUID storeId,
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        storeService.deleteStore(storeId, userPrincipal);
        return ApiResponse.ok(null);
    }
}

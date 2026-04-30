package com.example.delivery.area.presentation.controller;

import com.example.delivery.area.application.service.AreaServiceV1;
import com.example.delivery.area.presentation.dto.request.ReqCreateAreaDto;
import com.example.delivery.area.presentation.dto.request.ReqUpdateAreaDto;
import com.example.delivery.area.presentation.dto.response.ResCreateAreaDto;
import com.example.delivery.area.presentation.dto.response.ResGetAreaDto;
import com.example.delivery.global.common.response.ApiResponse;
import com.example.delivery.global.common.response.PageResponse;
import com.example.delivery.global.infrastructure.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Tag(name = "Area", description = "지역 API")
@RestController
@RequestMapping("/api/v1/areas")
@RequiredArgsConstructor
public class AreaControllerV1 {

    private final AreaServiceV1 areaService;

    @Operation(summary = "지역 생성", description = "MANAGER / MASTER 권한으로 지역을 생성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 본문 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "지역명 중복")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'MASTER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ResCreateAreaDto> createArea(@Valid @RequestBody ReqCreateAreaDto request) {
        return ApiResponse.created(areaService.createArea(request));
    }

    @Operation(summary = "지역 목록 조회", description = "키워드로 필터링하여 지역 목록을 페이지네이션 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ApiResponse<PageResponse<ResGetAreaDto>> getAllAreas(
            @Parameter(description = "검색 키워드 (이름/시·도/구·군 부분 일치)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "페이지 번호 (0부터 시작)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기")
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.ok(areaService.getAllAreas(keyword, page, size));
    }

    @Operation(summary = "지역 단건 조회", description = "지역 ID로 단건 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "지역 없음")
    })
    @GetMapping("/{areaId}")
    public ApiResponse<ResGetAreaDto> getArea(
            @Parameter(description = "지역 ID") @PathVariable UUID areaId
    ) {
        return ApiResponse.ok(areaService.getArea(areaId));
    }

    @Operation(summary = "지역 수정", description = "MANAGER / MASTER 권한으로 지역 정보를 수정합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 본문 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "지역 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "지역명 중복")
    })
    @PatchMapping("/{areaId}")
    @PreAuthorize("hasAnyRole('MANAGER', 'MASTER')")
    public ApiResponse<ResGetAreaDto> updateArea(
            @Parameter(description = "지역 ID") @PathVariable UUID areaId,
            @Valid @RequestBody ReqUpdateAreaDto request
    ) {
        return ApiResponse.ok(areaService.updateArea(areaId, request));
    }

    @Operation(summary = "지역 삭제(soft delete)",
            description = "MASTER 권한 전용. 사용 중인 가게가 있으면 삭제 거부됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "지역 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "사용 중인 가게가 있어 삭제 불가")
    })
    @DeleteMapping("/{areaId}")
    @PreAuthorize("hasAnyRole('MASTER')")
    public ApiResponse<Void> deleteArea(
            @Parameter(description = "지역 ID") @PathVariable UUID areaId,
            @AuthenticationPrincipal UserPrincipal userPrincipal
    ) {
        areaService.deleteArea(areaId, userPrincipal.username());
        return ApiResponse.ok(null);
    }
}

# 04. API 명세

> 이전: [데이터 명세](03-data-spec.md) · 다음: [서비스 로직 명세](05-service-spec.md)

---

## 1. 공통 규약

- **Base URL**: `http://{SERVER_URL}/api/v1`
- **인증**: `Authorization: Bearer {JWT}` (회원가입 · 로그인 제외)
- **Content-Type**: `application/json`
- **페이지네이션**: `page`, `size`(10/30/50만 허용, 그 외는 10), `sort`(기본 `createdAt,DESC`)
- **권한 재검증**: 매 요청 JWT payload role ↔ DB role 비교
- **API 문서**: springdoc-openapi(Swagger UI) 기반. 실행 후 `/swagger-ui/index.html`에서 조회

### 응답 포맷

**성공**

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    ...
  }
}
```

**에러**

```json
{
  "status": 400,
  "message": "VALIDATION_ERROR",
  "errors": [
    {
      "field": "username",
      "message": "..."
    }
  ]
}
```

**페이지네이션**

```json
{
  "status": 200,
  "message": "SUCCESS",
  "data": {
    "content": [
      ...
    ],
    "page": 0,
    "size": 10,
    "totalElements": 42,
    "totalPages": 5,
    "sort": "createdAt,DESC"
  }
}
```

## 2. 엔드포인트 목록

### 2.1 Auth `/api/v1/auth`

| Method | Path      | 설명           | 권한  |
|--------|-----------|--------------|-----|
| POST   | `/signup` | 회원가입         | 비인증 |
| POST   | `/login`  | 로그인 → JWT 발급 | 비인증 |

### 2.2 User `/api/v1/users`

| Method | Path               | 설명                    | 권한                |
|--------|--------------------|-----------------------|-------------------|
| GET    | `/`                | 목록 (keyword, role 필터) | MANAGER/MASTER    |
| GET    | `/{username}`      | 상세                    | 본인/MANAGER/MASTER |
| PATCH  | `/{username}`      | 정보 수정                 | 본인/MANAGER/MASTER |
| DELETE | `/{username}`      | Soft Delete           | MANAGER/MASTER    |
| PATCH  | `/{username}/role` | 권한 변경                 | MASTER            |

수정 가능 필드(본인): nickname, email, password, is_public / MANAGER·MASTER는 password 제외 / role은 MASTER 전용

> **Privileged 대상 예외 규칙**: 대상 계정 role이 `MANAGER` 또는 `MASTER`인 경우, 위 표에서 `MANAGER/MASTER`에 해당하던 권한은 **본인 또는 MASTER만** 허용된다. 즉 MANAGER끼리는 서로 **조회/수정/삭제/role 변경이 모두 불가**하며, privileged 계정의 CUD는 사실상 MASTER 전용이다. (role 변경은 본 규칙과 무관하게 항상 MASTER 전용이고 본인 변경은 항상 금지.)

> 상세 조회(`GET /{username}`)는 위 권한 규칙을 따르며, `is_public`은 현재 조회 범위를 제어하지 않는다. 저장·수정만 허용되는 예약 필드로, 향후 공개 프로필 엔드포인트 도입 시 활용한다.

### 2.3 Area `/api/v1/areas`

| Method | Path        | 설명           | 권한             |
|--------|-------------|--------------|----------------|
| POST   | `/`         | 등록           | MANAGER/MASTER |
| GET    | `/`         | 목록 (keyword) | ALL            |
| GET    | `/{areaId}` | 상세           | ALL            |
| PATCH  | `/{areaId}` | 수정           | MANAGER/MASTER |
| DELETE | `/{areaId}` | Soft Delete  | MASTER         |

### 2.4 Category `/api/v1/categories`

| Method | Path            | 설명           | 권한             |
|--------|-----------------|--------------|----------------|
| POST   | `/`             | 등록           | MANAGER/MASTER |
| GET    | `/`             | 목록 (keyword) | ALL            |
| GET    | `/{categoryId}` | 상세           | ALL            |
| PATCH  | `/{categoryId}` | 수정           | MANAGER/MASTER |
| DELETE | `/{categoryId}` | Soft Delete  | MASTER         |

### 2.5 Store `/api/v1/stores`

| Method | Path              | 설명                                      | 권한                       |
|--------|-------------------|-----------------------------------------|--------------------------|
| POST   | `/`               | 가게 등록                                   | OWNER                    |
| GET    | `/`               | 목록(평점 포함, keyword/categoryId/areaId 필터) | ALL                      |
| GET    | `/{storeId}`      | 상세(평점 포함)                               | ALL                      |
| PATCH  | `/{storeId}`      | 수정                                      | OWNER(본인)/MANAGER/MASTER |
| DELETE | `/{storeId}`      | Soft Delete                             | OWNER(본인)/MASTER         |
| PATCH  | `/{storeId}/hide` | 숨김 토글                                   | OWNER(본인)/MANAGER/MASTER |

### 2.6 Menu `/api/v1/...`

| Method | Path                         | 설명            | 권한                       |
|--------|------------------------------|---------------|--------------------------|
| POST   | `/stores/{storeId}/menus`    | 메뉴 등록 (AI 옵션) | OWNER(본인)                |
| GET    | `/stores/{storeId}/menus`    | 가게 메뉴 목록      | ALL                      |
| GET    | `/menus/{menuId}`            | 메뉴 상세         | ALL                      |
| PATCH  | `/menus/{menuId}`            | 수정            | OWNER(본인)/MANAGER/MASTER |
| DELETE | `/menus/{menuId}`            | Soft Delete   | OWNER(본인)/MASTER         |
| PATCH  | `/menus/{menuId}/visibility` | 숨김 토글         | OWNER(본인)/MANAGER/MASTER |
| PATCH  | `/menus/{menuId}/sold-out`   | 메뉴 품절 토글      | OWNER(본인)/MANAGER/MASTER |
| PATCH  | `/menus/{menuId}/restore`    | 삭제된 메뉴 복구     | OWNER(본인)/MANAGER/MASTER |

> [메뉴 노출 기준 (Query DSL 적용)]
> 목록 조회 (`GET /stores/{storeOd}/menus`) 및 상세 조회 (`GET /menus/{menuId}`) 시 요청자의 권한에 따라 노출되는 데이터가 다릅니다.
> CUSTOMER(일반 유저): 속한 가게(Store)와 메뉴(Menu)가 모두 `deletedAt == null`이고 `isHidden == false`인 정상 데이터만 조회 가능합니다.
> OWNER / MANAGER / MASTER : 숨김(isHidden) 또는 소프트 삭제(deletedAt) 처리된 내역도 조회 가능합니다. (단, OWNER는 본인 소유 가게에 한함)

### 2.7 Order `/api/v1/orders`

| Method | Path                | 설명                     | 권한                             |
|--------|---------------------|------------------------|--------------------------------|
| POST   | `/`                 | 주문 생성                  | CUSTOMER                       |
| GET    | `/`                 | 목록(storeId, status 필터) | 본인/OWNER(본인 가게)/MANAGER/MASTER |
| GET    | `/{orderId}`        | 상세                     | 본인/OWNER(본인 가게)/MANAGER/MASTER |
| PATCH  | `/{orderId}`        | 요청사항 수정                | CUSTOMER(본인, PENDING만)/MASTER  |
| PATCH  | `/{orderId}/status` | 상태 변경                  | OWNER(본인)/MANAGER/MASTER       |
| PATCH  | `/{orderId}/cancel` | 취소(5분 이내)              | CUSTOMER(본인)/MASTER            |
| DELETE | `/{orderId}`        | Soft Delete            | MASTER                         |

### 2.8 Payment `/api/v1/...`

| Method | Path                         | 설명          | 권한                |
|--------|------------------------------|-------------|-------------------|
| POST   | `/orders/{orderId}/payments` | 결제 처리       | CUSTOMER(본인)      |
| GET    | `/payments`                  | 목록          | 본인/MANAGER/MASTER |
| GET    | `/payments/{paymentId}`      | 상세          | 본인/MANAGER/MASTER |
| PATCH  | `/payments/{paymentId}`      | 상태 수정       | MANAGER/MASTER    |
| DELETE | `/payments/{paymentId}`      | Soft Delete | MASTER            |

### 2.9 Review `/api/v1/...`

| Method | Path                        | 설명                | 권한                      |
|--------|-----------------------------|-------------------|-------------------------|
| POST   | `/orders/{orderId}/reviews` | 리뷰 작성             | CUSTOMER(본인, COMPLETED) |
| GET    | `/stores/{storeId}/reviews` | 목록(rating 필터)     | ALL                     |
| GET    | `/reviews/{reviewId}`       | 상세                | ALL                     |
| PATCH  | `/reviews/{reviewId}`       | 수정                | CUSTOMER(본인)            |
| DELETE | `/reviews/{reviewId}`       | Soft Delete       | 본인/MANAGER/MASTER       |

### 2.10 Address `/api/v1/addresses`

| Method | Path                   | 설명          | 권한                  |
|--------|------------------------|-------------|---------------------|
| POST   | `/`                    | 등록          | CUSTOMER            |
| GET    | `/`                    | 내 목록        | CUSTOMER(본인)        |
| GET    | `/{addressId}`         | 상세          | CUSTOMER(본인)        |
| PATCH  | `/{addressId}`         | 수정          | CUSTOMER(본인)        |
| DELETE | `/{addressId}`         | Soft Delete | CUSTOMER(본인)/MASTER |
| PATCH  | `/{addressId}/default` | 기본 배송지 설정   | CUSTOMER(본인)        |

### 2.11 AI `/api/v1/ai`

| Method | Path                                    | 설명                    | 권한    |
|--------|-----------------------------------------|-----------------------|-------|
| POST   | `/api/v1/menus/{menuId}/ai-description` | 특정 메뉴의 AI 설명 생성       | OWNER |
| PATCH  | `/api/v1/ai/logs/{aiLogId}/apply`       | 생성된 AI 설명을 메뉴에 실제 반영  | OWNER |
→ 향후 메뉴 외에 리뷰, 가게 홍보 등 새로운 AI 기능 추가 시, 해당 도메인의 하위 리소스로 API를 설계하여 도메인 간 독립성을 유지할 예정
→ 예: POST /api/v1/reviews/{reviewId}/ai-summary (리뷰 요약)


## 3. 대표 요청/응답 예시

### 3.1 회원가입

```http
POST /api/v1/auth/signup
{
  "username": "user01",
  "password": "Password1!",
  "nickname": "홍길동",
  "email": "user01@example.com",
  "role": "CUSTOMER"
}
```

→ `201 CREATED` · `data`: { username, nickname, email, role, createdAt }

### 3.2 로그인

```http
POST /api/v1/auth/login
{ "username": "user01", "password": "Password1!" }
```

→ `data`: { accessToken, username, role }

### 3.3 가게 등록

```http
POST /api/v1/stores
Authorization: Bearer {JWT}
{
  "name": "맛있는 한식당",
  "categoryId": "550e8400-...-440001",
  "areaId": "550e8400-...-440010",
  "address": "서울시 종로구 광화문로 123",
  "phone": "02-1234-5678"
}
```

### 3.4 메뉴 등록 (AI 설명 자동 생성)

```http
POST /api/v1/stores/{storeId}/menus
{
  "name": "특제 만두",
  "price": 12000,
  "description": "",
  "aiDescription": true,
  "aiPrompt": "만두 상품의 매력적인 설명을 작성해줘"
}
```

→ 서버가 Gemini 호출 → description 자동 채움 → `p_ai_request_log` 기록

### 3.5 주문 생성

```http
POST /api/v1/orders
{
  "storeId": "...440002",
  "addressId": "...440020",
  "orderType": "ONLINE",
  "request": "덜 맵게 해주세요",
  "items": [
    { "menuId": "...440100", "quantity": 2 },
    { "menuId": "...440101", "quantity": 1 }
  ]
}
```

### 3.6 주문 취소 (5분 제한)

```http
PATCH /api/v1/orders/{orderId}/cancel
```

- 조건: `now() - order.createdAt ≤ 5분`
- 초과 시: `400 { "message": "주문 생성 후 5분이 경과하여 취소할 수 없습니다." }`

### 3.7 결제

```http
POST /api/v1/orders/{orderId}/payments
{ "paymentMethod": "CARD" }
```

### 3.8 리뷰 작성

```http
POST /api/v1/orders/{orderId}/reviews
{ "rating": 5, "content": "음식이 정말 맛있었습니다!" }
```

- 조건: 주문 `COMPLETED` + 본인 + 미작성

### 3.9 배송지 등록

```http
POST /api/v1/addresses
{
  "alias": "집",
  "address": "서울시 종로구 세종대로 172",
  "detail": "101동 1001호",
  "zipCode": "03154"
}
```

### 3.10 AI 상품 설명

-1. 설명 생성 요청
```http
POST /api/v1/menus/{menuId}/ai-description
{ "prompt": "매콤하고 바삭한 양념치킨의 특징을 살려줘" }
```
→ 서버가 prompt 끝에 `"답변을 최대한 간결하게 50자 이하로"` 자동 삽입
→ p_ai_request_log에 is_applied=false 상태로 기록 저장
→ `data`: { "aiLogId": "7fa93d23-5204-4cab-a044-0dd21aec16a2",
"prompt": "매콤하고 바삭한 양념치킨의 특징을 살려줘", "description": "입안 가득 퍼지는 소스와 바삭한 치킨의 조화" }

-2. 확정 및 메뉴 반영
```http
PATCH /api/v1/ai/logs/{aiLogId}/apply
Authorization : Bearer {JWT}
{
    "aiLogId": "7fa93d23-5204-4cab-a044-0dd21aec16a2",
    "description": "사장님이 직접 수정한 치킨!"
```
→ 해당 로그의 is_applied를 true로 변경
→ 연관된 Menu의 description 필드를 responseText 값으로 업데이트
→ 200 SUCCESS 반환

## 4. 에러 코드 (요약)

| 상태  | message 예시             | 조건               |
|-----|------------------------|------------------|
| 400 | `VALIDATION_ERROR`     | DTO 유효성 실패       |
| 400 | `ORDER_CANCEL_TIMEOUT` | 주문 5분 초과 취소      |
| 400 | `INVALID_ORDER_STATUS` | 역방향 상태 전이        |
| 400 | `AI_PROMPT_TOO_LONG`   | prompt 100자 초과   |
| 401 | `UNAUTHORIZED`         | 토큰 없음/만료         |
| 403 | `FORBIDDEN`            | 권한 부족 / role 불일치 |
| 404 | `RESOURCE_NOT_FOUND`   | 엔티티 없음           |
| 409 | `DUPLICATE_REVIEW`     | 1주문 1리뷰 위반       |
| 409 | `DUPLICATE_EMAIL`      | email UNIQUE 위반  |

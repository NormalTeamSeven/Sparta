# 03. 데이터 명세 (ERD + 테이블 명세)

> 이전: [도메인 명세](02-domain-spec.md) · 다음: [API 명세](04-api-spec.md)

---

## 1. 공통 규칙

- 모든 테이블 접두사: `p_`
- PK: UUID
- 유저 참조 컬럼(`customer_id`, `owner_id`, `user_id` 등)은 편의상 `username` VARCHAR(10) 문자열을 저장한다. p_user의 논리적 참조 키는 `username`(UNIQUE) 컬럼.
- **BaseEntity 상속 테이블** (자동 Audit 컬럼)
    - `created_at`, `created_by`, `updated_at`, `updated_by`, `deleted_at`, `deleted_by`
- **BaseEntity 미상속 테이블**: `p_order_item`, `p_ai_request_log`
    - 수정/삭제 이벤트가 없어 `created_at`, `created_by`만 직접 보유
- Soft Delete: `deleted_at IS NOT NULL`이면 논리 삭제
- 숨김: `is_hidden` (`p_store`, `p_menu`에만 존재, 삭제와 독립)

## 2. ERD

```mermaid
erDiagram
    p_user ||--o{ p_store : "OWNER → 가게"
    p_user ||--o{ p_order : "CUSTOMER → 주문"
    p_user ||--o{ p_review : "CUSTOMER → 리뷰"
    p_user ||--o{ p_address : "사용자 → 배송지"
    p_user ||--o{ p_ai_request_log : "사용자 → AI 요청"

    p_area ||--o{ p_store : "지역 → 가게"
    p_category ||--o{ p_store : "카테고리 → 가게"

    p_store ||--o{ p_menu : "가게 → 메뉴"
    p_store ||--o{ p_order : "가게 → 주문"
    p_store ||--o{ p_review : "가게 → 리뷰(역정규화)"

    p_order ||--o{ p_order_item : "주문 → 라인"
    p_order ||--o| p_review : "주문 → 리뷰 1:1"
    p_order ||--o| p_payment : "주문 → 결제 1:1"

    p_menu ||--o{ p_order_item : "메뉴 → 라인"
    p_address ||--o{ p_order : "배송지 → 주문"

    p_user {
      UUID user_id PK
      VARCHAR_10 username UK
      VARCHAR_100 nickname
      VARCHAR_255 email UK
      VARCHAR_255 password
      VARCHAR_20 role
      BOOLEAN is_public
    }
    p_area {
      UUID area_id PK
      VARCHAR_100 name UK
      VARCHAR_50 city
      VARCHAR_50 district
      BOOLEAN is_active
    }
    p_category {
      UUID category_id PK
      VARCHAR_50 name UK
    }
    p_store {
      UUID store_id PK
      VARCHAR_10 owner_id FK
      UUID category_id FK
      UUID area_id FK
      VARCHAR_100 name
      VARCHAR_255 address
      VARCHAR_20 phone
      DECIMAL average_rating
      BOOLEAN is_hidden
    }
    p_menu {
      UUID menu_id PK
      UUID store_id FK
      VARCHAR_100 name
      INTEGER price
      TEXT description
      BOOLEAN is_hidden
    }
    p_order {
      UUID order_id PK
      VARCHAR_10 customer_id FK
      UUID store_id FK
      UUID address_id FK
      VARCHAR_20 order_type
      VARCHAR_20 status
      INTEGER total_price
      TEXT request
    }
    p_order_item {
      UUID order_item_id PK
      UUID order_id FK
      UUID menu_id FK
      INTEGER quantity
      INTEGER unit_price
    }
    p_review {
      UUID review_id PK
      UUID order_id FK,UK
      UUID store_id FK
      VARCHAR_10 customer_id FK
      INTEGER rating
      TEXT content
    }
    p_payment {
      UUID payment_id PK
      UUID order_id FK,UK
      VARCHAR_20 payment_method
      VARCHAR_20 status
      INTEGER amount
    }
    p_address {
      UUID address_id PK
      VARCHAR_10 user_id FK
      VARCHAR_50 alias
      VARCHAR_255 address
      VARCHAR_255 detail
      VARCHAR_10 zip_code
      BOOLEAN is_default
    }
    p_ai_request_log {
      UUID ai_log_id PK
      VARCHAR_10 user_id FK
      VARCHAR_100 request_text
      TEXT response_text
      VARCHAR_30 request_type
    }
```

## 3. 테이블 명세

### 3.1 `p_user` (사용자)

| 필드         | 타입           | 제약                       | 설명                            |
|------------|--------------|--------------------------|-------------------------------|
| user_id    | UUID         | PK                       |                               |
| username   | VARCHAR(10)  | UNIQUE, NOT NULL         | `^[a-z0-9]{4,10}$`            |
| nickname   | VARCHAR(100) | NOT NULL                 |                               |
| email      | VARCHAR(255) | UNIQUE, NOT NULL         |                               |
| password   | VARCHAR(255) | NOT NULL                 | BCrypt                        |
| role       | VARCHAR(20)  | NOT NULL                 | CUSTOMER/OWNER/MANAGER/MASTER |
| is_public  | BOOLEAN      | NOT NULL, DEFAULT true   | 예약 필드(현재 read 범위 제어에 사용되지 않음) |
| + Audit 6개 |              |                          | BaseEntity                    |

### 3.2 `p_area` (운영 지역)

| 필드         | 타입           | 제약               | 설명     |
|------------|--------------|------------------|--------|
| area_id    | UUID         | PK               |        |
| name       | VARCHAR(100) | UNIQUE, NOT NULL | 예: 광화문 |
| city       | VARCHAR(50)  | NOT NULL         |        |
| district   | VARCHAR(50)  | NOT NULL         |        |
| is_active  | BOOLEAN      | DEFAULT true     |        |
| + Audit 6개 |              |                  |        |

### 3.3 `p_category` (카테고리)

| 필드          | 타입          | 제약               | 설명             |
|-------------|-------------|------------------|----------------|
| category_id | UUID        | PK               |                |
| name        | VARCHAR(50) | UNIQUE, NOT NULL | 한식/중식/분식/치킨/피자 |
| + Audit 6개  |             |                  |                |

### 3.4 `p_store` (가게)

| 필드             | 타입           | 제약                             | 설명    |
|----------------|--------------|--------------------------------|-------|
| store_id       | UUID         | PK                             |       |
| owner_id       | VARCHAR(10)  | FK → p_user.username, NOT NULL |       |
| category_id    | UUID         | FK → p_category, NOT NULL      |       |
| area_id        | UUID         | FK → p_area, NOT NULL          |       |
| name           | VARCHAR(100) | NOT NULL                       |       |
| address        | VARCHAR(255) | NOT NULL                       |       |
| phone          | VARCHAR(20)  |                                |       |
| average_rating | DECIMAL(2,1) | DEFAULT 0.0                    | 캐싱 컬럼 |
| is_hidden      | BOOLEAN      | DEFAULT false                  |       |
| + Audit 6개     |              |                                |       |

### 3.5 `p_menu` (메뉴)

| 필드          | 타입           | 제약                     | 설명          |
|-------------|--------------|------------------------|-------------|
| menu_id     | UUID         | PK                     |             |
| store_id    | UUID         | FK → p_store, NOT NULL |             |
| name        | VARCHAR(100) | NOT NULL               |             |
| price       | INTEGER      | NOT NULL, CHECK > 0    | 원           |
| description | TEXT         |                        | AI 자동 생성 가능 |
| is_hidden   | BOOLEAN      | DEFAULT false          |             |
| + Audit 6개  |              |                        |             |

### 3.6 `p_order` (주문)

| 필드          | 타입          | 제약                             | 설명   |
|-------------|-------------|--------------------------------|------|
| order_id    | UUID        | PK                             |      |
| customer_id | VARCHAR(10) | FK → p_user.username, NOT NULL |      |
| store_id    | UUID        | FK → p_store, NOT NULL         |      |
| address_id  | UUID        | FK → p_address                 |      |
| order_type  | VARCHAR(20) | NOT NULL, DEFAULT 'ONLINE'     |      |
| status      | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING'    |      |
| total_price | INTEGER     | NOT NULL                       |      |
| request     | TEXT        |                                | 요청사항 |
| + Audit 6개  |             |                                |      |

### 3.7 `p_order_item` (주문 라인) — Audit 미상속

| 필드            | 타입          | 제약                     | 설명           |
|---------------|-------------|------------------------|--------------|
| order_item_id | UUID        | PK                     |              |
| order_id      | UUID        | FK → p_order, NOT NULL |              |
| menu_id       | UUID        | FK → p_menu, NOT NULL  |              |
| quantity      | INTEGER     | NOT NULL, CHECK > 0    |              |
| unit_price    | INTEGER     | NOT NULL               | 주문 당시 단가 스냅샷 |
| created_at    | TIMESTAMP   | NOT NULL               |              |
| created_by    | VARCHAR(10) |                        |              |

### 3.8 `p_review` (리뷰)

| 필드          | 타입          | 제약                             | 설명      |
|-------------|-------------|--------------------------------|---------|
| review_id   | UUID        | PK                             |         |
| order_id    | UUID        | FK → p_order, UNIQUE, NOT NULL | 1주문 1리뷰 |
| store_id    | UUID        | FK → p_store, NOT NULL         | 역정규화    |
| customer_id       | VARCHAR(10) | FK → p_user.username, NOT NULL |       |
| customer_nickname | VARCHAR(50) | NOT NULL                       | 작성 시점 닉네임 |
| rating      | INTEGER     | NOT NULL, CHECK 1~5            |         |
| content     | TEXT        |                                |         |
| + Audit 6개  |             |                                |         |

### 3.9 `p_payment` (결제)

| 필드             | 타입          | 제약                             | 설명                          |
|----------------|-------------|--------------------------------|-----------------------------|
| payment_id     | UUID        | PK                             |                             |
| order_id       | UUID        | FK → p_order, UNIQUE, NOT NULL | 1주문 1결제                     |
| payment_method | VARCHAR(20) | NOT NULL, DEFAULT 'CARD'       | CARD만 허용                    |
| status         | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING'    | PENDING/COMPLETED/CANCELLED |
| amount         | INTEGER     | NOT NULL                       |                             |
| + Audit 6개     |             |                                |                             |

### 3.10 `p_address` (배송지)

| 필드         | 타입           | 제약                             | 설명        |
|------------|--------------|--------------------------------|-----------|
| address_id | UUID         | PK                             |           |
| user_id    | VARCHAR(10)  | FK → p_user.username, NOT NULL |           |
| alias      | VARCHAR(50)  |                                | 집/회사      |
| address    | VARCHAR(255) | NOT NULL                       | 필수        |
| detail     | VARCHAR(255) |                                |           |
| zip_code   | VARCHAR(10)  |                                |           |
| is_default | BOOLEAN      | DEFAULT false                  | 유저당 최대 1개 |
| + Audit 6개 |              |                                |           |

### 3.11 `p_ai_request_log` (AI 요청 로그) — Audit 미상속

| 필드            | 타입          | 제약                             | 설명                  |
|---------------|-------------|--------------------------------|---------------------|
| ai_log_id     | UUID        | PK                             |                     |
| user_id       | VARCHAR(10) | FK → p_user.username, NOT NULL |                     |
| request_text  | VARCHAR(100) | NOT NULL                       | 최대 100자 (prompt)    |
| response_text | TEXT        |                                | Gemini 응답           |
| request_type  | VARCHAR(30) | NOT NULL                       | PRODUCT_DESCRIPTION |
| menu_id       | UUID        | FK → p_menu.menu_id            | AI 응답이 적용된 메뉴 ID |
| is_applied    | BOOLEAN     | NOT NULL, DEFAULT false        | 메뉴에 실제 반영되었는지 여부 |
| created_at    | TIMESTAMP   | NOT NULL                       |                     |
| created_by    | VARCHAR(10) |                                |                     |

## 4. 인덱스 가이드 (권장)

| 테이블              | 인덱스                                               | 목적                   |
|------------------|---------------------------------------------------|----------------------|
| p_store          | `(category_id)`, `(area_id)`, `(name)`            | 복합 검색                |
| p_menu           | `(store_id)`                                      | 가게별 메뉴 조회            |
| p_order          | `(customer_id, created_at)`, `(store_id, status)` | 사용자/가게 주문 목록         |
| p_order_item     | `(order_id)`                                      | 라인 조회                |
| p_review         | `(store_id, created_at)`                          | 가게 리뷰 리스트            |
| p_payment        | `(order_id)` UNIQUE                               | 결제 1:1               |
| p_address        | `(user_id, is_default)`                           | 기본 배송지 조회            |
| p_ai_request_log | `(user_id, created_at)`                           | 사용자별 AI 호출 이력 (로그)조회 |
| p_ai_request_log | `(menu_id, is_applied)`                           | 특정 메뉴에 최종 반영된 AI 로그 조회 |
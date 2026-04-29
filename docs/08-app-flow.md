# 08. 애플리케이션 흐름

> 이전: [코드 레벨 설계](07-code-design.md)

애플리케이션 런타임 관점의 호출 흐름·시퀀스 모음.

---

## 0. 런타임 컴포넌트 도식

요청이 논리 컴포넌트를 따라 어떻게 흐르는지의 전반적인 그림. 주요 유스케이스별 시퀀스는 다음 섹션에서 상세히 다룬다.

```mermaid
flowchart LR
    Client["클라이언트<br/>(Postman / Swagger UI)"]
    subgraph App["Spring Boot App (Monolith)"]
        Filter["JwtAuthenticationFilter"]
        Controller["@RestController"]
        Service["@Service<br/>(@Transactional)"]
        Repo["Repository (JPA / QueryDSL)"]
        subgraph Ext["외부 연동 어댑터 (Strategy · Profile 기반 주입)"]
            IExt["«interface»<br/>*Client<br/>(예: AiClient · PaymentClient ...)"]
            Real["Real 구현체<br/>(profile = prod)"]
            Fake["Fake 구현체<br/>(profile = local / test)"]
        end
    end
    DB[("PostgreSQL<br/>(p_user · p_order · ...)")]
    External["외부 시스템<br/>(예: Gemini · PG ...)"]
    Client -->|" HTTPS + Bearer JWT "| Filter
    Filter --> Controller
    Controller --> Service
    Service --> Repo
    Repo -->|" JDBC "| DB
    Service -->|" uses "| IExt
    Real -.->|" impl "| IExt
    Fake -.->|" impl "| IExt
    Real -->|" HTTPS REST "| External
```

- 검증 레이어: Controller는 `@NotBlank`/`@Size` 등 **물리 Validation**, 도메인 객체/Service는 **논리 규칙**(정규식·범위·상태 전이)
- `JwtAuthenticationFilter`는 매 요청마다 DB에서 현재 `role` / `deleted_at`을 재조회해 토큰 payload와 대조
- **외부 연동 어댑터**: AI · PG 등 외부 시스템 호출은 `*Client` 인터페이스 + Real/Fake 구현체 2종 패턴 사용 고려.
- 외부 호출 로그(예: `AiRequestLog`)는 Service 레이어에서 성공/실패 모두

## 1. 인증 파이프라인

로그인으로 JWT를 발급받고, 이후 요청마다 Filter가 **DB에서 role/삭제 여부를 재검증**하는 두 단계 흐름.

```mermaid
sequenceDiagram
    autonumber
    actor Client as 클라이언트
    participant Auth as AuthController / Service
    participant UR as UserRepository
    participant JTP as JwtTokenProvider
    participant F as JwtAuthenticationFilter
    participant BC as Business Layer
    Note over Client,JTP: 1단계 · 로그인 (JWT 발급)
    Client ->> Auth: POST /auth/login
    Auth ->> UR: findByUsername + BCrypt.matches
    UR -->> Auth: UserEntity
    Auth ->> JTP: generate(username, role)
    JTP -->> Auth: JWT (HS256)
    Auth -->> Client: 200 OK + JWT
    Note right of Auth: 실패 시 401<br/>(유저 없음 · 비밀번호 불일치)
    Note over Client,BC: 2단계 · 인증된 요청 (매 요청 DB 재검증)
    Client ->> F: Bearer JWT + 요청
    F ->> JTP: parse(token)
    JTP -->> F: claims(username, role)
    F ->> UR: findByUsername(username)
    UR -->> F: UserEntity
    F ->> BC: SecurityContext 주입 후 chain 진행
    BC -->> Client: 200 OK
    Note right of F: 실패 시<br/>• JWT 만료 / 파싱 실패 → 401<br/>• 유저 없음 / soft-deleted → 401<br/>• payload.role ≠ DB.role → 403
```

**설계 포인트**

- **매 요청 DB 재검증**: JWT payload만 신뢰하지 않고 `UserRepository.findByUsername`으로 현재 `role`·`deleted_at`을 확인. 권한 변경/탈퇴 시 기존 토큰이
  **즉시 무력화**되는 효과 (별도 블랙리스트 불필요).
    - 트레이드오프: 모든 요청이 DB 1회 조회를 추가로 발생시킴. 부하 커지면 Redis 캐시 고려
- **401 vs 403 구분**: "본인 확인 실패" = 401 (토큰 파싱 실패, 유저 없음, 삭제됨), "본인은 맞지만 권한 부족" = 403 (payload.role ≠ DB.role).

## 2. 주문 생성

CUSTOMER가 주문을 생성하는 흐름. 컨트롤러 진입 이후 서비스는 트랜잭션 경계·조립만 담당하고, 상태 전이/불변식은 `OrderEntity`가 스스로 검증한다.

```mermaid
sequenceDiagram
  autonumber
  actor Client as 클라이언트 (CUSTOMER)
  participant F as JwtAuthenticationFilter
  participant C as OrderControllerV1
  participant S as OrderServiceV1
  participant OE as OrderEntity<br/>/OrderItemEntity
  participant R as OrderRepository
  participant DB as PostgreSQL

  Note over Client,F: 인증 파이프라인은 §1 참조

  Client ->> F: 주문 생성 요청
  F ->> C: 인증 통과 후 컨트롤러로 위임
  C ->> C: 요청 DTO 물리 검증
  C ->> S: 서비스 호출 (트랜잭션 시작)

  S ->> S: 주문 총액 계산 (스냅샷)
  S ->> OE: 주문 엔티티 생성

  loop 각 주문 항목 반복
    S ->> OE: 주문 항목 엔티티 생성
    Note right of OE: 엔티티 불변식 검증
    S ->> OE: 주문-항목 연관관계 연결
  end

  S ->> R: 주문 영속화 요청
  R ->> DB: 주문 + 항목 INSERT (cascade)
  DB -->> R: 영속 엔티티 반환
  R -->> S: 저장된 주문 반환
  S -->> C: 응답 DTO 변환 (트랜잭션 커밋)
  C -->> Client: 생성된 주문 응답

  Note right of S: 실패 시 VALIDATION_ERROR 등으로<br/>예외 응답 반환
```

**설계 포인트**

- **주문 시점 스냅샷**: `totalPrice`는 서비스가 클라이언트 입력 `quantity × unitPrice`로 계산해 저장. 이후 메뉴 가격이 바뀌거나 메뉴가 삭제되어도 과거 주문 금액은 불변.
- **물리 검증(@Valid) vs 논리 불변식**: DTO 레이어에서 `@NotNull`/`@Min` 같은 물리 검증을 먼저 걷어내고, `OrderItemEntity` 생성자가 `quantity > 0` / `unitPrice ≥ 0` 논리 불변식을 한 번 더 방어한다.
- **Cascade ALL + orphanRemoval**: `OrderItemEntity`는 별도 save 호출 없이 `addItem()` → `orderRepository.save(order)` 한 번으로 함께 영속화된다.
- **도메인 로직 위임**: 서비스는 트랜잭션 경계·조립만 담당하고, 상태 전이/불변식 검증은 `OrderEntity`가 스스로 수행한다 (DDD 관점).
- **Soft Delete**: `@SQLRestriction("deleted_at IS NULL")` 로 조회 시 자동 제외.

## 3. 결제 (Fake / 실 PG)

> **TBD**

## 4. 메뉴 생성 + AI 

메뉴 등록 시 AI를 적용하여 메뉴 설명을 생성하는 2단계(생성/저장 -> 검토/반영)프로세스. AI의 답변을 사장님이 직접 수정하여 반영할 수 있도록 함

```mermaid
sequenceDiagram
autonumber
actor Owner as 사장님 (OWNER)
participant AC as AiControllerV1
participant S as AiServiceV1
participant GC as GeminiClient
participant Ext as Google Gemini API
participant LR as AiRequestLogRepository
participant MR as MenuRepository
participant DB as PostgreSQL

    Note over Owner, DB: [Step 1] 메뉴 설명 AI 생성 및 임시 저장 (GeneratedAndLog)
    Owner ->> AC: POST /api/v1/menus/ai-description<br>(menuName)
    
    AC ->> S: generatedAndLogDescription(menuName, userId)
    
    S ->> GC: generateMenuDescription(menuName)
    Note right of GC: 프롬프트 구성 (System Instruction + Few-shot)
    
    Note right of GC: [방어 로직] try-catch 기반 장애 격리
    GC ->> Ext: POST /v1beta/models/gemini (API 호출)
    
    alt API 통신 성공
        Ext -->> GC: 200 OK (응답 텍스트)
        alt 응답에 "정확히 입력해주세요" 포함 (사후 필터링)
            GC -->> S: "직접 입력해주세요" (Fallback 반환)
        else 정상 결과
            GC -->> S: 생성된 설명 텍스트 반환
        end
    else 통신 에러/타임아웃 (catch Exception)
        GC -->> S: "직접 입력해주세요" (Fallback 반환)
    end
    
    S ->> LR: save(AiRequestLogEntity)<br>상태: isApplied = false
    LR -->> S: 저장된 로그 (aiLogId 포함)
    
    S -->> AC: aiLogId, 생성된 텍스트 반환
    AC -->> Owner: 200 OK (aiLogId, description)

    Note over Owner, DB: [Step 2] 사장님 검토 후 최종 반영 (Apply)
    Owner ->> AC: PATCH /api/v1/menus/{menuId}/ai-description/apply<br>(aiLogId, description)
    AC ->> S: applyAiDescription(menuId, request)
    
    Note over S, DB: @Transactional 시작
    S ->> LR: findById(aiLogId)
    LR -->> S: AiRequestLogEntity
    
    S ->> MR: findById(menuId)
    MR -->> S: MenuEntity
    
    Note right of S: [방어 로직] 중복 반영 검증
    alt isApplied == true
        S -->> AC: BusinessException (ALREADY_APPLIED)
        AC -->> Owner: 400 Bad Request
    end
    
    Note right of S: [비즈니스 로직] 텍스트 결정<br>(사장님 수정본이 있으면 우선 반영)
    S ->> S: String finalDesc = (request.desc != null) ? userDesc : aiDesc
    
    S ->> S: MenuEntity.updateProduct() 반영
    S ->> S: AiRequestLogEntity.assignToMenu() <br>(isApplied = true 변경)
    
    Note over S, DB: @Transactional 종료 (DB Update 반영)
    S -->> AC: 성공 응답
    AC -->> Owner: 200 OK ("AI 메뉴 설명 적용 완료")
```

**1.동기적 핵심 로직 + 비동기 부가 로직**

- 사장님이 다음 단계에서 사용해야 할 `aiLogId`를 즉시 받아야 하므로 로그 저장은 동기로 처리

- 단, 분석용 로그나 통계 기록 등은 `Spring Event`를 통해 비동기로 처리하여 API 응답 속도를 최적화

**2.다중 방어 계층 (Multi-Layer Defense)**

- **1단계:** `@Valid`를 이용한 입구 컷

- **2단계:** `Caffeine` 캐시를 이용한 무분별한 AI 호출 차단(비용 절감)

- **3단계:** `Resilience4j` 서킷 브레이커로 외부 API(Gemini) 장애가 우리 시스템으로 전파되는 것을 방지

**3.데이터 무결성 보장 (2-Step)**

- `isApplied` 플래그를 통해 한 번 생성된 AI 로그가 여러 번 중복 반영되는 것을 원천 차단

- 최종 반영 시 사장님이 수정한 내용이 있다면 이를 우선시하여 비즈니스 요구사항을 충족

**4.인프라 효율성**

- Redis 같은 별도 인프라 없이 `Caffeine` 로컬 캐시를 사용하여 간단하면서도 강력한 Rate Limit을 구현
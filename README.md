# 배달 주문 관리 플랫폼

Java 17 · Spring Boot 3.x · JPA · PostgreSQL 기반의 배달 주문 관리 플랫폼.

## 7️⃣ Team Members & Roles

| Name | Role & Responsibilities                                   |
| :---: |:----------------------------------------------------------|
| **권효승 (팀장)** | • 회원(User) 및 인증/인가(Auth) 서비스 구축<br>• 시스템 인프라(Infra) 환경 구축 |
| **김민제** | • 주문(Order) 서비스 구축                                        |
| **이선진** | • 리뷰(Review) 서비스 구축<br>• 시스템 인프라(Infra) 환경 구축             |
| **이설아** | • 가게(Store), 카테고리(Category), 지역(Area) 서비스 구축              |
| **오민수** | • 결제(Payment) 서비스 구축                                      |
| **장하영** | • 상품(Menu) 서비스 구축 및 AI(AiRequest) 서비스 연동                  |

> **Common Task:** 프로젝트 발표 자료 구성 및 제작


## 실행

```bash
./gradlew bootRun
```

또는 Docker Compose 사용:

```bash
docker-compose up
```

## API 문서 (Swagger)

springdoc-openapi 기반 Swagger UI를 제공한다. 애플리케이션 기동 후 아래 URL로 접속:

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## 테스트

Repository / Service 단위 테스트는 **필수**이며, 각 기능마다 성공·실패 케이스를 함께 작성한다.

```bash
./gradlew test
```

## 문서

설계 문서는 [`docs/`](docs/) 디렉토리를 참고한다. 시작은 [`docs/00-OVERVIEW.md`](docs/00-OVERVIEW.md).

# 서비스 API 보호

보호 대상은 `POST /api/v1/coupang/purchase-options/infer`이며 `testMode=true`도 동일하게 보호한다. 현재 이 API가 유일한 `/api` endpoint이므로 Servlet Filter에서 `/api` 경로를 조기에 보호한다. 페이지·CSS·JavaScript·설명서는 공개다. 서버의 기존 요청 검증과 AI 추론 전에 서비스 API Key와 Rate Limit을 검사한다.

| 환경변수 | 설정 | 기본값 |
|---|---|---|
| `AI_AGENT_SECURITY_ENABLED` | `app.security.enabled` | true (dev profile은 false) |
| `AI_AGENT_API_KEY` | `app.security.api-key` | 빈 값. 인증 ON일 때 미설정이면 시작 실패 |
| `AI_AGENT_API_KEY_ID` | `app.security.api-key-id` | internal-test |
| `AI_AGENT_RATE_LIMIT_ENABLED` | `app.security.rate-limit.enabled` | true |
| `AI_AGENT_RATE_LIMIT_PER_MINUTE` | `app.security.rate-limit.per-minute` | 30 |

Key는 충분히 긴 임의 Secret을 발급하여 환경변수/운영 Secret에 저장한다. `OPENAI_API_KEY`와 별개이며 서로 다른 값을 사용한다. Key ID는 영문·숫자·점·밑줄·하이픈으로 된 1~100자 식별자다. Key를 ID로 사용하면 안 된다. 운영에서는 인증을 켜고 HTTPS로 호출한다. Secret 값을 명령줄 기록·저장소·문서에 남기지 않는다.

```sh
curl -X POST https://<service-host>/api/v1/coupang/purchase-options/infer \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: ${AI_AGENT_API_KEY}" \
  --data-binary @examples/ai-request.json
```

누락·빈 Key·잘못된 Key·중복 Header는 기존 오류 응답 구조의 HTTP 401 / `errorCode=UNAUTHORIZED`로 반환한다. 키의 SHA-256 digest를 고정 길이로 만들고 JDK `MessageDigest.isEqual`로 비교한다. 응답·콘솔·JSONL에 Header나 Secret을 기록하지 않는다.

인증 통과 후 Key ID별 첫 요청부터 60초 window에서 기본 30회까지 허용한다. 검증 실패 및 모의 테스트도 요청 수에 포함된다. 하나의 추론 요청에서 LIGHT/FULL 호출이 두 번 발생해도 요청 수는 1회다. 초과 시 HTTP 429 / `errorCode=RATE_LIMIT_EXCEEDED`, 남은 window를 초 단위 올림한 `Retry-After`를 반환한다. window 종료 후 다시 허용한다. 인증 실패는 제한 횟수를 소비하지 않는다. 인증 OFF이고 제한 ON이면 `security-disabled` 하나의 공용 bucket을 사용한다.

현재 Rate Limit은 서버 인스턴스별 메모리 기준이므로 다중 서버/컨테이너 환경에서는 전역 Rate Limit이 아니다. 재시작하면 초기화되고 window 경계 전후에는 짧은 시간 동안 두 window의 요청이 가능하다. 다중 인스턴스 운영 시 Redis/API Gateway 등 중앙 제한으로 전환한다. 현재는 설정된 Key 하나를 지원하며 bucket은 ID 기준이라 향후 여러 Key에도 확장 가능하다.

실제 AI 호출 로그에는 기존 필드를 유지하며 `apiKeyId`를 추가한다. 인증 OFF는 `security-disabled`, HTTP 요청 밖의 내부 호출은 `internal-call`이다. 인증·Rate Limit 거부는 OpenAI 호출 및 AI Usage JSONL을 생성하지 않는다. 기존 API 응답 DTO에는 변경이 없다.

## 브라우저 테스트 페이지

현재 페이지는 JavaScript에서 추론 API에 직접 POST한다. 운영에서 인증 ON이면 Key 없는 호출은 401이다. Key를 HTML/JS·브라우저 저장소에 넣거나 공개 same-origin 우회 API를 만들지 않았다. 운영에서 실제 테스트는 Key를 보관하는 사내 서버 또는 API 클라이언트를 이용한다. 페이지 자체와 문서 열람은 가능하다.

로컬에서 `SPRING_PROFILES_ACTIVE=dev` 또는 `AI_AGENT_SECURITY_ENABLED=false`를 사용하면 기존 테스트 페이지로 호출할 수 있다. 필요하면 `AI_AGENT_RATE_LIMIT_ENABLED=false`도 설정한다. dev profile을 공개 운영 서비스에 적용하지 않는다. Spring Boot는 `.env` 파일을 자동으로 읽지 않으므로 Java 직접 실행 시 환경변수로 설정한다. Compose는 `.env`의 값을 컨테이너 환경변수로 전달한다. 기존 `.env`는 자동 변경하지 않았다.

운영 배포 전 서비스 Key Secret을 설정해야 한다. 배포 후 Key 없는 추론 요청이 401로 차단되는지 확인한다.

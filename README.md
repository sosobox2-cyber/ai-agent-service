# ai-agent-service

상품의 원본 단품 옵션명에서 쿠팡 구매옵션 값을 추출하는 Spring Boot 서비스입니다. 원본 옵션은 `optionId`와 `optionName1`만 전달합니다. 예를 들어 `배기핏 남색 100`이라는 **한 문자열**에서 `핏=배기핏`, `색상=남색`, `사이즈=100`을 만들 수 있습니다.

## 실행과 웹 테스트 화면

Java 17이 필요합니다. 내장 Tomcat을 사용하므로 Tomcat을 별도로 설치하거나 실행할 필요는 없습니다. IntelliJ에서 `pom.xml`을 Maven 프로젝트로 열고 Project SDK를 JDK 17로 지정한 다음 **AI Agent Service** 실행 구성을 실행하세요. 이 PC의 JDK 17 경로는 `C:\Program Files\Java\jdk-17`입니다. 기본 Java 8로는 실행할 수 없습니다.

서버가 시작되면 [http://127.0.0.1:8081/](http://127.0.0.1:8081/)에서 상품 정보와 옵션을 직접 입력할 수 있습니다. **테스트 모드**는 API 키 없이 `핏`, `색상`, 숫자 `사이즈`의 기본 패턴을 모의 추출합니다. 테스트 결과에는 `inferenceSource=TEST`, `errorCode=TEST_MODE`, `success=false`, `autoApplyCandidate=false`, `confidence=0`이 표시되며 자동 적용 대상이 아닙니다. 인식하지 못하는 표현을 시험하거나 실제 AI 추론을 사용하려면 실행 환경에 `OPENAI_API_KEY`를 설정하고 화면에서 테스트 모드를 끄세요.

이미 빌드한 JAR은 다음과 같이 실행할 수 있습니다. 소스나 화면을 바꿨다면 JAR을 다시 빌드해야 변경 사항이 반영됩니다. 이전 화면이 보이면 서버를 재시작하고 브라우저에서 Ctrl+F5로 새로고침하세요.

```powershell
& 'C:\Program Files\Java\jdk-17\bin\java.exe' -jar .\target\ai-agent-service-0.1.0-SNAPSHOT.jar
```

기본 포트는 `8081`, 바인딩 주소는 `127.0.0.1`이며 각각 `PORT`, `SERVER_ADDRESS` 환경 변수로 변경할 수 있습니다. 종료하려면 IntelliJ의 Stop 버튼 또는 터미널의 Ctrl+C를 사용하세요.

## Docker 실행

Docker Engine과 Compose 플러그인이 필요합니다. Windows에서는 Docker Desktop을 실행하고 Linux 컨테이너를 사용하세요. Java와 Maven은 이미지 빌드 과정에서 제공되므로 호스트에 별도로 설치할 필요가 없습니다.

프로젝트 루트에서 실행합니다.

```powershell
docker compose up -d --build
docker compose logs -f
```

[http://127.0.0.1:8081/](http://127.0.0.1:8081/)에 접속한 뒤 테스트 모드를 사용하면 API 키 없이 확인할 수 있습니다. 첫 빌드는 이미지와 Maven 의존성을 다운로드하므로 시간이 걸릴 수 있습니다. 빌드 중 기존 테스트도 실행하며, 실패하면 이미지 생성이 중단됩니다.

실제 AI 추론을 사용하거나 호스트 포트를 변경하려면 `.env.example`을 `.env`로 복사하고 값을 수정한 다음 `docker compose up -d`를 다시 실행하세요. 기존 `.env`가 있다면 복사하지 말고 필요한 값을 추가하세요.

```powershell
Copy-Item .env.example .env
```

`.env`의 `OPENAI_API_KEY`에 키를 입력합니다. IntelliJ가 이미 8081 포트를 사용 중이면 `HOST_PORT=8082`로 설정하고 `http://127.0.0.1:8082/`에 접속하세요. 기본적으로 이 PC에서만 접속할 수 있습니다. 다른 PC에서도 접속하게 하려면 `HOST_ADDRESS=0.0.0.0`으로 설정하고 호스트 방화벽에서 해당 포트를 허용하세요.

컨테이너 내부는 `0.0.0.0:8081`로 실행됩니다. `.env`는 Git과 Docker 빌드 컨텍스트에서 제외되며 API 키는 실행 시 환경 변수로 전달됩니다. 소스 수정 후에는 `docker compose up -d --build`로 다시 빌드하세요.

```powershell
# 상태 확인
docker compose ps
# 종료 및 컨테이너 제거
docker compose down
```

Compose 없이 직접 실행할 수도 있습니다.

```powershell
docker build -t ai-agent-service:local .
docker run -d --name ai-agent-service -p 127.0.0.1:8081:8081 ai-agent-service:local
```

Dockerfile은 [Docker의 다단계 빌드 방식](https://docs.docker.com/build/building/multi-stage/)으로 Maven 빌드 환경과 Java 실행 환경을 분리하며, 실행 컨테이너는 일반 사용자로 동작합니다.

## API

`POST /api/v1/coupang/purchase-options/infer`로 다음 JSON을 보냅니다. `allowedPurchaseOptions`는 카테고리에서 허용하는 구매옵션명이고, `requiredPurchaseOptions`는 그중 각 단품에 반드시 추출해야 하는 이름입니다. 두 목록에 같은 이름이 들어가는 것은 정상이며 필수 옵션을 여러 개 지정할 수 있습니다.

```json
{
  "goodsId": "100000123",
  "goodsName": "남성 배기핏 바지",
  "brand": "ABC",
  "categoryName": "패션 > 남성의류 > 바지",
  "coupangCategoryId": "123456",
  "coupangCategoryName": "남성 바지",
  "allowedPurchaseOptions": ["핏", "색상", "사이즈"],
  "requiredPurchaseOptions": ["핏", "색상", "사이즈"],
  "options": [
    {"optionId": "1", "optionName1": "배기핏 남색 100 "},
    {"optionId": "2", "optionName1": "배기핏 남색 150"},
    {"optionId": "3", "optionName1": "배기핏 남색 200"}
  ]
}
```

응답의 `optionMappings`는 단품 ID, 원본 이름, 구매옵션명, 추출한 값을 각각 보여줍니다. `items`는 단품별 결과입니다. 위 예제의 첫 단품은 `{"optionId":"1","purchaseOptions":{"핏":"배기핏","색상":"남색","사이즈":"100"}}`처럼 나옵니다. 추출한 값은 원본 옵션명에 실제로 포함된 문자열이어야 합니다. 필수 옵션을 빠뜨리거나 단품들의 최종 구매옵션 조합이 중복되면 검토가 필요한 결과로 처리합니다.

키 없이 API를 시험할 때는 `POST /api/v1/coupang/purchase-options/infer?testMode=true`를 사용하세요. 실제 AI 추론은 `OPENAI_API_KEY`가 필요합니다. 응답이 HTTP 200이어도 `success=false`이면 검토가 필요하며, 자동 적용 후보는 `success`와 `autoApplyCandidate`가 모두 `true`인 경우뿐입니다. 이 서비스는 상품이나 DB를 직접 수정하지 않습니다.

## 예제

서버 실행 후 프로젝트 루트에서 다음을 사용할 수 있습니다. `examples/ai-agent-service.postman_collection.json`은 같은 요청을 담은 Postman 컬렉션입니다. 기존 `rule-request.json` 파일명은 유지했지만 현재는 모의 추출 예제입니다.

```powershell
.\examples\call.ps1 -TestMode
.\examples\call.ps1 -Example ai
.\examples\call.ps1 -Example uncertain -TestMode
```

## 설정과 검증

| 설정 | 기본값 또는 용도 |
|---|---|
| `OPENAI_API_KEY` | 실제 AI 추론에 필요 |
| `OPENAI_MODEL` | `gpt-4.1-mini` |
| `PORT` | `8081` |
| `SERVER_ADDRESS` | `127.0.0.1` |
| `app.inference.confidence-threshold` | `0.95` |
| `app.inference.prompt-version` | `coupang-option-v3` |

Java 17과 Maven이 설정된 환경에서 `mvn test`로 요청 검증, 모의 추출, AI 응답 검증 및 API 테스트를 실행할 수 있습니다.

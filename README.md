# ai-agent-service

상품의 원본 단품 옵션명에서 쿠팡 구매옵션 값을 추출하는 Spring Boot 서비스입니다. 원본 옵션은 `optionId`와 `optionName1`만 전달합니다. 예를 들어 `블랙/90`이라는 **한 문자열**에서 `패션의류/잡화 사이즈=90`, `색상=블랙`을 만들 수 있습니다.

SK스토아 상품정보고시는 `productNoticeText`에 하나의 긴 텍스트로 전달할 수 있습니다. 실제 AI는 이 내용을 원본 옵션명의 의미를 판단하는 참고 자료로 사용합니다.

프로젝트 소개와 화면 사용 방법은 [비개발자용 안내](docs/project-overview.md), 기술 사양과 처리 흐름은 [기술 담당자용 문서](docs/project-flow.md)를 참고하세요.

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

## Vercel 배포

Vercel의 [Container Images](https://vercel.com/docs/functions/container-images) 기능으로 테스트 화면과 Spring Boot API를 함께 배포합니다. 이 기능은 현재 베타이며 Vercel Functions의 요금 및 실행 제한이 적용됩니다. 프로젝트 루트의 `Dockerfile.vercel`을 자동 감지해 Java 17 이미지로 빌드하고 모든 요청을 컨테이너로 연결합니다.

1. 프로젝트를 GitHub 저장소에 올립니다. `.env`와 실제 API 키는 커밋하지 않습니다.
2. Vercel에서 **Add New → Project**를 선택하고 해당 저장소를 Import합니다. Root Directory는 `pom.xml`과 `Dockerfile.vercel`이 있는 프로젝트 루트로 지정합니다.
3. 배포 설정의 Environment Variables에 아래 값을 추가합니다. 실제 AI를 사용할 환경(Production 및 필요하면 Preview)에 적용합니다.

| 환경 변수 | 값 |
|---|---|
| `PORT` | `8081` — Vercel이 전달할 포트와 서버 포트를 일치시킵니다. |
| `SERVER_ADDRESS` | `0.0.0.0` |
| `OPENAI_API_KEY` | 실제 AI 추론에 사용할 API 키. 모의 테스트만 할 때는 생략합니다. |
| `OPENAI_MODEL` | `gpt-4.1-mini` (선택) |

4. **Deploy**를 실행합니다. 이미지 빌드 중 `mvn verify`로 테스트도 실행합니다.
5. 발급된 배포 주소의 `/`에 접속하고, 테스트 모드를 켜서 모의 추출을 확인합니다. 실제 AI는 API 키를 설정하고 테스트 모드를 해제해 확인합니다.

Vercel은 로컬 `.env`나 `compose.yaml`을 실행 환경 설정으로 사용하지 않습니다. 환경 변수를 변경했다면 새로 배포해야 반영됩니다. 한동안 요청이 없으면 컨테이너가 내려갔다가 다음 요청에 다시 시작하므로 첫 접속은 느릴 수 있습니다. 실제 AI 호출에 사용하는 페이지이므로 공유 범위에 맞게 Vercel Deployment Protection 또는 앱 인증으로 접근을 제한하세요.

`AI_RATE_LIMIT`(HTTP 429)은 OpenAI의 크레딧 또는 호출·사용 한도 문제일 수 있습니다. Vercel 배포로 해결되지 않으며, OpenAI 계정의 크레딧과 한도를 별도로 확인해야 합니다.

## API

`POST /api/v1/coupang/purchase-options/infer`로 다음 JSON을 보냅니다. `allowedPurchaseOptions`는 카테고리에서 허용하는 구매옵션명이고, `requiredPurchaseOptions`는 그중 각 단품에 반드시 추출해야 하는 이름입니다. 두 목록에 같은 이름이 들어가는 것은 정상이며 필수 옵션을 여러 개 지정할 수 있습니다.

```json
{
  "goodsId": "68535109",
  "goodsName": "[아이그너](방송에서만 이가격) 아이그너 레터링 자카드 니트탑",
  "brand": "아이그너",
  "categoryName": "스포츠/레저>스포츠패션/슈즈/아웃도어>스포츠의류(여성)긴팔",
  "coupangCategoryId": "1007572",
  "coupangCategoryName": "스포츠 의류>긴팔>여성 긴팔>",
  "allowedPurchaseOptions": [
    "패션의류/잡화 사이즈",
    "색상"
  ],
  "requiredPurchaseOptions": [
    "패션의류/잡화 사이즈",
    "색상"
  ],
  "productNoticeText": "1. 제품 소재 : 소재 : 비스코스 레이온 47% + 폴리에스터 27% + 나일론 22% + 폴리우레탄 4%\n2. 색상 : 블랙\n3. 치수 : S(90) / M(95) / L(100) /XL(105)\n4. 제조자,수입품의 경우 수입자를 함께 표기 : ㈜엘케이플래닝 OEM\n5. 제조국: 베트남\n6. 세탁방법 및 취급시 주의사항: 단독손세탁\n7. 제조연월: 2025년 07월\n8. 품질보증기준: 관련 법 및 소비자 분쟁 해결 기준을 따름\n9. A/S 책임자와 전화번호: 유닉유니온/ 070-8656-3977",
  "options": [
    {
      "optionId": "1",
      "optionName1": "블랙/90"
    },
    {
      "optionId": "2",
      "optionName1": "블랙/95"
    },
    {
      "optionId": "3",
      "optionName1": "블랙/100"
    },
    {
      "optionId": "4",
      "optionName1": "블랙/105"
    }
  ]
}
```

응답의 `optionMappings`는 단품 ID, 원본 이름, 구매옵션명, 추출한 값을 각각 보여줍니다. `items`는 단품별 결과입니다. 위 예제의 첫 단품은 `{"optionId":"1","purchaseOptions":{"패션의류/잡화 사이즈":"90","색상":"블랙"}}`처럼 매핑할 수 있습니다. 추출한 값은 원본 옵션명에 실제로 포함된 문자열이어야 합니다. 필수 옵션을 빠뜨리거나 단품들의 최종 구매옵션 조합이 중복되면 검토가 필요한 결과로 처리합니다.

`productNoticeText`는 선택 입력이며 최대 20,000자입니다. SK스토아의 정보고시 항목명과 값을 공백이나 줄바꿈으로 이어 붙이면 됩니다. 웹 테스트 화면의 **SK스토아 상품정보고시** 입력란에 그대로 붙여 넣을 수 있으며, 쿠팡 정보고시 항목 목록은 필요하지 않습니다. 정보고시를 생략한 기존 요청도 사용할 수 있습니다. 정보고시에만 있는 값으로 단품 옵션을 채우지 않으며, 충돌하거나 모호한 경우 검토가 필요한 결과로 처리합니다. 테스트 모드는 정보고시를 요청에 포함하지만 기존 기본 패턴만 모의 추출하므로, 정보고시를 활용한 판단은 테스트 모드를 끈 실제 AI 추론에서 확인하세요.

키 없이 API를 시험할 때는 `POST /api/v1/coupang/purchase-options/infer?testMode=true`를 사용하세요. 실제 AI 추론은 `OPENAI_API_KEY`가 필요합니다. 응답이 HTTP 200이어도 `success=false`이면 검토가 필요하며, 자동 적용 후보는 `success`와 `autoApplyCandidate`가 모두 `true`인 경우뿐입니다. 이 서비스는 상품이나 DB를 직접 수정하지 않습니다.

## 예제

기본 예제는 SK스토아 상품 `68535109`의 아이그너 레터링 자카드 니트탑입니다. 중복 입력을 제외한 `블랙/90`, `블랙/95`, `블랙/100`, `블랙/105` 네 단품과 상품정보고시를 포함합니다. 실제 단품 ID가 제공되지 않아 `optionId`에는 테스트용 `1`~`4`를 부여했습니다. `블랙/90`에서는 사이즈 `90`과 색상 `블랙`을 추출합니다. 정보고시의 `S(90)`를 참고하더라도 원본에 없는 `S`로 바꾸지 않습니다.

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
| `app.inference.prompt-version` | `coupang-option-v5` |

Java 17과 Maven이 설정된 환경에서 `mvn test`로 요청 검증, 모의 추출, AI 응답 검증 및 API 테스트를 실행할 수 있습니다.

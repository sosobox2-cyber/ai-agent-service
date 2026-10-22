# API 기술 스택 표 — 영향 분석

- 목적: 문서 변경의 근거와 영향 범위를 확인한다.
- 작성일: 2026-10-22 (Asia/Seoul)
- 단계: 영향 분석
- 범위: API 명세서와 해당 Markdown을 표시하는 기존 명세서 페이지.
- 근거: `pom.xml`의 Java 17, Spring Boot 3.5.16, Spring AI 1.1.8, POI 5.5.1 및 `application.yml`의 기본 모델·생성 설정, `Dockerfile`의 Maven·Temurin 이미지.
- 실행 코드·API 계약·운영 설정에는 변경이 없다. 운영 모델은 외부 설정에 따라 달라질 수 있음을 명시한다.
- 이전 단계: [요구사항](API-기술스택-표_요구사항.md)

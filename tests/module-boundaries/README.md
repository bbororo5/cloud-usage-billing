# Module boundary guardrails

## 목적과 규칙

실행 단위의 내부 코드를 다른 실행 단위가 가져다 쓰지 못하게 한다. 공용 모듈을 새로 만들거나 내부 패키지 계층을 강제하지 않는다.

| 제품 코드의 의존 | 판정 |
|---|---|
| `apps:*` → `apps:*` | 금지: 실행 단위 간 구현 결합 |
| `libs:*` → `apps:*` | 금지: 공용 코드를 통한 우회 결합 |
| `apps:*`·`libs:*` → `tests:*` | 금지: 제품 실행에 테스트 코드 혼입 |
| `apps:*` → `libs:*`, `libs:*` → `libs:*` | 허용 |

테스트 코드가 제품 코드를 참조하는 것은 허용한다. 이전 수집 앱도 남아 있는 동안 같은 규칙으로 검사하며, 해당 실행 구조를 목표 아키텍처로 승인한다는 뜻은 아니다.

## 실행과 검증

```bash
./gradlew verifyModuleBoundaries :tests:module-boundaries:test --no-daemon
```

실제 검사는 `gradle/module-boundaries.gradle.kts`에 있다. Java 모듈의 `main` 컴파일·실행·annotation processor 구성에서 상속된 구성을 포함한 프로젝트 의존을 읽는다. 모든 제품 모듈을 검사하므로 공용 모듈을 통한 우회 경로도 해당 위반 지점에서 차단한다. 외부 라이브러리를 내려받거나 소스 텍스트를 검색해 판단하지 않는다.

각 Java 프로젝트의 `check`와 CI에 연결했다. 일반 `test`만 실행하면 실제 저장소 경계 검사까지 수행하는 것은 아니므로 위 명령 또는 `check`를 사용한다.

[Gradle TestKit](https://docs.gradle.org/current/userguide/test_kit.html)으로 임시 프로젝트에 실제 검사 스크립트를 적용한다. 정상 4건, 금지 의존·상속·우회·`check` 연결 9건을 검사한다. **무검사 기준선에서 9건 실패 → 구현 후 13건 통과**를 확인했다. 실제 제품 모듈에는 위반 의존을 삽입하지 않는다.

## 검증하지 않는 것

패키지 내부 계층, 클래스 복사·수동 JAR/소스 연결, 외부 배포물·별도 빌드의 우회 의존, 사용자 정의 제품 source set, HTTP·DB 접근은 검사 범위가 아니다. 현재는 승인된 Java `main` 프로젝트 의존만 방어한다. 새 실행 방식은 별도 설계 후 검사를 확장한다.

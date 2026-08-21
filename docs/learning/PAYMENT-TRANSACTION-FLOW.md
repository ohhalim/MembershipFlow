# MembershipFlow 결제 트랜잭션 이해 문서

> 검증 기준: 2026-08-20 현재 코드
>
> 목적: 면접용 모범답안 암기가 아니라, 현재 구현의 트랜잭션 범위와 실패 시 상태를
> 코드 근거로 설명하기 위한 학습 기록

## 1. 먼저 구분할 것

MySQL 트랜잭션이 롤백할 수 있는 것은 우리 DB 변경뿐이다.

```text
MySQL의 Subscription·PaymentHistory·BillingAttempt 변경
!=
외부 TossPayments의 빌링키 발급·결제 승인
```

Toss 승인이 성공한 뒤 DB 저장이 실패하면 Toss 승인까지 자동으로 취소되지 않는다.
따라서 결제 코드는 다음 두 문제를 별도로 다뤄야 한다.

1. DB 변경을 어디까지 하나의 트랜잭션으로 묶을 것인가?
2. 외부 호출 성공 후 DB 반영이 실패했을 때 어떻게 상태를 복구할 것인가?

## 2. 최초 결제 전체 흐름

진입점은
[`SubscriptionService.handleCallback()`](../../src/main/java/com/membershipflow/subscription/service/SubscriptionService.java)이다.
이 메서드 자체에는 `@Transactional`이 없다. 트랜잭션이 붙은 별도 서비스인
`InitialPaymentStateService`를 호출하므로 각 단계가 서로 다른 트랜잭션으로 실행된다.

```text
handleCallback() 시작
  |
  |-- [트랜잭션 1] claim()
  |     회원 행 잠금
  |     결제 시도 행 잠금
  |     상태·중복 구독 검증
  |     PROCESSING 선점 및 발급 멱등키 보장
  |     commit
  |
  |-- [트랜잭션 밖] Toss 빌링키 발급
  |
  |-- [트랜잭션 2] storeBillingKey()
  |     결제 시도 행 잠금
  |     암호화한 빌링키·고정 orderId·결제 멱등키 저장
  |     commit
  |
  |-- [트랜잭션 밖] Toss 최초 결제 승인
  |     실패하거나 응답이 유실되면 orderId로 승인 결과 조회
  |
  `-- [트랜잭션 3] complete()
        결제 시도 행 잠금
        결제 응답의 orderId·금액·상태·타입 검증
        Subscription 저장 또는 재구독 처리
        PaymentHistory 저장
        BillingAttempt COMPLETED 변경
        commit
```

### 트랜잭션 1: 결제 시도 선점

코드:
[`InitialPaymentStateService.claim()`](../../src/main/java/com/membershipflow/subscription/service/InitialPaymentStateService.java)

- `@Transactional` 시작
- `MemberRepository.findByIdForUpdate()`로 회원 행 비관적 쓰기 락
- `BillingAttemptRepository.findByCustomerKeyForUpdate()`로 결제 시도 행 비관적 쓰기 락
- 같은 회원의 활성 결제 시도 개수 확인
- 이미 완료·처리 중·중단된 시도 분기
- `issueIdempotencyKey` 보장
- `BillingAttempt` 상태를 `PROCESSING`으로 변경
- 메서드 정상 종료 후 commit, 락 해제

회원 행도 잠그는 이유는 서로 다른 `customerKey`를 사용한 동시 콜백도 회원 단위로
직렬화하기 위해서다.

### 외부 호출 1: 빌링키 발급

코드:
[`TossPaymentsClient.issueBillingKey()`](../../src/main/java/com/membershipflow/subscription/client/TossPaymentsClient.java)

- DB 트랜잭션 밖에서 실행
- `Idempotency-Key` 헤더에 저장된 `issueIdempotencyKey` 사용
- 네트워크 대기 중 DB 커넥션과 행 락을 유지하지 않음

### 트랜잭션 2: 복구 정보 저장

코드:
[`InitialPaymentStateService.storeBillingKey()`](../../src/main/java/com/membershipflow/subscription/service/InitialPaymentStateService.java)

- 결제 시도 행 비관적 쓰기 락
- 상태가 `PROCESSING`인지 재검증
- 빌링키를 암호화한 값으로 저장
- 고정 주문번호 `ORDER-{customerKey}` 저장
- 최초 결제용 `chargeIdempotencyKey` 생성·저장
- 카드 마스킹 번호와 카드사 저장

이 단계가 commit된 뒤 최초 결제를 호출하므로, 결제 응답이 유실돼도 저장된 주문번호와
멱등키를 이용할 수 있다.

### 외부 호출 2: 최초 결제 승인

코드:
[`SubscriptionService.handleCallback()`](../../src/main/java/com/membershipflow/subscription/service/SubscriptionService.java),
[`TossPaymentsClient.charge()`](../../src/main/java/com/membershipflow/subscription/client/TossPaymentsClient.java)

- DB 트랜잭션 밖에서 실행
- 저장된 `chargeIdempotencyKey`를 `Idempotency-Key` 헤더로 전송
- 승인 호출이 예외를 반환하면 같은 `orderId`로 승인 결과 조회
- 응답의 `paymentKey`, `orderId`, 금액, `DONE`, `BILLING` 검증

### 트랜잭션 3: 내부 완료 처리

코드:
[`InitialPaymentStateService.complete()`](../../src/main/java/com/membershipflow/subscription/service/InitialPaymentStateService.java)

- 결제 시도 행 비관적 쓰기 락
- `COMPLETED`라면 저장된 구독 반환
- 결제 응답과 저장된 결제 시도 값 재검증
- 구독 생성 또는 기존 구독 재활성화
- 같은 Toss 주문번호의 이력이 없을 때만 성공 `PaymentHistory` 저장
- 결제 시도를 `COMPLETED`로 변경
- 위 DB 변경을 함께 commit 또는 rollback

## 3. 최초 결제 실패 상황

| 실패 시점 | 외부 상태 | DB 상태 | 현재 복구 단서 |
|---|---|---|---|
| `claim()` 실패 | 외부 호출 전 | 트랜잭션 rollback | 다시 요청 가능 여부를 상태로 판단 |
| 빌링키 발급 응답 유실 | 발급 여부 불명 | `PROCESSING`, 발급 멱등키 저장 | 같은 발급 멱등키로 재요청 |
| 빌링키 발급 성공 후 저장 실패 | 빌링키 발급됨 | 빌링키·orderId 미저장 | 같은 발급 멱등키 재요청, 장기 중단 시 재인증 요구 |
| 결제 승인 응답 유실 | 승인 여부 불명 | 빌링키·orderId·결제 멱등키 저장 | 같은 orderId 조회 또는 같은 멱등키 재요청 |
| 결제 승인 후 `complete()` 실패 | Toss 승인됨 | 구독·이력·완료 상태 rollback | 다음 콜백에서 멱등 요청·orderId 조회 후 완료 재시도 |

현재 구조가 외부 결제와 DB를 하나의 원자적 트랜잭션으로 만든 것은 아니다. 대신 상태,
고정 주문번호, 단계별 멱등키와 승인 조회를 이용해 중단된 흐름을 이어갈 수 있게 했다.

## 4. 정기결제 트랜잭션 범위

코드:
[`SubscriptionService.processBilling()`](../../src/main/java/com/membershipflow/subscription/service/SubscriptionService.java)

최초 결제와 달리 `processBilling()` 전체에 `@Transactional`이 적용돼 있다.

```text
[하나의 DB 트랜잭션 시작]
  구독 행 비관적 쓰기 락
  결제 가능 상태와 nextBillingAt 재검증
  빌링키 복호화
  Toss 정기결제 승인 호출             <- 외부 호출도 트랜잭션 안
  실패 시 Toss orderId 승인 조회      <- 이 조회도 트랜잭션 안
  성공: 구독 상태·다음 결제일 갱신 + 성공 이력 저장
  실패: 실패 횟수·상태 갱신 + 실패 이력 저장
[commit]
```

`recordSuccessfulBilling()`은 같은 클래스의 private 메서드이므로 별도 트랜잭션이 아니다.
`processBilling()`에서 시작된 트랜잭션에 그대로 포함된다.

### 현재 장점

- `findByIdForUpdate()`로 같은 구독의 동시 정기결제 직렬화
- 락 획득 뒤 상태와 `nextBillingAt` 재검증
- 이미 취소됐거나 다음 결제일이 미래인 구독의 중복 과금 차단
- 성공 시 구독 갱신과 성공 이력 저장을 같은 DB 트랜잭션으로 처리
- 실패 시 승인 결과를 orderId로 조회해 확인된 승인은 성공으로 복구

### 현재 한계

1. Toss 네트워크 호출 동안 DB 트랜잭션·커넥션·구독 행 락 유지
2. 외부 승인 성공 후 DB commit 실패 시 Toss 승인은 롤백되지 않음
3. 정기결제 `charge()`에는 별도 `Idempotency-Key`를 전달하지 않음
4. 승인 조회가 일시적으로 결과를 찾지 못하면 실패 횟수가 증가
5. 실패 횟수가 증가하면 다음 재시도의 주문번호 suffix가 바뀌므로, 늦게 확인된 이전
   승인이 있는 상황에서 새 주문번호로 재과금될 위험을 추가 검증해야 함

따라서 정기결제를 “완전한 멱등 처리” 또는 “외부 호출을 트랜잭션 밖으로 분리한 구조”라고
설명하면 현재 코드와 다르다.

## 5. 면접 답변

### 최초 결제

> 최초 구독 결제는 전체 과정을 하나의 긴 DB 트랜잭션으로 묶지 않았습니다. 먼저
> `claim()`의 짧은 트랜잭션에서 회원과 결제 시도를 잠그고 `PROCESSING` 상태로
> 선점합니다. Toss 빌링키 발급은 트랜잭션 밖에서 실행하고, 발급 결과는
> `storeBillingKey()` 트랜잭션에서 암호화된 빌링키와 고정 주문번호, 결제 멱등키로
> 저장합니다. 최초 결제 승인도 트랜잭션 밖에서 호출하며, 성공 후 `complete()`
> 트랜잭션에서 구독, 결제 이력과 결제 시도 완료 상태를 함께 반영합니다. 응답 유실이나
> DB 반영 실패에는 저장한 멱등키와 주문번호 승인 조회로 재처리합니다.

### 정기결제

> 정기결제는 현재 `processBilling()` 전체가 하나의 DB 트랜잭션입니다. 구독 행을
> 비관적 락으로 조회하고 상태와 다음 결제일을 재검증한 뒤, Toss 승인 호출과 승인 결과
> 조회, 구독 갱신과 결제 이력 저장까지 수행하고 commit합니다. DB 변경은 함께
> rollback되지만 외부 Toss 승인은 rollback되지 않습니다. 또한 네트워크 대기 동안
> 커넥션과 구독 락을 점유하므로, 최초 결제처럼 상태 선점·외부 호출·결과 반영을 짧은
> 트랜잭션으로 분리하고 정기결제 전용 멱등키를 저장하는 것이 개선 대상입니다.

## 6. 말하면 안 되는 문장

- “결제 전체가 하나의 트랜잭션입니다.”
  - 최초 결제와 정기결제의 구조가 다르다.
- “Toss 승인도 DB 오류가 나면 rollback됩니다.”
  - 외부 시스템 작업은 MySQL 트랜잭션으로 rollback되지 않는다.
- “모든 Toss 호출을 트랜잭션 밖으로 분리했습니다.”
  - 정기결제 Toss 호출은 현재 트랜잭션 안에 있다.
- “정기결제도 멱등키로 중복 과금이 완전히 방지됩니다.”
  - 정기결제 승인 호출에는 별도 멱등키가 없고 실패 후 주문번호가 바뀔 수 있다.
- “정확히 한 번만 결제되는 exactly-once 구조입니다.”
  - 현재 코드와 검증 범위를 넘어선 표현이다.

## 7. 코드 근거와 테스트

- 최초 결제 오케스트레이션: `SubscriptionService.handleCallback()`
- 최초 결제 DB 상태 전이: `InitialPaymentStateService`
- 결제 시도 락: `BillingAttemptRepository.findByCustomerKeyForUpdate()`
- 회원 락: `MemberRepository.findByIdForUpdate()`
- 정기결제 구독 락: `SubscriptionRepository.findByIdForUpdate()`
- Toss 멱등키 헤더와 승인 조회: `TossPaymentsClient`
- 최초 결제 중복·응답 유실 테스트: `SubscriptionServiceTest`
- 상태 전이·중복 완료 테스트: `InitialPaymentStateServiceTest`
- 정기결제 대상별 격리: `BillingSchedulerTest`

## 8. 내 말로 설명하기

문서를 보지 않고 아래 질문에 답한 뒤 코드에서 근거를 다시 찾는다.

1. `handleCallback()`에 `@Transactional`이 없는데 DB 변경은 왜 commit되는가?
2. `claim()`에서 회원 행과 결제 시도 행을 모두 잠그는 이유는 무엇인가?
3. 빌링키를 저장한 뒤 결제하는 순서가 복구에 어떤 도움을 주는가?
4. Toss 승인 후 `complete()`가 실패하면 DB와 Toss 상태는 각각 어떻게 되는가?
5. 최초 결제와 정기결제의 가장 큰 트랜잭션 차이는 무엇인가?
6. 정기결제 외부 호출이 트랜잭션 안에 있을 때 어떤 자원을 오래 점유하는가?
7. 정기결제를 다시 개선한다면 어떤 상태와 멱등 정보를 먼저 저장해야 하는가?

### 한 문장 설명 연습

```text
최초 결제 트랜잭션:


정기결제 트랜잭션:


PG 성공 후 DB 실패 복구:

```

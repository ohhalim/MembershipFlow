# 문항별 근거 검토 — 2026-09-30

AI 검토 자료. 사람 승인 아님. 로컬 corpus 기준이며 실제 ES 일치 여부는 별도 확인.

## ret-001: 이용 가능한 구독의 판정 기준은?

ACTIVE는 만료시각과 무관하게 true. CANCELLED는 now < nextBillingAt일 때만 true. isActive는 위임 래퍼이므로 판정 기준의 필수 근거로 추가하지 않음.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > isActiveAt`: 1개
  - 근거 행: 243–246

- 사람 판정: 미검토

## ret-002: Paddle 구독 상태를 반영할 때, 받은 이벤트가 마지막 반영 이벤트보다 오래됐는지 무엇으로 판단해?

externalUpdatedAt이 존재하고 occurredAt이 그보다 이전인지 비교. ret-003과의 차이는 어휘 격차 가설의 대조 관측이며 원인 확정 근거는 아님. 동일 시각은 stale로 판정하지 않음.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > isStaleExternalEvent`: 1개
  - 근거 행: 178–180

- 사람 판정: 미검토

## ret-003: isStaleExternalEvent 는 무엇을 비교해?

ret-002와 같은 정답을 가진 심볼명 대조 문항. 두 결과만으로 검색 실패 원인을 확정하지 않음.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > isStaleExternalEvent`: 1개
  - 근거 행: 178–180

- 사람 판정: 미검토

## ret-004: 정기 결제 시점이 된 구독을 조회할 때 어떤 조건으로 걸러?

paymentProvider 일치, 지정 statuses 포함, nextBillingAt <= now의 세 조건. 호출자가 전달하는 상태 목록 자체는 이 근거의 범위 밖.

- `src/main/java/com/membershipflow/subscription/repository/SubscriptionRepository.java` / `SubscriptionRepository > findDueForBilling`: 1개
  - 근거 행: 49–58

- 사람 판정: 미검토

## ret-005: 구독 ID로 수정 대상 구독을 조회할 때 어떤 잠금을 사용해?

findByIdForUpdate의 PESSIMISTIC_WRITE 확인. 회원 ID 또는 외부 구독 ID 조회와 구분. 전체 동시성 보장을 묻는 문항이 아님.

- `src/main/java/com/membershipflow/subscription/repository/SubscriptionRepository.java` / `SubscriptionRepository > findByIdForUpdate`: 1개
  - 근거 행: 21–23

- 사람 판정: 미검토

## ret-006: 여러 소스의 가격 중 이상치를 찾을 때 기준 가격과 이탈 정도는 어떻게 계산해?

소스 가격의 중앙값을 기준으로 (소스 가격 - 중앙값) / 중앙값 계산 후 절댓값 비교. 기준 가격 계산 방법(정렬 후 중앙값, 짝수 개는 가운데 두 값 평균)은 evaluatePriceOutliers 가 아니라 median 청크(L125-133)에 있어 근거 2건으로 둔다. 최소 소스 수와 임계값의 숫자는 어느 청크에도 정의가 없어 평가 대상에서 제외. 상수 정의 수집은 별도 미해결 항목. 근거 범위 확정 2026-09-22, 분석은 RET-006-EVIDENCE.md.

- `src/main/java/com/membershipflow/collect/service/AnomalyDetectionService.java` / `AnomalyDetectionService > evaluatePriceOutliers`: 1개
  - 근거 행: 102–122
- `src/main/java/com/membershipflow/collect/service/AnomalyDetectionService.java` / `AnomalyDetectionService > median`: 1개
  - 근거 행: 125–133

- 사람 판정: 미검토

## ret-007: 수집량 감소를 비교할 때 이전 수집 건수가 0이면 어떻게 처리해?

previousCount <= 0이면 즉시 return. 급감 비율의 숫자는 현재 청크에 정의가 없어 평가 대상에서 제외. 전체 급감 탐지 품질 검증으로 해석하지 않음.

- `src/main/java/com/membershipflow/collect/service/AnomalyDetectionService.java` / `AnomalyDetectionService > evaluateCollectDrop`: 1개
  - 근거 행: 63–75

- 사람 판정: 미검토

## ret-008: 구독 상태가 CANCELLED이고 이용 종료 시점이 아직 남았다면 이용 가능 판정은 어떻게 돼?

isActiveAt에서 now < nextBillingAt이면 이용 가능, 같은 시각이면 불가. 취소 처리 메서드만으로는 이용 가능 판정을 확인할 수 없음. 취소 유형별 nextBillingAt 설정은 질문 범위 밖.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > isActiveAt`: 1개
  - 근거 행: 243–246

- 사람 판정: 미검토

## ret-009: 문서에 정리된 최초 결제와 정기결제의 트랜잭션·외부 호출 범위는 어떻게 달라?

expected_sources 두 항목 모두 필요한 근거. 각 anchor는 경로 구성요소의 연속된 suffix로 일치시킬 것. Recall@k는 회수한 근거 수/2, MRR은 첫 관련 근거 순위만 측정하므로 양쪽 근거 충족률도 별도 기록.

- `docs/learning/PAYMENT-TRANSACTION-FLOW.md` / `5. 면접 답변 > 최초 결제`: 1개
  - 근거 행: 182–191
- `docs/learning/PAYMENT-TRANSACTION-FLOW.md` / `5. 면접 답변 > 정기결제`: 1개
  - 근거 행: 192–200

- 사람 판정: 미검토

## ret-010: 토스 자동결제 심사 전 홈페이지·구매 흐름에서 보완해야 할 항목은?

홈페이지·구매 흐름 하위 절의 비회원 구매, 사업자 정보, 결제 직전 고지, 약관 항목. 운영 준비는 질문 범위 밖. 부모 제목만 있는 청크는 정답 불인정.

- `docs/operations/toss-billing-review-readiness.md` / `심사 전 차단 항목 > 홈페이지·구매 흐름`: 1개
  - 근거 행: 31–46

- 사람 판정: 미검토

## ret-011: 결제 준비 API 부하 테스트는 어떻게 돌려?

문서 실행 절의 BASE_URL, PLAN_ID, PROFILE, TOKENS_FILE과 k6 명령이 답. 테스트 계정 발급, 실결제 실행, 운영 부하 허용 범위 전체는 이 질문의 채점 범위 밖.

- `docs/operations/payment-load-test.md` / `실행`: 1개
  - 근거 행: 38–78

- 사람 판정: 미검토

## ret-012: Paddle에서 이미 처리한 이벤트 ID를 다시 보내면 어디서 걸러?

process의 provider+event ID 존재 확인 후 조기 return. 동시 최초 수신의 경쟁 조건이나 전체 멱등성 보장까지 평가하지 않음. 역순 시각 비교와 구분.

- `src/main/java/com/membershipflow/subscription/service/PaddleWebhookService.java` / `PaddleWebhookService > process`: 1개
  - 근거 행: 53–75

- 사람 판정: 미검토

## ret-013: 토스 정기결제가 실패할 때 실패 횟수에 따라 구독 상태는 어떻게 바뀌어?

paymentFailed에서 failCount 증가 후 3회 이상 SUSPENDED, 미만 PAYMENT_FAILED. Paddle 이벤트 처리와 구분.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > paymentFailed`: 1개
  - 근거 행: 233–237

- 사람 판정: 미검토

## ret-014: 배포할 때 SSL 인증서는 어떻게 받아?

문서 기준 HTTP nginx 기동, certbot webroot 인증서 발급, nginx 재시작 순서. 실제 발급 성공/현재 인프라 일치 여부는 평가하지 않음. 명령의 개인 이메일 값은 정답 요건이 아니며 답변에는 자리표시자 사용.

- `docs/DEPLOYMENT.md` / `5. SSL 인증서 발급`: 1개
  - 근거 행: 68–85

- 사람 판정: 미검토

## ret-015: 에러 응답 형식이 어떻게 돼?

문서 공통 에러 응답의 code와 message 필드 및 문자열 예시가 근거. 개별 오류 코드 목록, 모든 런타임 오류의 형식 일치 여부는 평가 범위 밖.

- `docs/API.md` / `공통 에러 응답`: 1개
  - 근거 행: 50–61

- 사람 판정: 미검토

## ret-101: Paddle 해지 예약을 반영할 때 서비스 종료일은 어느 필드에 저장해?

AI 작성 초안; 예상 답: nextBillingAt에 serviceEndsAt 저장. 취소 상태와 시각도 갱신. 역순 이벤트 판정 구현은 범위 밖.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > schedulePaddleCancellation`: 1개
  - 근거 행: 123–131

- 사람 판정: 미검토

## ret-102: Paddle 활성 상태 동기화에서 다음 결제일이 빠져 있으면 기존 날짜를 지워?

AI 작성 초안; 예상 답: null이면 기존 nextBillingAt 유지, failCount=0, cancelledAt=null.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > syncPaddleActive`: 1개
  - 근거 행: 133–142

- 사람 판정: 미검토

## ret-103: Paddle 연체 상태를 동기화하는 메서드는 실패 횟수도 증가시켜?

AI 작성 초안; 예상 답: PAYMENT_FAILED로 변경하지만 failCount 증가 없음. 결제 실패 이벤트 처리와 구분.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > syncPaddlePastDue`: 1개
  - 근거 행: 154–159

- 사람 판정: 미검토

## ret-104: Paddle 정지 상태 동기화가 받아들여지면 어떤 구독 상태가 저장돼?

AI 작성 초안; 예상 답: SUSPENDED 저장. 역순 이벤트 판정 상세는 범위 밖.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > syncPaddleSuspended`: 1개
  - 근거 행: 161–166

- 사람 판정: 미검토

## ret-105: Paddle 취소 완료를 반영하면 다음 결제일과 취소일은 각각 무엇으로 바뀌어?

AI 작성 초안; 예상 답: 두 필드 모두 전달된 canceledAt. 해지 예약과 구분.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > syncPaddleCanceled`: 1개
  - 근거 행: 168–176

- 사람 판정: 미검토

## ret-106: 기존 구독을 토스로 재구독할 때 이전 Paddle 식별자는 어떻게 처리해?

AI 작성 초안; 예상 답: externalCustomerId, externalSubscriptionId, externalUpdatedAt을 null로 초기화.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > resubscribe`: 1개
  - 근거 행: 182–204

- 사람 판정: 미검토

## ret-107: Paddle로 재구독할 때 이전 토스 빌링키와 카드 정보는 남겨?

AI 작성 초안; 예상 답: billingKey, customerKey, cardNumberMasked, cardCompany를 null로 초기화.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > resubscribeWithPaddle`: 1개
  - 근거 행: 206–224

- 사람 판정: 미검토

## ret-108: 토스 결제가 성공하면 누적 실패 횟수와 다음 결제일은 어떻게 갱신돼?

AI 작성 초안; 예상 답: ACTIVE, failCount=0, 전달된 nextBillingAt 저장. 외부 승인 과정은 제외.

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java` / `Subscription > paymentSuccess`: 1개
  - 근거 행: 226–231

- 사람 판정: 미검토

## ret-109: 정기 청구 배치에서 한 구독 처리 중 예외가 나면 나머지 구독도 중단돼?

AI 작성 초안; 예상 답: 개별 반복의 try/catch로 로그 후 다음 구독 계속. 모든 결제 성공 보장과 구분.

- `src/main/java/com/membershipflow/subscription/scheduler/BillingScheduler.java` / `BillingScheduler > processDueBillings`: 1개
  - 근거 행: 45–66

- 사람 판정: 미검토

## ret-110: 결제 배치 하트비트 게이지는 애플리케이션 시작 시 어떤 값으로 초기화돼?

AI 작성 초안; 예상 답: lastSuccessEpochSeconds(BILLING_BATCH)로 초기화 후 billing_last_run_timestamp_seconds 게이지 등록. 저장소 구현 제외.

- `src/main/java/com/membershipflow/subscription/scheduler/BillingScheduler.java` / `BillingScheduler > registerMetrics`: 1개
  - 근거 행: 36–43

- 사람 판정: 미검토

## ret-111: 결제 준비 부하 테스트에서 실제 과금 위험 때문에 제외한 경로는 무엇이야?

AI 작성 초안; 예상 답: callback, processBilling, Toss 빌링키 발급·승인 API 제외. prepare는 외부 Toss 호출 없음.

- `docs/operations/payment-load-test.md` / `테스트 범위`: 1개
  - 근거 행: 10–22

- 사람 판정: 미검토

## ret-112: 결제 부하 테스트용 토큰 파일은 어떻게 준비하고 어떤 계정을 써야 해?

AI 작성 초안; 예상 답: 미구독 전용계정/단기 access token, 예제 복사 및 chmod600. 실제 고객 토큰 사용 금지.

- `docs/operations/payment-load-test.md` / `테스트 사용자 준비`: 1개
  - 근거 행: 23–37

- 사람 판정: 미검토

## ret-113: 결제 준비 부하 테스트 후 중복 활성 시도가 없는지 어떤 DB 결과로 확인해?

AI 작성 초안; 예상 답: PENDING/PROCESSING을 회원별 집계해 count>1인 행이 0건. 실제 운영 DB 조회 요청 아님.

- `docs/operations/payment-load-test.md` / `DB 사후 검증`: 1개
  - 근거 행: 79–93

- 사람 판정: 미검토

## ret-114: 새 결제 준비 요청에서 자동 만료 가능한 기존 결제 시도는 무엇이야?

AI 작성 초안; 예상 답: 만료 PENDING 중 외부 결제 정보 없는 행. PROCESSING과 외부정보 있는 행은 PAYMENT_IN_PROGRESS.

- `docs/operations/initial-payment-migration-preflight.md` / `애플리케이션 동작`: 1개
  - 근거 행: 32–37

- 사람 판정: 미검토

## ret-115: V19 이전 처리중 결제에 주문번호만 있고 멱등키가 없으면 승인을 다시 호출해도 돼?

AI 작성 초안; 예상 답: 재승인 금지, order_id 조회만. 신규 V19 멱등키 경로와 구분.

- `docs/operations/initial-payment-migration-preflight.md` / `V19 외부 요청 멱등키`: 1개
  - 근거 행: 38–63

- 사람 판정: 미검토

## ret-116: 배포 문서에서 main push 이후 백엔드 배포까지 어떤 순서로 진행돼?

AI 작성 초안; 예상 답: CI test→Docker image build→GHCR push→SSH→backend 배포/health gate. 현재 인프라 실측 아님.

- `docs/DEPLOYMENT.md` / `CI/CD 흐름`: 1개
  - 근거 행: 25–36

- 사람 판정: 미검토

## ret-117: 배포 문서에서 루트 도메인과 www는 어떤 DNS 레코드로 연결해?

AI 작성 초안; 예상 답: @와 www 각각 A 레코드, EC2 Elastic IP, TTL600. 현재 DNS 상태 아님.

- `docs/DEPLOYMENT.md` / `2. Gabia DNS 설정`: 1개
  - 근거 행: 44–52

- 사람 판정: 미검토

## ret-118: 배포 문서에 따르면 구글 OAuth 클라이언트에 어떤 콜백 경로를 등록해?

AI 작성 초안; 예상 답: https://membershipflow.site/login/oauth2/code/google. 실제 콘솔 설정 확인은 제외.

- `docs/DEPLOYMENT.md` / `8. Google OAuth2 Redirect URI 추가`: 1개
  - 근거 행: 108–116

- 사람 판정: 미검토

## ret-119: 배포 문서의 인증서 자동 갱신 명령 뒤에 어떤 작업이 이어져?

AI 작성 초안; 예상 답: certbot renew 성공 시 && docker compose restart nginx. 신규 발급과 구분.

- `docs/DEPLOYMENT.md` / `SSL 자동 갱신 (cron)`: 1개
  - 근거 행: 117–123

- 사람 판정: 미검토

## ret-120: 배포 문서에서 백엔드는 IDE로 실행하고 DB만 Docker로 띄우려면 어떤 명령을 써?

AI 작성 초안; 예상 답: docker compose up mysql. 전체 compose up --build와 구분.

- `docs/DEPLOYMENT.md` / `로컬 개발`: 1개
  - 근거 행: 124–132

- 사람 판정: 미검토

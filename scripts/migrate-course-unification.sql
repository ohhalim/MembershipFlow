-- =====================================================================================
-- #130 코스명 정규화·구분 추출로 동아/동부 코스 통합 — 프로덕션 1회성 데이터 마이그레이션
-- =====================================================================================
-- 배경:
--   동아골프 크롤러는 구분이 코스명에 붙은 채 저장("가야일반", "경주신라주주")되고,
--   동부회원권은 구분 컬럼이 분리("가야|우대")되어 같은 회원권이 코스 2개로 저장됨.
--   코드(CourseNameNormalizer + course_alias)가 정규화된 이름으로 수집하도록 변경되었으므로
--   기존 데이터도 동일 규칙으로 정규화·병합한다.
--
-- 실행 전 필수:
--   1) DB 전체 백업 (mysqldump)
--   2) Flyway V7__course_alias.sql이 적용된 신규 코드 배포 완료 상태에서 실행
--      (구버전 크롤러가 돌면 옛 이름의 코스를 재생성함)
--   3) 아래 "검증용 SELECT"를 먼저 실행해 병합 대상이 26쌍인지 확인
--
-- 주의:
--   - 이 파일은 Flyway 마이그레이션이 아님. 수동으로 1회만 실행한다.
--   - 병합 canonical은 낮은 id 기준 (예외 없음: 300↔150은 150이 낮으므로 150 유지)
--   - course_id FK 보유 테이블: price_history, watchlist, course_source_mapping (전수 확인됨)
--
-- 병합 확정 26쌍 (dup → keep):
--   90→1(88), 92→2(가야 우대), 98→4(경주신라 주주), 99→6(골드 주주), 100→7(골드레이크),
--   105→10(금강), 106→11(기흥), 109→15(남부), 111→17(남서울 여자), 122→22(뉴코리아 여자),
--   140→29(레이크우드), 142→31(롯데스카이힐제주), 300→150(발리오스 우대), 151→33(발리오스 일반),
--   167→34(블루원용인), 196→45(안성베네스트), 103→51(오라), 242→63(지산),
--   252→68(캐슬렉스서울), 253→69(캐슬렉스제주), 254→70(코리아 일반), 255→71(코리아 주주),
--   274→78(플라자설악), 275→79(플라자용인), 277→80(한림광릉),
--   268→75(팔공) ※이슈 목록 외 추가 — "팔공(일반)"(268) 정규화 결과가 동부 "팔공"(75)과
--                 동일 (name, course_type, membership_type)이 되어 일반 병합 규칙에 해당
--
-- 병합 보류 (애매 — 그대로 둠, 이름 정규화만 적용):
--   남서울(동부16 MALE) ↔ 남서울(동아110, 성별 무표기)
--   뉴코리아(21 MALE) ↔ 뉴코리아(121)
--   안성(44 MALE) ↔ 안성일반(195→안성 REGULAR)
--   동래베네스트(25 REGULAR) ↔ 동아 남자(125)/여자(126)
--   여주(50 REGULAR, 307 SHAREHOLDER) ↔ 여주일반(E)(216)/여주일반(기타)(217)/여주주식(有)(218)
--   4WELL 3종(87,88,89) ↔ 포웰cc(김해)(76)
--   파미힐스(74 REGULAR) ↔ 파미힐스주주(263→파미힐스 SHAREHOLDER)
--   가평베네스트(3) ↔ 가평베네스트(50000)(94)
--   기흥 법인(12), 아시아나 주중(302), 안성베네스트 우대(303)/주중(304)
--   수원(39/301 주주) ↔ 수원입회(181)/수원주식(有)(182)
-- =====================================================================================

-- ─────────────────────────────────────────────────────────────────────────────────────
-- [검증용 SELECT] 실행 전 아래 쿼리로 병합 대상을 확인한다 (주석 해제 후 실행)
-- ─────────────────────────────────────────────────────────────────────────────────────
-- SELECT k.id  AS keep_id,  k.name AS keep_name,  k.membership_type AS keep_type,
--        d.id  AS dup_id,   d.name AS dup_name,   d.membership_type AS dup_type,
--        (SELECT COUNT(*) FROM price_history ph WHERE ph.course_id = d.id) AS dup_price_rows,
--        (SELECT COUNT(*) FROM watchlist w      WHERE w.course_id  = d.id) AS dup_watchlist_rows
-- FROM (SELECT 90 dup_id, 1 keep_id UNION ALL SELECT 92,2 UNION ALL SELECT 98,4
--       UNION ALL SELECT 99,6   UNION ALL SELECT 100,7  UNION ALL SELECT 105,10
--       UNION ALL SELECT 106,11 UNION ALL SELECT 109,15 UNION ALL SELECT 111,17
--       UNION ALL SELECT 122,22 UNION ALL SELECT 140,29 UNION ALL SELECT 142,31
--       UNION ALL SELECT 300,150 UNION ALL SELECT 151,33 UNION ALL SELECT 167,34
--       UNION ALL SELECT 196,45 UNION ALL SELECT 103,51 UNION ALL SELECT 242,63
--       UNION ALL SELECT 252,68 UNION ALL SELECT 253,69 UNION ALL SELECT 254,70
--       UNION ALL SELECT 255,71 UNION ALL SELECT 274,78 UNION ALL SELECT 275,79
--       UNION ALL SELECT 277,80 UNION ALL SELECT 268,75) pairs
-- JOIN membership_course d ON d.id = pairs.dup_id
-- JOIN membership_course k ON k.id = pairs.keep_id;
--
-- 마이그레이션 후 중복 검증 (0건이어야 함):
-- SELECT name, course_type, membership_type, COUNT(*) cnt
-- FROM membership_course GROUP BY name, course_type, membership_type HAVING cnt > 1;

START TRANSACTION;

-- ─────────────────────────────────────────────────────────────────────────────────────
-- 1. 병합 쌍 임시 테이블
-- ─────────────────────────────────────────────────────────────────────────────────────
CREATE TEMPORARY TABLE course_merge (
    dup_id  BIGINT NOT NULL PRIMARY KEY,
    keep_id BIGINT NOT NULL
);

INSERT INTO course_merge (dup_id, keep_id) VALUES
    ( 90,   1),  -- 88(팔팔)            → 88cc(→88)
    ( 92,   2),  -- 가야우대            → 가야 PREFERRED
    ( 98,   4),  -- 경주신라주주        → 경주신라 SHAREHOLDER
    ( 99,   6),  -- 골드주주            → 골드 SHAREHOLDER
    (100,   7),  -- 골드레이크일반      → 골드레이크
    (105,  10),  -- 금강(일반)          → 금강
    (106,  11),  -- 기흥(동아 REGULAR)  → 기흥(→REGULAR)
    (109,  15),  -- 남부일반            → 남부
    (111,  17),  -- 남서울여자          → 남서울 FEMALE
    (122,  22),  -- 뉴코리아여자        → 뉴코리아 FEMALE
    (140,  29),  -- 레이크우드일반(구.로얄) → 레이크우드
    (142,  31),  -- 롯데스카이힐제주    → 롯데스카이힐 제주(→롯데스카이힐제주)
    (300, 150),  -- 발리오스 PREFERRED(동부) → 발리오스-VIP(→발리오스 PREFERRED)
    (151,  33),  -- 발리오스-일반       → 발리오스 REGULAR
    (167,  34),  -- 블루원용인          → 블루원용인cc(→블루원용인)
    (196,  45),  -- 안성베네스트(구.나다) → 안성베네스트
    (103,  51),  -- 오라                → 오라cc(→오라)
    (242,  63),  -- 지산일반            → 지산 REGULAR
    (252,  68),  -- 캐슬렉스서울일반    → 캐슬렉스서울
    (253,  69),  -- 캐슬렉스제주일반    → 캐슬렉스제주
    (254,  70),  -- 코리아일반          → 코리아 REGULAR
    (255,  71),  -- 코리아주주          → 코리아 SHAREHOLDER
    (274,  78),  -- 플라자설악(舊.설악프라자) → 플라자설악
    (275,  79),  -- 플라자용인(舊.프라자) → 플라자용인
    (277,  80),  -- 한림광릉            → 한림광릉(광릉포레스트)(→한림광릉)
    (268,  75);  -- 팔공(일반)          → 팔공 ※일반 병합 규칙에 따른 추가

-- ─────────────────────────────────────────────────────────────────────────────────────
-- 2. FK repoint: price_history
-- ─────────────────────────────────────────────────────────────────────────────────────
UPDATE price_history ph
JOIN course_merge m ON ph.course_id = m.dup_id
SET ph.course_id = m.keep_id;

-- ─────────────────────────────────────────────────────────────────────────────────────
-- 3. FK repoint: watchlist — 같은 회원이 keep 코스도 이미 구독 중이면 dup 쪽 행 제거 후 repoint
--    (uk_watchlist_member_course 위반 방지)
-- ─────────────────────────────────────────────────────────────────────────────────────
DELETE w
FROM watchlist w
JOIN course_merge m ON w.course_id = m.dup_id
JOIN watchlist keep_w ON keep_w.member_id = w.member_id AND keep_w.course_id = m.keep_id;

UPDATE watchlist w
JOIN course_merge m ON w.course_id = m.dup_id
SET w.course_id = m.keep_id;

-- ─────────────────────────────────────────────────────────────────────────────────────
-- 4. FK repoint: course_source_mapping — keep 코스에 같은 source 매핑이 있으면 dup 쪽 제거
--    (uk_course_source 위반 방지)
-- ─────────────────────────────────────────────────────────────────────────────────────
DELETE cm
FROM course_source_mapping cm
JOIN course_merge m ON cm.course_id = m.dup_id
JOIN course_source_mapping keep_cm ON keep_cm.source_id = cm.source_id AND keep_cm.course_id = m.keep_id;

UPDATE course_source_mapping cm
JOIN course_merge m ON cm.course_id = m.dup_id
SET cm.course_id = m.keep_id;

-- ─────────────────────────────────────────────────────────────────────────────────────
-- 5. 중복 코스 row 삭제
-- ─────────────────────────────────────────────────────────────────────────────────────
DELETE mc
FROM membership_course mc
JOIN course_merge m ON mc.id = m.dup_id;

-- ─────────────────────────────────────────────────────────────────────────────────────
-- 6. canonical 코스 정규화 (반드시 5의 dup 삭제 후 실행 — unique 제약 충돌 방지)
-- ─────────────────────────────────────────────────────────────────────────────────────
UPDATE membership_course SET name = '88', membership_type = 'REGULAR', updated_at = NOW()
WHERE id = 1 AND name = '88cc';                                     -- 개인(정회원) → REGULAR
UPDATE membership_course SET membership_type = 'REGULAR', updated_at = NOW()
WHERE id = 11 AND name = '기흥' AND membership_type = 'INDIVIDUAL'; -- 개인(정회원) → REGULAR
UPDATE membership_course SET name = '롯데스카이힐제주', updated_at = NOW()
WHERE id = 31 AND name = '롯데스카이힐 제주';
UPDATE membership_course SET name = '블루원용인', updated_at = NOW()
WHERE id = 34 AND name = '블루원용인cc';
UPDATE membership_course SET name = '오라', updated_at = NOW()
WHERE id = 51 AND name = '오라cc';
UPDATE membership_course SET name = '한림광릉', updated_at = NOW()
WHERE id = 80 AND name = '한림광릉(광릉포레스트)';
UPDATE membership_course SET name = '발리오스', membership_type = 'PREFERRED', updated_at = NOW()
WHERE id = 150 AND name = '발리오스-VIP';

-- ─────────────────────────────────────────────────────────────────────────────────────
-- 7. 병합 없는 단순 정규화 — 동아 코스 이름/구분 UPDATE
--    (신규 크롤러가 생성할 (정규명, 구분)과 일치시켜 중복 재생성 방지)
-- ─────────────────────────────────────────────────────────────────────────────────────
-- 구분 토큰 추출 (이름 끝 일반/우대/주주/남자/여자/주중/VIP, 괄호 단독 토큰)
UPDATE membership_course SET name = '가야', updated_at = NOW()
WHERE id =  91 AND name = '가야-주중';                              -- WEEKDAY 유지
UPDATE membership_course SET name = '가야', updated_at = NOW()
WHERE id =  93 AND name = '가야일반';                               -- REGULAR 유지
UPDATE membership_course SET name = '강동디아너스', membership_type = 'PREFERRED', updated_at = NOW()
WHERE id =  97 AND name = '강동디아너스-VIP';
UPDATE membership_course SET name = '김포', membership_type = 'FEMALE', updated_at = NOW()
WHERE id = 108 AND name = '김포여자';
UPDATE membership_course SET name = '덕유산', updated_at = NOW()
WHERE id = 149 AND name = '덕유산-일반';                            -- REGULAR 유지
UPDATE membership_course SET name = '동래베네스트', membership_type = 'MALE', updated_at = NOW()
WHERE id = 125 AND name = '동래베네스트-남자';
UPDATE membership_course SET name = '동래베네스트', membership_type = 'FEMALE', updated_at = NOW()
WHERE id = 126 AND name = '동래베네스트-여자';
UPDATE membership_course SET name = '부산', membership_type = 'MALE', updated_at = NOW()
WHERE id = 164 AND name = '부산-남자';
UPDATE membership_course SET name = '부산', membership_type = 'FEMALE', updated_at = NOW()
WHERE id = 165 AND name = '부산-여자';
UPDATE membership_course SET name = '서울', membership_type = 'FEMALE', updated_at = NOW()
WHERE id = 174 AND name = '서울(여자)';
UPDATE membership_course SET name = '서울', updated_at = NOW()
WHERE id = 175 AND name = '서울(일반)';                             -- REGULAR 유지
UPDATE membership_course SET name = '안성', membership_type = 'FEMALE', updated_at = NOW()
WHERE id = 194 AND name = '안성여자';
UPDATE membership_course SET name = '안성', updated_at = NOW()
WHERE id = 195 AND name = '안성일반';                               -- REGULAR 유지 (동부 안성 44=MALE와 병합 안 함)
UPDATE membership_course SET name = '용원', membership_type = 'MALE', updated_at = NOW()
WHERE id = 229 AND name = '용원남자';
UPDATE membership_course SET name = '용원', membership_type = 'FEMALE', updated_at = NOW()
WHERE id = 230 AND name = '용원여자';
UPDATE membership_course SET name = '울산', membership_type = 'MALE', updated_at = NOW()
WHERE id = 233 AND name = '울산(남자)';
UPDATE membership_course SET name = '울산', membership_type = 'FEMALE', updated_at = NOW()
WHERE id = 234 AND name = '울산(여자)';
UPDATE membership_course SET name = '창원', membership_type = 'MALE', updated_at = NOW()
WHERE id = 247 AND name = '창원남자';
UPDATE membership_course SET name = '창원', membership_type = 'FEMALE', updated_at = NOW()
WHERE id = 248 AND name = '창원여자';
UPDATE membership_course SET name = '태광', membership_type = 'FEMALE', updated_at = NOW()
WHERE id = 261 AND name = '태광여자';
UPDATE membership_course SET name = '파미힐스', membership_type = 'SHAREHOLDER', updated_at = NOW()
WHERE id = 263 AND name = '파미힐스주주';                           -- 동부 파미힐스 74=REGULAR와 병합 안 함
UPDATE membership_course SET name = '한양', membership_type = 'FEMALE', updated_at = NOW()
WHERE id = 278 AND name = '한양여자';

-- 괄호 밖 공백·하이픈 제거 (이름만 변경, 구분 유지)
UPDATE membership_course SET name = '남안동일반(7000)', updated_at = NOW()
WHERE id = 112 AND name = '남안동-일반(7000)';
UPDATE membership_course SET name = '남안동주중(2500)', updated_at = NOW()
WHERE id = 113 AND name = '남안동-주중(2500)';
UPDATE membership_course SET name = '더시에나서울주중개인', updated_at = NOW()
WHERE id = 124 AND name = '더시에나서울-주중개인';
UPDATE membership_course SET name = '드비치VVIP(85000)', updated_at = NOW()
WHERE id = 138 AND name = '드비치-VVIP(85000)';
UPDATE membership_course SET name = '레이크우드프리빌리지', updated_at = NOW()
WHERE id = 139 AND name = '레이크우드-프리빌리지';
UPDATE membership_course SET name = '마우나오션VIP(9500)', updated_at = NOW()
WHERE id = 144 AND name = '마우나오션-VIP(9500)';
UPDATE membership_course SET name = '마우나오션VVIP(55000)', updated_at = NOW()
WHERE id = 145 AND name = '마우나오션-VVIP(55000)';
UPDATE membership_course SET name = '마우나오션주중(2500)', updated_at = NOW()
WHERE id = 146 AND name = '마우나오션-주중(2500)';
UPDATE membership_course SET name = '베이사이드로얄(25000)', updated_at = NOW()
WHERE id = 152 AND name = '베이사이드-로얄(25000)';
UPDATE membership_course SET name = '베이사이드로얄(30000)', updated_at = NOW()
WHERE id = 153 AND name = '베이사이드-로얄(30000)';
UPDATE membership_course SET name = '베이사이드프리미어(24500)', updated_at = NOW()
WHERE id = 154 AND name = '베이사이드-프리미어(24500)';
UPDATE membership_course SET name = '베이사이드프리미어(26000)', updated_at = NOW()
WHERE id = 155 AND name = '베이사이드-프리미어(26000)';
UPDATE membership_course SET name = '세종에머슨주중(3000)', updated_at = NOW()
WHERE id = 179 AND name = '세종에머슨-주중(3000)';
UPDATE membership_course SET name = '아난티중앙가족(舊.에머슨)', updated_at = NOW()
WHERE id = 186 AND name = '아난티중앙-가족(舊.에머슨)';
UPDATE membership_course SET name = '아난티중앙개인(舊.에머슨)', updated_at = NOW()
WHERE id = 187 AND name = '아난티중앙-개인(舊.에머슨)';
UPDATE membership_course SET name = '에스파크A등급(30000)', updated_at = NOW()
WHERE id = 203 AND name = '에스파크-A등급(30000)';
UPDATE membership_course SET name = '에스파크K등급(18000)', updated_at = NOW()
WHERE id = 204 AND name = '에스파크-K등급(18000)';
UPDATE membership_course SET name = '에스파크P등급(50000)', updated_at = NOW()
WHERE id = 205 AND name = '에스파크-P등급(50000)';
UPDATE membership_course SET name = '에스파크R등급(18000)', updated_at = NOW()
WHERE id = 206 AND name = '에스파크-R등급(18000)';
UPDATE membership_course SET name = '에이원VIP(16000)', updated_at = NOW()
WHERE id = 211 AND name = '에이원-VIP(16000)';
UPDATE membership_course SET name = '에이치원(H1)일반(舊.덕평)', updated_at = NOW()
WHERE id = 212 AND name = '에이치원(H1)-일반(舊.덕평)';
UPDATE membership_course SET name = '오펠비즈니스(23000)', updated_at = NOW()
WHERE id = 225 AND name = '오펠-비즈니스(23000)';
UPDATE membership_course SET name = '오펠프리미엄(17000)', updated_at = NOW()
WHERE id = 226 AND name = '오펠-프리미엄(17000)';
UPDATE membership_course SET name = '오펠프리미엄(19000)', updated_at = NOW()
WHERE id = 227 AND name = '오펠-프리미엄(19000)';
UPDATE membership_course SET name = '용평1,2차', updated_at = NOW()
WHERE id = 231 AND name = '용평-1,2차';
UPDATE membership_course SET name = '용평3차', updated_at = NOW()
WHERE id = 232 AND name = '용평-3차';
UPDATE membership_course SET name = '핀크스1차', updated_at = NOW()
WHERE id = 276 AND name = '핀크스-1차';
UPDATE membership_course SET name = '해운대VIP(30000)', updated_at = NOW()
WHERE id = 284 AND name = '해운대-VIP(30000)';
UPDATE membership_course SET name = '해운대VIP(48000)', updated_at = NOW()
WHERE id = 285 AND name = '해운대-VIP(48000)';
UPDATE membership_course SET name = '해운대VVIP(80000)', updated_at = NOW()
WHERE id = 286 AND name = '해운대-VVIP(80000)';
UPDATE membership_course SET name = '해운대로얄(25000)', updated_at = NOW()
WHERE id = 287 AND name = '해운대-로얄(25000)';
UPDATE membership_course SET name = '해운대비치VVIP(100000)', updated_at = NOW()
WHERE id = 288 AND name = '해운대비치-VVIP(100000)';
UPDATE membership_course SET name = '해운대비치로얄(52000)', updated_at = NOW()
WHERE id = 289 AND name = '해운대비치-로얄(52000)';
UPDATE membership_course SET name = '해운대비치빌리지(22000)', updated_at = NOW()
WHERE id = 290 AND name = '해운대비치-빌리지(22000)';
UPDATE membership_course SET name = '해운대비치빌리지(9900)', updated_at = NOW()
WHERE id = 291 AND name = '해운대비치-빌리지(9900)';
UPDATE membership_course SET name = '해운대비치창립(24000)', updated_at = NOW()
WHERE id = 292 AND name = '해운대비치-창립(24000)';

-- ─────────────────────────────────────────────────────────────────────────────────────
-- 8. 동부 코스 단순 정규화 (병합 없음)
-- ─────────────────────────────────────────────────────────────────────────────────────
UPDATE membership_course SET name = '비에이비스타', updated_at = NOW()
WHERE id =  35 AND name = '비에이비스타cc';
UPDATE membership_course SET name = '한림광릉', updated_at = NOW()
WHERE id = 310 AND name = '한림광릉(광릉포레스트)';                 -- WEEKDAY, 별칭 canonical과 일치

DROP TEMPORARY TABLE course_merge;

COMMIT;

-- ─────────────────────────────────────────────────────────────────────────────────────
-- [실행 후 검증]
-- 1) 중복 코스 0건 확인:
--    SELECT name, course_type, membership_type, COUNT(*) cnt
--    FROM membership_course GROUP BY name, course_type, membership_type HAVING cnt > 1;
-- 2) 고아 FK 0건 확인:
--    SELECT COUNT(*) FROM price_history ph LEFT JOIN membership_course c ON ph.course_id = c.id WHERE c.id IS NULL;
--    SELECT COUNT(*) FROM watchlist w      LEFT JOIN membership_course c ON w.course_id  = c.id WHERE c.id IS NULL;
-- 3) 병합 코스에 양쪽 소스 가격이 모두 쌓이는지 확인 (예: 88):
--    SELECT s.name, COUNT(*), MAX(ph.collected_at)
--    FROM price_history ph JOIN crawl_source s ON ph.source_id = s.id
--    WHERE ph.course_id = 1 GROUP BY s.name;

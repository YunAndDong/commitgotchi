---
name: Commit-Gotchi
description: 혼자 CS를 공부하는 사람의 매일 학습을 픽셀 캐릭터의 성장(육아)으로 바꾸는 학습 동반자. Stardew풍 민트+크림 코지 픽셀 감성, Galmuri 폰트. 3테마(cozy/device/cli) 전환 지원.
status: final
created: 2026-06-07
updated: 2026-06-07
sources:
  - ../../prds/prd-commitgotchi-2026-06-07/prd.md
  - ../../briefs/brief-commitgotchi-2026-06-07/brief.md
  - "file:../../../../docs/Commit-Gotchi 목업 (Vue) - 단독.html"  # 기준 목업(source of truth)
themes:
  default: cozy
  list: [cozy, device, cli]
colors:
  # === THEME A · cozy — 민트+크림 (Stardew) · 기본 ===
  cozy-bg-page: '#e7dcc4'
  cozy-popup-bg: '#fbf3e0'
  cozy-popup-edge: '#3f3328'
  cozy-ink: '#3c2f24'
  cozy-ink-soft: '#7a6550'
  cozy-ink-faint: '#a4917a'
  cozy-surface: '#fffdf5'
  cozy-surface-2: '#f3ead4'
  cozy-surface-edge: '#d8c7a6'
  cozy-screen: '#fffdf5'
  cozy-primary: '#54b878'
  cozy-primary-d: '#3c9a5e'
  cozy-on-primary: '#ffffff'
  cozy-accent: '#e08658'
  cozy-accent-d: '#c66a3e'
  cozy-accent2: '#eab43f'
  cozy-joy: '#54b878'
  cozy-sad: '#5f97d6'
  cozy-angry: '#e0705a'
  cozy-fire: '#ef7a3a'
  cozy-gold: '#eab43f'
  cozy-track: '#e7dcc4'
  cozy-shadow-hard: 'rgba(63,51,40,.22)'
  # === THEME B · device — 회색 셸 + 민트 LCD (다마고치 핸드헬드) ===
  device-bg-page: '#dfe3e8'
  device-popup-bg: '#c2c7cf'
  device-popup-edge: '#71767f'
  device-ink: '#2c4537'
  device-surface: '#d4ecd9'
  device-screen: '#cfe8d5'
  device-primary: '#4a9e6b'
  device-accent: '#e8746a'
  device-accent2: '#5c8fd6'
  device-joy: '#4a9e6b'
  device-sad: '#5c8fd6'
  device-angry: '#e8746a'
  device-gold: '#cf9a3a'
  device-shadow-hard: 'rgba(60,70,75,.28)'
  # === THEME C · cli — 블랙 + 네온 (git 터미널) ===
  cli-bg-page: '#0a0c10'
  cli-popup-bg: '#0e1217'
  cli-ink: '#d6e6da'
  cli-ink-soft: '#7c8a93'
  cli-surface: '#141a21'
  cli-surface-edge: '#26303d'
  cli-screen: '#0c1015'
  cli-primary: '#3ddc84'
  cli-accent: '#37e1c4'
  cli-accent2: '#c9a8ff'
  cli-joy: '#3ddc84'
  cli-sad: '#5fb0ff'
  cli-angry: '#ff6b6b'
  cli-gold: '#ffd24a'
  cli-shadow-hard: 'rgba(0,0,0,.5)'
  # === 스탯 5종 · 감정 3종 (의미 고정, 테마 무관 — 목업 토큰 매핑) ===
  emo-joy: '#54b878'
  emo-sad: '#5f97d6'
  emo-angry: '#e0705a'
typography:
  display:
    fontFamily: "'Gasoek One', 'Galmuri14', sans-serif"
    note: '대형 타이틀/로고 강조. 두꺼운 디스플레이.'
  head:
    fontFamily: "'Galmuri11', 'Gothic A1', sans-serif"
    note: 'cli 테마에서는 GalmuriMono11. 헤딩·라벨·수치.'
  body:
    fontFamily: "'Galmuri11', 'Gothic A1', sans-serif"
    note: 'cli 테마에서는 GalmuriMono11. 본문·폼.'
  mono:
    fontFamily: "'GalmuriMono11', 'Galmuri11', monospace"
    note: '수치·코드·태그.'
  scale:
    note: 'Galmuri7 / Galmuri9 / Galmuri11 / Galmuri14 픽셀 사이즈 패밀리 사용. 작은 메타=7~9, 본문=11, 타이틀=14+.'
rounded:
  cozy: '9px'
  device: '7px'
  cli: '5px'
  pill: '999px'
spacing:
  '1': '4px'
  '2': '8px'
  '3': '12px'
  '4': '16px'
  '5': '24px'
  '6': '32px'
components:
  cg-btn:
    border: '2px solid (primary-d / accent-d / surface-edge)'
    radius: '{rounded.cozy}'
    hover: 'translate(-1px,-1px) + shadow 4px 4px 0 shadow-hard'
    active: 'translate(2px,2px) + shadow 1px 1px 0 shadow-hard'
    primary: 'bg primary · color on-primary'
    accent: 'bg accent · color #fff'
  cg-card:
    background: 'surface'
    border: '2px solid surface-edge'
    radius: '{rounded.cozy}'
  gauge:
    track: 'track · 2px solid surface-edge · radius 7px · height 12px'
    label: 'font-head 11px'
    semantics: '육아점수 진척 게이지'
  badge:
    warn: 'bg #fff4cf · #9a7012 · border #e8c34a'
    ok: 'bg #dff3df · #3c8a4e · border #7fc78c'
    fire: 'bg #ffe2cf · #d2581f · border fire'
  emo:
    dot: '8px 원형'
    map: 'joy 😊 / sad 😢 / angry 😠'
  cg-spr:
    note: '캐릭터 픽셀 스프라이트. 3프레임 1x3 스프라이트시트(열: joy/sad/angry)에서 emotion 프레임 선택. 진화는 baby/evolved 시트 URL 전환으로 처리. emotion · grey(미획득) · bob(idle) 프로퍼티. image PENDING 시 플레이스홀더. 그림자 spr-shadow.'
  cg-radar:
    note: '5각형 레이더. 축 순서: 알고리즘 · CS · DB · 네트워크 · 프레임워크.'
---

# Commit-Gotchi — Design Spine

> **이 스파인은 기존 Vue 목업(`docs/Commit-Gotchi 목업 (Vue) - 단독.html`)에서 증류했다. 목업이 시각 정체성의 기준(source of truth)이며, 토큰은 목업 CSS에서 그대로 추출했다.** 시각 정체성은 이 문서가, 동작·플로우는 `EXPERIENCE.md`가 소유한다. (BMad 기본 규칙은 "스파인이 목업을 이긴다"이나, 본 런은 사용자 지시로 목업을 기준으로 삼는다 — 이후 변경은 목업과 동기화한다.)

## Brand & Style

Commit-Gotchi는 **Stardew Valley풍 코지 픽셀** 세계다. 따뜻한 크림 종이 위에서 픽셀 캐릭터(다이노/새싹이 등)가 살고, 학습이 곧 **육아**다 — 캐릭터를 먹이고 키워 진화시킨다. 미감의 중심은 부드러운 라운드 코너(9px), 청키한 보더, 살짝 떠 있는 하드 오프셋 그림자, 그리고 픽셀 폰트 Galmuri의 또박또박한 질감이다. 버튼은 hover 시 살짝 떠오르고 press 시 눌리는 물리적 피드백을 준다.

세 가지 테마로 같은 골격을 갈아입는다: **cozy**(민트+크림, 기본 — Stardew 농장 감성), **device**(회색 플라스틱 셸 + 민트 LCD — 다마고치 핸드헬드), **cli**(블랙 + 네온 그린 — git 터미널, GalmuriMono). 마스코트 **새싹이**가 화면 하단을 돌아다니며 생기를 더한다. 제품의 본체는 AI 채점·피드백 품질이므로, 연출이 학습 정보의 가독성을 이기지 않는다.

## Colors

색은 **테마별 토큰 세트**로 정의된다(프론트매터 `colors` 참조). 각 테마는 동일한 의미 슬롯(`bg-page · surface · ink · primary · accent · gold · joy/sad/angry · track · shadow-hard`)을 자기 팔레트로 채운다.

- **cozy (기본)** — 캔버스 크림(`#e7dcc4`), 표면 아이보리(`#fffdf5`), 잉크 브라운(`#3c2f24`). primary 그린(`#54b878`)은 저장·생성·긍정 행동, accent 코랄오렌지(`#e08658`)는 보조 강조, gold(`#eab43f`)는 보상·하이라이트.
- **device** — 회색 셸(`#c2c7cf`) 안 민트 LCD(`#cfe8d5`) 위 짙은 그린 잉크(`#2c4537`). 하드웨어 버튼 코랄(`#e8746a`).
- **cli** — 다크(`#0a0c10`) 바탕 네온 그린(`#3ddc84`)·시안(`#37e1c4`). 터미널 글로우 그림자.
- **감정 3색 (의미 고정)** — 기쁨=그린(`#54b878`), 슬픔=블루(`#5f97d6`), 화남=레드오렌지(`#e0705a`). 8px 원형 dot + 얼굴(😊😢😠)로 병기.
- **스탯 5종** — 레이더/타일에서 알고리즘·CS·DB·네트워크·프레임워크. (목업은 스탯별 고정 색을 강하게 쓰기보다 단일 강조로 그리므로, 스탯 색 고정은 [ASSUMPTION]으로 둔다 — 확정 시 본 절에 추가.)
- **배지** — warn(연노랑), ok(연녹), fire(연주황) 파스텔 채움 + 진한 보더.

피해야 할 것: 테마 토큰을 넘나드는 색 혼용(예: cli 네온을 cozy 화면에), 감정색을 장식으로 전용.

## Typography

**Galmuri 픽셀 폰트 패밀리**가 정체성이다 — Galmuri7/9/11/14 + GalmuriMono7/9/11, 한글 폴백 `Gothic A1`. 대형 타이틀/로고는 디스플레이용 **Gasoek One**. cli 테마는 본문·헤딩을 **GalmuriMono11**로 바꿔 터미널 느낌을 강화한다.

역할: `display`(Gasoek One/Galmuri14 — 큰 타이틀·로고), `head`(Galmuri11 — 헤딩·라벨·수치), `body`(Galmuri11 — 본문·폼), `mono`(GalmuriMono11 — 수치·태그·코드). 픽셀 폰트 특성상 작은 크기에서 또렷하도록 Galmuri7/9를 메타 텍스트에, 11을 본문에, 14+를 타이틀에 매핑한다. 긴 본문(리포트·피드백)은 줄간격을 충분히 줘 가독성을 확보한다.

## Layout & Spacing

8px 그리드(4/8/12/16/24/32). 목업은 화면별 고정 아트보드 폭(주로 1180px, 도감 1320px)으로 설계된 **데스크톱 우선** 레이아웃이다. 대표 패턴: 좌측 히어로(캐릭터 스테이지) + 우측 사이드 레일(게이지·랭킹·감정·상태 메시지). 카드 그리드(게시판 1행 3열 + 페이지네이션). 모바일 웹은 단일 컬럼으로 접힌다. [ASSUMPTION: 모바일 브레이크포인트 — 목업은 데스크톱 고정폭 기준]

## Elevation & Depth

깊이는 **하드 오프셋 그림자**(`shadow-hard`, 테마별 톤)로 만든다. 버튼은 hover 시 `translate(-1px,-1px)` + `4px 4px 0` 그림자로 떠오르고, active 시 `translate(2px,2px)` + `1px 1px 0`으로 눌린다 — 픽셀/카트리지 질감의 물리적 클릭감. cli 테마는 네온 글로우(`0 0 28px rgba(61,220,132,.06)`)를 더한다. 부드러운 머티리얼 블러 그림자는 쓰지 않는다.

## Shapes

라운드 코너를 쓴다(직각 아님) — cozy 9px, device 7px, cli 5px. 게이지·트랙 등 작은 요소는 7px, 감정 dot·일부 토큰은 원형(pill/999px). 보더는 2px 솔리드(테마 edge/primary-d/accent-d 색)로 또렷하게 닫는다.

## Components

목업에서 추출한 컴포넌트(`cg-*`). 동작 규칙은 `EXPERIENCE.md.Component Patterns`.

- **cg-spr / cg-char** — 캐릭터 픽셀 스프라이트. **3프레임 1×3 스프라이트시트**(열0 joy / 열1 sad / 열2 angry)에서 `emotion`에 해당하는 프레임 1개를 `background-position`으로 노출. 진화는 시트 내부 행 전환이 아니라 baby/evolved 스프라이트 URL 전환으로 처리한다. `emotion`(joy/sad/angry), `grey`(도감 미획득=회색), `bob`(통통 idle 모션 — "Sprite를 이용한 약간의 애니메이션"), `spr-shadow`(바닥 그림자). 이미지 PENDING 동안 로딩 플레이스홀더. (메타: 아키텍처 §7.6 / `sprite_meta`)
- **cg-gauge** — 육아점수 진척 게이지. track(2px edge 보더, radius 7px, height 12px) + head 11px 라벨/수치.
- **cg-radar** — 5각형 능력치 레이더. 축: 알고리즘·CS·DB·네트워크·프레임워크.
- **cg-stattile** — 능력치 타일(개별 스탯 수치).
- **cg-btn** — primary(green)·accent(coral)·default. 2px 보더 + hover/active 오프셋 그림자.
- **cg-card / cg-section / cg-artboard** — 카드 컨테이너(surface + 2px edge 보더 + 라운드), 섹션 제목/부제, 고정 크기 아트보드 프레임.
- **cg-badge / cg-emo** — 상태 배지(warn/ok/fire 파스텔), 감정 칩(dot + 얼굴 + 라벨).
- **cg-delta** — 점수 변화량 표시(+N).
- **cg-board / cg-boardcard / cg-shared / cg-detail / cg-reviewrow / cg-stars** — 공유 게시판 카드(상하 2분할), 공유 캐릭터 상세, 리뷰 행, 평점 별.
- **cg-codex** — 도감/보관함 커버플로우(컬러=수집 / 회색=미획득).
- **cg-ranking / cg-pager** — 포디움 TOP3 + 리스트, 페이지네이션.
- **cg-confetti / cg-firework** — 캐릭터 생성 완료·진화 연출(컨페티·불꽃·등장 pop).
- **cg-browser / cg-faded-page / cg-popup / cg-scrim** — 브라우저 윈도우 크롬·딤·팝업(목업 갤러리/확장 뷰 셸).
- **cg-brand / cg-lockup** — 로고/브랜드 락업(Commit-Gotchi + 새싹이/다이노).

## Do's and Don'ts

| Do | Don't |
|---|---|
| 테마(cozy/device/cli) 토큰 세트를 한 화면에서 일관되게 | 테마 간 색 혼용(cli 네온을 cozy에) |
| Galmuri 패밀리 + Gothic A1 폴백 유지 | 픽셀 정체성과 무관한 일반 산세리프로 교체 |
| 라운드 코너(9/7/5px) + 2px 청키 보더 | 직각 0px·얇은 회색 보더 |
| hover 떠오름 / active 눌림 하드 그림자 | 머티리얼 블러 그림자 |
| 감정=색+얼굴+라벨 병기 | 색만으로 감정/상태 전달 |
| 주 지표 라벨은 목업대로 "육아점수" | UI에서 "전투력/육아점수" 혼용(§EXPERIENCE 불일치 항목 참조) |
| 새싹이 마스코트로 빈 공간에 생기 | 마스코트를 핵심 정보 위에 겹쳐 가독성 저해 |

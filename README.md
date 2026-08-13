# APP-FE

시각장애인 실내 내비게이션 **사용자 앱** (Android)

한이음 2026 · 6팀

---

## 개요

BLE 비콘을 체크포인트로 삼아 실내 경로를 음성으로 안내하는 앱입니다.
앱은 RSSI 수집·전송과 음성 입출력만 담당하고, **위치 판정과 경로 탐색은 서버**가 수행합니다 (얇은 클라이언트).

| 항목 | 값 |
| --- | --- |
| 언어 | Java |
| minSdk / targetSdk | 26 / 36 |
| UI | XML View 시스템 (Compose 미사용) |
| 접근성 | **TalkBack 전제.** 자체 제스처 문법 없음 |
| 입력 | Android `SpeechRecognizer` + TalkBack 터치 |
| 출력 | TTS + 진동 |

> Compose를 쓰지 않는 이유: TalkBack 포커스를 `requestFocus()` + `sendAccessibilityEvent()`로 강제 이동해야 하는데, View 시스템이 훨씬 직관적입니다.

## 상태 구조

앱은 화면이 아니라 **상태**로 설계합니다. 사용자가 화면을 보지 않으므로, "지금 어떤 상태이고 그것을 어떻게 알리나"가 본체입니다.

```
READY ──더블탭──▶ LISTENING ──매칭성공──▶ ROUTING ──▶ NAVIGATING ──도착──▶ ARRIVED
  ▲                    │                                  │              │
  └────────────────────┴──────────────────────────────────┴──────────────┘
                          (취소 · 중지 · 종료는 모두 READY로)
```

1차 개발 범위는 위 5개 상태입니다. `DEVIATED` · `SIGNAL_LOST` · `LOCATION_UNKNOWN` · `DISAMBIGUATE` · `CONFIRM`은 2차입니다.

## 모듈 구조

```
app/src/main/java/org/mcsmtp/wayfinder/
├── MainActivity.java              단일 Activity + Fragment 전환
├── state/       NavState · NavStateMachine
├── ui/          Home · Destination · Navigation · Arrival Fragment
├── ble/         BleScanner(복사) · ScanService(Foreground)
├── speech/      SttManager · SpeechOutput
├── net/         ApiClient · WebSocketManager(복사) · model/
├── mock/        MockApi (assets JSON)
└── util/        Haptics · ShakeDetector
```

## 재사용 파일

`Android_app` 리포(실측앱)에서 검증된 코드를 복사해 씁니다.

- [ ] `ble/BleScanner.java` — BLE 스캔
- [ ] `WebSocketManager.java` — OkHttp WebSocket
- [ ] `data/BeaconDevice.java` — 비콘 모델
- [ ] `data/RssiPoint.java` — RSSI 시계열

`RssiFilterPipeline.java`는 **복사하지 않습니다.** 필터링은 서버가 수행합니다.

## 사용 API

| 우선순위 | Method | 경로 |
| --- | --- | --- |
| 필수 | `GET` | `/api/floors/{floorId}/destinations` |
| 필수 | `POST` | `/api/route` |
| 필수 | `WS` | `/ws/navigation` |
| 2차 | `POST` | `/api/locate` · `/api/route/reroute` |
| 여유 | `GET` | `/api/config` |

API 확정 전까지는 `assets/mock/`의 JSON으로 개발합니다. `BASE_URL`은 빌드 설정으로 분리합니다.

## 구현 시 주의

| # | 항목 | 처리 |
| --- | --- | --- |
| 1 | 화면 전환 후 포커스 유실 | `requestFocus()` + `sendAccessibilityEvent(TYPE_VIEW_FOCUSED)` |
| 2 | 안내 중 화면 꺼짐 | `FLAG_KEEP_SCREEN_ON` |
| 3 | STT가 자기 TTS를 인식 | 마이크 열기 전 TTS 완전 종료 대기 |
| 4 | 백그라운드 BLE 스캔 중단 | **Foreground Service 필수** |
| 5 | 권한 거부 시 먹통 | 위치 · 마이크 · 블루투스. 거부 시 **음성으로** 안내 |

**앱 TTS와 TalkBack이 동시에 발화하면 둘 다 들리지 않습니다.** 일반 안내는 `announceForAccessibility()`로 TalkBack 큐에 위임하고, 자체 TTS(`QUEUE_FLUSH`)는 계단·이탈·횡단 같은 안전 안내에만 사용합니다.

## 관련 문서

- 노션 「사용자 앱 기획」 — 상태 구조 · 입출력 규칙 · 화면 명세 · 기술 구조
- `Dev/info/사용자앱-API-설계.md`

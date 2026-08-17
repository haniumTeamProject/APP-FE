package org.mcsmtp.wayfinder.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.util.Log;


import org.mcsmtp.wayfinder.data.BeaconDevice;
import org.mcsmtp.wayfinder.data.RssiPoint;
import org.mcsmtp.wayfinder.net.NavClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BLE 스캔 → 서버 전송.
 *
 * **`server/bleapp` 의 BleScanner 를 그대로 가져온 것이다.** 조각조각 옮기다가
 * 스캔 설정·워치독·스캐너 갱신을 번번이 빠뜨려서, 아예 통째로 가져와 쓰지 않는
 * 부분만 쳐내는 쪽으로 바꿨다.
 *
 * 실측하며 겪고 고친 것들이 여기 들어 있다 — 코드만 보면 왜 필요한지 안 보이는 것들이다.
 *
 *   · MATCH_NUM_MAX_ADVERTISEMENT   기본값이 광고를 억제해 수신이 뚝 떨어진다
 *   · refreshScanner()              블루투스를 껐다 켜면 옛 스캐너는 무효다
 *   · 워치독                         스캔이 조용히 죽는 것을 감지해 재시작한다
 *   · 로그를 필터 바깥에              "안 돎"과 "걸러짐"을 구분하려면 그래야 한다
 *
 * 쳐낸 것: 실측용(측정 구간·Survey 메타)과 음성(SpeechGuide·목적지 요청).
 * 목적지와 안내는 NavClient·NavCoordinator 가 맡는다.
 *
 * 바꾼 로직은 **전송부 한 곳뿐**이다.
 */
public class BleScanner {

    public interface Listener {
        void onScanUpdate(Map<String, BeaconDevice> devices, Map<String, List<RssiPoint>> history);
    }

    private static final String LOG_TAG = "BLETEST";

    private static volatile BleScanner instance;

    public static BleScanner getInstance(Context context) {
        if (instance == null) {
            synchronized (BleScanner.class) {
                if (instance == null) {
                    instance = new BleScanner(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // 웹소켓 주소를 따로 안 정해줬을 때 쓰는 기본값 (MainActivity에서 입력 후 setServerUrl로 바꿀 수 있음)

    // 서버로 전송할 비콘 이름 접두사 (테스트용 — 이 접두사로 시작하는 이름만 웹소켓으로 보냄)

    // iBeacon 광고에서 major/minor 를 뽑기 위한 값.
    //
    // 제조사 데이터의 회사 ID 0x004C(Apple) 아래에 iBeacon 규격이 실린다.
    //
    //   [0]=0x02 [1]=0x15  [2..17]=UUID(16)  [18..19]=major  [20..21]=minor  [22]=txPower
    //
    // 서버는 **major/minor 로 비콘을 가린다.** major = 100 + 층번호 라서 층까지 한 번에
    // 나오고, minor 는 펌웨어에 새겨 넣는 논리 번호라 기기를 교체해도 그대로다.
    // (MAC 은 기기를 바꾸면 달라져서 그때마다 DB 를 다시 입력해야 한다)
    private static final int APPLE_COMPANY_ID = 0x004C;
    private static final byte IBEACON_TYPE = 0x02;
    private static final byte IBEACON_LENGTH = 0x15;

    /**
     * 우리 비콘의 iBeacon UUID. **펌웨어(firmware/beacon/beacon.ino)와 같아야 한다.**
     *
     * ── 왜 이름이 아니라 UUID 로 거르는가 ────────────────────────
     *
     * 예전에는 이름이 "ESP32" 로 시작하는 것만 보냈다. 그런데 광고 패킷이 31바이트를
     * 넘어 이름을 스캔 응답으로 옮긴 뒤로, 두 패킷이 합쳐지기 전에는
     * getDeviceName() 이 null 이라 **우리 비콘이 통째로 걸러졌다.** 그래서 이름
     * 필터를 없앴는데, 이번에는 반대로 주변의 남의 iBeacon 이 전부 올라왔다
     * (major=30002 minor=60581 같은 것들이 모니터 그래프에 뜬다).
     *
     * UUID 는 원래 이 용도다 — "이 배포에 속한 비콘인가"를 가리는 값이다. 이름과
     * 달리 광고 패킷 안에 있어서 스캔 응답을 기다릴 필요가 없고, 남이 우연히 같은
     * 값을 쓸 일도 없다.
     *
     * 건물마다 UUID 를 다르게 구우면 서버가 MAC 없이도 건물을 가릴 수 있다.
     * 지금은 전 비콘이 같은 값이라 건물 판별에 MAC 을 쓴다.
     */
    private static final String BEACON_UUID = "8ec76ea3-6668-48da-9866-75be8bc86f4d";

    /**
     * 광고에 실제로 실리는 바이트. **두 벌을 들고 비교한다.**
     *
     * ESP32 의 BLEBeacon 은 UUID 를 문자열 순서 그대로 싣지 않는다. BLEUUID 가
     * 128비트 값을 ESP-IDF 방식(리틀엔디언)으로 담고 있어서, setProximityUUID 가
     * 그 내부 표현을 그대로 복사하면 **바이트가 뒤집힌 채로 나간다.**
     *
     *     문자열   8e c7 6e a3 ... 6f 4d
     *     광고     4d 6f ... a3 6e c7 8e
     *
     * 회사 ID 를 0x004C 가 아니라 0x4C00 으로 넣어야 했던 것과 같은 이유다.
     * 어느 쪽으로 나가는지는 펌웨어 라이브러리 판에 따라 달라서, 기기를 다시 구워
     * 확인하는 것보다 양쪽을 다 받아주는 편이 확실하다. 남의 UUID 가 우연히
     * 우리 것의 역순일 확률은 없다.
     */
    private static final byte[] BEACON_UUID_BYTES = hexToBytes(BEACON_UUID);
    private static final byte[] BEACON_UUID_REVERSED = reversed(BEACON_UUID_BYTES);

    private static byte[] hexToBytes(String uuid) {
        String hex = uuid.replace("-", "");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] reversed(byte[] src) {
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[src.length - 1 - i];
        return out;
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private final NavClient navClient;

    // 서버가 내려준 안내 문장을 읽어주는 TTS.
    // 판정은 서버가 하고 앱은 받은 문장을 읽기만 한다 — 문구를 바꿔도 앱을 다시 빌드할 필요가 없고,
    // /monitor를 안 열어놔도 동작한다.

    // 서버 전송용 원본 로직에서 쓰던 맵 (원본 그대로 유지)

    private final Map<String, BeaconDevice> scannedDevices = new LinkedHashMap<>();
    private final Map<String, List<RssiPoint>> rssiHistory = new LinkedHashMap<>();
    private static final int MAX_HISTORY_SIZE = 200;

    private boolean isScanning = false;
    private int sentCount = 0;

    // ---- 스캔 워치독 ----
    // 안드로이드는 오래 연속 스캔하면 오류(onScanFailed) 없이 조용히 결과 전달을 멈추는 경우가 있다.
    // 그러면 앱은 스캔 중이라고 믿고 있는데 실제로는 아무것도 안 들어와서, 사람이 "연결"을 다시
    // 누를 때까지 데이터가 끊긴다. 그래서 일정 시간 결과가 없으면 스캔을 자동으로 다시 시작한다.
    // (실사용 BLE 라이브러리들도 같은 이유로 주기적으로 스캔을 재시작한다)
    private static final long SCAN_STALL_MS = 5000;      // 이 시간 동안 결과가 없으면 멈춘 것으로 본다
    private static final long SCAN_RESTART_MIN_MS = 10000; // 재시작 최소 간격 (아래 주석 참고)
    private static final long WATCHDOG_PERIOD_MS = 2000;

    // 재시작해도 안 살아나면 간격을 늘린다. 안드로이드는 30초에 startScan 5회를 넘기면 앱의
    // 스캔을 차단하는데, 계속 두드리면 차단 창이 갱신되어 오히려 회복을 막는다.
    private static final long SCAN_RESTART_BACKOFF_1 = 30000;
    private static final long SCAN_RESTART_BACKOFF_2 = 60000;

    // 멈춘 뒤에 되살리는 것보다, 애초에 억제가 쌓이지 않게 주기적으로 스캔을 새로 시작한다.
    // 안드로이드 제한(30초에 5회)을 고려해 25초 간격 = 30초당 1.2회로 잡았다.
    private static final long SCAN_REFRESH_PERIOD_MS = 25000;

    private volatile long lastScanResultAt = 0;
    private volatile long lastScanRestartAt = 0;
    private int scanRestartCount = 0;
    private int failedRestarts = 0;                       // 재시작했는데도 결과가 안 온 횟수
    private long restartIntervalMs = SCAN_RESTART_MIN_MS;
    private volatile String lastScanIssue = "";           // 화면에 이유를 보여주기 위함

    public String getLastScanIssue() {
        return lastScanIssue;
    }

    private final android.os.Handler watchdogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable watchdogTask = new Runnable() {
        @Override
        public void run() {
            checkScanAlive();
            watchdogHandler.postDelayed(this, WATCHDOG_PERIOD_MS);
        }
    };

    /**
     * BluetoothLeScanner를 다시 가져온다.
     *
     * 생성자에서 한 번 받아둔 객체를 계속 쓰면, 블루투스를 껐다 켰을 때 그 객체가 무효가 된다.
     * null이 아니라서 startScan()이 예외도 없이 조용히 아무 일도 안 하게 되고, 워치독이
     * 아무리 재시작해도 죽은 스캐너에 대고 재시작하는 셈이라 영원히 안 살아난다.
     */
    /**
     * 스캔 설정. 기본값으로 두면 안드로이드가 "같은 내용의 광고"를 억제해서, 이름·데이터가
     * 항상 똑같은 정적 비콘(ESP32)은 한동안 보고되다가 조용히 끊긴다. 반면 데이터가 계속
     * 바뀌는 기기(로봇청소기 등)는 매번 새 광고로 인식돼 계속 들어온다.
     * 실제로 "위치는 그대로인데 ESP32만 안 잡히는" 현상이 이것 때문이었다.
     */
    private ScanSettings buildScanSettings() {
        ScanSettings.Builder b = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0);   // 배치 모드는 중복을 합쳐버리므로 즉시 보고

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            // 기기당 보고할 광고 수를 최대로. 기본값이 억제의 주된 원인이다.
            b.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
             .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE);
        }
        return b.build();
    }

    private boolean refreshScanner() {
        if (bluetoothManager != null) bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            lastScanIssue = "블루투스 어댑터 없음";
            return false;
        }
        if (!bluetoothAdapter.isEnabled()) {
            lastScanIssue = "블루투스 꺼짐";
            return false;
        }
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            lastScanIssue = "스캐너를 가져올 수 없음";
            return false;
        }
        return true;
    }

    /** 스캔을 중지했다 다시 시작한다. 중복 억제 캐시를 비우는 효과가 있다. */
    @SuppressLint("MissingPermission")
    private void restartScanNow(String reason) {
        // 어떤 경로로 끝나든 다음 시도까지 간격을 둔다.
        // (실패해서 일찍 빠져나갈 때 이걸 안 찍으면 워치독 주기마다 계속 재시도하게 됨)
        lastScanRestartAt = System.currentTimeMillis();

        if (!refreshScanner()) {
            Log.w(LOG_TAG, "스캔 재시작 불가(" + reason + "): " + lastScanIssue);
            notifyScanStatus();
            return;
        }

        scanRestartCount++;
        Log.d(LOG_TAG, "스캔 재시작 [" + reason + "] " + scanRestartCount + "번째");

        try {
            bluetoothLeScanner.stopScan(leScanCallback);
        } catch (Exception e) {
            Log.e(LOG_TAG, "스캔 중지 실패", e);
        }
        try {
            bluetoothLeScanner.startScan(null, buildScanSettings(), leScanCallback);
        } catch (Exception e) {
            Log.e(LOG_TAG, "스캔 재시작 실패", e);
            lastScanIssue = "재시작 실패: " + e.getClass().getSimpleName();
        }
        notifyScanStatus();
    }

    @SuppressLint("MissingPermission")
    private void checkScanAlive() {
        if (!isScanning) return;

        long now = System.currentTimeMillis();
        if (lastScanResultAt == 0) lastScanResultAt = now;   // 시작 직후 유예

        if (now - lastScanResultAt < SCAN_STALL_MS) {
            // 결과가 잘 들어오고 있으면 재시도 상태를 원상복구
            if (failedRestarts != 0 || restartIntervalMs != SCAN_RESTART_MIN_MS) {
                failedRestarts = 0;
                restartIntervalMs = SCAN_RESTART_MIN_MS;
                lastScanIssue = "";
            }
            // 잘 돌고 있어도 주기적으로 한 번씩 새로 시작해서 중복 억제 캐시를 비운다.
            // (정적 광고를 쏘는 비콘이 조용히 보고에서 빠지는 것을 예방)
            if (now - lastScanRestartAt >= SCAN_REFRESH_PERIOD_MS) {
                restartScanNow("주기 갱신");
            }
            return;
        }
        if (now - lastScanRestartAt < restartIntervalMs) return;

        failedRestarts++;
        Log.w(LOG_TAG, "스캔이 " + (now - lastScanResultAt) + "ms 동안 멈춤 (연속 실패 "
                + failedRestarts + ")");
        restartScanNow("멈춤 감지");   // 블루투스 상태 확인·간격 갱신은 여기서 함께 처리

        // 재시작해도 계속 안 살아나면 간격을 늘려서 안드로이드 차단을 피한다
        if (failedRestarts >= 6) {
            restartIntervalMs = SCAN_RESTART_BACKOFF_2;
            lastScanIssue = "재시작 " + failedRestarts + "회 실패 — 60초 간격으로 대기";
        } else if (failedRestarts >= 3) {
            restartIntervalMs = SCAN_RESTART_BACKOFF_1;
            lastScanIssue = "재시작 " + failedRestarts + "회 실패 — 30초 간격으로 대기";
        }

        // lastScanResultAt은 여기서 건드리지 않는다.
        // 예전에는 재시작 직후 now로 갱신했는데, 그러면 실제 결과가 하나도 안 왔는데도
        // 잠시 "정상"으로 판정되어 실패 카운터(failedRestarts)가 초기화됐다.
        // 그 탓에 백오프가 영영 발동하지 않고 10초마다 계속 두드리게 된다.
        // 다음 재시작을 막는 건 lastScanRestartAt + restartIntervalMs 검사가 이미 하고 있다.
        notifyScanStatus();
        // 웹소켓 쪽은 여기서 건드리지 않는다. 끊김 처리는 WebSocketManager가 자체 재연결로
        // 이미 담당하고 있어서, 여기서 connect()를 또 부르면 소켓이 두 번 열릴 수 있다.
    }

    /** 마지막 수신 시각·재시작 횟수를 화면에 알리기 위한 콜백 */
    public interface ScanStatusListener {
        void onScanStatus(long msSinceLastResult, int restartCount);
    }

    private ScanStatusListener scanStatusListener;

    public void setScanStatusListener(ScanStatusListener l) {
        this.scanStatusListener = l;
    }


    private void notifyScanStatus() {
        ScanStatusListener l = scanStatusListener;
        if (l == null) return;
        long since = lastScanResultAt == 0 ? 0 : System.currentTimeMillis() - lastScanResultAt;
        l.onScanStatus(since, scanRestartCount);
    }

    public int getScanRestartCount() {
        return scanRestartCount;
    }

    public long getMsSinceLastScanResult() {
        return lastScanResultAt == 0 ? -1 : System.currentTimeMillis() - lastScanResultAt;
    }

    private BleScanner(Context appContext) {
        bluetoothManager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }
        navClient = new NavClient(ServerConfig.NAVIGATION_URL);

        // 서버가 비콘 전환을 판단해서 내려주는 안내 메시지를 받아 음성으로 읽어준다
    }

    // ---- 서버 안내(음성) ----



    // ---- 음성 목적지 ----
    // 폰은 받아적은 문자열만 올려보내고, 어느 장소인지 판단하는 일은 서버가 한다.
    // 그래야 별칭이나 판정 기준을 고쳐도 앱을 다시 빌드하지 않아도 된다.
    private static final String DESTINATION_TYPE = "destination";

    /** 서버의 목적지 응답을 받는 콜백. 웹소켓 스레드에서 불린다. */










    public boolean isScanning() {
        return isScanning;
    }


    // ---- 측정 구간 제어 ----
    // 서버(/monitor)에서 버튼을 누르는 대신, 실제로 걸어다니는 폰에서 구간을 지정할 수 있게
    // "type":"measure" 형태의 제어 JSON을 웹소켓으로 보낸다. RSSI 전송과 같은 연결을 쓴다.
    private static final String MEASURE_TYPE = "measure";

    private String measureSessionId = null;
    private String measureLabel = null;
    private int markCount = 0;








    /** 서버와의 통로. 화면들이 여기에 리스너를 붙인다. */
    public NavClient client() {
        return navClient;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onScanUpdate(getScannedDevices(), getRssiHistory());
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized Map<String, BeaconDevice> getScannedDevices() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(scannedDevices));
    }

    public synchronized Map<String, List<RssiPoint>> getRssiHistory() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(rssiHistory));
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (isScanning) return;

        // 생성자 때 받아둔 스캐너는 블루투스를 껐다 켜면 무효가 되므로 여기서 다시 가져온다
        if (!refreshScanner()) {
            Log.d(LOG_TAG, "스캔 시작 불가: " + lastScanIssue);
            return;
        }

        navClient.connect();

        bluetoothLeScanner.startScan(null, buildScanSettings(), leScanCallback);
        isScanning = true;

        // 스캔을 새로 시작하면 중복 통계·재시도 상태도 새로 센다
        resetPacketStats();
        lastPacketNanos.clear();
        foreignSeen.clear();
        failedRestarts = 0;
        restartIntervalMs = SCAN_RESTART_MIN_MS;
        lastScanIssue = "";

        // 스캔이 조용히 죽는지 감시 시작
        lastScanResultAt = System.currentTimeMillis();
        lastScanRestartAt = System.currentTimeMillis();
        watchdogHandler.removeCallbacks(watchdogTask);
        watchdogHandler.postDelayed(watchdogTask, WATCHDOG_PERIOD_MS);
    }




    // ---- 중복 패킷 진단 ----
    // 같은 RSSI 값이 25개 연속으로 똑같이 나오는 구간이 관찰됐다. 실제 전파라면 ±1~2dB는
    // 흔들리므로, 안드로이드가 같은 패킷을 여러 번 전달하는지 확인이 필요하다.
    // ScanResult.getTimestampNanos()는 그 패킷이 실제로 관측된 시각이라, 값이 같으면
    // "새 패킷이 아니라 같은 패킷의 재전달"이라는 뜻이다.
    private final Map<String, Long> lastPacketNanos = new HashMap<>();

    // UUID 가 달라 버린 iBeacon. 기기당 한 번만 로그를 남기려고 들고 있다.
    // 카페 하나만 지나가도 수십 개가 잡히므로 매번 찍으면 로그가 그것만 남는다.
    private final java.util.Set<String> foreignSeen = new java.util.HashSet<>();

    // 로그를 뒤지지 않고 화면에서 바로 볼 수 있도록 중복 비율을 세어둔다
    private volatile int packetCount = 0;
    private volatile int duplicateCount = 0;

    /** 지금까지 받은 패킷 중 "같은 패킷 재전달"이었던 비율(%). 패킷이 없으면 -1 */
    public int getDuplicatePercent() {
        int n = packetCount;
        return n == 0 ? -1 : (duplicateCount * 100) / n;   // 0%와 "데이터 없음"을 구분
    }

    public int getPacketCount() {
        return packetCount;
    }

    public void resetPacketStats() {
        packetCount = 0;
        duplicateCount = 0;
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            if (result == null || result.getDevice() == null) return;

            // 워치독 기준점 — 결과가 들어오고 있다는 증거
            lastScanResultAt = System.currentTimeMillis();

            String address = result.getDevice().getAddress();
            int rssi = result.getRssi();

            if (rssi == 127) return;

            String name = null;
            if (result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name == null) name = result.getDevice().getName();
            if (name == null) name = "unknown";

            long now = System.currentTimeMillis();

            // 화면 표시용 기기 목록/이력 갱신
            BeaconDevice beacon = new BeaconDevice(address, name, rssi, now);
            synchronized (BleScanner.this) {
                scannedDevices.put(address, beacon);

                List<RssiPoint> existing = rssiHistory.get(address);
                List<RssiPoint> points = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
                points.add(new RssiPoint(now, rssi));
                while (points.size() > MAX_HISTORY_SIZE) {
                    points.remove(0);
                }
                rssiHistory.put(address, points);
            }

            // 서버 실시간 전송 — iBeacon UUID 가 우리 것인 비콘만 보낸다.
            // (화면 목록/이력에는 위에서 이미 전체 기기를 다 넣었으므로 앱에서는 전부 보이고, 웹소켓 전송만 걸러짐)
            // 로그는 필터 바깥에 둔다. 안쪽에만 있으면 "스캔이 멈춘 것"과
            // "이름이 안 잡혀 전송에서 걸러진 것"을 로그로 구분할 수 없다.
            //
            // dt = 같은 기기의 직전 패킷과의 관측 시각 차이(ms).
            //   dt=0    -> 안드로이드가 같은 패킷을 다시 전달한 것 (중복)
            //   dt=60내외 -> 광고 주기(64ms)대로 들어온 새 패킷
            long packetNanos = result.getTimestampNanos();
            Long prevNanos = lastPacketNanos.put(address, packetNanos);
            long dtMs = (prevNanos == null) ? -1 : (packetNanos - prevNanos) / 1_000_000L;

            packetCount++;
            if (dtMs == 0) duplicateCount++;

            Log.d(LOG_TAG, name + ", " + address + ", " + rssi
                    + ", dt=" + (dtMs < 0 ? "첫패킷" : dtMs + "ms")
                    + (dtMs == 0 ? " [중복]" : ""));

            // ── 서버 전송 ── (여기만 wayfinder 용으로 바꿨다)
            //
            // 이름으로 거르지 않는다. 광고 패킷이 31바이트를 넘어 이름을 스캔 응답으로
            // 옮긴 뒤로, 두 패킷이 합쳐지기 전에는 getDeviceName() 이 null 이라
            // 우리 비콘도 "unknown" 이 되어 걸러졌다. 판정은 minor 로 하므로 이름은
            // 애초에 볼 필요가 없다.
            //
            // 대신 iBeacon UUID 로 거른다(parseIBeacon). 이름과 달리 광고 패킷 안에
            // 있어서 스캔 응답을 기다릴 필요가 없다.
            int[] ids = parseIBeacon(result);
            if (ids != null) {
                sentCount++;
                if (sentCount == 1) {
                    // 첫 통과 때 UUID 를 한 번 찍는다. 펌웨어가 문자열 그대로 실었는지
                    // 뒤집어 실었는지를 이 한 줄로 알 수 있다.
                    Log.d(LOG_TAG, "UUID 통과 — 광고에 실린 값 " + uuidOfResult(result));
                }
                if (sentCount % 60 == 1) {
                    Log.d(LOG_TAG, "보냄 " + name + " " + address
                            + " major=" + ids[0] + " minor=" + ids[1]
                            + " rssi=" + rssi + " · 누적 " + sentCount);
                }
                navClient.sendBeacon(ids[0], ids[1], rssi, address, name);
            }

            notifyListeners();
        }

        /**
         * iBeacon 광고에서 major/minor 를 뽑는다. iBeacon 이 아니면 null.
         *
         * 길이와 머리 두 바이트(0x02 0x15)를 반드시 확인한다. 0x004C 는 애플이 쓰는
         * 회사 ID 라서 iBeacon 이 아닌 애플 기기(에어팟·핸드오프 등)도 같은 자리에
         * 자기 데이터를 싣는다. 확인 없이 읽으면 엉뚱한 바이트를 major 로 쓴다.
         */
        private int[] parseIBeacon(ScanResult result) {
            if (result.getScanRecord() == null) return null;
            android.util.SparseArray<byte[]> all =
                    result.getScanRecord().getManufacturerSpecificData();
            if (all == null) return null;

            // **회사 ID 를 정해놓고 찾지 않는다.**
            //
            // 규격대로면 애플의 0x004C 지만, 펌웨어가 그 값을 바이트 순서를 뒤집어
            // 넣으면 0x4C00 으로 잡힌다(우리 beacon.ino 가 setManufacturerId(0x4C00)).
            // 어느 쪽인지 확인하려고 기기를 다시 굽는 것보다, 들어온 것 중에서
            // iBeacon 모양인 것을 찾는 편이 확실하고 펌웨어가 바뀌어도 안 깨진다.
            for (int i = 0; i < all.size(); i++) {
                byte[] md = all.valueAt(i);
                if (md == null || md.length < 23) continue;
                if (md[0] != IBEACON_TYPE || md[1] != IBEACON_LENGTH) continue;

                int major = ((md[18] & 0xFF) << 8) | (md[19] & 0xFF);
                int minor = ((md[20] & 0xFF) << 8) | (md[21] & 0xFF);

                // 남의 iBeacon 은 여기서 걸러진다.
                //
                // **버리는 것을 로그로 남긴다.** 조용히 버리면 나중에 우리 비콘이
                // 안 잡힐 때 "스캔이 안 되는 것"과 "UUID 가 달라 걸러진 것"을
                // 구분할 방법이 없다 — 이름 필터 때 정확히 그래서 헤맸다.
                if (!uuidMatches(md)) {
                    if (foreignSeen.add(result.getDevice().getAddress())) {
                        Log.d(LOG_TAG, "남의 iBeacon 무시 " + result.getDevice().getAddress()
                                + " major=" + major + " minor=" + minor
                                + " uuid=" + uuidOf(md));
                    }
                    continue;
                }
                return new int[]{major, minor};
            }
            return null;
        }

        private boolean uuidMatches(byte[] md) {
            return matches(md, BEACON_UUID_BYTES) || matches(md, BEACON_UUID_REVERSED);
        }

        private boolean matches(byte[] md, byte[] want) {
            for (int i = 0; i < 16; i++) {
                if (md[2 + i] != want[i]) return false;
            }
            return true;
        }

        /** 로그용 — iBeacon 모양인 제조사 데이터를 찾아 UUID 부분만 뽑는다. */
        private String uuidOfResult(ScanResult result) {
            if (result.getScanRecord() == null) return "?";
            android.util.SparseArray<byte[]> all =
                    result.getScanRecord().getManufacturerSpecificData();
            if (all == null) return "?";
            for (int i = 0; i < all.size(); i++) {
                byte[] md = all.valueAt(i);
                if (md == null || md.length < 23) continue;
                if (md[0] != IBEACON_TYPE || md[1] != IBEACON_LENGTH) continue;
                return uuidOf(md);
            }
            return "?";
        }

        private String uuidOf(byte[] md) {
            StringBuilder sb = new StringBuilder(32);
            for (int i = 2; i < 18; i++) sb.append(String.format("%02x", md[i]));
            return sb.toString();
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            isScanning = false;
            Log.e(LOG_TAG, "스캔 실패: errorCode=" + errorCode);
        }
    };

    // 스캔 결과가 올 때마다 바로 알린다 (그래프가 부드럽게 갱신되도록).
    // 목록 화면처럼 잦은 갱신이 문제가 되는 화면은 각자 필요하면 자체적으로 갱신 빈도를 조절한다.
    private void notifyListeners() {
        Map<String, BeaconDevice> devices = getScannedDevices();
        Map<String, List<RssiPoint>> history = getRssiHistory();
        for (Listener listener : listeners) {
            listener.onScanUpdate(devices, history);
        }
    }
}

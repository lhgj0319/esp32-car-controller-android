package com.example.btcarcar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.widget.EditText;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.StorageService;

/**
 * 蓝牙小车遥控器（横屏）
 *
 * 经典蓝牙 SPP 连接 ESP32（BluetoothSerial）/ HC-05 / HC-06 等蓝牙串口模块，
 * 按住方向键持续发送指令，松开可选自动发送停止指令。
 * 适配 ESP32 状态机固件（firmware/esp32_car/esp32_car.ino）：
 * 固件收到指令后一直执行直到收到 S，因此 App 在松开按键和断开连接时都会补发停止指令。
 */
public class MainActivity extends Activity {

    /** 经典蓝牙 SPP 串口服务 UUID（HC-05 / HC-06 / JDY-31 通用，不要改） */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** ── 指令字：按你的小车固件修改这里即可 ── */
    private static final String CMD_FORWARD = "F";
    private static final String CMD_BACKWARD = "B";
    private static final String CMD_LEFT = "L";
    private static final String CMD_RIGHT = "R";
    private static final String CMD_STOP = "S";

    /** 按住按键时的连发间隔（毫秒） */
    private static final long REPEAT_INTERVAL_MS = 120;

    private static final int REQUEST_ENABLE_BT = 1001;
    private static final int REQUEST_PERMISSIONS = 1002;

    private BluetoothAdapter mAdapter;
    private BluetoothSocket mSocket;
    private OutputStream mOut;
    private InputStream mIn;
    private volatile boolean mConnected;

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Handler mRepeat = new Handler(Looper.getMainLooper());
    private Runnable mRepeatTask;

    private TextView mStatusText;
    private TextView mDeviceText;
    private TextView mLogText;
    private Button mConnectBtn;
    private CheckBox mAutoStopCheck;
    private EditText mCommandInput;

    private Model mVoskModel;
    private Recognizer mVoskRecognizer;
    private android.media.AudioRecord mAudioRecord;
    private volatile boolean mListening;

    private final List<BluetoothDevice> mDiscovered = new ArrayList<>();
    private boolean mDiscovering;
    private AlertDialog mDeviceDialog;

    /** 蓝牙搜索广播接收器 */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = extractDevice(intent);
                if (device != null && !containsDevice(device)) {
                    mDiscovered.add(device);
                    appendLog("发现设备: " + deviceLabel(device));
                    refreshDeviceDialog();
                } else if (device != null && device.getName() != null) {
                    appendLog("设备更新: " + deviceLabel(device));
                    refreshDeviceDialog();
                }
            } else if (BluetoothDevice.ACTION_NAME_CHANGED.equals(action)) {
                BluetoothDevice device = extractDevice(intent);
                if (device != null && !containsDevice(device)) {
                    mDiscovered.add(device);
                }
                appendLog("设备名称更新: " + deviceLabel(device));
                refreshDeviceDialog();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                mDiscovering = true;
                appendLog("正在搜索附近蓝牙设备…（请确保手机「位置信息」已开启）");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                mDiscovering = false;
                appendLog("搜索完成，共发现 " + mDiscovered.size() + " 个新设备");
                refreshDeviceDialog();
            }
        }
    };

    /** 从广播中安全取出 BluetoothDevice（兼容 Android 13+ 的新旧 API） */
    private BluetoothDevice extractDevice(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        } else {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        }
    }

    /** 按 MAC 地址判断是否已收录（BluetoothDevice.equals 即按地址比较） */
    private boolean containsDevice(BluetoothDevice device) {
        for (BluetoothDevice d : mDiscovered) {
            if (d.getAddress().equals(device.getAddress())) return true;
        }
        return false;
    }

    /** 统一设备显示文本：无名设备显示「未知设备」+ 地址，不再被过滤 */
    private String deviceLabel(BluetoothDevice device) {
        if (device == null) return "未知设备";
        String name = device.getName();
        return (name == null || name.isEmpty() ? "未知设备" : name) + "  " + device.getAddress();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 控制期间保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mStatusText = findViewById(R.id.statusText);
        mDeviceText = findViewById(R.id.deviceText);
        mLogText = findViewById(R.id.logText);
        mConnectBtn = findViewById(R.id.connectBtn);
        mAutoStopCheck = findViewById(R.id.autoStopCheck);
        mCommandInput = findViewById(R.id.commandInput);
        findViewById(R.id.sendCmdBtn).setOnClickListener(v -> sendTypedCommand());
        findViewById(R.id.voiceBtn).setOnClickListener(v -> startVoiceInput());

        mConnectBtn.setOnClickListener(v -> onConnectClicked());
        findViewById(R.id.btnUp).setOnTouchListener(holdSend(CMD_FORWARD));
        findViewById(R.id.btnDown).setOnTouchListener(holdSend(CMD_BACKWARD));
        findViewById(R.id.btnLeft).setOnTouchListener(holdSend(CMD_LEFT));
        findViewById(R.id.btnRight).setOnTouchListener(holdSend(CMD_RIGHT));
        findViewById(R.id.btnStop).setOnTouchListener(holdSend(CMD_STOP));

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter == null) {
            appendLog("此设备不支持蓝牙");
            mConnectBtn.setEnabled(false);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        // 系统广播（ACTION_FOUND 等）的发送方是系统进程，
        // Android 14+ 上 RECEIVER_NOT_EXPORTED 会拦截外部广播，必须用 RECEIVER_EXPORTED
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mReceiver, filter);
        }

        initOfflineVoice();
    }

    private void initOfflineVoice() {
        StorageService.unpack(this, "model", "model",
                model -> {
                    mVoskModel = model;
                    try {
                        mVoskRecognizer = new Recognizer(mVoskModel, 16000);
                        mUi.post(() -> appendLog("离线语音已就绪"));
                    } catch (Exception e) {
                        mUi.post(() -> appendLog("离线语音初始化失败: " + e.getMessage()));
                    }
                },
                exception -> mUi.post(() -> appendLog("离线语音模型加载失败: " + exception.getMessage())));
    }

    // ─────────────── 连接流程 ───────────────

    private void onConnectClicked() {
        if (mConnected) {
            disconnect();
            return;
        }
        if (mAdapter == null) {
            toast("此设备不支持蓝牙");
            return;
        }
        if (!ensurePermissions()) return;
        if (!mAdapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
            return;
        }
        showDeviceDialog();
    }

    /** 按系统版本申请蓝牙相关运行时权限 */
    private boolean ensurePermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+：新蓝牙权限
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            // 关键：BLUETOOTH_SCAN 没有 neverForLocation 时，
            // startDiscovery 在 Android 12+ 上仍然需要定位权限才能收到 ACTION_FOUND
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            // Android 6~11：搜索蓝牙设备需要定位权限
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (need.isEmpty()) {
            appendLog("权限检查通过");
            return true;
        }
        appendLog("申请权限: " + need);
        requestPermissions(need.toArray(new String[0]), REQUEST_PERMISSIONS);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1003) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceInput();
            } else {
                toast("需要麦克风权限才能使用语音控制");
            }
            return;
        }
        if (requestCode != REQUEST_PERMISSIONS) return;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                toast("需要蓝牙权限才能连接");
                return;
            }
        }
        onConnectClicked();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            showDeviceDialog();
        }
    }

    /** 弹出/刷新设备选择列表：已配对 + 已发现 + 搜索入口 */
    private void showDeviceDialog() {
        if (mAdapter == null) return;
        if (mDeviceDialog != null && mDeviceDialog.isShowing()) {
            refreshDeviceDialog();
            return;
        }
        mDeviceDialog = buildDeviceDialog();
        mDeviceDialog.show();
    }

    private void refreshDeviceDialog() {
        if (mDeviceDialog != null && mDeviceDialog.isShowing()) {
            AlertDialog fresh = buildDeviceDialog();
            mDeviceDialog.dismiss();
            mDeviceDialog = fresh;
            mDeviceDialog.show();
        }
    }

    private AlertDialog buildDeviceDialog() {
        final List<BluetoothDevice> devices = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        Set<BluetoothDevice> bonded = mAdapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                devices.add(d);
                labels.add((d.getName() == null ? "未知设备" : d.getName()) + "\n" + d.getAddress() + "（已配对）");
            }
        }
        for (BluetoothDevice d : mDiscovered) {
            boolean already = false;
            for (BluetoothDevice b : devices) {
                if (b != null && b.getAddress().equals(d.getAddress())) { already = true; break; }
            }
            if (!already) {
                devices.add(d);
                labels.add(deviceLabel(d) + "（未配对）");
            }
        }
        devices.add(null);
        labels.add(mDiscovering ? "⏳ 正在搜索新设备…" : "🔍 搜索新设备…");

        return new AlertDialog.Builder(this)
                .setTitle("选择蓝牙设备")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    BluetoothDevice dev = devices.get(which);
                    if (dev == null) {
                        startDiscovery();
                        // 保持对话框打开，边搜边刷新
                        mUi.postDelayed(this::showDeviceDialog, 300);
                    } else {
                        if (mDeviceDialog != null) mDeviceDialog.dismiss();
                        connect(dev);
                    }
                })
                .setNegativeButton("关闭", (d, w) -> {
                    if (mAdapter != null && mAdapter.isDiscovering()) mAdapter.cancelDiscovery();
                })
                .create();
    }

    private void startDiscovery() {
        if (mAdapter == null || !mAdapter.isEnabled()) {
            appendLog("⚠ 蓝牙未开启");
            return;
        }
        if (mDiscovering) {
            appendLog("已经在搜索中，请稍候…");
            return;
        }
        // 打印当前权限状态，方便诊断
        if (Build.VERSION.SDK_INT >= 31) {
            appendLog("权限状态 SCAN=" + checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    + " CONNECT=" + checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    + " LOC=" + checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    + "（0=已授权）");
        }
        // 新手机上经典蓝牙搜索依赖系统定位开关，未开启会静默失败
        if (!isLocationEnabled()) {
            appendLog("⚠ 请先打开手机系统「位置信息 / GPS」开关，否则搜索不到蓝牙设备");
            toast("请开启手机「位置信息」后再搜索");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (Exception ignored) {
            }
            return;
        }
        mDiscovered.clear();
        mDiscovering = true;
        appendLog("开始搜索附近蓝牙设备…");
        boolean started = mAdapter.startDiscovery();
        appendLog("startDiscovery() 返回: " + started);
        if (!started) {
            mDiscovering = false;
            appendLog("⚠ 搜索启动失败：请确认定位已开启、蓝牙已打开，并授予了蓝牙/定位权限");
            toast("搜索启动失败，请检查定位与权限");
        }
    }

    /** 判断系统定位服务是否开启 */
    private boolean isLocationEnabled() {
        try {
            android.location.LocationManager lm =
                    (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return true;
            if (Build.VERSION.SDK_INT >= 28) {
                return lm.isLocationEnabled();
            }
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return true;
        }
    }

    /** 后台线程连接蓝牙串口 */
    private void connect(final BluetoothDevice device) {
        setConnecting(true);
        // 连接前立即停止搜索：搜索与连接共用射频，不停会严重干扰连接
        if (mAdapter != null && mAdapter.isDiscovering()) {
            mAdapter.cancelDiscovery();
        }
        new Thread(() -> {
            // 陌生（未配对）设备先触发系统配对，很多 ROM 上安全 socket 需要已配对才能连
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                mUi.post(() -> appendLog("设备未配对，正在请求配对…"));
                try {
                    device.createBond();
                    int waited = 0;
                    while (device.getBondState() != BluetoothDevice.BOND_BONDED && waited < 15000) {
                        if (device.getBondState() == BluetoothDevice.BOND_NONE) break;
                        Thread.sleep(300);
                        waited += 300;
                    }
                } catch (Exception e) {
                    mUi.post(() -> appendLog("配对异常: " + e.getMessage()));
                }
            }

            BluetoothSocket socket = tryConnect(device);
            if (socket != null) {
                mSocket = socket;
                try {
                    mOut = socket.getOutputStream();
                    mIn = socket.getInputStream();
                    mConnected = true;
                    mUi.post(() -> {
                        setConnecting(false);
                        mDeviceText.setText("设备: " + (device.getName() == null ? "未知设备" : device.getName()));
                        mStatusText.setText("已连接");
                        mStatusText.setTextColor(0xFF2E9E5B);
                        mConnectBtn.setText("断开连接");
                        appendLog("== 已连接 " + (device.getName() == null ? device.getAddress() : device.getName()) + " ==");
                    });
                    startReadLoop();
                    return;
                } catch (IOException e) {
                    closeSocketQuietly(socket);
                }
            }

            // 所有连接方式都失败
            mConnected = false;
            closeQuietly();
            mUi.post(() -> {
                setConnecting(false);
                mStatusText.setText("未连接");
                mStatusText.setTextColor(0xFFD64545);
                mConnectBtn.setText("连接蓝牙");
                appendLog("连接失败：已尝试安全/非安全/通道1 三种方式");
            });
        }).start();
    }

    /**
     * 多级降级连接：陌生设备用安全连接常失败，这里依次尝试
     * 1) 安全 RFCOMM  2) 非安全 RFCOMM  3) 反射固定通道 1（HC-05/HC-06/ESP32 通用）
     */
    private BluetoothSocket tryConnect(BluetoothDevice device) {
        // 1. 安全连接（已配对设备优先）
        try {
            BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            appendLogOnUiThread("安全连接成功");
            return socket;
        } catch (IOException e) {
            appendLogOnUiThread("安全连接失败，尝试非安全连接…");
        }

        // 2. 非安全连接（陌生设备/无认证模块）
        try {
            BluetoothSocket socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            appendLogOnUiThread("非安全连接成功");
            return socket;
        } catch (IOException e) {
            appendLogOnUiThread("非安全连接失败，尝试反射通道1…");
        }

        // 3. 反射固定通道 1：绕过 SDP 查询，兼容大量串口蓝牙模块
        try {
            BluetoothSocket socket = (BluetoothSocket) device.getClass()
                    .getMethod("createRfcommSocket", int.class)
                    .invoke(device, 1);
            socket.connect();
            appendLogOnUiThread("反射通道1连接成功");
            return socket;
        } catch (Exception e) {
            appendLogOnUiThread("反射通道1连接失败: " + e.getMessage());
        }
        return null;
    }

    private void appendLogOnUiThread(String line) {
        mUi.post(() -> appendLog(line));
    }

    private void closeSocketQuietly(BluetoothSocket socket) {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    /** 后台线程循环读取小车返回的数据 */
    private void startReadLoop() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[256];
            InputStream in = mIn;
            try {
                int n;
                while (mConnected && in != null && (n = in.read(buf)) > 0) {
                    final String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                    mUi.post(() -> appendLog("收: " + text));
                }
            } catch (IOException ignored) {
                // 断开时 read 会抛异常，正常退出即可
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ─────────────── 发送与按键 ───────────────

    private void sendTypedCommand() {
        String command = mCommandInput.getText().toString().trim();
        if (command.length() == 0) { toast("请输入命令"); return; }
        send(command);
        appendLog("发: " + command);
    }

    private void startVoiceInput() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1003);
            return;
        }
        if (mVoskRecognizer == null) {
            toast("离线语音模型尚未加载");
            return;
        }
        if (mListening) {
            stopOfflineListening();
        } else {
            startOfflineListening();
        }
    }

    private void startOfflineListening() {
        int sampleRate = 16000;
        int bufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize <= 0) bufferSize = 4096;
        mAudioRecord = new android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                Math.max(bufferSize, 4096));
        mListening = true;
        mAudioRecord.startRecording();
        appendLog("离线语音监听中...");

        new Thread(() -> {
            byte[] buffer = new byte[2048];
            try {
                while (mListening && mAudioRecord != null) {
                    int count = mAudioRecord.read(buffer, 0, buffer.length);
                    if (count > 0 && mVoskRecognizer.acceptWaveForm(buffer, count)) {
                        handleOfflineResult(mVoskRecognizer.getResult());
                    }
                }
            } catch (Exception e) {
                mUi.post(() -> toast("离线录音失败: " + e.getMessage()));
            }
        }).start();

        mUi.postDelayed(() -> {
            if (mListening) stopOfflineListening();
        }, 6000);
    }

    private void stopOfflineListening() {
        mListening = false;
        if (mAudioRecord != null) {
            try { mAudioRecord.stop(); } catch (Exception ignored) { }
            try { mAudioRecord.release(); } catch (Exception ignored) { }
            mAudioRecord = null;
        }
        if (mVoskRecognizer != null) handleOfflineResult(mVoskRecognizer.getFinalResult());
    }

    private void handleOfflineResult(String json) {
        try {
            JSONObject object = new JSONObject(json);
            String text = object.optString("text", "").trim();
            if (text.length() == 0) return;
            mUi.post(() -> {
                mCommandInput.setText(text);
                send(text);
                appendLog("离线语音发: " + text);
            });
        } catch (Exception ignored) { }
    }

    private void send(String cmd) {
        if (!mConnected || mOut == null) {
            toast("请先连接蓝牙小车");
            return;
        }
        try {
            String payload = cmd.length() == 1 ? cmd : cmd + "\n";
            mOut.write(payload.getBytes(StandardCharsets.UTF_8));
            mOut.flush();
        } catch (IOException e) {
            toast("发送失败，连接已断开");
            disconnect();
        }
    }

    /** 按住连发、松开（可选）自动停止 的触摸监听 */
    private View.OnTouchListener holdSend(final String cmd) {
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    send(cmd);
                    mRepeatTask = () -> {
                        send(cmd);
                        mRepeat.postDelayed(mRepeatTask, REPEAT_INTERVAL_MS);
                    };
                    mRepeat.postDelayed(mRepeatTask, REPEAT_INTERVAL_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    stopRepeat();
                    if (mAutoStopCheck.isChecked()) send(CMD_STOP);
                    return true;
                default:
                    return false;
            }
        };
    }

    private void stopRepeat() {
        if (mRepeatTask != null) {
            mRepeat.removeCallbacks(mRepeatTask);
            mRepeatTask = null;
        }
    }

    // ─────────────── 断开与收尾 ───────────────

    private void disconnect() {
        stopRepeat();
        // 固件是状态机：不收到新指令就一直执行当前动作，
        // 断开前必须补发停止指令，否则小车会继续跑
        sendStopBeforeClose();
        mConnected = false;
        closeQuietly();
        mUi.post(() -> {
            mStatusText.setText("未连接");
            mStatusText.setTextColor(0xFFD64545);
            mDeviceText.setText("未选择设备");
            mConnectBtn.setText("连接蓝牙");
            appendLog("== 已断开 ==");
        });
    }

    /** 关闭连接前补发一次停止指令（针对“保持最后指令”型固件） */
    private void sendStopBeforeClose() {
        if (mOut == null) return;
        try {
            mOut.write(CMD_STOP.getBytes(StandardCharsets.UTF_8));
            mOut.flush();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly() {
        try {
            if (mIn != null) mIn.close();
        } catch (IOException ignored) {
        }
        try {
            if (mOut != null) mOut.close();
        } catch (IOException ignored) {
        }
        try {
            if (mSocket != null) mSocket.close();
        } catch (IOException ignored) {
        }
        mIn = null;
        mOut = null;
        mSocket = null;
    }

    private void setConnecting(boolean connecting) {
        mConnectBtn.setEnabled(!connecting);
        mConnectBtn.setText(connecting ? "连接中…" : (mConnected ? "断开连接" : "连接蓝牙"));
        if (connecting) {
            mStatusText.setText("连接中…");
            mStatusText.setTextColor(0xFFD9A406);
        }
    }

    /** 只在主线程调用 */
    private void appendLog(String line) {
        mLogText.append(line + "\n");
        String t = mLogText.getText().toString();
        if (t.length() > 3000) {
            mLogText.setText(t.substring(t.length() - 3000));
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDeviceDialog != null && mDeviceDialog.isShowing()) {
            try { mDeviceDialog.dismiss(); } catch (Exception ignored) {}
        }
        mDeviceDialog = null;
        stopOfflineListening();
        if (mVoskRecognizer != null) {
            mVoskRecognizer.close();
            mVoskRecognizer = null;
        }
        if (mVoskModel != null) {
            mVoskModel.close();
            mVoskModel = null;
        }
        try {
            unregisterReceiver(mReceiver);
        } catch (Exception ignored) {
        }
        if (mAdapter != null && mAdapter.isDiscovering()) {
            mAdapter.cancelDiscovery();
        }
        stopRepeat();
        sendStopBeforeClose();
        mConnected = false;
        closeQuietly();
    }
}

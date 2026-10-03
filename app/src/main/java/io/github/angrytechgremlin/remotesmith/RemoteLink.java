package io.github.angrytechgremlin.remotesmith;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

/**
 * A session with the paired remote over GATT. Jobs (upload codes, clear, suppress, watch key
 * presses, beep, list services) queue up and run one GATT operation at a time. The link connects
 * when a job needs it and stays open until {@link #close()}. All state lives on the main thread.
 * Protocol: docs/protocol.md.
 *
 * Every Bluetooth call sits behind the permission check in {@link #pump()}.
 */
@SuppressLint("MissingPermission")
final class RemoteLink {
    static final UUID IR_SERVICE = UUID.fromString("d343bfc0-5a21-4f05-bc7d-af01f617b664");
    static final UUID PROG_CONTROL = UUID.fromString("d343bfc1-5a21-4f05-bc7d-af01f617b664");
    static final UUID KEY_ID = UUID.fromString("d343bfc2-5a21-4f05-bc7d-af01f617b664");
    static final UUID CODE = UUID.fromString("d343bfc3-5a21-4f05-bc7d-af01f617b664");
    static final UUID IR_SUPPRESS = UUID.fromString("d343bfc4-5a21-4f05-bc7d-af01f617b664");
    static final UUID KEY_EVENT = UUID.fromString("d343bfc5-5a21-4f05-bc7d-af01f617b664");

    static final UUID FMR_SERVICE = UUID.fromString("18030001-5a21-4f05-bc7d-af01f617b664");
    static final UUID FMR_CONTROL = UUID.fromString("18030002-5a21-4f05-bc7d-af01f617b664");
    private static final UUID CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Longest buzz the remote accepts, in tenths of a second. */
    static final int FMR_MAX_TENTHS = 300;

    /** Find-my-remote reply events. */
    static final int BEEP_ACCEPTED = 0, BEEP_FINISHED = 1, BEEP_FOUND = 2, BEEP_CAPPED = 3, BEEP_LOW_BATTERY = 4;

    // An awake remote answers within milliseconds; a sleeping one never does.
    private static final long CONNECT_TIMEOUT_MS = 4000;
    private static final long STEP_TIMEOUT_MS = 8000;

    /** Why a job did not finish. */
    enum Failure {
        /** The app may not use Bluetooth. */
        NO_PERMISSION,
        BLUETOOTH_OFF,
        /** No Bluetooth LE device is paired at all. */
        NOT_PAIRED,
        /** Paired, but it did not answer: asleep, out of range or out of battery. */
        UNREACHABLE,
        /** The connected remote lacks the service this job needs. */
        UNSUPPORTED,
        /** The remote refused a write, or the link dropped part-way. */
        REFUSED
    }

    interface Listener {
        /** A line for the diagnostic log; never shown as is. */
        void log(String line);

        /** A job ended; failure is null when it went through. */
        void jobDone(String job, Failure failure);

        /** The remote is asleep and a patient job is waiting for it to wake. */
        void waiting();

        /** A programmed key went down or up (after watchKeys(true)). */
        void keyEvent(int keycode, boolean down);

        /** The remote reported on a buzz: one of the BEEP_ events and the tenths of a second left. */
        void beepEvent(int event, int tenths);
    }

    private enum Kind { WRITE, WRITE_NO_RESPONSE, SUBSCRIBE, UNSUBSCRIBE, LIST }

    private static final class Step {
        final Kind kind;
        final UUID service, characteristic;
        final byte[] value;
        final String label;

        Step(Kind kind, UUID service, UUID characteristic, byte[] value, String label) {
            this.kind = kind;
            this.service = service;
            this.characteristic = characteristic;
            this.value = value;
            this.label = label;
        }
    }

    private static final class Job {
        final String name;
        final ArrayDeque<Step> steps = new ArrayDeque<>();
        /** Wait for a sleeping remote to come back instead of failing after a few seconds. */
        long patienceMs;

        Job(String name) {
            this.name = name;
        }

        Job write(UUID characteristic, byte[] value, String label) {
            steps.add(new Step(Kind.WRITE, IR_SERVICE, characteristic, value, label));
            return this;
        }
    }

    private final Context context;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private final ArrayDeque<BluetoothDevice> candidates = new ArrayDeque<>();
    private Job current;
    private BluetoothGatt gatt;
    private boolean ready;
    private boolean closed;

    RemoteLink(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.prefs = this.context.getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    /**
     * Replace the remote's whole IR table: begin, then (key id, code) per key in ascending key
     * order, then end. Starting a session wipes every slot, so codes must hold the full set.
     */
    void upload(SortedMap<Integer, byte[]> codes) {
        Job job = new Job("upload").write(PROG_CONTROL, new byte[] {1}, "begin");
        for (Map.Entry<Integer, byte[]> e : codes.entrySet()) {
            int k = e.getKey();
            job.write(KEY_ID, new byte[] {(byte) (k >> 8), (byte) k}, "key " + k);
            job.write(CODE, e.getValue(), "code " + k + " (" + e.getValue().length + " bytes)");
        }
        submit(job.write(PROG_CONTROL, new byte[] {0}, "end"));
    }

    /** Erase the remote's IR table: every TV button goes back to plain Bluetooth. */
    void clear() {
        submit(new Job("clear").write(PROG_CONTROL, new byte[] {1}, "begin").write(PROG_CONTROL, new byte[] {0}, "end"));
    }

    /** Send these keys over Bluetooth instead of IR until the link drops; none = all back to IR. */
    void suppress(int... keycodes) {
        byte[] list = new byte[keycodes.length * 2];
        for (int i = 0; i < keycodes.length; i++) {
            list[2 * i] = (byte) (keycodes[i] >> 8);
            list[2 * i + 1] = (byte) keycodes[i];
        }
        submit(new Job("suppress").write(IR_SUPPRESS, list, keycodes.length == 0 ? "all keys to IR" : "keys to Bluetooth"));
    }

    /** Have the remote report presses of programmed keys, which no longer arrive as key events. */
    void watchKeys(boolean on) {
        Job job = new Job("watchKeys");
        job.steps.add(new Step(on ? Kind.SUBSCRIBE : Kind.UNSUBSCRIBE, IR_SERVICE, KEY_EVENT, null,
                on ? "report key presses" : "stop reporting key presses"));
        submit(job);
    }

    /**
     * Sound the remote's buzzer for this long (tenths of a second); 0 stops a running buzz. With
     * patienceMs above zero, a sleeping remote is waited for that long.
     */
    void beep(int tenths, long patienceMs) {
        int t = Math.max(0, Math.min(FMR_MAX_TENTHS, tenths));
        Job job = new Job("beep");
        job.patienceMs = patienceMs;
        // The remote answers a buzz request with notifications, so subscribe before asking.
        job.steps.add(new Step(Kind.SUBSCRIBE, FMR_SERVICE, FMR_CONTROL, null, "listen for the remote's reply"));
        // Request: 0x00, then the duration in tenths of a second, big-endian.
        job.steps.add(new Step(Kind.WRITE_NO_RESPONSE, FMR_SERVICE, FMR_CONTROL,
                new byte[] {0, (byte) (t >> 8), (byte) t}, t == 0 ? "stop buzz" : "buzz " + t / 10.0 + " s"));
        submit(job);
    }

    /** Read-only: log every service and characteristic on the remote. */
    void listServices() {
        Job job = new Job("listServices");
        job.steps.add(new Step(Kind.LIST, null, null, null, "list services"));
        submit(job);
    }

    /** Give up on a job that is waiting for a sleeping remote, so the jobs behind it can run. */
    void cancelWaiting() {
        handler.post(() -> {
            if (current != null && current.patienceMs > 0 && !ready) {
                dropLink();
                finishJob(Failure.UNREACHABLE);
            }
        });
    }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        jobs.clear();
        current = null;
        dropLink();
    }

    private void submit(Job job) {
        handler.post(() -> {
            if (closed) return;
            jobs.add(job);
            pump();
        });
    }

    private void pump() {
        if (current != null || jobs.isEmpty()) return;
        current = jobs.poll();
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            dropLink();
            finishJob(Failure.NO_PERMISSION);
        } else if (ready) {
            runStep();
        } else {
            connect();
        }
    }

    private void finishJob(Failure failure) {
        handler.removeCallbacks(stepTimeout);
        handler.removeCallbacks(connectTimeout);
        Job job = current;
        current = null;
        if (job != null) {
            listener.log(job.name + (failure == null ? ": done" : ": failed, " + failure));
            listener.jobDone(job.name, failure);
        }
        pump();
    }

    private void dropLink() {
        ready = false;
        if (gatt != null) {
            gatt.close();
            gatt = null;
        }
    }

    // --- connecting -------------------------------------------------------------------------

    private void connect() {
        BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            finishJob(Failure.BLUETOOTH_OFF);
            return;
        }
        // The remote we used last goes first, then anything named like a remote, then the rest:
        // every wrong guess costs a connect timeout.
        String last = prefs.getString("address", "");
        candidates.clear();
        ArrayDeque<BluetoothDevice> named = new ArrayDeque<>(), others = new ArrayDeque<>();
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (d.getType() != BluetoothDevice.DEVICE_TYPE_LE && d.getType() != BluetoothDevice.DEVICE_TYPE_DUAL) continue;
            if (d.getAddress().equals(last)) {
                candidates.add(d);
            } else if (String.valueOf(d.getName()).toLowerCase(Locale.ROOT).contains("remote")) {
                named.add(d);
            } else {
                others.add(d);
            }
        }
        candidates.addAll(named);
        candidates.addAll(others);
        if (candidates.isEmpty()) {
            finishJob(Failure.NOT_PAIRED);
            return;
        }
        if (current.patienceMs > 0) {
            // A background connection completes whenever the remote next wakes and advertises.
            BluetoothDevice d = candidates.peekFirst();
            candidates.clear();
            listener.log("waiting for " + d.getName() + " to wake up");
            listener.waiting();
            gatt = d.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE);
            handler.postDelayed(connectTimeout, current.patienceMs);
        } else {
            tryNextCandidate();
        }
    }

    private void tryNextCandidate() {
        handler.removeCallbacks(connectTimeout);
        dropLink();
        BluetoothDevice d = candidates.poll();
        if (d == null) {
            finishJob(Failure.UNREACHABLE);
            return;
        }
        gatt = d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private final Runnable connectTimeout = this::onConnectTimeout;

    private void onConnectTimeout() {
        if (ready || current == null) return;
        if (current.patienceMs > 0) {
            dropLink();
            finishJob(Failure.UNREACHABLE);
        } else {
            tryNextCandidate();
        }
    }

    private void onServices(BluetoothGatt g) {
        if (g.getService(IR_SERVICE) == null && g.getService(FMR_SERVICE) == null) {
            // Some other paired LE device (a game controller, say).
            if (current != null && current.patienceMs > 0) {
                dropLink();
                finishJob(Failure.UNSUPPORTED);
            } else {
                tryNextCandidate();
            }
            return;
        }
        handler.removeCallbacks(connectTimeout);
        ready = true;
        prefs.edit().putString("address", g.getDevice().getAddress()).apply();
        listener.log("connected to " + g.getDevice().getName());
        if (current != null) runStep();
    }

    // --- running a job ----------------------------------------------------------------------

    private void runStep() {
        Step step = current.steps.peek();
        if (step == null) {
            finishJob(null);
            return;
        }
        if (step.kind == Kind.LIST) {
            for (BluetoothGattService sv : gatt.getServices()) {
                listener.log("service " + sv.getUuid());
                for (BluetoothGattCharacteristic c : sv.getCharacteristics()) {
                    StringBuilder d = new StringBuilder();
                    for (BluetoothGattDescriptor ds : c.getDescriptors()) d.append(' ').append(ds.getUuid().toString(), 4, 8);
                    listener.log("  char " + c.getUuid() + " props=0x" + Integer.toHexString(c.getProperties())
                            + (d.length() > 0 ? " desc" + d : ""));
                }
            }
            current.steps.poll();
            runStep();
            return;
        }
        BluetoothGattService service = gatt.getService(step.service);
        BluetoothGattCharacteristic c = service == null ? null : service.getCharacteristic(step.characteristic);
        if (c == null) {
            listener.log("the remote has no " + step.characteristic);
            finishJob(Failure.UNSUPPORTED);
            return;
        }
        int rc;
        if (step.kind == Kind.SUBSCRIBE || step.kind == Kind.UNSUBSCRIBE) {
            boolean on = step.kind == Kind.SUBSCRIBE;
            BluetoothGattDescriptor config = c.getDescriptor(CLIENT_CONFIG);
            if (config == null) {
                listener.log(step.characteristic + " cannot notify");
                finishJob(Failure.UNSUPPORTED);
                return;
            }
            gatt.setCharacteristicNotification(c, on);
            rc = gatt.writeDescriptor(config, on
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        } else {
            rc = gatt.writeCharacteristic(c, step.value, step.kind == Kind.WRITE
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        }
        if (rc != BluetoothStatusCodes.SUCCESS) {
            listener.log("FAIL " + step.label + " (refused locally: " + rc + ")");
            dropLink();
            finishJob(Failure.REFUSED);
            return;
        }
        handler.postDelayed(stepTimeout, STEP_TIMEOUT_MS);
    }

    private final Runnable stepTimeout = this::onStepTimeout;

    private void onStepTimeout() {
        if (current == null) return;
        listener.log("the remote stopped answering");
        dropLink();
        finishJob(Failure.UNREACHABLE);
    }

    private void stepAnswered(int status) {
        if (current == null) return;
        handler.removeCallbacks(stepTimeout);
        Step step = current.steps.peek();
        if (step == null) return;
        if (status == BluetoothGatt.GATT_SUCCESS) {
            listener.log("ok   " + step.label);
            current.steps.poll();
            runStep();
        } else {
            listener.log("FAIL " + step.label + " (GATT status " + status + ")");
            finishJob(Failure.REFUSED);
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            handler.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean wasReady = ready;
                    if (!wasReady && current != null && current.patienceMs == 0) {
                        tryNextCandidate();
                        return;
                    }
                    if (!wasReady && current != null) return;   // still waiting patiently
                    dropLink();
                    if (current != null) {
                        listener.log("the link to the remote dropped (status " + status + ")");
                        finishJob(Failure.REFUSED);
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.post(() -> {
                if (g == gatt) onServices(g);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            handler.post(() -> {
                if (g == gatt) stepAnswered(status);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            handler.post(() -> {
                if (g == gatt) stepAnswered(status);
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            handler.post(() -> {
                if (g != gatt || value.length < 3) return;
                int word = ((value[1] & 0xFF) << 8) | (value[2] & 0xFF);
                if (KEY_EVENT.equals(c.getUuid())) {
                    listener.keyEvent(word, value[0] == 0);
                } else if (FMR_CONTROL.equals(c.getUuid())) {
                    listener.beepEvent(value[0] & 0xFF, word);
                }
            });
        }
    };
}

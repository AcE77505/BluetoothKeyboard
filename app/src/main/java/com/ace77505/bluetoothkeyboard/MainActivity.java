package com.ace77505.bluetoothkeyboard;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.DynamicColors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "keyboard_prefs";
    private static final String KEY_LAST_CONNECTED_ADDRESS = "last_connected_address";
    private static final byte REPORT_ID_KEYBOARD = 1;
    private static volatile boolean hidAppRegisteredInProcess = false;

    private final List<BluetoothDevice> bondedDevices = new ArrayList<>();
    private final ExecutorService hidExecutor = Executors.newSingleThreadExecutor();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHidDevice bluetoothHidDevice;
    private BluetoothDevice connectedHost;
    private BluetoothDevice selectedDevice;

    private TextView statusText;
    private EditText inputEditText;
    private Button connectButton;
    private DeviceListAdapter deviceListAdapter;

    private SharedPreferences sharedPreferences;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasBluetoothPermissions()) {
                    initBluetooth();
                    loadBondedDevices();
                } else {
                    Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
                    updateStatus(getString(R.string.status_permission_needed));
                }
            });

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = (BluetoothHidDevice) proxy;
                registerHidApp();
                autoReconnectLastDeviceIfNeeded();
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = null;
                connectedHost = null;
                hidAppRegisteredInProcess = false;
                updateStatus(getString(R.string.status_hid_disconnected));
                refreshSendState();
            }
        }
    };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            hidAppRegisteredInProcess = registered;
            if (registered) {
                updateStatus(getString(R.string.status_ready_select_device));
                autoReconnectLastDeviceIfNeeded();
            } else {
                updateStatus(getString(R.string.status_hid_register_failed));
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                connectedHost = device;
                persistLastConnected(device);
                updateStatus(getString(R.string.status_connected, readableName(device)));
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                updateStatus(getString(R.string.status_connecting, readableName(device)));
            } else {
                if (connectedHost != null && connectedHost.getAddress().equals(device.getAddress())) {
                    connectedHost = null;
                }
                updateStatus(getString(R.string.status_disconnected, readableName(device)));
            }
            refreshSendState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivitiesIfAvailable(getApplication());
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        statusText = findViewById(R.id.statusText);
        inputEditText = findViewById(R.id.inputEditText);
        connectButton = findViewById(R.id.connectButton);

        ListView deviceListView = findViewById(R.id.deviceListView);
        deviceListAdapter = new DeviceListAdapter(this, bondedDevices);
        deviceListView.setAdapter(deviceListAdapter);
        deviceListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            selectedDevice = bondedDevices.get(position);
            refreshSendState();
        });

        connectButton.setOnClickListener(v -> connectSelectedDevice());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        });
        inputEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // no-op
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (connectedHost == null || bluetoothHidDevice == null) {
                    return;
                }

                if (before > 0) {
                    hidExecutor.execute(() -> {
                        for (int i = 0; i < before; i++) {
                            sendBackspace();
                            sleepShortly();
                        }
                    });
                }

                if (count > 0) {
                    String inserted = s.subSequence(start, start + count).toString();
                    hidExecutor.execute(() -> {
                        for (char c : inserted.toCharArray()) {
                            sendKeyForChar(c);
                            sleepShortly();
                        }
                    });
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                // no-op
            }
        });

        refreshSendState();
        requestBluetoothPermissionsIfNeeded();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Intentionally keep HID session alive when leaving UI so host connection isn't dropped.
        // Session cleanup is left to system process teardown.
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasBluetoothPermissions()) {
            initBluetooth();
            loadBondedDevices();
            autoReconnectLastDeviceIfNeeded();
        }
    }

    private void requestBluetoothPermissionsIfNeeded() {
        if (hasBluetoothPermissions()) {
            initBluetooth();
            loadBondedDevices();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            });
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
            });
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED;
    }

    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            updateStatus(getString(R.string.status_bluetooth_not_supported));
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            updateStatus(getString(R.string.status_enable_bluetooth));
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            updateStatus(getString(R.string.status_need_android_p));
            return;
        }

        if (bluetoothHidDevice != null) {
            registerHidApp();
            return;
        }

        bluetoothAdapter.getProfileProxy(this, serviceListener, BluetoothProfile.HID_DEVICE);
    }

    private void registerHidApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || bluetoothHidDevice == null) {
            return;
        }
        if (isHidAppRegistered()) {
            updateStatus(getString(R.string.status_ready_select_device));
            return;
        }

        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                getString(R.string.hid_name),
                getString(R.string.hid_description),
                getString(R.string.hid_provider),
                BluetoothHidDevice.SUBCLASS1_COMBO,
                buildKeyboardDescriptor()
        );

        boolean ok = bluetoothHidDevice.registerApp(sdp, null, null, hidExecutor, hidCallback);
        if (!ok) {
            updateStatus(getString(R.string.status_hid_register_failed));
        }
    }

    private boolean isHidAppRegistered() {
        return hidAppRegisteredInProcess;
    }

    private void loadBondedDevices() {
        bondedDevices.clear();
        if (bluetoothAdapter == null || !hasBluetoothPermissions()) {
            deviceListAdapter.notifyDataSetChanged();
            return;
        }

        Set<BluetoothDevice> set = bluetoothAdapter.getBondedDevices();
        if (set != null) {
            bondedDevices.addAll(set);
        }

        String lastAddress = sharedPreferences.getString(KEY_LAST_CONNECTED_ADDRESS, null);
        sortDevices(bondedDevices, lastAddress);

        deviceListAdapter.notifyDataSetChanged();
        if (bondedDevices.isEmpty()) {
            updateStatus(getString(R.string.status_no_paired));
        }
    }

    private void connectSelectedDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || bluetoothHidDevice == null) {
            Toast.makeText(this, R.string.hid_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedDevice == null) {
            Toast.makeText(this, R.string.select_device_first, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean requested = bluetoothHidDevice.connect(selectedDevice);
        if (!requested) {
            Toast.makeText(this, R.string.connect_request_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void autoReconnectLastDeviceIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        if (bluetoothHidDevice == null || connectedHost != null || !isHidAppRegistered()) {
            return;
        }

        String lastAddress = sharedPreferences.getString(KEY_LAST_CONNECTED_ADDRESS, null);
        if (TextUtils.isEmpty(lastAddress)) {
            return;
        }

        BluetoothDevice lastDevice = findBondedDeviceByAddress(lastAddress);
        if (lastDevice == null) {
            return;
        }

        int state = bluetoothHidDevice.getConnectionState(lastDevice);
        if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING) {
            selectedDevice = lastDevice;
            refreshSendState();
            return;
        }

        selectedDevice = lastDevice;
        refreshSendState();
        bluetoothHidDevice.connect(lastDevice);
    }

    private BluetoothDevice findBondedDeviceByAddress(String address) {
        if (bluetoothAdapter == null || TextUtils.isEmpty(address) || !hasBluetoothPermissions()) {
            return null;
        }
        Set<BluetoothDevice> bondedSet = bluetoothAdapter.getBondedDevices();
        if (bondedSet == null) {
            return null;
        }
        for (BluetoothDevice device : bondedSet) {
            if (address.equals(device.getAddress())) {
                return device;
            }
        }
        return null;
    }

    private void sendKeyForChar(char c) {
        KeyStroke keyStroke = KeyStroke.from(c);
        if (keyStroke == null || connectedHost == null || bluetoothHidDevice == null) {
            return;
        }

        byte[] report = new byte[8];
        report[0] = keyStroke.modifier;
        report[2] = keyStroke.keyCode;
        bluetoothHidDevice.sendReport(connectedHost, REPORT_ID_KEYBOARD, report);

        byte[] release = new byte[8];
        bluetoothHidDevice.sendReport(connectedHost, REPORT_ID_KEYBOARD, release);
    }

    private void sendBackspace() {
        if (connectedHost == null || bluetoothHidDevice == null) {
            return;
        }
        byte[] report = new byte[8];
        report[2] = 0x2A;
        bluetoothHidDevice.sendReport(connectedHost, REPORT_ID_KEYBOARD, report);
        bluetoothHidDevice.sendReport(connectedHost, REPORT_ID_KEYBOARD, new byte[8]);
    }

    private void refreshSendState() {
        runOnUiThread(() -> {
            boolean readyToConnect = selectedDevice != null;
            boolean readyToSend = connectedHost != null;
            connectButton.setEnabled(readyToConnect);
            inputEditText.setEnabled(readyToSend);
        });
    }

    private void updateStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    private void persistLastConnected(BluetoothDevice device) {
        sharedPreferences.edit().putString(KEY_LAST_CONNECTED_ADDRESS, device.getAddress()).apply();
        sortDevices(bondedDevices, device.getAddress());
        runOnUiThread(() -> deviceListAdapter.notifyDataSetChanged());
    }

    private void sortDevices(List<BluetoothDevice> devices, String lastConnectedAddress) {
        Collections.sort(devices, Comparator
                .comparing((BluetoothDevice d) -> !d.getAddress().equals(lastConnectedAddress))
                .thenComparing(this::readableName));
    }

    @NonNull
    private String readableName(BluetoothDevice device) {
        String name = hasBluetoothPermissions() ? device.getName() : null;
        if (TextUtils.isEmpty(name)) {
            return device.getAddress();
        }
        return name + " (" + device.getAddress() + ")";
    }

    private byte[] buildKeyboardDescriptor() {
        return new byte[]{
                0x05, 0x01,
                0x09, 0x06,
                (byte) 0xA1, 0x01,
                (byte) 0x85, 0x01,
                0x05, 0x07,
                0x19, (byte) 0xE0,
                0x29, (byte) 0xE7,
                0x15, 0x00,
                0x25, 0x01,
                0x75, 0x01,
                (byte) 0x95, 0x08,
                (byte) 0x81, 0x02,
                (byte) 0x95, 0x01,
                0x75, 0x08,
                (byte) 0x81, 0x01,
                (byte) 0x95, 0x06,
                0x75, 0x08,
                0x15, 0x00,
                0x25, 0x65,
                0x05, 0x07,
                0x19, 0x00,
                0x29, 0x65,
                (byte) 0x81, 0x00,
                (byte) 0xC0
        };
    }

    private void sleepShortly() {
        try {
            Thread.sleep(12);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static class DeviceListAdapter extends ArrayAdapter<BluetoothDevice> {

        private final LayoutInflater inflater;

        DeviceListAdapter(@NonNull Context context, @NonNull List<BluetoothDevice> devices) {
            super(context, 0, devices);
            inflater = LayoutInflater.from(context);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(android.R.layout.simple_list_item_single_choice, parent, false);
            }

            BluetoothDevice device = getItem(position);
            TextView text = view.findViewById(android.R.id.text1);
            if (device != null) {
                String deviceName = device.getName();
                if (TextUtils.isEmpty(deviceName)) {
                    deviceName = device.getAddress();
                } else {
                    deviceName = deviceName + "\n" + device.getAddress();
                }
                text.setText(deviceName);
            }
            return view;
        }
    }

    private static class KeyStroke {
        final byte keyCode;
        final byte modifier;

        KeyStroke(byte keyCode, byte modifier) {
            this.keyCode = keyCode;
            this.modifier = modifier;
        }

        static KeyStroke from(char c) {
            if (c >= 'a' && c <= 'z') {
                return new KeyStroke((byte) (0x04 + (c - 'a')), (byte) 0x00);
            }
            if (c >= 'A' && c <= 'Z') {
                return new KeyStroke((byte) (0x04 + (c - 'A')), (byte) 0x02);
            }
            if (c >= '1' && c <= '9') {
                return new KeyStroke((byte) (0x1E + (c - '1')), (byte) 0x00);
            }
            if (c == '0') {
                return new KeyStroke((byte) 0x27, (byte) 0x00);
            }

            switch (c) {
                case ' ':
                    return new KeyStroke((byte) 0x2C, (byte) 0x00);
                case '\n':
                    return new KeyStroke((byte) 0x28, (byte) 0x00);
                case '.':
                    return new KeyStroke((byte) 0x37, (byte) 0x00);
                case ',':
                    return new KeyStroke((byte) 0x36, (byte) 0x00);
                case '!':
                    return new KeyStroke((byte) 0x1E, (byte) 0x02);
                case '?':
                    return new KeyStroke((byte) 0x38, (byte) 0x02);
                case '-':
                    return new KeyStroke((byte) 0x2D, (byte) 0x00);
                case '_':
                    return new KeyStroke((byte) 0x2D, (byte) 0x02);
                case ':':
                    return new KeyStroke((byte) 0x33, (byte) 0x02);
                case ';':
                    return new KeyStroke((byte) 0x33, (byte) 0x00);
                case '/':
                    return new KeyStroke((byte) 0x38, (byte) 0x00);
                case '@':
                    return new KeyStroke((byte) 0x1F, (byte) 0x02);
                default:
                    return null;
            }
        }
    }
}

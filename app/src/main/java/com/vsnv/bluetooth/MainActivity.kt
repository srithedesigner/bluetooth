package com.vsnv.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vsnv.bluetooth.ui.theme.BluetoothTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    // --- Bluetooth Core Components ---
    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }

    // --- BLE Components for Discovery ---
    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }
    private val bleAdvertiser by lazy { bluetoothAdapter?.bluetoothLeAdvertiser }

    // --- Audio Components ---
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var bufferSizeInBytes = 0
    // NEW: Audio Effects for cleaning up mic input
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    // --- Permissions ---
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.RECORD_AUDIO
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.RECORD_AUDIO
        )
    }

    // --- State Management ---
    private var hasPermissions by mutableStateOf(false)
    private var isScanning by mutableStateOf(false)
    private var isAdvertising by mutableStateOf(false)
    private var isServerRunning by mutableStateOf(false)
    private var discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private var connectionStatus by mutableStateOf("Not connected")
    private var isTransmitting by mutableStateOf(false)

    // --- Custom UUID for your app ---
    companion object {
        val AUDIO_SHARE_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        const val SERVICE_NAME = "AudioShareService"

        // MODIFIED: Audio Configuration optimized for voice
        private const val SAMPLE_RATE = 16000 // Good quality for voice
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    // --- Classic Bluetooth Components for Connection ---
    private var bluetoothServerSocket: BluetoothServerSocket? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var connectedThread: ConnectedThread? = null

    // --- Activity Result Launchers ---
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
        if (!hasPermissions) {
            Toast.makeText(this, "All permissions are required.", Toast.LENGTH_SHORT).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, "Bluetooth must be enabled.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasPermissions = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        setContent {
            BluetoothTheme {
                if (hasPermissions) {
                    AudioShareScreen(
                        devices = discoveredDevices,
                        isScanning = isScanning,
                        isBroadcasting = isAdvertising,
                        isServerRunning = isServerRunning,
                        connectionStatus = connectionStatus,
                        isTransmitting = isTransmitting,
                        onStartServerClick = { startServerAndAdvertise() },
                        onScanClick = { startBleScan() },
                        onDeviceClick = { device -> connectToDevice(device) },
                        onTransmitClick = { toggleTransmission() }
                    )
                } else {
                    PermissionRequestScreen(
                        onRequestPermissions = { permissionLauncher.launch(requiredPermissions) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        stopBleScan()
        stopAdvertising()
        stopTransmission()
        releaseAudioResources()
        connectedThread?.cancel()
    }

    // =================================================================================
    // ADVERTISING & SERVER (HOST ROLE)
    // =================================================================================

    private fun startServerAndAdvertise() {
        if (bluetoothAdapter?.isEnabled == false) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startServer()
        startAdvertising()
    }

    private fun startAdvertising() {
        if (isAdvertising) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(AUDIO_SHARE_UUID))
            .build()
        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (!isAdvertising) return
        bleAdvertiser?.stopAdvertising(advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i("BLE", "Advertising started successfully.")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BLE", "Advertising onStartFailure: $errorCode")
            isAdvertising = false
        }
    }

    private fun startServer() {
        if (isServerRunning) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                bluetoothServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, AUDIO_SHARE_UUID)
                withContext(Dispatchers.Main) {
                    isServerRunning = true
                    connectionStatus = "Server listening..."
                }
                val socket = bluetoothServerSocket?.accept()
                socket?.let {
                    withContext(Dispatchers.Main) {
                        connectionStatus = "Connected to ${it.remoteDevice.name}"
                        stopAdvertising()
                    }
                    manageConnectedSocket(it)
                }
            } catch (e: IOException) { Log.e("Bluetooth", "Server socket failed: ${e.message}") }
        }
    }

    private fun stopServer() {
        isServerRunning = false
        try {
            bluetoothServerSocket?.close()
        } catch (e: IOException) { Log.e("Bluetooth", "Error closing server socket", e) }
    }


    // =================================================================================
    // SCANNING & CONNECTING (CLIENT ROLE)
    // =================================================================================

    private fun startBleScan() {
        if (bluetoothAdapter?.isEnabled == false) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (isScanning) return
        discoveredDevices.clear()
        val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(AUDIO_SHARE_UUID)).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        isScanning = true
    }

    private fun stopBleScan() {
        if (!isScanning) return
        bleScanner?.stopScan(scanCallback)
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!discoveredDevices.contains(device) && device.name != null) {
                discoveredDevices.add(device)
            }
        }
        override fun onScanFailed(errorCode: Int) { Log.e("BLE", "Scan failed: $errorCode") }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        stopBleScan()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { connectionStatus = "Connecting to ${device.name}..." }
                val socket = device.createRfcommSocketToServiceRecord(AUDIO_SHARE_UUID)
                socket.connect()
                withContext(Dispatchers.Main) {
                    connectionStatus = "Connected to ${device.name}"
                }
                manageConnectedSocket(socket)
            } catch (e: IOException) {
                Log.e("Bluetooth", "Connection failed: ${e.message}")
                withContext(Dispatchers.Main) { connectionStatus = "Connection failed" }
            }
        }
    }

    // =================================================================================
    // AUDIO & DATA TRANSFER
    // =================================================================================

    private fun manageConnectedSocket(socket: BluetoothSocket) {
        bluetoothSocket = socket
        setupAudio()
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
    }

    // MODIFIED: Set up audio components and apply audio effects
    private fun setupAudio() {
        Log.d("Audio", "Setting up AudioRecord and AudioTrack")
        try {
            bufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            audioRecord = AudioRecord(android.media.MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSizeInBytes)
            audioTrack = AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT, bufferSizeInBytes, AudioTrack.MODE_STREAM)
            audioTrack?.play()

            // NEW: Apply audio effects
            val sessionId = audioRecord!!.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)
                acousticEchoCanceler?.enabled = true
                Log.i("Audio", "AcousticEchoCanceler enabled")
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.i("Audio", "NoiseSuppressor enabled")
            }

        } catch (e: Exception) {
            Log.e("Audio", "Error setting up audio components", e)
        }
    }

    private fun toggleTransmission() {
        if (isTransmitting) {
            stopTransmission()
        } else {
            startTransmission()
        }
    }

    private fun startTransmission() {
        Log.d("Audio", "Starting transmission...")
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("Audio", "AudioRecord not initialized")
            return
        }
        isTransmitting = true
        audioRecord?.startRecording()

        lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSizeInBytes)
            while (isActive && isTransmitting) {
                val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readBytes > 0) {
                    connectedThread?.write(buffer)
                }
            }
        }
    }

    private fun stopTransmission() {
        Log.d("Audio", "Stopping transmission...")
        if (isTransmitting) {
            isTransmitting = false
            audioRecord?.stop()
        }
    }

    // MODIFIED: Clean up audio effects
    private fun releaseAudioResources() {
        acousticEchoCanceler?.release()
        noiseSuppressor?.release()
        acousticEchoCanceler = null
        noiseSuppressor = null

        audioRecord?.release()
        audioRecord = null
        audioTrack?.release()
        audioTrack = null
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val inputStream: InputStream = socket.inputStream
        private val outputStream: OutputStream = socket.outputStream

        override fun run() {
            val buffer = ByteArray(bufferSizeInBytes)
            while (true) {
                try {
                    val numBytes = inputStream.read(buffer)
                    audioTrack?.write(buffer, 0, numBytes)
                } catch (e: IOException) {
                    Log.d("Bluetooth", "Input stream was disconnected", e)
                    lifecycleScope.launch(Dispatchers.Main) {
                        connectionStatus = "Connection lost"
                        stopTransmission()
                    }
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                outputStream.write(bytes)
            } catch (e: IOException) {
                Log.e("Bluetooth", "Error occurred when sending data", e)
            }
        }

        fun cancel() {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e("Bluetooth", "Could not close the connect socket", e)
            }
        }
    }
}

// =================================================================================
// UI COMPONENTS
// =================================================================================

@Composable
fun PermissionRequestScreen(onRequestPermissions: () -> Unit) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Permissions Required", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "This app needs Bluetooth and Microphone permissions to function.",
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermissions) {
                Text("Grant Permissions")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioShareScreen(
    devices: List<BluetoothDevice>,
    isScanning: Boolean,
    isBroadcasting: Boolean,
    isServerRunning: Boolean,
    connectionStatus: String,
    isTransmitting: Boolean,
    onStartServerClick: () -> Unit,
    onScanClick: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit,
    onTransmitClick: () -> Unit
) {
    val isConnected = connectionStatus.startsWith("Connected")

    Scaffold(
        topBar = { TopAppBar(title = { Text("Audio Share (Hybrid)") }) }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = if (isServerRunning) "🟢 Server Listening" else "⚪ Server Offline")
                    Text(text = if (isBroadcasting) "📡 Broadcasting (Visible)" else "🔒 Hidden")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = connectionStatus, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartServerClick, enabled = !isBroadcasting && !isConnected, modifier = Modifier.weight(1f)) {
                    Text("Become Host")
                }
                Button(onClick = onScanClick, enabled = !isScanning && !isConnected, modifier = Modifier.weight(1f)) {
                    Text(if (isScanning) "Scanning..." else "Find Host")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // MODIFIED: Streaming button text and state name
            Button(
                onClick = onTransmitClick,
                enabled = isConnected,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTransmitting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isTransmitting) "Mute Microphone" else "Unmute Microphone")
            }

            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (devices.isEmpty() && !isScanning) {
                    item {
                        Text(
                            text = "No hosts found.\n\n" +
                                    "Make sure the other device:\n" +
                                    "1. Has this app running\n" +
                                    "2. Clicked 'Become a Host'",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    items(devices, key = { it.address }) { device ->
                        DeviceListItem(device = device, onClick = onDeviceClick)
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceListItem(device: BluetoothDevice, onClick: (BluetoothDevice) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick(device) },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(device.name ?: "Unknown Device", style = MaterialTheme.typography.titleMedium)
            Text(device.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}


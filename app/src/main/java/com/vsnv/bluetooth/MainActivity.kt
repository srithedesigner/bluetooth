package com.vsnv.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vsnv.bluetooth.ui.theme.BluetoothTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*

@SuppressLint("MissingPermission") // Permissions are checked dynamically
class MainActivity : ComponentActivity() {

    // --- Bluetooth Core Components ---
    private val bluetoothManager by lazy { getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }

    // --- Permissions ---
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_ADMIN)
    }

    // --- State Management ---
    private var hasPermissions by mutableStateOf(false)
    private var isDiscovering by mutableStateOf(false)
    private var discoveredDevices = mutableStateListOf<BluetoothDevice>()

    // --- UUID for Audio Streaming (A2DP Profile) ---
    private val A2DP_UUID: UUID = UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB")
    private var bluetoothSocket: BluetoothSocket? = null

    // --- Activity Result Launchers ---
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
        if (!hasPermissions) Toast.makeText(this, "Permissions are required.", Toast.LENGTH_SHORT).show()
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) Toast.makeText(this, "Bluetooth must be enabled.", Toast.LENGTH_SHORT).show()
    }

    // --- Broadcast Receiver for Classic Discovery ---
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        // MODIFIED: Accept the device even if its name is null.
                        if (!discoveredDevices.contains(it)) {
                            Log.i("BluetoothClassic", "✅ Found device! Name: ${it.name ?: "N/A"}, Address: ${it.address}")
                            discoveredDevices.add(it)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.i("BluetoothClassic", "🟡 Discovery finished.")
                    isDiscovering = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasPermissions = requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

        setContent {
            BluetoothTheme {
                SystemBroadcastReceiver() // Manages receiver lifecycle

                if (hasPermissions) {
                    ClassicBluetoothScreen(
                        devices = discoveredDevices,
                        isDiscovering = isDiscovering,
                        onScanClick = { startClassicDiscovery() },
                        onDeviceClick = { device -> connectToDevice(device) }
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
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e("BluetoothClassic", "Could not close the socket", e)
        }
    }

    private fun startClassicDiscovery() {
        if (bluetoothAdapter?.isEnabled == false) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter!!.cancelDiscovery()
        }
        Log.d("BluetoothClassic", "🚀 Starting discovery...")
        discoveredDevices.clear()
        bluetoothAdapter?.startDiscovery()
        isDiscovering = true
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter!!.cancelDiscovery()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connecting to ${device.name ?: device.address}...", Toast.LENGTH_SHORT).show()
                }

                bluetoothSocket = device.createRfcommSocketToServiceRecord(A2DP_UUID)
                bluetoothSocket?.connect()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "✅ Connected!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Log.e("BluetoothClassic", "🔴 Connection failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connection failed.", Toast.LENGTH_SHORT).show()
                }
                try {
                    bluetoothSocket?.close()
                } catch (closeException: IOException) {
                    Log.e("BluetoothClassic", "Could not close the socket", closeException)
                }
            }
        }
    }

    @Composable
    private fun SystemBroadcastReceiver() {
        val context = LocalContext.current
        DisposableEffect(Unit) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(discoveryReceiver, filter)
            onDispose { context.unregisterReceiver(discoveryReceiver) }
        }
    }
}



// --- UI Composable Functions ---

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
            Text("This app needs Bluetooth & Location permissions to find and connect to audio devices.", textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermissions) {
                Text("Grant Permissions")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassicBluetoothScreen(
    devices: List<BluetoothDevice>,
    isDiscovering: Boolean,
    onScanClick: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Classic Bluetooth Audio") }) }) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onScanClick,
                enabled = !isDiscovering,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isDiscovering) "Discovering..." else "Scan for Audio Devices")
            }
            if (isDiscovering) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(devices, key = { it.address }) { device ->
                    DeviceListItem(device = device, onClick = onDeviceClick)
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceListItem(device: BluetoothDevice, onClick: (BluetoothDevice) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick(device) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(device.name ?: "Unknown Device", style = MaterialTheme.typography.titleMedium)
            Text(device.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}
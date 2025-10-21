package org.kmp.shots.k.sensor.sample

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import org.kmp.shots.k.sensor.KSensor
import org.kmp.shots.k.sensor.PermissionStatus
import org.kmp.shots.k.sensor.PermissionType
import org.kmp.shots.k.sensor.SensorData
import org.kmp.shots.k.sensor.SensorType

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                App()
            }
        }
    }
}

private enum class Page(val title: String) { 
    Location("Location"),
    Motion("Motion"),
    Environment("Environment"),
    Device("Device")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Page.values().forEachIndexed { index, page ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(page.title) }
                    )
                }
            }
            when (Page.values()[selectedTab]) {
                Page.Location -> LocationPage()
                Page.Motion -> MotionPage()
                Page.Environment -> EnvironmentPage()
                Page.Device -> DevicePage()
            }
        }
    }
}

@Composable
private fun LocationPage() {
    val sensors = remember { listOf(SensorType.LOCATION) }

    var latest by remember { mutableStateOf<SensorData.Location?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var permission by remember { mutableStateOf(PermissionStatus.DENIED) }

    // Ask for location permission on this page only
    KSensor.HandelPermissions(permission = PermissionType.LOCATION) { status ->
        permission = status
    }

    LaunchedEffect(Unit) {
        KSensor.registerSensors(types = sensors)
            .collect { update ->
                when (update) {
                    is org.kmp.shots.k.sensor.SensorUpdate.Data -> if (update.data is SensorData.Location) latest = update.data as SensorData.Location
                    is org.kmp.shots.k.sensor.SensorUpdate.Error -> lastError = update.exception.message ?: update.exception.toString()
                }
            }
    }
    DisposableEffect(Unit) { onDispose { KSensor.unregisterSensors(sensors) } }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Location", style = MaterialTheme.typography.titleLarge) }
        item { Text("Permission: ${permission.name}") }
        val err = lastError
        if (err != null) item { Text("Error: $err", color = MaterialTheme.colorScheme.error) }
        item {
            val d = latest
            if (permission == PermissionStatus.GRANTED && d != null) {
                Text("lat=${d.latitude}, lon=${d.longitude}, alt=${d.altitude}, platform=${d.platformType}")
            } else if (permission != PermissionStatus.GRANTED) {
                Text("Waiting for location permission…")
            } else {
                Text("No data yet")
            }
        }
        item { Divider() }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun MotionPage() {
    val sensors = remember { listOf(
        SensorType.ACCELEROMETER,
        SensorType.GYROSCOPE,
        SensorType.MAGNETOMETER,
        SensorType.STEP_COUNTER
    ) }

    // Activity Recognition permission (Android 10+)
    val needsActivityPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val activityPermissionState = if (needsActivityPermission) rememberPermissionState(Manifest.permission.ACTIVITY_RECOGNITION) else null

    LaunchedEffect(needsActivityPermission) {
        if (needsActivityPermission && activityPermissionState?.status?.isGranted == false) {
            activityPermissionState.launchPermissionRequest()
        }
    }

    var latest by remember { mutableStateOf<Map<SensorType, SensorData>>(emptyMap()) }
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        KSensor.registerSensors(types = sensors)
            .collect { update ->
                when (update) {
                    is org.kmp.shots.k.sensor.SensorUpdate.Data -> latest = latest + (update.type to update.data)
                    is org.kmp.shots.k.sensor.SensorUpdate.Error -> lastError = update.exception.message ?: update.exception.toString()
                }
            }
    }
    DisposableEffect(Unit) { onDispose { KSensor.unregisterSensors(sensors) } }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Motion", style = MaterialTheme.typography.titleLarge) }
        if (needsActivityPermission) {
            item {
                val granted = activityPermissionState?.status?.isGranted == true
                Text("Activity Recognition permission: ${if (granted) "GRANTED" else "DENIED"}")
            }
        }
        val err = lastError
        if (err != null) item { Text("Error: $err", color = MaterialTheme.colorScheme.error) }
        items(listOf(SensorType.ACCELEROMETER, SensorType.GYROSCOPE, SensorType.MAGNETOMETER, SensorType.STEP_COUNTER)) { type ->
            Text(type.name, style = MaterialTheme.typography.titleMedium)
            when (val d = latest[type]) {
                is SensorData.Accelerometer -> Text("x=${d.x}, y=${d.y}, z=${d.z}, platform=${d.platformType}")
                is SensorData.Gyroscope -> Text("x=${d.x}, y=${d.y}, z=${d.z}, platform=${d.platformType}")
                is SensorData.Magnetometer -> Text("x=${d.x}, y=${d.y}, z=${d.z}, platform=${d.platformType}")
                is SensorData.StepCounter -> Text("steps=${d.steps}, platform=${d.platformType}")
                null -> Text("No data yet or sensor not available")
                else -> {}
            }
            Divider()
        }
    }
}

@Composable
private fun EnvironmentPage() {
    val sensors = remember { listOf(
        SensorType.BAROMETER,
        SensorType.LIGHT,
        SensorType.PROXIMITY
    ) }

    var latest by remember { mutableStateOf<Map<SensorType, SensorData>>(emptyMap()) }
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        KSensor.registerSensors(types = sensors)
            .collect { update ->
                when (update) {
                    is org.kmp.shots.k.sensor.SensorUpdate.Data -> latest = latest + (update.type to update.data)
                    is org.kmp.shots.k.sensor.SensorUpdate.Error -> lastError = update.exception.message ?: update.exception.toString()
                }
            }
    }
    DisposableEffect(Unit) { onDispose { KSensor.unregisterSensors(sensors) } }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Environment", style = MaterialTheme.typography.titleLarge) }
        val err = lastError
        if (err != null) item { Text("Error: $err", color = MaterialTheme.colorScheme.error) }
        items(listOf(SensorType.BAROMETER, SensorType.LIGHT, SensorType.PROXIMITY)) { type ->
            Text(type.name, style = MaterialTheme.typography.titleMedium)
            when (val d = latest[type]) {
                is SensorData.Barometer -> Text("pressure=${d.pressure}, platform=${d.platformType}")
                is SensorData.LightIlluminance -> Text("illuminance=${d.illuminance} lx, platform=${d.platformType}")
                is SensorData.Proximity -> Text("distanceCM=${d.distanceInCM}, near=${d.isNear}, platform=${d.platformType}")
                null -> Text("No data yet or sensor not available")
                else -> {}
            }
            Divider()
        }
    }
}

@Composable
private fun DevicePage() {
    val sensors = remember { listOf(
        SensorType.DEVICE_ORIENTATION,
        SensorType.BATTERY
    ) }

    var latest by remember { mutableStateOf<Map<SensorType, SensorData>>(emptyMap()) }
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        KSensor.registerSensors(types = sensors)
            .collect { update ->
                when (update) {
                    is org.kmp.shots.k.sensor.SensorUpdate.Data -> latest = latest + (update.type to update.data)
                    is org.kmp.shots.k.sensor.SensorUpdate.Error -> lastError = update.exception.message ?: update.exception.toString()
                }
            }
    }
    DisposableEffect(Unit) { onDispose { KSensor.unregisterSensors(sensors) } }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Device", style = MaterialTheme.typography.titleLarge) }
        val err = lastError
        if (err != null) item { Text("Error: $err", color = MaterialTheme.colorScheme.error) }
        items(listOf(SensorType.DEVICE_ORIENTATION, SensorType.BATTERY)) { type ->
            Text(type.name, style = MaterialTheme.typography.titleMedium)
            when (val d = latest[type]) {
                is SensorData.Orientation -> Text("orientation=${d.orientation} (${d.orientationInt}), platform=${d.platformType}")
                is SensorData.BatteryStatus -> Text("level=${d.levelPercent}%, charging=${d.chargingState}, health=${d.health}, tempC=${d.temperatureC}, platform=${d.platformType}")
                null -> Text("No data yet or sensor not available")
                else -> {}
            }
            Divider()
        }
    }
}

package org.kmp.shots.k.sensor

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal actual class StateHandler : StateController{
    private val context: Context by lazy { AppContext.get() }
    private val lifecycleOwner = ProcessLifecycleOwner.get()

    private val activeStateObservers = mutableMapOf<StateType, Any>()

    private data class ConnectivityObservers(
        val networkCallback: ConnectivityManager.NetworkCallback?,
        val bluetoothReceiver: BroadcastReceiver?
    )

    actual override fun addObserver(types: List<StateType>): Flow<StateUpdate> = callbackFlow {
        types.forEach { stateType ->
            if(activeStateObservers.contains(stateType))return@forEach

            when(stateType){
                StateType.SCREEN_STATE -> observerScreenState { trySend(it).isSuccess }
                StateType.APP_VISIBILITY -> observerAppVisibility { trySend(it).isSuccess }
                StateType.CONNECTIVITY -> observerConnectivity { trySend(it).isSuccess }
            }.also {
                println("Observer added for $stateType on Android")
            }

            awaitClose { removeObserver(types) }
        }
    }

    actual override fun removeObserver(types: List<StateType>) {
        types.forEach { stateType ->
            when (val listener = activeStateObservers.remove(stateType)) {
                is ScreenStateReceiver -> context.unregisterReceiver(listener)
                is LifecycleEventObserver -> lifecycleOwner.lifecycle.removeObserver(listener)
                is ConnectivityObservers -> {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    listener.networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                    listener.bluetoothReceiver?.let { runCatching { context.unregisterReceiver(it) } }
                }
                else -> println("Observer not found for $stateType on Android")
            }.also {
                println("Observer removed for $stateType on Android")
            }
        }
    }

    @Composable
    actual override fun HandelPermissions(permission: PermissionType, onPermissionStatus: (PermissionStatus) -> Unit) = Unit


    private fun observerAppVisibility(onData: (StateUpdate) -> Boolean) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> onData(StateUpdate.Data(type = StateType.APP_VISIBILITY, StateData.AppVisibilityStatus(
                    AppVisibility.INVISIBLE, PlatformType.Android)))
                Lifecycle.Event.ON_START -> onData(StateUpdate.Data(type = StateType.APP_VISIBILITY, StateData.AppVisibilityStatus(
                    AppVisibility.VISIBLE, PlatformType.Android)))
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        activeStateObservers[StateType.APP_VISIBILITY] = observer
    }


    private fun observerScreenState(onData: (StateUpdate) -> Boolean) {
        val screenStateReceiver = ScreenStateReceiver(
            onScreenOn = {
                onData(
                    StateUpdate.Data(
                        StateType.SCREEN_STATE,
                        StateData.ScreenStatus(ScreenState.ON, PlatformType.Android)
                    )
                )
            },
            onScreenOff = {
                onData(
                    StateUpdate.Data(
                        StateType.SCREEN_STATE,
                        StateData.ScreenStatus(ScreenState.OFF, PlatformType.Android)
                    )
                )
            }
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(screenStateReceiver, filter)
        activeStateObservers[StateType.SCREEN_STATE] = screenStateReceiver
    }

    private fun observerConnectivity(onData: (StateUpdate) -> Boolean) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        onData(StateUpdate.Data(StateType.CONNECTIVITY, StateData.ConnectivityStatus(ConnectivityType.WIFI, ConnectionStatus.CONNECTED, PlatformType.Android)))
                    }
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        onData(StateUpdate.Data(StateType.CONNECTIVITY, StateData.ConnectivityStatus(ConnectivityType.CELLULAR, ConnectionStatus.CONNECTED, PlatformType.Android)))
                    }
                }
            }

            override fun onLost(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                // When lost, capabilities may be null; send DISCONNECTED for both as a conservative signal
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    onData(StateUpdate.Data(StateType.CONNECTIVITY, StateData.ConnectivityStatus(ConnectivityType.WIFI, ConnectionStatus.DISCONNECTED, PlatformType.Android)))
                }
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    onData(StateUpdate.Data(StateType.CONNECTIVITY, StateData.ConnectivityStatus(ConnectivityType.CELLULAR, ConnectionStatus.DISCONNECTED, PlatformType.Android)))
                }
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    onData(StateUpdate.Data(StateType.CONNECTIVITY, StateData.ConnectivityStatus(ConnectivityType.WIFI, ConnectionStatus.CONNECTING, PlatformType.Android)))
                }
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                    onData(StateUpdate.Data(StateType.CONNECTIVITY, StateData.ConnectivityStatus(ConnectivityType.CELLULAR, ConnectionStatus.CONNECTING, PlatformType.Android)))
                }
            }
        }

        // Register for both WiFi and Cellular
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
        }

        // Bluetooth receiver
        val btReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return
                when (action) {
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, BluetoothAdapter.ERROR)
                        val status = when (state) {
                            BluetoothAdapter.STATE_CONNECTING -> ConnectionStatus.CONNECTING
                            BluetoothAdapter.STATE_CONNECTED -> ConnectionStatus.CONNECTED
                            BluetoothAdapter.STATE_DISCONNECTING -> ConnectionStatus.CONNECTING
                            BluetoothAdapter.STATE_DISCONNECTED -> ConnectionStatus.DISCONNECTED
                            else -> return
                        }
                        onData(StateUpdate.Data(StateType.CONNECTIVITY, StateData.ConnectivityStatus(ConnectivityType.BLUETOOTH, status, PlatformType.Android)))
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        val status = when (state) {
                            BluetoothAdapter.STATE_TURNING_ON -> ConnectionStatus.CONNECTING
                            BluetoothAdapter.STATE_ON -> ConnectionStatus.CONNECTED
                            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> ConnectionStatus.DISCONNECTED
                            else -> return
                        }
                        onData(StateUpdate.Data(StateType.CONNECTIVITY, StateData.ConnectivityStatus(ConnectivityType.BLUETOOTH, status, PlatformType.Android)))
                    }
                }
            }
        }

        val btFilter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        val registeredBt = runCatching { context.registerReceiver(btReceiver, btFilter) }.isSuccess

        activeStateObservers[StateType.CONNECTIVITY] = ConnectivityObservers(networkCallback, if (registeredBt) btReceiver else null)
    }
}
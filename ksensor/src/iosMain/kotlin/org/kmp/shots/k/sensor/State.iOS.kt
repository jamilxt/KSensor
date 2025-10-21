package org.kmp.shots.k.sensor

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Network.nw_interface_get_type
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_path_enumerate_interfaces
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

internal actual class StateHandler : StateController {
    private var foregroundObserver: NSObject? = null
    private var backgroundObserver: NSObject? = null

    // Connectivity (iOS)
    private var pathMonitor: platform.Network.nw_path_monitor_t? = null
    private val pathQueue = dispatch_queue_create("ksensor.connectivity", null)
    private var centralManager: CBCentralManager? = null
    private var centralDelegate: CBCentralDelegate? = null

    actual override fun addObserver(types: List<StateType>): Flow<StateUpdate> = callbackFlow {
        types.forEach { stateType ->
            when (stateType) {
                StateType.SCREEN_STATE -> trySend(StateUpdate.Error(exception = Exception("iOS dos not have a convince way to check screen state")))
                StateType.APP_VISIBILITY -> observerAppVisibility { trySend(it).isSuccess }
                StateType.CONNECTIVITY -> observerConnectivity { trySend(it).isSuccess }
            }.also {
                println("Observer added for $stateType on iOS")
            }
        }
    }

    actual override fun removeObserver(types: List<StateType>) {
        types.forEach { stateType ->
            when (stateType) {
                StateType.SCREEN_STATE -> println("iOS dos not have a convince way to check screen state")
                StateType.APP_VISIBILITY -> {
                    foregroundObserver?.let {
                        NSNotificationCenter.defaultCenter.removeObserver(it)
                    }
                    backgroundObserver?.let {
                        NSNotificationCenter.defaultCenter.removeObserver(it)
                    }
                }
                StateType.CONNECTIVITY -> {
                    pathMonitor?.let { nw_path_monitor_cancel(it) }
                    pathMonitor = null
                    centralManager = null
                    centralDelegate = null
                }
            }.also {
                println("Observer removed for $stateType on iOS")
            }
        }
    }

    @Composable
    actual override fun HandelPermissions(permission: PermissionType,onPermissionStatus: (PermissionStatus) -> Unit) = Unit

    private fun observerAppVisibility(onData: (StateUpdate) -> Boolean) {
        onData(
            StateUpdate.Data(
                type = StateType.APP_VISIBILITY, StateData.AppVisibilityStatus(
                    AppVisibility.INVISIBLE, PlatformType.iOS
                )
            )
        )

        foregroundObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue()
        ) {
            onData(
                StateUpdate.Data(
                    type = StateType.APP_VISIBILITY, StateData.AppVisibilityStatus(
                        AppVisibility.VISIBLE, PlatformType.iOS
                    )
                )
            )
        } as NSObject?

        backgroundObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue()
        ) {
            onData(
                StateUpdate.Data(
                    type = StateType.APP_VISIBILITY, StateData.AppVisibilityStatus(
                        AppVisibility.INVISIBLE, PlatformType.iOS
                    )
                )
            )
        } as NSObject?
    }

    private fun observerConnectivity(onData: (StateUpdate) -> Boolean) {
        // Network (WiFi/Cellular) using NWPathMonitor
        pathMonitor = nw_path_monitor_create()
        pathMonitor?.let { monitor ->
            nw_path_monitor_set_queue(monitor, pathQueue)
            nw_path_monitor_set_update_handler(monitor) { path ->
                val status = nw_path_get_status(path)
                val connStatus = when (status) {
                    nw_path_status_satisfied -> ConnectionStatus.CONNECTED
                    nw_path_status_satisfiable -> ConnectionStatus.CONNECTING
                    else -> ConnectionStatus.DISCONNECTED
                }
                // Enumerate interfaces to determine WiFi/Cellular
                nw_path_enumerate_interfaces(path) { iface ->
                    val type = nw_interface_get_type(iface)
                    when (type) {
                        nw_interface_type_wifi -> {
                            onData(StateUpdate.Data(StateType.CONNECTIVITY, StateData.ConnectivityStatus(ConnectivityType.WIFI, connStatus, PlatformType.iOS)))
                            true
                        }
                        nw_interface_type_cellular -> {
                            onData(StateUpdate.Data(StateType.CONNECTIVITY, StateData.ConnectivityStatus(ConnectivityType.CELLULAR, connStatus, PlatformType.iOS)))
                            true
                        }
                        else -> true
                    }
                }
            }
            platform.Network.nw_path_monitor_start(monitor)
        }

        // Bluetooth using CoreBluetooth: treat PoweredOn as CONNECTED (available), PoweredOff as DISCONNECTED
        centralDelegate = CBCentralDelegate(onData)
        centralManager = CBCentralManager(centralDelegate, dispatch_get_main_queue())
        // Immediately emit current state if available
        centralManager?.let { cm ->
            val status = when (cm.state) {
                CBManagerStatePoweredOn -> ConnectionStatus.CONNECTED
                CBManagerStatePoweredOff -> ConnectionStatus.DISCONNECTED
                else -> ConnectionStatus.DISCONNECTED
            }
            onData(StateUpdate.Data(StateType.CONNECTIVITY, StateData.ConnectivityStatus(ConnectivityType.BLUETOOTH, status, PlatformType.iOS)))
        }
    }

    private class CBCentralDelegate(val onData: (StateUpdate) -> Boolean) : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            val status = when (central.state) {
                CBManagerStatePoweredOn -> ConnectionStatus.CONNECTED
                CBManagerStatePoweredOff -> ConnectionStatus.DISCONNECTED
                else -> ConnectionStatus.DISCONNECTED
            }
            onData(
                StateUpdate.Data(
                    StateType.CONNECTIVITY,
                    StateData.ConnectivityStatus(ConnectivityType.BLUETOOTH, status, PlatformType.iOS)
                )
            )
        }
    }
}
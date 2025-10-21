package org.kmp.shots.k.sensor


enum class StateType{
    SCREEN_STATE,
    APP_VISIBILITY,
    CONNECTIVITY
}

enum class AppVisibility{
    VISIBLE,
    INVISIBLE
}

enum class ScreenState{
    ON, OFF
}

enum class ConnectivityType{
    WIFI, CELLULAR, BLUETOOTH
}

enum class ConnectionStatus{
    CONNECTED, DISCONNECTED, CONNECTING
}

sealed class StateData{
    data class AppVisibilityStatus(
        val appVisibility: AppVisibility,
        val platformType: PlatformType
    ): StateData()

    data class ScreenStatus(
        val screenState: ScreenState,
        val platformType: PlatformType
    ): StateData()

    data class ConnectivityStatus(
        val connectionType: ConnectivityType,
        val status: ConnectionStatus,
        val platformType: PlatformType
    ): StateData()
}
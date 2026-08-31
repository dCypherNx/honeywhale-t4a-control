package br.com.t4acontrol.ui

import br.com.t4acontrol.ui.dashboard.T4ADashboardActions

internal interface T4AUiActions : T4ADashboardActions {
    fun setSettingsVisible(visible: Boolean)
    fun login(email: String, password: String)
    fun pair()
    fun scan()
    fun setKeepScreenOn(enabled: Boolean)
    fun setTheme(mode: String)
    fun setBatteryRechargeMinGapHours(hours: Int)
    fun openMqttSettings()
    fun setAutoLockFromSettings(enabled: Boolean)
    fun setAutoLockDistanceFromSettings(distance: String)
    fun setUnit(unit: String)
    fun confirmUnpair()
    fun setRawLogFilter(filter: String)
    fun sectionExpanded(key: String, defaultExpanded: Boolean): Boolean
    fun setSectionExpanded(key: String, expanded: Boolean)
    fun copyRawLog()
    fun saveRawLog()
}

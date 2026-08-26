package br.com.t4acontrol.backend;

import com.thingclips.smart.android.ble.api.LeScanSetting;
import com.thingclips.smart.android.ble.api.ScanDeviceBean;
import com.thingclips.smart.sdk.api.IThingDevice;
import com.thingclips.smart.sdk.api.IBleActivatorListener;
import com.thingclips.smart.sdk.bean.BleActivatorBean;
import com.thingclips.smart.home.sdk.bean.HomeBean;
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback;
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.thingclips.smart.home.sdk.ThingHomeSdk;
import com.thingclips.smart.android.user.api.ILoginCallback;
import com.thingclips.smart.android.user.bean.User;
import com.thingclips.smart.sdk.api.IThingActivatorGetToken;

import java.util.List;
import java.util.Collections;

/**
 * Facade interface that abstracts ThingHomeSdk calls used by the app.
 * Keeps the UI dependent only on this contract.
 */
public interface DeviceController {
    // Callbacks
    interface ScanCallback { void onScan(ScanDeviceBean scanDevice); }
    interface RssiCallback { void onResult(boolean isSuccess, int rssi); }

    // User
    User getUser();
    void login(String countryCode, String email, String password, ILoginCallback callback);

    // Homes
    void queryHomeList(IThingGetHomeListCallback callback);
    void getHomeDetail(long homeId, IThingHomeResultCallback callback);

    // Activator / scanning
    void getActivatorToken(long homeId, IThingActivatorGetToken callback);
    void startLeScan(LeScanSetting setting, ScanCallback callback);
    void startActivator(BleActivatorBean bean, IBleActivatorListener listener);

    // Devices
    com.thingclips.smart.sdk.bean.DeviceBean getDeviceBean(String devId);
    IThingDevice newDeviceInstance(String devId);
    void publishDps(IThingDevice device, String json, IResultCallback callback);
    void readBluetoothRssi(String mac, RssiCallback callback);
    void connectBleDevice(java.util.List<com.thingclips.smart.android.ble.builder.BleConnectBuilder> builders);

    // Utility passthroughs
    void removeDevice(IThingDevice device, IResultCallback callback);
}

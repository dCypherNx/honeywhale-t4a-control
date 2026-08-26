package br.com.t4acontrol.backend;

import android.content.Context;
import com.thingclips.smart.android.ble.api.LeScanSetting;
import com.thingclips.smart.android.ble.api.ScanDeviceBean;
import com.thingclips.smart.android.user.api.ILoginCallback;
import com.thingclips.smart.android.user.bean.User;
import com.thingclips.smart.home.sdk.ThingHomeSdk;
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback;
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback;
import com.thingclips.smart.sdk.api.IBleActivatorListener;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.thingclips.smart.sdk.api.IThingActivatorGetToken;
import com.thingclips.smart.sdk.api.IThingDevice;
import com.thingclips.smart.sdk.bean.BleActivatorBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple Tuya-based DeviceController implementation that delegates to ThingHomeSdk. This class
 * centralizes SDK interactions so the UI can stay thin.
 */
public class TuyaDeviceController implements DeviceController {
  private final Context context;

  public interface DeviceEventsListener {
    void onDpUpdate(String devId, Map<String, Object> dps);

    void onDeviceRemoved(String devId);

    void onStatusChanged(String devId, boolean online);

    void onScan(ScanDeviceBean scanDevice);

    void onActivatorSuccess(com.thingclips.smart.sdk.bean.DeviceBean d);

    void onActivatorFailure(int code, String msg, Object hint);
  }

  private DeviceEventsListener listener;
  private final Map<String, IThingDevice> deviceInstances = new HashMap<>();

  public TuyaDeviceController(Context ctx) {
    this.context = ctx.getApplicationContext();
  }

  public void setDeviceEventsListener(DeviceEventsListener l) {
    this.listener = l;
  }

  @Override
  public User getUser() {
    return ThingHomeSdk.getUserInstance().getUser();
  }

  @Override
  public void login(String countryCode, String email, String password, ILoginCallback callback) {
    ThingHomeSdk.getUserInstance().loginWithEmail(countryCode, email, password, callback);
  }

  @Override
  public void queryHomeList(IThingGetHomeListCallback callback) {
    ThingHomeSdk.getHomeManagerInstance().queryHomeList(callback);
  }

  @Override
  public void getHomeDetail(long homeId, IThingHomeResultCallback callback) {
    ThingHomeSdk.newHomeInstance(homeId).getHomeDetail(callback);
  }

  @Override
  public void getActivatorToken(long homeId, IThingActivatorGetToken callback) {
    ThingHomeSdk.getActivatorInstance().getActivatorToken(homeId, callback);
  }

  @Override
  public void startLeScan(LeScanSetting setting, ScanCallback callback) {
    ThingHomeSdk.getBleOperator()
        .startLeScan(
            setting,
            scanDevice -> {
              if (callback != null) callback.onScan(scanDevice);
              if (listener != null) listener.onScan(scanDevice);
            });
  }

  @Override
  public void startActivator(BleActivatorBean b, IBleActivatorListener listener) {
    ThingHomeSdk.getActivator().newBleActivator().startActivator(b, listener);
  }

  @Override
  public com.thingclips.smart.sdk.bean.DeviceBean getDeviceBean(String devId) {
    return ThingHomeSdk.getDataInstance().getDeviceBean(devId);
  }

  @Override
  public IThingDevice newDeviceInstance(String devId) {
    IThingDevice td = ThingHomeSdk.newDeviceInstance(devId);
    deviceInstances.put(devId, td);
    return td;
  }

  @Override
  public void publishDps(IThingDevice device, String json, IResultCallback callback) {
    device.publishDps(json, callback);
  }

  @Override
  public void readBluetoothRssi(String mac, RssiCallback callback) {
    ThingHomeSdk.getBleOperator()
        .readBluetoothRssi(
            mac,
            (isSuccess, rssi) -> {
              if (callback != null) callback.onResult(isSuccess, rssi);
            });
  }

  @Override
  public void connectBleDevice(
      List<com.thingclips.smart.android.ble.builder.BleConnectBuilder> builders) {
    ThingHomeSdk.getBleManager().connectBleDevice(builders);
  }

  @Override
  public void removeDevice(IThingDevice device, IResultCallback callback) {
    device.removeDevice(callback);
  }
}

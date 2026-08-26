package br.com.t4acontrol.backend;

import br.com.t4acontrol.backend.T4AContracts.Callback;
import br.com.t4acontrol.backend.T4AContracts.Device;
import br.com.t4acontrol.backend.T4AContracts.DeviceListener;
import br.com.t4acontrol.backend.T4AContracts.DiscoveredDevice;
import br.com.t4acontrol.backend.T4AContracts.DiscoveryListener;
import br.com.t4acontrol.backend.T4AContracts.DpSchema;
import br.com.t4acontrol.backend.T4AContracts.Home;
import br.com.t4acontrol.backend.T4AContracts.ResultCallback;
import br.com.t4acontrol.backend.T4AContracts.RssiCallback;
import com.alibaba.fastjson.JSON;
import com.thingclips.smart.android.ble.api.LeScanSetting;
import com.thingclips.smart.android.ble.api.ScanDeviceBean;
import com.thingclips.smart.android.ble.api.ScanType;
import com.thingclips.smart.android.ble.builder.BleConnectBuilder;
import com.thingclips.smart.android.device.bean.SchemaBean;
import com.thingclips.smart.android.user.api.ILoginCallback;
import com.thingclips.smart.android.user.bean.User;
import com.thingclips.smart.home.sdk.ThingHomeSdk;
import com.thingclips.smart.home.sdk.bean.HomeBean;
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback;
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback;
import com.thingclips.smart.sdk.api.IBleActivatorListener;
import com.thingclips.smart.sdk.api.IDeviceListener;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.thingclips.smart.sdk.api.IThingActivatorGetToken;
import com.thingclips.smart.sdk.api.IThingDevice;
import com.thingclips.smart.sdk.bean.BleActivatorBean;
import com.thingclips.smart.sdk.bean.DeviceBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ThingClips implementation of both replaceable boundaries. No Tuya type escapes this class. */
public final class TuyaT4APlatform implements T4AProvisioner, T4ATransport {
  private final Map<String, ScanDeviceBean> discoveries = new HashMap<>();
  private IThingDevice activeDevice;

  @Override
  public String currentAccount() {
    User user = ThingHomeSdk.getUserInstance().getUser();
    return user == null ? null : T4AContracts.value(user.getEmail());
  }

  @Override
  public void login(
      String countryCode, String email, String password, Callback<String> callback) {
    ThingHomeSdk.getUserInstance()
        .loginWithEmail(
            countryCode,
            email,
            password,
            new ILoginCallback() {
              @Override
              public void onSuccess(User user) {
                String account = user == null ? "" : T4AContracts.value(user.getEmail());
                callback.onSuccess(account.isEmpty() ? email : account);
              }

              @Override
              public void onError(String code, String error) {
                callback.onError(code, error);
              }
            });
  }

  @Override
  public void loadPrimaryHome(Callback<Home> callback) {
    ThingHomeSdk.getHomeManagerInstance()
        .queryHomeList(
            new IThingGetHomeListCallback() {
              @Override
              public void onSuccess(List<HomeBean> homes) {
                if (homes == null || homes.isEmpty()) {
                  callback.onSuccess(new Home(0L, Collections.emptyList()));
                  return;
                }
                long homeId = homes.get(0).getHomeId();
                ThingHomeSdk.newHomeInstance(homeId)
                    .getHomeDetail(
                        new IThingHomeResultCallback() {
                          @Override
                          public void onSuccess(HomeBean home) {
                            List<Device> devices = new ArrayList<>();
                            if (home != null && home.getDeviceList() != null) {
                              for (DeviceBean device : home.getDeviceList()) {
                                devices.add(toDevice(device));
                              }
                            }
                            callback.onSuccess(new Home(homeId, devices));
                          }

                          @Override
                          public void onError(String code, String error) {
                            callback.onError(code, error);
                          }
                        });
              }

              @Override
              public void onError(String code, String error) {
                callback.onError(code, error);
              }
            });
  }

  @Override
  public void startDiscovery(long timeoutMs, DiscoveryListener listener) {
    discoveries.clear();
    LeScanSetting setting =
        new LeScanSetting.Builder()
            .setTimeout(timeoutMs)
            .addScanType(ScanType.SINGLE)
            .setNeedBoundResult(false)
            .build();
    ThingHomeSdk.getBleOperator()
        .startLeScan(
            setting,
            found -> {
              if (found == null) return;
              String discoveryId = discoveryId(found);
              discoveries.put(discoveryId, found);
              listener.onDevice(
                  new DiscoveredDevice(
                      discoveryId,
                      found.getAddress(),
                      found.getUuid(),
                      found.getProductId(),
                      found.getDeviceType(),
                      found.getIsbind(),
                      found.getRssi()));
            });
  }

  @Override
  public void stopDiscovery() {
    ThingHomeSdk.getBleOperator().stopLeScan();
  }

  @Override
  public void pair(long homeId, DiscoveredDevice device, Callback<Device> callback) {
    ScanDeviceBean nativeDevice = discoveries.get(device.discoveryId);
    if (nativeDevice == null) {
      callback.onError("DISCOVERY_EXPIRED", "O resultado do scan não está mais disponível");
      return;
    }
    ThingHomeSdk.getActivatorInstance()
        .getActivatorToken(
            homeId,
            new IThingActivatorGetToken() {
              @Override
              public void onSuccess(String token) {
                activate(homeId, nativeDevice, token, callback);
              }

              @Override
              public void onFailure(String code, String error) {
                callback.onError(code, error);
              }
            });
  }

  private void activate(
      long homeId, ScanDeviceBean candidate, String token, Callback<Device> callback) {
    BleActivatorBean bean = new BleActivatorBean(candidate);
    bean.homeId = homeId;
    bean.address = candidate.getAddress();
    bean.uuid = candidate.getUuid();
    bean.productId = candidate.getProductId();
    bean.deviceType = candidate.getDeviceType();
    Map<String, Object> extra = new HashMap<>();
    extra.put("token", token);
    bean.setExtendsData(extra);
    ThingHomeSdk.getActivator()
        .newBleActivator()
        .startActivator(
            bean,
            new IBleActivatorListener() {
              @Override
              public void onSuccess(DeviceBean result) {
                callback.onSuccess(toDevice(result));
              }

              @Override
              public void onFailure(int code, String message, Object handle) {
                callback.onError(String.valueOf(code), message);
              }
            });
  }

  @Override
  public void attach(Device device, DeviceListener listener) {
    detach();
    activeDevice = ThingHomeSdk.newDeviceInstance(device.id);
    activeDevice.registerDeviceListener(
        new IDeviceListener() {
          @Override
          public void onDpUpdate(String id, Map<String, Object> update) {
            listener.onDpUpdate(id, update);
          }

          @Override
          public void onRemoved(String id) {
            listener.onRemoved(id);
          }

          @Override
          public void onStatusChanged(String id, boolean online) {
            listener.onConnectionChanged(id, isConnected(id));
          }

          @Override
          public void onNetworkStatusChanged(String id, boolean online) {}

          @Override
          public void onDevInfoUpdate(String id) {
            listener.onDeviceInfoChanged(id);
          }
        });
  }

  @Override
  public void detach() {
    if (activeDevice == null) return;
    activeDevice.unRegisterDevListener();
    activeDevice.onDestroy();
    activeDevice = null;
  }

  @Override
  public void connect(Device device) {
    BleConnectBuilder builder =
        new BleConnectBuilder()
            .setDevId(device.id)
            .setUuid(device.uuid)
            .setDirectConnect(true)
            .setAutoConnect(true)
            .setScanTimeout(30);
    ThingHomeSdk.getBleManager().connectBleDevice(Collections.singletonList(builder));
  }

  @Override
  public boolean isConnected(String deviceId) {
    return ThingHomeSdk.getBleManager().isBleLocalOnline(deviceId);
  }

  @Override
  public Device cachedDevice(String deviceId) {
    return toDevice(ThingHomeSdk.getDataInstance().getDeviceBean(deviceId));
  }

  @Override
  public void publish(String deviceId, Map<String, Object> dps, ResultCallback callback) {
    if (activeDevice == null) {
      callback.onError("NOT_ATTACHED", "Dispositivo sem sessão ativa");
      return;
    }
    activeDevice.publishDps(
        JSON.toJSONString(dps),
        new IResultCallback() {
          @Override
          public void onSuccess() {
            callback.onSuccess();
          }

          @Override
          public void onError(String code, String error) {
            callback.onError(code, error);
          }
        });
  }

  @Override
  public void readRssi(String mac, RssiCallback callback) {
    ThingHomeSdk.getBleOperator().readBluetoothRssi(mac, callback::onResult);
  }

  @Override
  public void remove(String deviceId, ResultCallback callback) {
    if (activeDevice == null) {
      callback.onError("NOT_ATTACHED", "Dispositivo sem sessão ativa");
      return;
    }
    activeDevice.removeDevice(
        new IResultCallback() {
          @Override
          public void onSuccess() {
            callback.onSuccess();
          }

          @Override
          public void onError(String code, String error) {
            callback.onError(code, error);
          }
        });
  }

  @Override
  public void destroy() {
    stopDiscovery();
    detach();
    discoveries.clear();
  }

  private static String discoveryId(ScanDeviceBean device) {
    return T4AContracts.value(device.getUuid())
        + "|"
        + T4AContracts.value(device.getAddress());
  }

  private static Device toDevice(DeviceBean source) {
    if (source == null) return null;
    Map<String, DpSchema> schema = new HashMap<>();
    if (source.getSchemaMap() != null) {
      for (Map.Entry<String, SchemaBean> entry : source.getSchemaMap().entrySet()) {
        SchemaBean item = entry.getValue();
        schema.put(entry.getKey(), new DpSchema(item.getCode(), item.getMode(), item.getType()));
      }
    }
    return new Device(
        source.getDevId(),
        source.getName(),
        source.getMac(),
        source.getUuid(),
        source.getDps(),
        schema);
  }
}

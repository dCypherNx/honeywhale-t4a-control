package br.com.t4acontrol.backend;

import android.app.Application;

import com.thingclips.smart.home.sdk.ThingHomeSdk;

public final class T4ASdk {
    private T4ASdk() { }

    public static void initialize(Application application, boolean debug) {
        ThingHomeSdk.setDebugMode(debug);
        ThingHomeSdk.init(application);
    }

    public static void destroy() {
        ThingHomeSdk.onDestroy();
    }
}

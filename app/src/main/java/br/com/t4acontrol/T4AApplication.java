package br.com.t4acontrol;

import android.app.Application;

import com.thingclips.smart.home.sdk.ThingHomeSdk;

public final class T4AApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThingHomeSdk.setDebugMode(BuildConfig.DEBUG);
        ThingHomeSdk.init(this);
    }

    @Override
    public void onTerminate() {
        ThingHomeSdk.onDestroy();
        super.onTerminate();
    }
}

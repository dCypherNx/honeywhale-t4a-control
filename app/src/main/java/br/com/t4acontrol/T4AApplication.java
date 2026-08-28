package br.com.t4acontrol;

import android.app.Application;
import br.com.t4acontrol.backend.T4ABackend;
import br.com.t4acontrol.backend.T4ASdk;
import br.com.t4acontrol.backend.TuyaT4APlatform;

public final class T4AApplication extends Application {
  /**
   * Application composition root for a live T4A session.
   *
   * <p>Provider-specific construction remains here. The session service owns the returned backend;
   * Activities must depend on the UI-facing session facade instead of constructing this graph.
   */
  public T4ABackend createSessionBackend(T4ABackend.Listener listener) {
    TuyaT4APlatform platform = new TuyaT4APlatform();
    return new T4ABackend(this, platform, platform, listener);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    T4ASdk.initialize(this, BuildConfig.DEBUG);
  }

  @Override
  public void onTerminate() {
    T4ASdk.destroy();
    super.onTerminate();
  }
}

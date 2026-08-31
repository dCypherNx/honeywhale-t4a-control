package br.com.t4acontrol.runtime;

import android.os.Handler;
import android.os.Looper;
import br.com.t4acontrol.backend.Scheduler;

/** Main-thread Android scheduler adapter for the live session backend. */
public final class AndroidScheduler implements Scheduler {
  private final Handler handler = new Handler(Looper.getMainLooper());

  @Override public void post(Runnable task) {
    handler.post(task);
  }

  @Override public void postDelayed(Runnable task, long delayMillis) {
    handler.postDelayed(task, delayMillis);
  }

  @Override public void cancel(Runnable task) {
    handler.removeCallbacks(task);
  }

  @Override public void cancelAll() {
    handler.removeCallbacksAndMessages(null);
  }
}

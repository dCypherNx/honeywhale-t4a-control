package br.com.t4acontrol.runtime;

import br.com.t4acontrol.backend.Clock;

/** Production wall-clock adapter kept outside the backend core. */
public final class AndroidClock implements Clock {
  @Override public long nowMillis() {
    return System.currentTimeMillis();
  }
}

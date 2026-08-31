package br.com.t4acontrol.backend;

/** Neutral wall-clock source for deterministic backend timing. */
public interface Clock {
  long nowMillis();
}

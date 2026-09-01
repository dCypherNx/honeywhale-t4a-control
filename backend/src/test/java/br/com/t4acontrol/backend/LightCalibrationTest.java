package br.com.t4acontrol.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LightCalibrationTest {
  @Test
  public void requiresTwoSupportingSamplesForNewExtrema() {
    LightCalibration calibration = new LightCalibration(null, null);

    assertFalse(calibration.observe(10f));
    assertTrue(calibration.observe(10f));
    assertFalse(calibration.snapshot().ready);

    assertFalse(calibration.observe(100f));
    assertTrue(calibration.observe(100f));
    LightCalibration.Snapshot learned = calibration.snapshot();
    assertTrue(learned.ready);
    assertEquals(10f, learned.minLux, 0.001f);
    assertEquals(100f, learned.maxLux, 0.001f);

    assertFalse(calibration.observe(1f));
    assertEquals(10f, calibration.snapshot().minLux, 0.001f);
    assertTrue(calibration.observe(1f));
    assertEquals(1f, calibration.snapshot().minLux, 0.001f);

    assertFalse(calibration.observe(1000f));
    assertEquals(100f, calibration.snapshot().maxLux, 0.001f);
    assertTrue(calibration.observe(1000f));
    assertEquals(1000f, calibration.snapshot().maxLux, 0.001f);
  }

  @Test
  public void rejectsNegativeAndNonFiniteLux() {
    LightCalibration calibration = new LightCalibration(null, null);
    assertFalse(calibration.observe(-1f));
    assertFalse(calibration.observe(Float.NaN));
    assertFalse(calibration.observe(Float.POSITIVE_INFINITY));
    assertFalse(calibration.snapshot().ready);
  }
}

package br.com.t4acontrol.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RssiCalibrationTest {
  @Test public void derivesWeightedBandsFromMinusFifteenAndMinusNinety() {
    RssiCalibration calibration = new RssiCalibration(-15, -90);
    RssiCalibration.Snapshot snapshot = calibration.snapshot();

    assertTrue(snapshot.ready);
    assertEquals(Integer.valueOf(-82), snapshot.stableWorst);
    assertEquals(Integer.valueOf(-48), snapshot.shortMin);
    assertEquals(Integer.valueOf(-69), snapshot.mediumMin);
    assertEquals(Integer.valueOf(-82), snapshot.longMin);

    int shortWidth = snapshot.best - snapshot.shortMin;
    int mediumWidth = snapshot.shortMin - snapshot.mediumMin;
    int longWidth = snapshot.mediumMin - snapshot.longMin;
    assertTrue(shortWidth > mediumWidth);
    assertTrue(mediumWidth > longWidth);
  }

  @Test public void eachInitialExtremeNeedsItsOwnTwoConsecutiveReadings() {
    RssiCalibration calibration = new RssiCalibration(null, null);

    assertFalse(calibration.observe(-50));
    assertTrue(calibration.observe(-49));
    assertFalse(calibration.snapshot().ready);

    assertFalse(calibration.observe(-80));
    assertTrue(calibration.observe(-82));

    RssiCalibration.Snapshot snapshot = calibration.snapshot();
    assertTrue(snapshot.ready);
    assertEquals(Integer.valueOf(-49), snapshot.best);
    assertEquals(Integer.valueOf(-82), snapshot.worst);
  }

  @Test public void oneWeakExcursionCannotExpandWorstExtreme() {
    RssiCalibration calibration = new RssiCalibration(-20, -60);

    assertFalse(calibration.observe(-85));
    assertFalse(calibration.observe(-55));

    assertEquals(Integer.valueOf(-60), calibration.snapshot().worst);
  }

  @Test public void twoConsecutiveWeakReadingsExpandWorstExtreme() {
    RssiCalibration calibration = new RssiCalibration(-20, -60);

    assertFalse(calibration.observe(-85));
    assertTrue(calibration.observe(-86));

    assertEquals(Integer.valueOf(-86), calibration.snapshot().worst);
  }

  @Test public void twoConsecutiveStrongReadingsExpandBestExtreme() {
    RssiCalibration calibration = new RssiCalibration(-40, -90);

    assertFalse(calibration.observe(-25));
    assertTrue(calibration.observe(-22));

    assertEquals(Integer.valueOf(-22), calibration.snapshot().best);
  }
}

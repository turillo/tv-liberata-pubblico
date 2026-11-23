package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SystemAlertsTest {

    @Test
    public void lowMeansUnderTenPercent() {
        assertTrue(SystemAlerts.low(9, 100));
        assertFalse(SystemAlerts.low(10, 100));
        assertFalse(SystemAlerts.low(50, 100));
        assertFalse(SystemAlerts.low(5, 0));     // unknown total
        assertFalse(SystemAlerts.low(-1, 100));  // unknown free
        assertTrue(SystemAlerts.low(700L << 20, 8L << 30));
    }

    @Test
    public void ramMustStayLowForFiveChecksInARow() {
        SystemAlerts.RamWatch w = new SystemAlerts.RamWatch();
        for (int i = 0; i < 4; i++) w.add(5, 100);
        assertFalse(w.low());
        w.add(5, 100);
        assertTrue(w.low());
        w.add(50, 100);                          // one good minute resets it
        assertFalse(w.low());
        for (int i = 0; i < 4; i++) w.add(5, 100);
        assertFalse(w.low());
    }

    @Test
    public void anAlertWaitsSixHoursBeforeShowingAgain() {
        long now = 100 * ServiceHealth.DAY;
        assertTrue(SystemAlerts.due(0, now));
        assertFalse(SystemAlerts.due(now - ServiceHealth.HOUR, now));
        assertTrue(SystemAlerts.due(now - 6 * ServiceHealth.HOUR, now));
        assertTrue(SystemAlerts.due(now + ServiceHealth.HOUR, now));   // clock went back
    }
}

package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** WMO codes to conditions and icons, and the condition list in every locale that has one. */
public class WeatherCodeTest {

    private static final int[] DOCUMENTED = {0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75,
            77, 80, 81, 82, 85, 86, 95, 96, 99};

    @Test
    public void everyDocumentedCodeHasACondition() {
        for (int code : DOCUMENTED) assertTrue("code " + code, WeatherCode.condition(code) >= 0);
        assertEquals(-1, WeatherCode.condition(4));
        assertEquals(-1, WeatherCode.condition(100));
    }

    @Test
    public void iconsFollowTheSky() {
        assertEquals(WeatherCode.ICON_CLEAR, WeatherCode.icon(0));
        assertEquals(WeatherCode.ICON_PARTLY, WeatherCode.icon(2));
        assertEquals(WeatherCode.ICON_CLOUD, WeatherCode.icon(3));
        assertEquals(WeatherCode.ICON_FOG, WeatherCode.icon(45));
        assertEquals(WeatherCode.ICON_RAIN, WeatherCode.icon(61));
        assertEquals(WeatherCode.ICON_RAIN, WeatherCode.icon(81));
        assertEquals(WeatherCode.ICON_SNOW, WeatherCode.icon(73));
        assertEquals(WeatherCode.ICON_STORM, WeatherCode.icon(95));
        assertEquals(WeatherCode.ICON_CLOUD, WeatherCode.icon(4));
    }

    @Test
    public void conditionListsHaveTwentyItems() throws Exception {
        Pattern array = Pattern.compile("<string-array name=\"weather_conditions\">(.*?)</string-array>", Pattern.DOTALL);
        File res = new File("src/main/res");
        int found = 0;
        for (File dir : res.listFiles((d, n) -> n.startsWith("values"))) {
            File strings = new File(dir, "strings.xml");
            if (!strings.isFile()) continue;
            Matcher m = array.matcher(new String(Files.readAllBytes(strings.toPath()), StandardCharsets.UTF_8));
            if (!m.find()) continue;
            found++;
            int items = m.group(1).split("<item>", -1).length - 1;
            assertEquals(dir.getName(), 20, items);
        }
        assertTrue("default and Italian lists", found >= 2);
    }
}

package com.gathin.tvhome;

/**
 * Open-Meteo's WMO weather codes as a condition (an index into the weather_conditions string array) and an icon
 * (WeatherIcon). Pure Java, so WeatherCodeTest runs it on the JVM.
 */
final class WeatherCode {

    static final int ICON_CLEAR = 0, ICON_PARTLY = 1, ICON_CLOUD = 2, ICON_FOG = 3, ICON_RAIN = 4,
            ICON_SNOW = 5, ICON_STORM = 6;

    private WeatherCode() {
    }

    /** Index into weather_conditions, or -1 for a code Open-Meteo does not document. */
    static int condition(int code) {
        switch (code) {
            case 0: return 0;               // clear sky
            case 1: return 1;               // mainly clear
            case 2: return 2;               // partly cloudy
            case 3: return 3;               // overcast
            case 45: case 48: return 4;     // fog, rime fog
            case 51: case 53: case 55: return 5;   // drizzle
            case 56: case 57: return 6;     // freezing drizzle
            case 61: return 7;              // light rain
            case 63: return 8;              // rain
            case 65: return 9;              // heavy rain
            case 66: case 67: return 10;    // freezing rain
            case 71: return 11;             // light snow
            case 73: return 12;             // snow
            case 75: return 13;             // heavy snow
            case 77: return 14;             // snow grains
            case 80: case 81: return 15;    // rain showers
            case 82: return 16;             // violent rain showers
            case 85: case 86: return 17;    // snow showers
            case 95: return 18;             // thunderstorm
            case 96: case 99: return 19;    // thunderstorm with hail
            default: return -1;
        }
    }

    static int icon(int code) {
        if (code == 0) return ICON_CLEAR;
        if (code == 1 || code == 2) return ICON_PARTLY;
        if (code == 45 || code == 48) return ICON_FOG;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return ICON_RAIN;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return ICON_SNOW;
        if (code >= 95 && code <= 99) return ICON_STORM;
        return ICON_CLOUD;
    }
}

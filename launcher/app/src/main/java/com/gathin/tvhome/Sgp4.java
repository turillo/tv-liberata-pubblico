package com.gathin.tvhome;

/**
 * A two-line element set (TLE) and the SGP4 propagator for near-Earth orbits (period under 225 minutes,
 * which covers the ISS), after Vallado, Crawford, Hujsak and Kelso, "Revisiting Spacetrack Report #3"
 * (2006), with WGS-72 constants as the element sets assume. Deep-space SDP4 is left out.
 *
 * Pure maths, no Android types: Sgp4Test checks it against the report's published test vectors.
 */
final class Sgp4 {

    // WGS-72
    static final double RADIUS_KM = 6378.135;
    private static final double MU = 398600.8;
    private static final double XKE = 60.0 / Math.sqrt(RADIUS_KM * RADIUS_KM * RADIUS_KM / MU);
    private static final double J2 = 0.001082616;
    private static final double J3 = -0.00000253881;
    private static final double J4 = -0.00000165597;
    private static final double J3OJ2 = J3 / J2;
    private static final double TWO_PI = 2 * Math.PI;
    private static final double X2O3 = 2.0 / 3.0;

    /** Julian date of the Unix epoch. */
    static final double JD_UNIX = 2440587.5;

    final String name;
    final int catalog;
    /** Element set epoch as a Julian date (UTC). */
    final double epochJd;

    // Elements in radians and radians per minute.
    private final double bstar, inclo, nodeo, ecco, argpo, mo, noKozai;

    // Set up once in init().
    private double noUnkozai, ao, con41, x1mth2, x7thm1, cc1, cc4, cc5, d2, d3, d4, delmo, eta, argpdot,
            omgcof, sinmao, t2cof, t3cof, t4cof, t5cof, xlcof, aycof, xmcof, nodecf, nodedot, mdot;
    private boolean isimp;

    /** Position (km) and velocity (km/s) in the TEME frame. */
    static final class State {
        final double x, y, z, vx, vy, vz;

        State(double x, double y, double z, double vx, double vy, double vz) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
        }

        double speedKmS() {
            return Math.sqrt(vx * vx + vy * vy + vz * vz);
        }
    }

    private Sgp4(String name, int catalog, double epochJd, double bstar, double inclo, double nodeo, double ecco,
                 double argpo, double mo, double noKozai) {
        this.name = name;
        this.catalog = catalog;
        this.epochJd = epochJd;
        this.bstar = bstar;
        this.inclo = inclo;
        this.nodeo = nodeo;
        this.ecco = ecco;
        this.argpo = argpo;
        this.mo = mo;
        this.noKozai = noKozai;
        init();
    }

    /** Epoch as Unix milliseconds. */
    long epochMillis() {
        return Math.round((epochJd - JD_UNIX) * 86_400_000.0);
    }

    /**
     * Parses a TLE: an optional name line, then lines 1 and 2. Checks the line numbers, the checksums and that
     * both lines name the same satellite; throws IllegalArgumentException otherwise, or for a deep-space orbit.
     */
    static Sgp4 parse(String text) {
        if (text == null) throw new IllegalArgumentException("no TLE");
        String name = "";
        String l1 = null, l2 = null;
        for (String raw : text.split("\r?\n")) {
            String line = raw.replaceAll("\\s+$", "");
            if (line.isEmpty()) continue;
            if (line.startsWith("1 ") && line.length() >= 69 && l1 == null) l1 = line;
            else if (line.startsWith("2 ") && line.length() >= 69 && l1 != null && l2 == null) l2 = line;
            else if (l1 == null) name = line.trim();
        }
        if (l1 == null || l2 == null) throw new IllegalArgumentException("TLE lines missing");
        if (!checksumOk(l1) || !checksumOk(l2)) throw new IllegalArgumentException("TLE checksum");
        int cat1 = Integer.parseInt(l1.substring(2, 7).trim());
        int cat2 = Integer.parseInt(l2.substring(2, 7).trim());
        if (cat1 != cat2) throw new IllegalArgumentException("TLE lines for different satellites");

        int yy = Integer.parseInt(l1.substring(18, 20).trim());
        int year = yy < 57 ? 2000 + yy : 1900 + yy;
        double day = Double.parseDouble(l1.substring(20, 32).trim());
        double epochJd = julianDate(year, 1, 1, 0, 0, 0) + day - 1;
        double bstar = exponent(l1.substring(53, 61));

        double deg = Math.PI / 180;
        double incl = Double.parseDouble(l2.substring(8, 16).trim()) * deg;
        double node = Double.parseDouble(l2.substring(17, 25).trim()) * deg;
        double ecc = Double.parseDouble("0." + l2.substring(26, 33).trim());
        double argp = Double.parseDouble(l2.substring(34, 42).trim()) * deg;
        double mean = Double.parseDouble(l2.substring(43, 51).trim()) * deg;
        double revsPerDay = Double.parseDouble(l2.substring(52, 63).trim());
        if (revsPerDay < 1440.0 / 225.0) throw new IllegalArgumentException("deep-space orbit");
        return new Sgp4(name, cat1, epochJd, bstar, incl, node, ecc, argp, mean, revsPerDay * TWO_PI / 1440.0);
    }

    /** TLE checksum: digits summed, a minus sign counts 1, modulo 10, in column 69. */
    static boolean checksumOk(String line) {
        if (line.length() < 69 || !Character.isDigit(line.charAt(68))) return false;
        int sum = 0;
        for (int i = 0; i < 68; i++) {
            char c = line.charAt(i);
            if (Character.isDigit(c)) sum += c - '0';
            else if (c == '-') sum++;
        }
        return sum % 10 == line.charAt(68) - '0';
    }

    /** "-11606-4" style fields: an implied leading decimal point and a power of ten. */
    private static double exponent(String field) {
        String f = field.trim();
        if (f.isEmpty()) return 0;
        int sign = 1;
        if (f.charAt(0) == '-' || f.charAt(0) == '+') {
            if (f.charAt(0) == '-') sign = -1;
            f = f.substring(1);
        }
        int split = Math.max(f.lastIndexOf('-'), f.lastIndexOf('+'));
        if (split <= 0) return sign * Double.parseDouble("0." + f);
        double mantissa = Double.parseDouble("0." + f.substring(0, split));
        int power = Integer.parseInt(f.substring(split).replace("+", ""));
        return sign * mantissa * Math.pow(10, power);
    }

    static double julianDate(int year, int month, int day, int hour, int minute, double second) {
        return 367.0 * year - Math.floor(7 * (year + Math.floor((month + 9) / 12.0)) * 0.25)
                + Math.floor(275 * month / 9.0) + day + 1721013.5
                + ((second / 60.0 + minute) / 60.0 + hour) / 24.0;
    }

    static double julianDate(long unixMillis) {
        return JD_UNIX + unixMillis / 86_400_000.0;
    }

    private void init() {
        double eccsq = ecco * ecco;
        double omeosq = 1 - eccsq;
        double rteosq = Math.sqrt(omeosq);
        double cosio = Math.cos(inclo);
        double cosio2 = cosio * cosio;
        double ak = Math.pow(XKE / noKozai, X2O3);
        double d1 = 0.75 * J2 * (3 * cosio2 - 1) / (rteosq * omeosq);
        double del = d1 / (ak * ak);
        double adel = ak * (1 - del * del - del * (1.0 / 3.0 + 134 * del * del / 81.0));
        del = d1 / (adel * adel);
        noUnkozai = noKozai / (1 + del);
        ao = Math.pow(XKE / noUnkozai, X2O3);
        double sinio = Math.sin(inclo);
        double po = ao * omeosq;
        double con42 = 1 - 5 * cosio2;
        con41 = -con42 - cosio2 - cosio2;
        double posq = po * po;
        double rp = ao * (1 - ecco);

        double ss = 78.0 / RADIUS_KM + 1;
        double qzms2t = Math.pow((120.0 - 78.0) / RADIUS_KM, 4);
        isimp = rp < 220.0 / RADIUS_KM + 1;
        double sfour = ss;
        double qzms24 = qzms2t;
        double perige = (rp - 1) * RADIUS_KM;
        if (perige < 156) {
            sfour = perige - 78;
            if (perige < 98) sfour = 20;
            qzms24 = Math.pow((120 - sfour) / RADIUS_KM, 4);
            sfour = sfour / RADIUS_KM + 1;
        }
        double pinvsq = 1 / posq;
        double tsi = 1 / (ao - sfour);
        eta = ao * ecco * tsi;
        double etasq = eta * eta;
        double eeta = ecco * eta;
        double psisq = Math.abs(1 - etasq);
        double coef = qzms24 * Math.pow(tsi, 4);
        double coef1 = coef / Math.pow(psisq, 3.5);
        double cc2 = coef1 * noUnkozai * (ao * (1 + 1.5 * etasq + eeta * (4 + etasq))
                + 0.375 * J2 * tsi / psisq * con41 * (8 + 3 * etasq * (8 + etasq)));
        cc1 = bstar * cc2;
        double cc3 = ecco > 1.0e-4 ? -2 * coef * tsi * J3OJ2 * noUnkozai * sinio / ecco : 0;
        x1mth2 = 1 - cosio2;
        cc4 = 2 * noUnkozai * coef1 * ao * omeosq * (eta * (2 + 0.5 * etasq) + ecco * (0.5 + 2 * etasq)
                - J2 * tsi / (ao * psisq) * (-3 * con41 * (1 - 2 * eeta + etasq * (1.5 - 0.5 * eeta))
                + 0.75 * x1mth2 * (2 * etasq - eeta * (1 + etasq)) * Math.cos(2 * argpo)));
        cc5 = 2 * coef1 * ao * omeosq * (1 + 2.75 * (etasq + eeta) + eeta * etasq);
        double cosio4 = cosio2 * cosio2;
        double temp1 = 1.5 * J2 * pinvsq * noUnkozai;
        double temp2 = 0.5 * temp1 * J2 * pinvsq;
        double temp3 = -0.46875 * J4 * pinvsq * pinvsq * noUnkozai;
        mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13 - 78 * cosio2 + 137 * cosio4);
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7 - 114 * cosio2 + 395 * cosio4)
                + temp3 * (3 - 36 * cosio2 + 49 * cosio4);
        double xhdot1 = -temp1 * cosio;
        nodedot = xhdot1 + (0.5 * temp2 * (4 - 19 * cosio2) + 2 * temp3 * (3 - 7 * cosio2)) * cosio;
        omgcof = bstar * cc3 * Math.cos(argpo);
        xmcof = ecco > 1.0e-4 ? -X2O3 * coef * bstar / eeta : 0;
        nodecf = 3.5 * omeosq * xhdot1 * cc1;
        t2cof = 1.5 * cc1;
        double denom = Math.abs(cosio + 1) > 1.5e-12 ? 1 + cosio : 1.5e-12;
        xlcof = -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / denom;
        aycof = -0.5 * J3OJ2 * sinio;
        delmo = Math.pow(1 + eta * Math.cos(mo), 3);
        sinmao = Math.sin(mo);
        x7thm1 = 7 * cosio2 - 1;
        if (!isimp) {
            double cc1sq = cc1 * cc1;
            d2 = 4 * ao * tsi * cc1sq;
            double temp = d2 * tsi * cc1 / 3;
            d3 = (17 * ao + sfour) * temp;
            d4 = 0.5 * temp * ao * tsi * (221 * ao + 31 * sfour) * cc1;
            t3cof = d2 + 2 * cc1sq;
            t4cof = 0.25 * (3 * d3 + cc1 * (12 * d2 + 10 * cc1sq));
            t5cof = 0.2 * (3 * d4 + 12 * cc1 * d3 + 6 * d2 * d2 + 15 * cc1sq * (2 * d2 + cc1sq));
        }
    }

    /** State at a Unix time in milliseconds. */
    State at(long unixMillis) {
        return propagate((julianDate(unixMillis) - epochJd) * 1440.0);
    }

    /** State {@code tsince} minutes after the epoch; throws IllegalStateException when the orbit has decayed. */
    State propagate(double tsince) {
        double t = tsince;
        double xmdf = mo + mdot * t;
        double argpdf = argpo + argpdot * t;
        double nodedf = nodeo + nodedot * t;
        double argpm = argpdf;
        double mm = xmdf;
        double t2 = t * t;
        double nodem = nodedf + nodecf * t2;
        double tempa = 1 - cc1 * t;
        double tempe = bstar * cc4 * t;
        double templ = t2cof * t2;
        if (!isimp) {
            double delomg = omgcof * t;
            double delm = xmcof * (Math.pow(1 + eta * Math.cos(xmdf), 3) - delmo);
            double temp = delomg + delm;
            mm = xmdf + temp;
            argpm = argpdf - temp;
            double t3 = t2 * t;
            double t4 = t3 * t;
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4;
            tempe = tempe + bstar * cc5 * (Math.sin(mm) - sinmao);
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof);
        }
        double am = Math.pow(XKE / noUnkozai, X2O3) * tempa * tempa;
        double nm = XKE / Math.pow(am, 1.5);
        double em = ecco - tempe;
        if (em >= 1 || em < -0.001) throw new IllegalStateException("orbit out of range");
        if (em < 1.0e-6) em = 1.0e-6;
        mm = mm + noUnkozai * templ;
        double xlm = mm + argpm + nodem;
        nodem = nodem % TWO_PI;
        argpm = argpm % TWO_PI;
        xlm = xlm % TWO_PI;
        mm = (xlm - argpm - nodem) % TWO_PI;

        double sinip = Math.sin(inclo);
        double cosip = Math.cos(inclo);
        double axnl = em * Math.cos(argpm);
        double temp = 1 / (am * (1 - em * em));
        double aynl = em * Math.sin(argpm) + temp * aycof;
        double xl = mm + argpm + nodem + temp * xlcof * axnl;

        // Kepler's equation for the eccentric anomaly plus argument of perigee.
        double u = (xl - nodem) % TWO_PI;
        double eo1 = u;
        double tem5 = 9999.9;
        double sineo1 = 0, coseo1 = 0;
        for (int ktr = 1; Math.abs(tem5) >= 1.0e-12 && ktr <= 10; ktr++) {
            sineo1 = Math.sin(eo1);
            coseo1 = Math.cos(eo1);
            tem5 = 1 - coseo1 * axnl - sineo1 * aynl;
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5;
            if (Math.abs(tem5) >= 0.95) tem5 = tem5 > 0 ? 0.95 : -0.95;
            eo1 += tem5;
        }

        double ecose = axnl * coseo1 + aynl * sineo1;
        double esine = axnl * sineo1 - aynl * coseo1;
        double el2 = axnl * axnl + aynl * aynl;
        double pl = am * (1 - el2);
        if (pl < 0) throw new IllegalStateException("semi-latus rectum < 0");
        double rl = am * (1 - ecose);
        double rdotl = Math.sqrt(am) * esine / rl;
        double rvdotl = Math.sqrt(pl) / rl;
        double betal = Math.sqrt(1 - el2);
        temp = esine / (1 + betal);
        double sinu = am / rl * (sineo1 - aynl - axnl * temp);
        double cosu = am / rl * (coseo1 - axnl + aynl * temp);
        double su = Math.atan2(sinu, cosu);
        double sin2u = (cosu + cosu) * sinu;
        double cos2u = 1 - 2 * sinu * sinu;
        temp = 1 / pl;
        double temp1 = 0.5 * J2 * temp;
        double temp2 = temp1 * temp;

        // Short-period periodics.
        double mrt = rl * (1 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u;
        su = su - 0.25 * temp2 * x7thm1 * sin2u;
        double xnode = nodem + 1.5 * temp2 * cosip * sin2u;
        double xinc = inclo + 1.5 * temp2 * cosip * sinip * cos2u;
        double mvt = rdotl - nm * temp1 * x1mth2 * sin2u / XKE;
        double rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / XKE;
        if (mrt < 1) throw new IllegalStateException("decayed");

        double sinsu = Math.sin(su), cossu = Math.cos(su);
        double snod = Math.sin(xnode), cnod = Math.cos(xnode);
        double sini = Math.sin(xinc), cosi = Math.cos(xinc);
        double xmx = -snod * cosi;
        double xmy = cnod * cosi;
        double ux = xmx * sinsu + cnod * cossu;
        double uy = xmy * sinsu + snod * cossu;
        double uz = sini * sinsu;
        double vx = xmx * cossu - cnod * sinsu;
        double vy = xmy * cossu - snod * sinsu;
        double vz = sini * cossu;
        double vkmpersec = RADIUS_KM * XKE / 60.0;
        double r = mrt * RADIUS_KM;
        return new State(r * ux, r * uy, r * uz,
                (mvt * ux + rvdot * vx) * vkmpersec,
                (mvt * uy + rvdot * vy) * vkmpersec,
                (mvt * uz + rvdot * vz) * vkmpersec);
    }
}

package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * "ADB locale": TV Liberata talks to the TV's own adbd (network debugging on 127.0.0.1:5555) as a PC would, with
 * its own RSA key. The first time, the TV shows "Consentire il debug USB?" for this key; with "Consenti sempre" it
 * is remembered, and from then on the app can run shell commands: disable or enable system apps (pm), install its own
 * update in silence (pm install). Nothing here works without that on-screen consent, and the key never leaves the
 * app's private files. Blocking calls: off the main thread.
 */
final class AdbLocal {

    private static final String TAG = "TvHome.AdbLocal";
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** PKCS#1 DigestInfo prefix for SHA-1: adbd signs the 20-byte token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    /** BLOCKED: adbd takes the connection and closes it at once; Fire OS 7.7 refuses adb from the TV itself. */
    static final int OK = 0, WAITING_CONSENT = 1, NO_ADB = 2, BLOCKED = 3;

    private AdbLocal() {
    }

    /** One shell command's output. Throws when adbd is not reachable or the key is not (yet) allowed. */
    static String shell(Context c, String command, int timeoutMs) throws IOException {
        try (Session s = connect(c, timeoutMs, false)) {
            return s.run("shell:" + command);
        }
    }

    /**
     * Tries the connection: OK (the key is allowed), WAITING_CONSENT (the TV now shows the question: answer "Consenti
     * sempre" and try again), NO_ADB (network debugging is off). With {@code ask} false, the TV is not asked.
     */
    static int check(Context c, boolean ask) {
        try (Session s = connect(c, ask ? 30_000 : 4_000, ask)) {
            return OK;
        } catch (ConsentNeeded e) {
            return WAITING_CONSENT;
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "local adb unavailable: " + e.getMessage());
            return "adb chiuso".equals(e.getMessage()) ? BLOCKED : NO_ADB;
        }
    }

    static boolean authorized(Context c) {
        return check(c, false) == OK;
    }

    static final class ConsentNeeded extends IOException {
        ConsentNeeded() {
            super("chiave non ancora autorizzata");
        }
    }

    // ---- protocol ----------------------------------------------------------------------------------------------

    private static Session connect(Context c, int timeoutMs, boolean ask) throws IOException {
        KeyPair keys = keys(c);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port()), 3000);
            socket.setSoTimeout(timeoutMs);
            Session s = new Session(socket);
            s.send(CNXN, VERSION, MAX_DATA, "host::tvliberata\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false, offered = false;
            while (true) {
                Message m = s.read();
                if (m.command == CNXN) return s;
                if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("risposta adb inattesa");
                if (!signed) {
                    s.send(AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data));
                    signed = true;
                } else if (ask && !offered) {
                    s.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKey(keys));   // the TV now asks on screen
                    offered = true;
                } else {
                    s.close();
                    throw new ConsentNeeded();
                }
            }
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closed
            }
            if (e instanceof java.net.SocketTimeoutException && ask) throw new ConsentNeeded();
            throw e;
        }
    }

    private static int port() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String p = (String) sp.getMethod("get", String.class, String.class).invoke(null, "service.adb.tcp.port", "5555");
            int n = Integer.parseInt(p.trim());
            return n > 0 ? n : 5555;
        } catch (Exception e) {
            return 5555;
        }
    }

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private static final class Session implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private int nextId = 1;

        Session(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        void send(int command, int arg0, int arg1, byte[] data) throws IOException {
            out.write(packet(command, arg0, arg1, data));
            out.flush();
        }

        Message read() throws IOException {
            byte[] h = readFully(24);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            Message m = new Message();
            m.command = b.getInt();
            m.arg0 = b.getInt();
            m.arg1 = b.getInt();
            int len = b.getInt();
            if (len < 0 || len > MAX_DATA * 4) throw new IOException("messaggio adb troppo grande");
            m.data = len == 0 ? new byte[0] : readFully(len);
            return m;
        }

        private byte[] readFully(int n) throws IOException {
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r < 0) throw new IOException("adb chiuso");
                off += r;
            }
            return buf;
        }

        /** Opens a service ("shell:..."), collects everything it writes until it closes. */
        String run(String service) throws IOException {
            int local = nextId++;
            send(OPEN, local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (true) {
                Message m = read();
                if (m.arg1 != local && m.command != CLSE) continue;
                if (m.command == WRTE) {
                    result.write(m.data);
                    send(OKAY, local, m.arg0, new byte[0]);
                } else if (m.command == CLSE) {
                    send(CLSE, local, m.arg0, new byte[0]);
                    return new String(result.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** One adb message: 24-byte little-endian header (with the old byte-sum checksum) and its data. */
    static byte[] packet(int command, int arg0, int arg1, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xFF;
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command);
        b.put(data);
        return b.array();
    }

    // ---- the key ----------------------------------------------------------------------------------------------

    private static synchronized KeyPair keys(Context c) throws IOException {
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.isFile() && pub.isFile()) {
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(read(pub))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(read(priv))));
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair k = g.generateKeyPair();
            write(priv, k.getPrivate().getEncoded());
            write(pub, k.getPublic().getEncoded());
            return k;
        } catch (GeneralSecurityException e) {
            throw new IOException("chiave adb non disponibile: " + e.getMessage());
        }
    }

    private static byte[] sign(KeyPair k, byte[] token) throws IOException {
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, k.getPrivate());
            rsa.update(SHA1_PREFIX);
            return rsa.doFinal(token);
        } catch (GeneralSecurityException e) {
            throw new IOException("firma adb non riuscita: " + e.getMessage());
        }
    }

    /** adb's public key format: Android's RSAPublicKey struct, base64, then " name\0". */
    static byte[] publicKey(KeyPair k) {
        return publicKeyString((RSAPublicKey) k.getPublic(), "tvliberata@tv").getBytes(StandardCharsets.UTF_8);
    }

    static String publicKeyString(RSAPublicKey key, String name) {
        int words = 2048 / 32;
        BigInteger n = key.getModulus(), r32 = BigInteger.ZERO.setBit(32);
        BigInteger rr = BigInteger.ZERO.setBit(2048).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32).negate().mod(r32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        putLittle(b, n, words);
        putLittle(b, rr, words);
        b.putInt(key.getPublicExponent().intValue());
        return java.util.Base64.getEncoder().encodeToString(b.array()) + " " + name + "\0";
    }

    private static void putLittle(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ZERO.setBit(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    /** Shell-quotes one argument. */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}

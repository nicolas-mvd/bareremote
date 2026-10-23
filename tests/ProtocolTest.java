package uy.nico.bareremote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Independent wire vectors and real loopback mutual-TLS protocol tests. */
public final class ProtocolTest {
    private static int assertions;
    private static final char[] PASSWORD = "test-only".toCharArray();

    interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        testProto();
        testSecret();
        SSLContext context = context(args[0]);
        final TvProtocol.Identity identity = new TvProtocol.Identity() {
            @Override public SSLContext context(byte[] pin) { return context; }
        };
        byte[] pin = certificate(args[0]);
        testPairing(context, identity, pin);
        testPairingCancellation(context, identity);
        testRemote(context, identity, pin);
        testKeyboardState();
        testKeyboardTransport(context, identity, pin);
        testPictureLinks(context, identity, pin);
        testInputRegistrationDelay(context, identity, pin);
        testDeferredDispatch(context, identity, pin);
        testDeliveryWaitCancellation(context, identity, pin);
        testImmediateClose(context, identity, pin);
        testPinAndTimeout(context, identity, pin);
        System.out.println("Protocol: " + assertions + " assertions passed");
    }

    private static void testProto() throws Exception {
        long[] values = {0, 1, 127, 128, 255, 16384, Integer.MAX_VALUE,
                0xffffffffL, Long.MAX_VALUE, Long.MIN_VALUE, -1};
        for (long value : values) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Proto.varint(out, value);
            check(Proto.readVarint(new ByteArrayInputStream(out.toByteArray())) == value, "varint roundtrip");
        }
        equal(new Proto.Writer().message(10, new Proto.Writer().number(1, 23).number(2, 3)).build(),
                hex("520408171003"), "short OK wire vector");
        equal(new Proto.Writer().message(90, new Proto.Writer().text(1, "barehome://picture/day")).build(),
                hex("d205180a1662617265686f6d653a2f2f706963747572652f646179"), "Day app-link wire vector");
        equal(new Proto.Writer().number(1, 2).number(2, 200)
                .message(30, new Proto.Writer().message(1, new Proto.Writer().number(1, 3).number(2, 6))
                        .number(2, 1)).build(),
                hex("080210c801f201080a04080310061001"), "pairing configuration wire vector");
        Proto.Message nested = Proto.parse(hex("f50501020304f9050102030405060708a206021807520408171003"));
        check(nested.message(10).integer(1, -1) == 23, "unknown fixed and nested fields skipped");
        check(nested.message(100).integer(3, -1) == 7, "high numbered nested field parsed");
        check(Proto.parse(hex("08010802")).integer(1, -1) == 2, "last duplicate value wins");
        check(Proto.parse(hex("0800")).integer(2, 9) == 9, "missing default");
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        byte[] large = new byte[300];
        Arrays.fill(large, (byte) 42);
        Proto.writeFrame(frames, large);
        Proto.writeFrame(frames, hex("0801"));
        final byte[] stream = frames.toByteArray();
        InputStream fragmented = new ByteArrayInputStream(stream) {
            @Override public int read(byte[] b, int offset, int count) {
                return super.read(b, offset, Math.min(count, 1));
            }
        };
        equal(Proto.readFrame(fragmented), large, "fragmented multi-byte length frame");
        equal(Proto.readFrame(fragmented), hex("0801"), "coalesced next frame");
        for (final String malformed : new String[] {"00", "0f", "0b", "0800ff", "08ff", "0a05aa", "0901", "0d01",
                "088080808080808080808002", "ffffffff1f00"}) {
            fails(new Action() { public void run() throws Exception { Proto.parse(hex(malformed)); } },
                    "malformed message rejected");
        }
        fails(new Action() { public void run() throws Exception { Proto.parse(hex("0a00")).integer(1, 0); } },
                "wrong known wire type rejected");
        for (final String malformed : new String[] {"00", "80", "808004", "0401", "ffffffffffffffffffff01"}) {
            fails(new Action() { public void run() throws Exception { Proto.readFrame(new ByteArrayInputStream(hex(malformed))); } },
                    "malformed frame rejected");
        }
        fails(new Action() { public void run() { new Proto.Writer().number(0, 1); } }, "zero tag rejected");
        fails(new Action() { public void run() { new Proto.Writer().bytes(1, new byte[Proto.MAX_FRAME + 1]); } },
                "oversize outbound rejected");
        ByteArrayOutputStream excessive = new ByteArrayOutputStream();
        for (int n = 0; n < 4097; n++) excessive.write(hex("0801"));
        final byte[] excessiveFields = excessive.toByteArray();
        fails(new Action() { public void run() throws Exception { Proto.parse(excessiveFields); } },
                "field count bounded");
    }

    private static void testSecret() throws Exception {
        final RSAPublicKey client = rsa("80ff", "010001");
        final RSAPublicKey server = rsa("deadbeef", "03");
        equal(TvProtocol.unsigned(new BigInteger("80ff", 16)), hex("80ff"), "unsigned modulus strips sign byte");
        equal(TvProtocol.unsigned(new BigInteger("010001", 16)), hex("010001"), "unsigned exponent retained");
        byte[] expected = hex("54794e89b7f113d8b4f45c7a648ec446bb96af58d13fe390c3e55cc5e4059121");
        equal(TvProtocol.pairingSecret(client, server, "54ab12"), expected, "independent SHA256 pairing vector");
        equal(TvProtocol.pairingSecret(client, server, "54AB12"), expected, "uppercase code");
        for (final String code : new String[] {null, "", "12345", "1234567", "12 456", "zz1234", "55ab12"}) {
            fails(new Action() { public void run() throws Exception { TvProtocol.pairingSecret(client, server, code); } },
                    "invalid code rejected locally");
        }
    }

    private static void testPairing(SSLContext context, TvProtocol.Identity identity, byte[] pin) throws Exception {
        for (final boolean echo : new boolean[] {true, false}) {
            final String[] code = new String[1];
            Server server = new Server(context, new Session() {
                @Override public void run(SSLSocket socket) throws Exception {
                    Proto.Message request = read(socket);
                    check(request.integer(1, -1) == 2 && request.integer(2, -1) == 200, "pairing outer version and status");
                    equal(request.message(10).bytes(1), "atvremote".getBytes("UTF-8"), "pairing service");
                    pairingReply(socket, 11, new Proto.Writer());
                    Proto.Message options = read(socket).message(20);
                    check(options.integer(3, -1) == 1 && options.message(1).integer(1, -1) == 3
                            && options.message(1).integer(2, -1) == 6, "hex input negotiation");
                    pairingReply(socket, 20, new Proto.Writer().message(2, new Proto.Writer().number(1, 3).number(2, 6)));
                    Proto.Message configuration = read(socket).message(30);
                    check(configuration.integer(2, -1) == 1 && configuration.message(1).integer(2, -1) == 6,
                            "pairing configuration");
                    RSAPublicKey client = (RSAPublicKey) socket.getSession().getPeerCertificates()[0].getPublicKey();
                    RSAPublicKey tv = (RSAPublicKey) socket.getSession().getLocalCertificates()[0].getPublicKey();
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    for (BigInteger value : new BigInteger[] {client.getModulus(), client.getPublicExponent(),
                            tv.getModulus(), tv.getPublicExponent()}) digest.update(TvProtocol.unsigned(value));
                    digest.update(hex("1234"));
                    byte[] secret = digest.digest();
                    code[0] = String.format("%02x1234", secret[0] & 255);
                    pairingReply(socket, 31, new Proto.Writer());
                    equal(read(socket).message(40).bytes(1), secret, "secret bound to both TLS certificates");
                    pairingReply(socket, 41, echo ? new Proto.Writer().bytes(1, secret) : new Proto.Writer());
                }
            });
            TvProtocol.Pairing pairing = TvProtocol.Pairing.start("127.0.0.1", server.port(), "Test phone", identity);
            fails(new Action() { public void run() throws Exception { pairing.finish("bad"); } }, "bad code leaves pairing available");
            equal(pairing.finish(code[0]), pin, "pin returned after ACK");
            fails(new Action() { public void run() throws Exception { pairing.finish(code[0]); } }, "pairing cannot finish twice");
            server.finish();
        }
        Server bad = new Server(context, new Session() {
            @Override public void run(SSLSocket socket) throws Exception {
                read(socket);
                write(socket, new Proto.Writer().number(1, 2).number(2, 402).message(11, new Proto.Writer()));
            }
        });
        fails(new Action() { public void run() throws Exception {
            TvProtocol.Pairing.start("127.0.0.1", bad.port(), "Test", identity);
        } }, "pairing server failure rejected");
        bad.finish();
    }

    private static void testRemote(SSLContext context, TvProtocol.Identity identity, byte[] pin) throws Exception {
        final CountDownLatch keysReceived = new CountDownLatch(1);
        Server server = new Server(context, new Session() {
            @Override public void run(SSLSocket socket) throws Exception {
                write(socket, new Proto.Writer().message(1, new Proto.Writer().number(1, 1023)));
                check(read(socket).message(1).integer(1, 0) == 615, "keys, IME and app links negotiated without voice");
                write(socket, new Proto.Writer().message(2, new Proto.Writer().number(1, 1023)));
                check(read(socket).message(2).integer(1, 0) == 615, "set active response");
                write(socket, new Proto.Writer().message(40, new Proto.Writer().number(1, 0)));
                check(read(socket).message(10).integer(2, 0) == 3, "short key sent after standby readiness");
                write(socket, new Proto.Writer().message(8, new Proto.Writer().number(1, -1)));
                boolean sawPing = false;
                boolean sawStart = false;
                boolean sawEnd = false;
                long start = 0;
                while (!sawPing || !sawEnd) {
                    Proto.Message msg = read(socket);
                    if (msg.has(9)) {
                        check(msg.message(9).integer(1, 0) == -1, "reader answers ping including signed value");
                        sawPing = true;
                    } else {
                        Proto.Message key = msg.message(10);
                        check(key.integer(1, 0) == 23, "long key code");
                        if (!sawStart) {
                            check(key.integer(2, 0) == 1, "long press starts");
                            start = System.nanoTime();
                            sawStart = true;
                        } else {
                            check(key.integer(2, 0) == 2, "long press releases");
                            check((System.nanoTime() - start) / 1000000L < 1500, "long press bounded");
                            sawEnd = true;
                        }
                    }
                }
                keysReceived.countDown();
            }
        });
        TvProtocol.Remote remote = TvProtocol.Remote.connect("127.0.0.1", server.port(), pin, identity);
        check(remote.isOpen(), "remote open after handshake");
        remote.sendKey(3);
        remote.sendLongKey(23);
        check(keysReceived.await(3, TimeUnit.SECONDS), "keys received");
        server.finish();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (remote.isOpen() && System.nanoTime() < until) Thread.sleep(10);
        check(!remote.isOpen(), "remote notices disconnect");
        fails(new Action() { public void run() throws Exception { remote.sendKey(3); } }, "closed remote rejects command");
        remote.close();
    }

    private static Proto.Message keyboardMessage(int envelope, Proto.Writer body) throws Exception {
        // Use actual serialized frames, not mock callbacks.
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        Proto.writeFrame(framed, new Proto.Writer().message(envelope, body).build());
        return Proto.parse(Proto.readFrame(new ByteArrayInputStream(framed.toByteArray())));
    }
    private static Proto.Writer editor(int id, int revision, int start, int end) {
        return new Proto.Writer().message(1, new Proto.Writer().number(1, id).number(2, 1)
                .text(12, "test.app")).message(2, new Proto.Writer().number(1, revision)
                .number(3, start).number(4, end));
    }
    private static Proto.Writer field(int revision, int start, int end) {
        return new Proto.Writer().number(1, revision).number(3, start).number(4, end);
    }
    private static void testKeyboardState() throws Exception {
        KeyboardSession keyboard = new KeyboardSession();
        keyboard.supported(true);
        keyboard.receive(keyboardMessage(20, new Proto.Writer().message(1,
                new Proto.Writer().text(12, "test.app"))));
        check(!keyboard.snapshot().active, "current app without an editor is not keyboard focus");
        keyboard.receive(keyboardMessage(20, editor(7, 9, 4, 7)));
        KeyboardState target = keyboard.snapshot();
        check(target.canType() && !target.showRequested,
                "server 20 independently supplies editor identity, content revision and selection");
        check(target.imeCounter == 7 && target.fieldCounter == 9,
                "editor counter and unrelated content revision are not required to match");
        Proto.Message batch = Proto.parse(keyboard.commit("é😀漢", target).build()).message(21);
        check(batch.integer(1, 0) == 7 && batch.integer(2, 0) == 9,
                "manual typing uses server 20 counters without waiting for server 21");
        Proto.Message object = batch.message(3).message(2);
        check(!batch.message(3).has(1), "replacement uses the real edit oneof without an insert integer");
        check(object.integer(1, 0) == 4 && object.integer(2, 0) == 7,
                "replacement uses actual selected TV coordinates, not inserted string length minus one");
        check(Arrays.equals(object.bytes(3), "é😀漢".getBytes("UTF-8")), "Unicode UTF-8 bytes preserved");
        batch = Proto.parse(keyboard.commit("!", target).build()).message(21);
        object = batch.message(3).message(2);
        check(object.integer(1, 0) == 8 && object.integer(2, 0) == 8,
                "rapid second chunk uses predicted UTF-16 cursor including surrogate pairs");
        keyboard.receive(keyboardMessage(22, new Proto.Writer().number(1, 1).message(2, field(10, 8, 8))));
        check(keyboard.snapshot().sameField(target), "own edit acknowledgment preserves queued editor token");
        batch = Proto.parse(keyboard.commit("?", target).build()).message(21);
        check(batch.integer(2, 0) == 10 && batch.message(3).message(2).integer(1, 0) == 9,
                "lagging own acknowledgment updates content revision without rewinding predicted cursor");
        check(!keyboard.snapshot().showRequested, "server 22 content updates do not request keyboard display");
        keyboard.receive(keyboardMessage(21, new Proto.Writer().number(1, 1).number(2, 3)));
        long show = keyboard.snapshot().showRequestID;
        check(keyboard.snapshot().showRequested && Boolean.TRUE.equals(keyboard.snapshot().keyboardVisible),
                "server 21 true requests automatic display and flags are not counters");
        check(keyboard.snapshot().imeCounter == 7 && keyboard.snapshot().fieldCounter == 10,
                "server 21 visibility cannot overwrite editor counters");
        keyboard.receive(keyboardMessage(21, new Proto.Writer().number(1, 1)));
        check(keyboard.snapshot().showRequestID == show, "duplicate show preserves manual Remote choice");
        keyboard.receive(keyboardMessage(23, new Proto.Writer().number(2, 2).number(3, 3)));
        check(!keyboard.snapshot().sameField(target), "external selection changes reject queued old-coordinate input");
        check(keyboard.snapshot().showRequestID == show, "selection changes do not replay keyboard show");
        final KeyboardState oldSelection = target;
        fails(() -> keyboard.commit("stale", oldSelection), "stale selected range is never retargeted");
        target = keyboard.snapshot();
        batch = Proto.parse(keyboard.commit("x", target).build()).message(21);
        check(batch.message(3).message(2).integer(1, 0) == 2
                && batch.message(3).message(2).integer(2, 0) == 3,
                "server 23 selection fields two and three are used for replacement");
        keyboard.receive(keyboardMessage(21, new Proto.Writer()));
        check(keyboard.snapshot().active && keyboard.snapshot().canType()
                && Boolean.FALSE.equals(keyboard.snapshot().keyboardVisible),
                "hidden TV keyboard retains editor for deliberate manual typing");
        keyboard.receive(keyboardMessage(20, editor(8, 17, 0, 0)));
        check(!keyboard.snapshot().sameField(target), "new editor invalidates previous editor token");
        keyboard.receive(keyboardMessage(21, new Proto.Writer().number(1, 1)));
        check(keyboard.snapshot().showRequestID > show, "new editor can request automatic keyboard again");
        target = keyboard.snapshot();
        keyboard.keyWritten(66);
        check(keyboard.snapshot().canType(), "Enter without a status callback cannot disable unchanged editor forever");
        keyboard.keyWritten(67);
        check(keyboard.snapshot().canType(), "no-op Delete at cursor zero cannot disable text entry");
        keyboard.receive(keyboardMessage(23, new Proto.Writer().number(2, 1).number(3, 1)));
        target = keyboard.snapshot();
        keyboard.keyWritten(67);
        check(!keyboard.snapshot().canType(), "editing keys require fresh exact TV cursor before the next text replacement");
        keyboard.checkField(target);
        keyboard.receive(keyboardMessage(22, new Proto.Writer().number(1, 1).message(2, field(18, 0, 0))));
        check(keyboard.snapshot().canType(), "a fresh status after an editing key restores text entry");
        keyboard.receive(keyboardMessage(24, new Proto.Writer()));
        check(!keyboard.snapshot().canType(), "external remote editing invalidates stale cursor until fresh status");
        keyboard.receive(keyboardMessage(20, new Proto.Writer().message(1, new Proto.Writer().text(12, "test.other"))));
        check(!keyboard.snapshot().active, "editor counter zero reports focus loss");
        fails(() -> keyboard.commit("hidden", keyboard.snapshot()), "focus loss rejects text");
        for (long bad : new long[] {-1, 2147483648L, Long.MAX_VALUE}) {
            fails(() -> keyboard.receive(keyboardMessage(20, editor(7, 9, 0, 0)
                    .message(1, new Proto.Writer().number(1, bad).number(2, 1)))), "invalid editor counter bounded");
            fails(() -> keyboard.receive(keyboardMessage(22, new Proto.Writer().message(2,
                    new Proto.Writer().number(1, bad)))), "invalid content revision bounded");
        }
        fails(() -> keyboard.receive(keyboardMessage(21, new Proto.Writer().number(1, 9))),
                "server visibility is a boolean, not a batch counter");
        fails(() -> keyboard.receive(keyboardMessage(22, new Proto.Writer().message(2,
                new Proto.Writer().bytes(2, new byte[] {(byte)0xff})))), "invalid UTF-8 rejected without a retained mirror");
        fails(() -> keyboard.receive(keyboardMessage(22, new Proto.Writer().message(2,
                new Proto.Writer().bytes(2, new byte[16385])))), "TV text size bounded");
        for (String text : new String[] {"", "\ud800", "\udc00", new String(new char[4097]).replace('\0', 'a')})
            fails(() -> KeyboardSession.validateCommit(text), "invalid/oversized outgoing text bounded");
        keyboard.clear(); keyboard.supported(true);
        keyboard.receive(keyboardMessage(20, editor(1, 0, 0, 0)));
        batch = Proto.parse(keyboard.commit("a", keyboard.snapshot()).build()).message(21);
        check(batch.integer(1, 0) == 1 && !batch.has(2), "zero content revision uses canonical default omission");
        check(!batch.message(3).message(2).has(1) && !batch.message(3).message(2).has(2), "zero insertion coordinates omitted");
        keyboard.clear();
        check(!keyboard.snapshot().active && !keyboard.snapshot().supported, "disconnect removes all keyboard metadata");
    }
    private static void testKeyboardTransport(SSLContext context, TvProtocol.Identity identity, byte[] pin) throws Exception {
        CountDownLatch gotCommit = new CountDownLatch(1), finish = new CountDownLatch(1);
        Server server = new Server(context, socket -> {
            write(socket, new Proto.Writer().message(1, new Proto.Writer().number(1, 7)));
            check(read(socket).message(1).integer(1, 0) == 7, "IME capability intersected over TLS");
            write(socket, new Proto.Writer().message(2, new Proto.Writer().number(1, 7))); read(socket);
            write(socket, new Proto.Writer().message(20, editor(9, 12, 3, 3)));
            write(socket, new Proto.Writer().message(21, new Proto.Writer().number(1, 1)));
            write(socket, new Proto.Writer().message(40, new Proto.Writer().number(1, 1)));
            Proto.Message batch = read(socket).message(21);
            check(batch.integer(1, 0) == 9 && batch.integer(2, 0) == 12, "authenticated text carries independent editor and content counters");
            check(batch.message(3).message(2).integer(1, 0) == 3 && batch.message(3).message(2).integer(2, 0) == 3,
                    "TLS replacement carries actual selected TV coordinates");
            check(Arrays.equals(batch.message(3).message(2).bytes(3), "paste 😀".getBytes("UTF-8")), "TLS Unicode commit preserved exactly once");
            check(read(socket).message(10).integer(1, 0) == 67, "backspace uses ordinary keyboard key");
            check(read(socket).message(10).integer(1, 0) == 66, "Enter uses ordinary keyboard key");
            gotCommit.countDown();
            write(socket, new Proto.Writer().message(20, new Proto.Writer()));
            finish.await(2, TimeUnit.SECONDS);
        });
        TvProtocol.Remote remote = TvProtocol.Remote.connect("127.0.0.1", server.port(), pin, identity);
        CountDownLatch blur = new CountDownLatch(1);
        remote.setKeyboardListener(value -> { if (!value.active) blur.countDown(); });
        KeyboardState target = remote.keyboardState();
        check(target.canType() && target.showRequested, "real reader consumes distinct editor and visibility frames during startup");
        remote.sendText("paste 😀", target); remote.sendKeyboardKey(67, target); remote.sendKeyboardKey(66, target);
        check(gotCommit.await(2, TimeUnit.SECONDS), "server receives commits and editing keys");
        check(blur.await(2, TimeUnit.SECONDS), "real reader emits focus loss without polling");
        fails(() -> remote.sendText("stale", target), "TLS session refuses obsolete focused field");
        finish.countDown(); remote.close(); server.finish();
    }

    private static void testPictureLinks(SSLContext context, TvProtocol.Identity identity, byte[] pin) throws Exception {
        for (final boolean supported : new boolean[] {true, false}) {
            final CountDownLatch received = new CountDownLatch(1);
            Server server = new Server(context, new Session() {
                @Override public void run(SSLSocket socket) throws Exception {
                    write(socket, new Proto.Writer().message(1, new Proto.Writer().number(1, supported ? 611 : 99)));
                    check(read(socket).message(1).integer(1, 0) == (supported ? 611 : 99), "app link feature intersection");
                    write(socket, new Proto.Writer().message(2, new Proto.Writer().number(1, supported ? 611 : 99)));
                    read(socket);
                    write(socket, new Proto.Writer().message(40, new Proto.Writer().number(1, 1)));
                    if (supported) {
                        equal(read(socket).message(90).bytes(1), "barehome://picture/day".getBytes("UTF-8"), "explicit Day link");
                        equal(read(socket).message(90).bytes(1), "barehome://picture/night".getBytes("UTF-8"), "explicit Night link");
                        equal(read(socket).message(90).bytes(1), "barehome://inputs".getBytes("UTF-8"), "native input picker link");
                    }
                    Proto.Message key = read(socket).message(10);
                    check(key.integer(1, 0) == 178 && key.integer(2, 0) == 3, "input key works with or without app links");
                    received.countDown();
                }
            });
            TvProtocol.Remote remote = TvProtocol.Remote.connect("127.0.0.1", server.port(), pin, identity);
            try {
                if (supported) {
                    remote.sendPictureMode(false);
                    remote.sendPictureMode(true);
                    remote.sendInputPicker();
                } else {
                    try {
                        remote.sendPictureMode(true);
                        throw new AssertionError("unsupported link accepted");
                    } catch (UnsupportedOperationException expected) {
                        check(remote.isOpen(), "unsupported link sends nothing and keeps key session open");
                    }
                }
                remote.sendKey(178);
                check(received.await(2, TimeUnit.SECONDS), "picture/input commands received");
                server.finish();
            } finally { remote.close(); }
        }
    }

    private static void testPairingCancellation(SSLContext context, TvProtocol.Identity identity) throws Exception {
        fails(new Action() { public void run() throws Exception {
            TvProtocol.Pairing.start("127.0.0.1", 1, "Test", identity, () -> true);
        } }, "pre-cancelled pairing does not connect");
        for (int stage = 0; stage < 3; stage++) {
            final int cancelAfter = stage;
            final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            Server server = new Server(context, new Session() {
                @Override public void run(SSLSocket socket) throws Exception {
                    read(socket).message(10);
                    if (cancelAfter == 0) cancelled.set(true);
                    pairingReply(socket, 11, new Proto.Writer());
                    if (cancelAfter > 0) {
                        read(socket).message(20);
                        if (cancelAfter == 1) cancelled.set(true);
                        pairingReply(socket, 20, new Proto.Writer());
                    }
                    if (cancelAfter > 1) {
                        read(socket).message(30);
                        cancelled.set(true);
                        pairingReply(socket, 31, new Proto.Writer());
                    }
                    check(socket.getInputStream().read() == -1, "cancelled pairing sends no later request and closes");
                }
            });
            fails(new Action() { public void run() throws Exception {
                TvProtocol.Pairing.start("127.0.0.1", server.port(), "Test", identity, cancelled::get);
            } }, "pairing cancellation checked between stages");
            server.finish();
        }
    }

    private static void testPinAndTimeout(SSLContext context, TvProtocol.Identity identity, byte[] pin) throws Exception {
        fails(new Action() { public void run() throws Exception { TvProtocol.Remote.connect("127.0.0.1", 1, null, identity); } },
                "remote requires paired pin");
        for (final String badAddress : new String[] {"example.com", "999.1.2.3", "127.1", "127.0.0.1:6466"}) {
            fails(new Action() { public void run() throws Exception {
                TvProtocol.Remote.connect(badAddress, 6466, pin, identity);
            } }, "invalid or DNS address rejected");
        }
        Server badPin = new Server(context, new Session() {
            @Override public void run(SSLSocket socket) throws Exception { socket.getInputStream().read(); }
        });
        fails(new Action() { public void run() throws Exception {
            TvProtocol.Remote.connect("127.0.0.1", badPin.port(), new byte[] {1, 2}, identity);
        } }, "protocol enforces pin even with permissive identity");
        badPin.finish();
        Server stalled = new Server(context, new Session() {
            @Override public void run(SSLSocket socket) throws Exception { socket.getInputStream().read(); }
        });
        long start = System.nanoTime();
        fails(new Action() { public void run() throws Exception {
            TvProtocol.Remote.connect("127.0.0.1", stalled.port(), pin, identity);
        } }, "stalled handshake times out");
        long elapsed = (System.nanoTime() - start) / 1000000L;
        check(elapsed >= 4500 && elapsed < 6500, "whole remote connect has five-second budget");
        stalled.finish();
    }

    private static void testImmediateClose(SSLContext context, TvProtocol.Identity identity, byte[] pin) throws Exception {
        Server server = new Server(context, new Session() {
            @Override public void run(SSLSocket socket) throws Exception {
                write(socket, new Proto.Writer().message(1, new Proto.Writer().number(1, 99)));
                read(socket).message(1);
                write(socket, new Proto.Writer().message(2, new Proto.Writer().number(1, 99)));
                read(socket).message(2);
                write(socket, new Proto.Writer().message(40, new Proto.Writer().number(1, 1)));
                check(socket.getInputStream().read() == -1, "close aborts live idle transport immediately");
            }
        });
        TvProtocol.Remote remote = TvProtocol.Remote.connect("127.0.0.1", server.port(), pin, identity);
        long start = System.nanoTime();
        remote.close();
        remote.close();
        check((System.nanoTime() - start) / 1000000L < 500, "close does not wait for TLS cleanup or peer");
        check(!remote.isOpen(), "closed state is immediate before async TLS cleanup");
        server.finish();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean cleanupAlive;
        do {
            cleanupAlive = false;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.isAlive() && thread.getName().equals("BareRemote-TLS-cleanup")) cleanupAlive = true;
            }
            if (cleanupAlive) Thread.sleep(20);
        } while (cleanupAlive && System.nanoTime() < deadline);
        check(!cleanupAlive, "TLS cleanup does not retain a permanent idle thread");
    }

    /** The TV receives a key first, then dispatches it later; disconnect cancels its queue. */
    private static void testDeferredDispatch(SSLContext context, TvProtocol.Identity identity, byte[] pin) throws Exception {
        for (final boolean waitForDispatch : new boolean[] {false, true}) {
            final CountDownLatch received = new CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger applied = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger pings = new java.util.concurrent.atomic.AtomicInteger();
            Server server = new Server(context, new Session() {
                @Override public void run(SSLSocket socket) throws Exception {
                    startRemote(socket);
                    Proto.Message key = read(socket).message(10);
                    check(key.integer(1, -1) == 3 && key.integer(2, -1) == 3, "deferred TV receives one short key");
                    long dispatchAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
                    write(socket, new Proto.Writer().message(8, new Proto.Writer().number(1, 456)));
                    received.countDown();
                    for (;;) {
                        long left = (dispatchAt - System.nanoTime()) / 1000000L;
                        if (left <= 0) { applied.incrementAndGet(); break; }
                        socket.setSoTimeout((int) Math.max(1, left));
                        try {
                            Proto.Message incoming = read(socket);
                            check(incoming.has(9), "delivery window does not resend a key");
                            check(incoming.message(9).integer(1, 0) == 456, "ping remains responsive during dispatch wait");
                            pings.incrementAndGet();
                        } catch (java.net.SocketTimeoutException dispatchNow) {
                            applied.incrementAndGet();
                            break;
                        } catch (java.io.EOFException disconnected) {
                            return; // Model TV service removing this session's pending input.
                        } catch (java.net.SocketException disconnected) {
                            return; // Closing with an unread server update can reset TCP as well.
                        }
                    }
                    socket.setSoTimeout(2000);
                    check(socket.getInputStream().read() == -1, "one-command session closes after dispatch window");
                }
            });
            TvProtocol.Remote remote = TvProtocol.Remote.connect("127.0.0.1", server.port(), pin, identity);
            remote.sendKey(3);
            check(received.await(2, TimeUnit.SECONDS), "TV has received key before session cleanup");
            long start = System.nanoTime();
            if (waitForDispatch) remote.awaitCommandDeliveryWindow();
            remote.close();
            server.finish();
            check(applied.get() == (waitForDispatch ? 1 : 0), waitForDispatch
                    ? "delivery window lets deferred TV apply key exactly once"
                    : "regression baseline: receive then immediate close loses deferred dispatch");
            if (waitForDispatch) {
                check(pings.get() == 1, "reader handles ping while one-command session stays alive");
                long elapsed = (System.nanoTime() - start) / 1000000L;
                check(elapsed >= 500 && elapsed < 1500, "delivery grace is bounded below the broadcast budget");
            }
        }
    }

    /** A START announcement can precede the virtual input device becoming usable. */
    private static void testInputRegistrationDelay(SSLContext context, TvProtocol.Identity identity, byte[] pin)
            throws Exception {
        for (final boolean waitForRegistration : new boolean[] {false, true}) {
            final java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger applied = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger pings = new java.util.concurrent.atomic.AtomicInteger();
            Server server = new Server(context, new Session() {
                @Override public void run(SSLSocket socket) throws Exception {
                    write(socket, new Proto.Writer().message(1, new Proto.Writer().number(1, 99)));
                    read(socket).message(1);
                    write(socket, new Proto.Writer().message(2, new Proto.Writer().number(1, 99)));
                    read(socket).message(2);
                    long registeredAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
                    write(socket, new Proto.Writer().message(40, new Proto.Writer().number(1, 1)));
                    write(socket, new Proto.Writer().message(8, new Proto.Writer().number(1, 789)));
                    for (;;) {
                        try {
                            Proto.Message message = read(socket);
                            if (message.has(9)) {
                                check(message.message(9).integer(1, 0) == 789, "activation wait answers ping");
                                pings.incrementAndGet();
                            } else {
                                Proto.Message key = message.message(10);
                                check(key.integer(1, 0) == 3 && key.integer(2, 0) == 3, "registration TV gets intended short key");
                                received.incrementAndGet();
                                if (System.nanoTime() >= registeredAt) applied.incrementAndGet();
                                // Early keys are discarded, even if the session remains open afterward.
                            }
                        } catch (java.io.EOFException disconnected) { return; }
                        catch (java.net.SocketException disconnected) { return; }
                    }
                }
            });
            if (waitForRegistration) {
                TvProtocol.Remote remote = TvProtocol.Remote.connect("127.0.0.1", server.port(), pin, identity);
                remote.sendKey(3);
                remote.awaitCommandDeliveryWindow();
                remote.close();
            } else {
                // Independent baseline: complete wire handshake, send immediately, keep open afterward.
                try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", server.port())) {
                    socket.setEnabledProtocols(new String[] {"TLSv1.2"});
                    socket.setSoTimeout(3000);
                    socket.startHandshake();
                    read(socket).message(1);
                    write(socket, new Proto.Writer().message(1, new Proto.Writer().number(1, 99)));
                    read(socket).message(2);
                    write(socket, new Proto.Writer().message(2, new Proto.Writer().number(1, 99)));
                    read(socket).message(40);
                    long ping = read(socket).message(8).integer(1, 0);
                    write(socket, new Proto.Writer().message(9, new Proto.Writer().number(1, ping)));
                    write(socket, new Proto.Writer().message(10, new Proto.Writer().number(1, 3).number(2, 3)));
                    Thread.sleep(750);
                }
            }
            server.finish();
            check(received.get() == 1, "registration regression sends exactly one key without retry");
            check(pings.get() == 1, "registration handshake keeps ping handling alive");
            check(applied.get() == (waitForRegistration ? 1 : 0), waitForRegistration
                    ? "pre-key registration wait applies first key exactly once"
                    : "post-key grace alone cannot rescue an early discarded key");
        }
    }

    private static void testDeliveryWaitCancellation(SSLContext context, TvProtocol.Identity identity, byte[] pin)
            throws Exception {
        for (final boolean interrupt : new boolean[] {false, true}) {
            Server server = new Server(context, new Session() {
                @Override public void run(SSLSocket socket) throws Exception {
                    startRemote(socket);
                    read(socket).message(10);
                    check(socket.getInputStream().read() == -1, "cancelled wait never resends command");
                }
            });
            final TvProtocol.Remote remote = TvProtocol.Remote.connect("127.0.0.1", server.port(), pin, identity);
            remote.sendKey(3);
            final CountDownLatch waiting = new CountDownLatch(1);
            final CountDownLatch finished = new CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread waiter = new Thread(new Runnable() {
                @Override public void run() {
                    waiting.countDown();
                    try {
                        remote.awaitCommandDeliveryWindow();
                        failure.set(new AssertionError("cancelled wait returned normally"));
                    } catch (IOException expected) {
                        if (interrupt && (!(expected instanceof java.io.InterruptedIOException)
                                || !Thread.currentThread().isInterrupted())) {
                            failure.set(new AssertionError("wait must preserve interrupt status"));
                        }
                    } finally { finished.countDown(); }
                }
            });
            waiter.start();
            check(waiting.await(2, TimeUnit.SECONDS), "delivery waiter started");
            if (interrupt) waiter.interrupt(); else remote.close();
            check(finished.await(300, TimeUnit.MILLISECONDS), "close/interruption aborts delivery wait promptly");
            remote.close();
            waiter.join(1000);
            check(failure.get() == null, "cancelled delivery reports uncertainty without retry");
            server.finish();
        }
    }

    private static void startRemote(SSLSocket socket) throws Exception {
        write(socket, new Proto.Writer().message(1, new Proto.Writer().number(1, 99)));
        read(socket).message(1);
        write(socket, new Proto.Writer().message(2, new Proto.Writer().number(1, 99)));
        read(socket).message(2);
        write(socket, new Proto.Writer().message(40, new Proto.Writer().number(1, 1)));
    }

    private interface Session { void run(SSLSocket socket) throws Exception; }

    private static final class Server {
        private final SSLServerSocket server;
        private final Thread thread;
        private volatile Throwable failure;

        Server(SSLContext context, final Session action) throws Exception {
            server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 1,
                    java.net.InetAddress.getByName("127.0.0.1"));
            server.setEnabledProtocols(new String[] {"TLSv1.2"});
            server.setNeedClientAuth(true);
            server.setSoTimeout(8000);
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    try (SSLSocket socket = (SSLSocket) server.accept()) {
                        socket.setSoTimeout(8000);
                        socket.startHandshake();
                        action.run(socket);
                    } catch (Throwable problem) { failure = problem; }
                }
            }, "FakeTV");
            thread.start();
        }

        int port() { return server.getLocalPort(); }

        void finish() throws Exception {
            thread.join(9000);
            server.close();
            if (failure != null) throw new AssertionError("Fake TV failed", failure);
            check(!thread.isAlive(), "fake TV finishes");
        }
    }

    private static void pairingReply(SSLSocket socket, int field, Proto.Writer body) throws Exception {
        write(socket, new Proto.Writer().number(1, 2).number(2, 200).message(field, body));
    }

    private static Proto.Message read(SSLSocket socket) throws Exception {
        return Proto.parse(Proto.readFrame(socket.getInputStream()));
    }

    private static void write(SSLSocket socket, Proto.Writer writer) throws Exception {
        Proto.writeFrame(socket.getOutputStream(), writer.build());
    }

    private static SSLContext context(String path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Paths.get(path))) { store.load(in, PASSWORD); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), new TrustManager[] {new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certs, String type) { }
            public void checkServerTrusted(X509Certificate[] certs, String type) { }
        }}, null);
        return context;
    }

    private static byte[] certificate(String path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Paths.get(path))) { store.load(in, PASSWORD); }
        return store.getCertificate("test").getEncoded();
    }

    private static RSAPublicKey rsa(final String modulus, final String exponent) {
        return new RSAPublicKey() {
            public BigInteger getModulus() { return new BigInteger(modulus, 16); }
            public BigInteger getPublicExponent() { return new BigInteger(exponent, 16); }
            public String getAlgorithm() { return "RSA"; }
            public String getFormat() { return null; }
            public byte[] getEncoded() { return null; }
        };
    }

    private static byte[] hex(String text) {
        byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return result;
    }

    private static void equal(byte[] actual, byte[] expected, String reason) {
        check(Arrays.equals(actual, expected), reason);
    }

    private static synchronized void check(boolean value, String reason) {
        assertions++;
        if (!value) throw new AssertionError(reason);
    }

    private static void fails(Action action, String reason) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { check(true, reason); return; }
        throw new AssertionError(reason);
    }
}

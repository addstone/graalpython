/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python;

import static com.oracle.graal.python.runtime.PosixConstants.AF_INET;
import static com.oracle.graal.python.runtime.PosixConstants.AF_INET6;
import static com.oracle.graal.python.runtime.PosixConstants.AF_UNSPEC;
import static com.oracle.graal.python.runtime.PosixConstants.AI_CANONNAME;
import static com.oracle.graal.python.runtime.PosixConstants.AI_PASSIVE;
import static com.oracle.graal.python.runtime.PosixConstants.EAI_NONAME;
import static com.oracle.graal.python.runtime.PosixConstants.IN6ADDR_LOOPBACK;
import static com.oracle.graal.python.runtime.PosixConstants.INADDR_ANY;
import static com.oracle.graal.python.runtime.PosixConstants.INADDR_LOOPBACK;
import static com.oracle.graal.python.runtime.PosixConstants.INADDR_NONE;
import static com.oracle.graal.python.runtime.PosixConstants.IPPROTO_TCP;
import static com.oracle.graal.python.runtime.PosixConstants.IPPROTO_UDP;
import static com.oracle.graal.python.runtime.PosixConstants.SOCK_DGRAM;
import static com.oracle.graal.python.runtime.PosixConstants.SOCK_STREAM;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import com.oracle.graal.python.builtins.objects.exception.OSErrorEnum;
import com.oracle.graal.python.runtime.PosixConstants.MandatoryIntConstant;
import com.oracle.graal.python.runtime.PosixSupportLibrary;
import com.oracle.graal.python.runtime.PosixSupportLibrary.AddrInfoCursor;
import com.oracle.graal.python.runtime.PosixSupportLibrary.AddrInfoCursorLibrary;
import com.oracle.graal.python.runtime.PosixSupportLibrary.Buffer;
import com.oracle.graal.python.runtime.PosixSupportLibrary.GetAddrInfoException;
import com.oracle.graal.python.runtime.PosixSupportLibrary.Inet4SockAddr;
import com.oracle.graal.python.runtime.PosixSupportLibrary.Inet6SockAddr;
import com.oracle.graal.python.runtime.PosixSupportLibrary.PosixException;
import com.oracle.graal.python.runtime.PosixSupportLibrary.UniversalSockAddr;
import com.oracle.graal.python.runtime.PosixSupportLibrary.UniversalSockAddrLibrary;

@RunWith(Parameterized.class)
public class SocketTests {

    @Parameter(0) public String backendName;

    @Parameters(name = "{0}")
    public static String[] params() {
        return new String[]{"native"};
    }

    @Rule public WithPythonContextRule withPythonContextRule = new WithPythonContextRule((o) -> o.put("python.PosixModuleBackend", backendName));

    @Rule public CleanupRule cleanup = new CleanupRule();
    @Rule public ExpectedException expectedException = ExpectedException.none();

    private Object posixSupport;
    private PosixSupportLibrary lib;
    private UniversalSockAddrLibrary usaLib;
    private AddrInfoCursorLibrary aicLib;

    @Before
    public void setUp() {
        posixSupport = withPythonContextRule.getPythonContext().getPosixSupport();
        lib = PosixSupportLibrary.getUncached();
        usaLib = UniversalSockAddrLibrary.getUncached();
        aicLib = AddrInfoCursorLibrary.getUncached();
    }

    @Test
    public void fillUniversalSockAddr() {
        Inet4SockAddr addr = new Inet4SockAddr(12345, INADDR_LOOPBACK.value);

        UniversalSockAddr usa = createUsa();
        usaLib.fill(usa, addr);
        assertEquals(AF_INET.value, usaLib.getFamily(usa));
        Inet4SockAddr addr2 = usaLib.asInet4SockAddr(usa);
        assertEquals(addr.getPort(), addr2.getPort());
        assertEquals(addr.getAddress(), addr2.getAddress());

        UniversalSockAddr usaCopy = createUsa();
        usaLib.fill(usaCopy, usa);
        assertEquals(AF_INET.value, usaLib.getFamily(usaCopy));
        Inet4SockAddr addr3 = usaLib.asInet4SockAddr(usaCopy);
        assertEquals(addr.getPort(), addr3.getPort());
        assertEquals(addr.getAddress(), addr3.getAddress());
    }

    @Test
    public void bindGetsockname() throws PosixException {
        int s = createSocket(AF_INET.value, SOCK_DGRAM.value, 0);
        UniversalSockAddr bindUsa = createUsa();
        usaLib.fill(bindUsa, new Inet4SockAddr(0, INADDR_LOOPBACK.value));
        lib.bind(posixSupport, s, bindUsa);

        UniversalSockAddr boundUsa = createUsa();
        lib.getsockname(posixSupport, s, boundUsa);
        assertEquals(AF_INET.value, usaLib.getFamily(boundUsa));
        Inet4SockAddr boundAddr = usaLib.asInet4SockAddr(boundUsa);
        assertTrue(boundAddr.getPort() != 0);
        assertEquals(INADDR_LOOPBACK.value, boundAddr.getAddress());
    }

    @Test
    public void sendtoRecvfromInet6() throws PosixException {
        assumeTrue(isInet6Supported());
        int srvSocket = createSocket(AF_INET6.value, SOCK_DGRAM.value, 0);
        UniversalSockAddr bindUsa = createUsa();
        usaLib.fill(bindUsa, new Inet6SockAddr(0, IN6ADDR_LOOPBACK, 0, 0));
        lib.bind(posixSupport, srvSocket, bindUsa);

        UniversalSockAddr srvUsa = createUsa();
        lib.getsockname(posixSupport, srvSocket, srvUsa);

        int cliSocket = createSocket(AF_INET6.value, SOCK_DGRAM.value, 0);
        byte[] data = new byte[]{1, 2, 3};
        int sentCount = lib.sendto(posixSupport, cliSocket, data, data.length, 0, srvUsa);
        assertEquals(data.length, sentCount);

        byte[] buf = new byte[100];
        UniversalSockAddr srcUsa = createUsa();
        int recvCount = lib.recvfrom(posixSupport, srvSocket, buf, buf.length, 0, srcUsa);

        assertEquals(data.length, recvCount);
        assertArrayEquals(data, Arrays.copyOf(buf, recvCount));

        assertEquals(AF_INET6.value, usaLib.getFamily(srcUsa));
        Inet6SockAddr srcAddr = usaLib.asInet6SockAddr(srcUsa);

        UniversalSockAddr cliUsa = createUsa();
        lib.getsockname(posixSupport, cliSocket, cliUsa);
        assertEquals(AF_INET6.value, usaLib.getFamily(cliUsa));
        Inet6SockAddr cliAddr = usaLib.asInet6SockAddr(cliUsa);

        assertArrayEquals(IN6ADDR_LOOPBACK, srcAddr.getAddress());
        assertEquals(cliAddr.getPort(), srcAddr.getPort());
    }

    @Test
    public void sendtoRecvfromInet() throws PosixException {
        int srvSocket = createSocket(AF_INET.value, SOCK_DGRAM.value, 0);
        UniversalSockAddr bindUsa = createUsa();
        usaLib.fill(bindUsa, new Inet4SockAddr(0, INADDR_LOOPBACK.value));
        lib.bind(posixSupport, srvSocket, bindUsa);

        UniversalSockAddr srvUsa = createUsa();
        lib.getsockname(posixSupport, srvSocket, srvUsa);

        int cliSocket = createSocket(AF_INET.value, SOCK_DGRAM.value, 0);
        byte[] data = new byte[]{1, 2, 3};
        int sentCount = lib.sendto(posixSupport, cliSocket, data, data.length, 0, srvUsa);
        assertEquals(data.length, sentCount);

        byte[] buf = new byte[100];
        UniversalSockAddr srcUsa = createUsa();
        int recvCount = lib.recvfrom(posixSupport, srvSocket, buf, buf.length, 0, srcUsa);

        assertEquals(data.length, recvCount);
        assertArrayEquals(data, Arrays.copyOf(buf, recvCount));

        assertEquals(AF_INET.value, usaLib.getFamily(srcUsa));
        Inet4SockAddr srcAddr = usaLib.asInet4SockAddr(srcUsa);

        UniversalSockAddr cliUsa = createUsa();
        lib.getsockname(posixSupport, cliSocket, cliUsa);
        assertEquals(AF_INET.value, usaLib.getFamily(cliUsa));
        Inet4SockAddr cliAddr = usaLib.asInet4SockAddr(cliUsa);

        assertEquals(INADDR_LOOPBACK.value, srcAddr.getAddress());
        assertEquals(cliAddr.getPort(), srcAddr.getPort());
    }

    @Test
    public void acceptConnectInet() throws PosixException {
        int listenSocket = createSocket(AF_INET.value, SOCK_STREAM.value, 0);
        UniversalSockAddr bindUsa = createUsa();
        usaLib.fill(bindUsa, new Inet4SockAddr(0, INADDR_LOOPBACK.value));
        lib.bind(posixSupport, listenSocket, bindUsa);
        lib.listen(posixSupport, listenSocket, 5);

        UniversalSockAddr listenAddrUsa = createUsa();
        lib.getsockname(posixSupport, listenSocket, listenAddrUsa);

        assertEquals(AF_INET.value, usaLib.getFamily(listenAddrUsa));

        int cliSocket = createSocket(AF_INET.value, SOCK_STREAM.value, 0);
        lib.connect(posixSupport, cliSocket, listenAddrUsa);

        UniversalSockAddr cliAddrOnClientUsa = createUsa();
        lib.getsockname(posixSupport, cliSocket, cliAddrOnClientUsa);

        UniversalSockAddr cliAddrOnServerUsa = createUsa();
        int srvSocket = lib.accept(posixSupport, listenSocket, cliAddrOnServerUsa);
        cleanup.add(() -> lib.close(posixSupport, srvSocket));

        assertEquals(usaLib.asInet4SockAddr(cliAddrOnServerUsa).getPort(), usaLib.asInet4SockAddr(cliAddrOnClientUsa).getPort());

        UniversalSockAddr srvAddrOnServerUsa = createUsa();
        lib.getsockname(posixSupport, srvSocket, srvAddrOnServerUsa);

        UniversalSockAddr srvAddrOnClientUsa = createUsa();
        lib.getpeername(posixSupport, cliSocket, srvAddrOnClientUsa);

        assertEquals(usaLib.asInet4SockAddr(srvAddrOnServerUsa).getPort(), usaLib.asInet4SockAddr(srvAddrOnClientUsa).getPort());

        byte[] data = new byte[]{1, 2, 3};
        assertEquals(data.length, lib.send(posixSupport, srvSocket, data, data.length, 0));

        byte[] buf = new byte[100];
        int cnt = lib.recv(posixSupport, cliSocket, buf, buf.length, 0);
        assertEquals(data.length, cnt);

        assertArrayEquals(data, Arrays.copyOf(buf, cnt));
    }

    @Test
    public void acceptConnectInet6() throws PosixException {
        assumeTrue(isInet6Supported());
        int listenSocket = createSocket(AF_INET6.value, SOCK_STREAM.value, 0);
        UniversalSockAddr bindUsa = createUsa();
        usaLib.fill(bindUsa, new Inet6SockAddr(0, IN6ADDR_LOOPBACK, 0, 0));
        lib.bind(posixSupport, listenSocket, bindUsa);
        lib.listen(posixSupport, listenSocket, 5);

        UniversalSockAddr listenAddrUsa = createUsa();
        lib.getsockname(posixSupport, listenSocket, listenAddrUsa);

        assertEquals(AF_INET6.value, usaLib.getFamily(listenAddrUsa));

        int cliSocket = createSocket(AF_INET6.value, SOCK_STREAM.value, 0);
        lib.connect(posixSupport, cliSocket, listenAddrUsa);

        UniversalSockAddr cliAddrOnClientUsa = createUsa();
        lib.getsockname(posixSupport, cliSocket, cliAddrOnClientUsa);

        UniversalSockAddr cliAddrOnServerUsa = createUsa();
        int srvSocket = lib.accept(posixSupport, listenSocket, cliAddrOnServerUsa);
        cleanup.add(() -> lib.close(posixSupport, srvSocket));

        assertEquals(usaLib.asInet6SockAddr(cliAddrOnServerUsa).getPort(), usaLib.asInet6SockAddr(cliAddrOnClientUsa).getPort());

        UniversalSockAddr srvAddrOnServerUsa = createUsa();
        lib.getsockname(posixSupport, srvSocket, srvAddrOnServerUsa);

        UniversalSockAddr srvAddrOnClientUsa = createUsa();
        lib.getpeername(posixSupport, cliSocket, srvAddrOnClientUsa);

        assertEquals(usaLib.asInet6SockAddr(srvAddrOnServerUsa).getPort(), usaLib.asInet6SockAddr(srvAddrOnClientUsa).getPort());

        byte[] data = new byte[]{1, 2, 3};
        assertEquals(data.length, lib.write(posixSupport, srvSocket, Buffer.wrap(data)));

        Buffer buf = lib.read(posixSupport, cliSocket, 100);
        assertEquals(data.length, buf.length);

        assertArrayEquals(data, Arrays.copyOf(buf.data, data.length));
    }

    @Test
    public void getpeernameNotConnected() throws PosixException {
        expectErrno(OSErrorEnum.ENOTCONN);
        int s = createSocket(AF_INET.value, SOCK_STREAM.value, 0);
        lib.getpeername(posixSupport, s, createUsa());
    }

    @Test
    public void getaddrinfoNoInput() throws GetAddrInfoException {
        expectGetAddrInfoException(EAI_NONAME);
        lib.getaddrinfo(posixSupport, null, null, AF_UNSPEC.value, 0, 0, 0);
    }

    @Test
    public void getaddrinfoServiceOnly() throws GetAddrInfoException {
        Object service = s2p("https");
        AddrInfoCursor aic = lib.getaddrinfo(posixSupport, null, service, AF_UNSPEC.value, SOCK_STREAM.value, 0, 0);
        cleanup.add(() -> aicLib.release(aic));
        do {
            int family = aicLib.getFamily(aic);

            assertEquals(SOCK_STREAM.value, aicLib.getSockType(aic));
            assertNull(aicLib.getCanonName(aic));

            UniversalSockAddr usa = createUsa();
            aicLib.getSockAddr(aic, usa);
            assertEquals(family, usaLib.getFamily(usa));
            if (family == AF_INET.value) {
                Inet4SockAddr addr2 = usaLib.asInet4SockAddr(usa);
                assertEquals(INADDR_LOOPBACK.value, addr2.getAddress());
                assertEquals(443, addr2.getPort());
            }
        } while (aicLib.next(aic));
    }

    @Test
    public void getaddrinfoPassive() throws GetAddrInfoException {
        Object service = s2p("https");
        AddrInfoCursor aic = lib.getaddrinfo(posixSupport, null, service, AF_INET.value, 0, IPPROTO_TCP.value, AI_PASSIVE.value);
        cleanup.add(() -> aicLib.release(aic));
        assertEquals(AF_INET.value, aicLib.getFamily(aic));
        assertEquals(SOCK_STREAM.value, aicLib.getSockType(aic));
        assertEquals(IPPROTO_TCP.value, aicLib.getProtocol(aic));
        assertNull(aicLib.getCanonName(aic));

        UniversalSockAddr usa = createUsa();
        aicLib.getSockAddr(aic, usa);
        Inet4SockAddr addr = usaLib.asInet4SockAddr(usa);
        assertEquals(INADDR_ANY.value, addr.getAddress());
        assertEquals(443, addr.getPort());
    }

    @Test
    public void getaddrinfoServerOnlyNoCanon() throws GetAddrInfoException {
        Object node = s2p("localhost");
        AddrInfoCursor aic = lib.getaddrinfo(posixSupport, node, null, AF_UNSPEC.value, SOCK_DGRAM.value, 0, 0);
        cleanup.add(() -> aicLib.release(aic));
        do {
            assertEquals(SOCK_DGRAM.value, aicLib.getSockType(aic));
            assertEquals(IPPROTO_UDP.value, aicLib.getProtocol(aic));
            assertNull(aicLib.getCanonName(aic));

            if (aicLib.getFamily(aic) == AF_INET.value) {
                UniversalSockAddr usa = createUsa();
                aicLib.getSockAddr(aic, usa);
                assertEquals(AF_INET.value, usaLib.getFamily(usa));
                Inet4SockAddr addr = usaLib.asInet4SockAddr(usa);
                assertEquals(INADDR_LOOPBACK.value, addr.getAddress());
                assertEquals(0, addr.getPort());
            }
        } while (aicLib.next(aic));
    }

    @Test
    public void getaddrinfo() throws GetAddrInfoException {
        Object node = s2p("localhost");
        Object service = s2p("https");
        AddrInfoCursor aic = lib.getaddrinfo(posixSupport, node, service, AF_INET.value, 0, IPPROTO_TCP.value, AI_CANONNAME.value);
        cleanup.add(() -> aicLib.release(aic));
        assertEquals(AF_INET.value, aicLib.getFamily(aic));
        assertEquals(SOCK_STREAM.value, aicLib.getSockType(aic));
        assertEquals(IPPROTO_TCP.value, aicLib.getProtocol(aic));
        assertEquals("localhost", p2s(aicLib.getCanonName(aic)));

        UniversalSockAddr usa = createUsa();
        aicLib.getSockAddr(aic, usa);
        assertEquals(AF_INET.value, usaLib.getFamily(usa));
        Inet4SockAddr addr2 = usaLib.asInet4SockAddr(usa);
        assertEquals(INADDR_LOOPBACK.value, addr2.getAddress());
        assertEquals(443, addr2.getPort());
    }

    @Test
    public void inet4Address() {
        Inet4SockAddr addr = new Inet4SockAddr(1234, 0x01020304);
        assertEquals(AF_INET.value, addr.getFamily());
        assertEquals(1234, addr.getPort());
        assertEquals(0x01020304, addr.getAddress());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, addr.getAddressAsBytes());

        addr.setPort(0);
        addr.setAddress(0xfffefdfc);
        assertEquals(0, addr.getPort());
        assertEquals(0xfffefdfc, addr.getAddress());
        assertArrayEquals(new byte[]{-1, -2, -3, -4}, addr.getAddressAsBytes());

        addr = new Inet4SockAddr(65535, new byte[]{6, 7, 8, 9, 10});
        assertEquals(AF_INET.value, addr.getFamily());
        assertEquals(65535, addr.getPort());
        assertEquals(0x06070809, addr.getAddress());
        assertArrayEquals(new byte[]{6, 7, 8, 9}, addr.getAddressAsBytes());

        addr.setPort(4321);
        addr.setAddress(new byte[]{1, 0, -1, -2, -3, -4});
        assertEquals(4321, addr.getPort());
        assertEquals(0x0100fffe, addr.getAddress());
        assertArrayEquals(new byte[]{1, 0, -1, -2}, addr.getAddressAsBytes());
    }

    static final Map<String, Integer> ip4Addresses = new HashMap<>();
    static {
        ip4Addresses.put("text", null);
        ip4Addresses.put("1.2.3.", null);
        ip4Addresses.put("1.2.65536", null);
        ip4Addresses.put("1.2.3.4.5", null);
        ip4Addresses.put("1.2.3.4", 0x01020304);
        ip4Addresses.put("1.2.0x3456", 0x01023456);
        ip4Addresses.put("1.2.0xffff", 0x0102ffff);
        ip4Addresses.put("1.0xffffff", 0x01ffffff);
        ip4Addresses.put("1.234567", 0x01039447);
        ip4Addresses.put("0x12345678", 0x12345678);
        ip4Addresses.put("0xff.0377.65535", 0xffffffff);
        ip4Addresses.put("0xa.012.10.0", 0x0a0a0a00);
        ip4Addresses.put("00.0x00000.0", 0x00000000);
    }

    @Test
    public void inet_addr() {
        for (Map.Entry<String, Integer> a : ip4Addresses.entrySet()) {
            String src = a.getKey();
            Integer expected = a.getValue();
            int actual = lib.inet_addr(posixSupport, s2p(src));
            assertEquals("inet_addr(\"" + src + "\")", expected == null ? INADDR_NONE.value : expected, actual);
        }
    }

    @Test
    public void inet_aton() {
        for (Map.Entry<String, Integer> a : ip4Addresses.entrySet()) {
            String src = a.getKey();
            Integer expected = a.getValue();
            Integer actual;
            try {
                actual = lib.inet_aton(posixSupport, s2p(src));
            } catch (IllegalArgumentException e) {
                actual = null;
            }
            assertEquals("inet_aton(\"" + src + "\")", expected, actual);
        }
    }

    @Test
    public void inet_ntoa() {
        assertEquals("0.0.0.0", p2s(lib.inet_ntoa(posixSupport, 0x00000000)));
        assertEquals("1.2.3.4", p2s(lib.inet_ntoa(posixSupport, 0x01020304)));
        assertEquals("18.52.86.120", p2s(lib.inet_ntoa(posixSupport, 0x12345678)));
        assertEquals("255.255.255.255", p2s(lib.inet_ntoa(posixSupport, 0xffffffff)));
    }

    @Test
    public void inet_pton() throws PosixException {
        assertArrayEquals(new byte[]{1, 2, -2, -1}, lib.inet_pton(posixSupport, AF_INET.value, s2p("1.2.254.255")));
        assertArrayEquals(new byte[]{0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1}, lib.inet_pton(posixSupport, AF_INET6.value, s2p("1::FF")));
    }

    @Test
    public void inet_pton_eafnosupport() throws PosixException {
        expectErrno(OSErrorEnum.EAFNOSUPPORT);
        lib.inet_pton(posixSupport, AF_UNSPEC.value, s2p(""));
    }

    @Test
    public void inet_pton_invalid() throws PosixException {
        expectedException.expect(IllegalArgumentException.class);
        lib.inet_pton(posixSupport, AF_INET6.value, s2p(":"));
    }

    @Test
    public void inet_ntop() throws PosixException {
        assertEquals("1.0.255.254", p2s(lib.inet_ntop(posixSupport, AF_INET.value, new byte[]{1, 0, -1, -2, -3})));
        assertEquals("fdfe:0:ff00::1:203", p2s(lib.inet_ntop(posixSupport, AF_INET6.value, new byte[]{-3, -2, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4})));
    }

    @Test
    public void inet_ntop_eafnosupport() throws PosixException {
        expectErrno(OSErrorEnum.EAFNOSUPPORT);
        lib.inet_ntop(posixSupport, AF_UNSPEC.value, new byte[16]);
    }

    @Test
    public void inet_ntop_len() throws PosixException {
        expectedException.expect(IllegalArgumentException.class);
        lib.inet_ntop(posixSupport, AF_INET6.value, new byte[15]);
    }

    private Object s2p(String s) {
        return lib.createPathFromString(posixSupport, s);
    }

    private String p2s(Object p) {
        return lib.getPathAsString(posixSupport, p);
    }

    private void expectErrno(OSErrorEnum expectedErrorCode) {
        expectedException.expect(new TypeSafeMatcher<PosixException>() {
            @Override
            protected boolean matchesSafely(PosixException item) {
                return item.getErrorCode() == expectedErrorCode.getNumber();
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("PosixException with error code ").appendValue(expectedErrorCode.name());
            }

            @Override
            protected void describeMismatchSafely(PosixException item, Description mismatchDescription) {
                mismatchDescription.appendText("the actual error code was ").appendValue(item.getErrorCode()).appendText(" (").appendValue(item).appendText(")");
            }
        });
    }

    private void expectGetAddrInfoException(MandatoryIntConstant expectedErrorCode) {
        expectedException.expect(new TypeSafeMatcher<GetAddrInfoException>() {
            @Override
            protected boolean matchesSafely(GetAddrInfoException item) {
                return item.getErrorCode() == expectedErrorCode.value;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("GetAddrInfoException with error code ").appendValue(expectedErrorCode.name);
            }

            @Override
            protected void describeMismatchSafely(GetAddrInfoException item, Description mismatchDescription) {
                mismatchDescription.appendText("the actual error code was ").appendValue(item.getErrorCode()).appendText(" (").appendValue(item).appendText(")");
            }
        });
    }

    private int createSocket(int family, int type, int protocol) throws PosixException {
        int sockfd = lib.socket(posixSupport, family, type, protocol);
        cleanup.add(() -> lib.close(posixSupport, sockfd));
        return sockfd;
    }

    private UniversalSockAddr createUsa() {
        UniversalSockAddr universalSockAddr = lib.allocUniversalSockAddr(posixSupport);
        cleanup.add(() -> usaLib.release(universalSockAddr));
        return universalSockAddr;
    }

    private static boolean isInet6Supported() {
        // Linux CI slaves currently do not support IPv6 reliably
        return !(runsOnCi() && runsOnLinux());
    }

    private static boolean runsOnLinux() {
        String property = System.getProperty("os.name");
        return (property != null && property.toLowerCase().contains("linux"));
    }

    private static boolean runsOnCi() {
        return "true".equals(System.getenv("CI"));
    }
}
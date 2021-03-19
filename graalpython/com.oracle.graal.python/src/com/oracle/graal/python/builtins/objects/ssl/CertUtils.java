/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.builtins.objects.ssl;

import static com.oracle.graal.python.builtins.modules.SSLModuleBuiltins.LOGGER;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.JAVA_X509_CA_ISSUERS;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.JAVA_X509_CRL_DISTRIBUTION_POINTS;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.JAVA_X509_ISSUER;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.JAVA_X509_NOT_AFTER;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.JAVA_X509_NOT_BEFORE;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.JAVA_X509_OCSP;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.JAVA_X509_SERIAL_NUMBER;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.JAVA_X509_SUBJECT;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.JAVA_X509_SUBJECT_ALT_NAME;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.JAVA_X509_VERSION;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.OID_AUTHORITY_INFO_ACCESS;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.OID_CA_ISSUERS;
import static com.oracle.graal.python.builtins.objects.ssl.ASN1Helper.OID_CRL_DISTRIBUTION_POINTS;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CRLException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;

import javax.crypto.interfaces.DHPrivateKey;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;

import com.oracle.graal.python.builtins.objects.common.HashingStorage;
import com.oracle.graal.python.builtins.objects.common.HashingStorageLibrary;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.tuple.PTuple;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.nodes.Node;

import sun.security.util.DerValue;
import sun.security.x509.AccessDescription;
import sun.security.x509.AuthorityInfoAccessExtension;
import sun.security.x509.CRLDistributionPointsExtension;
import sun.security.x509.DistributionPoint;
import sun.security.x509.GeneralName;
import sun.security.x509.GeneralNameInterface;
import sun.security.x509.GeneralNames;
import sun.security.x509.URIName;
import sun.security.x509.X509CertImpl;

public final class CertUtils {

    private static final String BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----";
    private static final String END_CERTIFICATE = "-----END CERTIFICATE-----";
    private static final String BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----";
    private static final String END_PRIVATE_KEY = "-----END PRIVATE KEY-----";
    private static final String BEGIN_DH_PARAMETERS = "-----BEGIN DH PARAMETERS-----";
    private static final String END_DH_PARAMETERS = "-----END DH PARAMETERS-----";
    private static final String BEGIN_X509_CRL = "-----BEGIN X509 CRL-----";
    private static final String END_X509_CRL = "-----END X509 CRL-----";

    /**
     * openssl v3_purp.c#check_ca
     */
    @TruffleBoundary
    static boolean isCA(X509Certificate cert, boolean[] keyUsage) {
        return keyUsage != null && keyUsage.length > 5 && keyUsage[5] ||
                        cert.getBasicConstraints() != -1 ||
                        cert.getVersion() == 1 && isSelfSigned(cert);
    }

    @TruffleBoundary
    static boolean isSelfSigned(X509Certificate cert) {
        try {
            cert.verify(cert.getPublicKey());
        } catch (InvalidKeyException | NoSuchAlgorithmException | NoSuchProviderException | SignatureException | CertificateException e) {
            return false;
        }
        return true;
    }

    @TruffleBoundary
    static boolean isCrl(boolean[] keyUsage) {
        return keyUsage != null && keyUsage.length > 6 && keyUsage[6];
    }

    /**
     * _ssl.c#_decode_certificate
     */
    @TruffleBoundary
    public static PDict decodeCertificate(X509Certificate cert) throws IOException, CertificateParsingException {
        PythonObjectFactory factory = PythonObjectFactory.getUncached();
        PDict dict = factory.createDict();
        HashingStorage storage = dict.getDictStorage();
        HashingStorageLibrary hlib = HashingStorageLibrary.getUncached();
        storage = setItem(hlib, storage, JAVA_X509_OCSP, parseOCSP(cert, factory));
        storage = setItem(hlib, storage, JAVA_X509_CA_ISSUERS, parseCAIssuers(cert, factory));
        storage = setItem(hlib, storage, JAVA_X509_ISSUER, createTupleForX509Name(cert.getIssuerDN().getName(), factory));
        storage = setItem(hlib, storage, JAVA_X509_NOT_AFTER, getNotAfter(cert));
        storage = setItem(hlib, storage, JAVA_X509_NOT_BEFORE, getNotBefore(cert));
        storage = setItem(hlib, storage, JAVA_X509_SERIAL_NUMBER, getSerialNumber(cert));
        storage = setItem(hlib, storage, JAVA_X509_CRL_DISTRIBUTION_POINTS, parseCRLPoints(cert, factory));
        storage = setItem(hlib, storage, JAVA_X509_SUBJECT, createTupleForX509Name(cert.getSubjectDN().getName(), factory));
        storage = setItem(hlib, storage, JAVA_X509_SUBJECT_ALT_NAME, parseSubjectAltName(cert, factory));
        storage = setItem(hlib, storage, JAVA_X509_VERSION, getVersion(cert));
        dict.setDictStorage(storage);
        return dict;
    }

    private static HashingStorage setItem(HashingStorageLibrary hlib, HashingStorage storage, String key, Object value) {
        if (value != null) {
            return hlib.setItem(storage, key, value);
        } else {
            return storage;
        }
    }

    @TruffleBoundary
    private static String getSerialNumber(X509Certificate x509Certificate) {
        String sn = x509Certificate.getSerialNumber().toString(16).toUpperCase();
        // i2a_ASN1_INTEGER pads the number to have even number of digits
        return sn.length() % 2 == 0 ? sn : '0' + sn;
    }

    @TruffleBoundary
    private static int getVersion(X509Certificate x509Certificate) {
        return x509Certificate.getVersion();
    }

    @TruffleBoundary
    private static String getNotAfter(X509Certificate x509Certificate) {
        return formatDate(x509Certificate.getNotAfter());
    }

    @TruffleBoundary
    private static String getNotBefore(X509Certificate x509Certificate) {
        return formatDate(x509Certificate.getNotBefore());
    }

    private static final ZoneId zoneId = ZoneId.of("GMT");
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("MMM ppd HH:mm:ss yyyy z");

    @TruffleBoundary
    private static String formatDate(Date d) {
        return ZonedDateTime.ofInstant(d.toInstant(), zoneId).format(DF);
    }

    @TruffleBoundary
    private static PTuple createTupleForX509Name(String name, PythonObjectFactory factory) {
        List<PTuple> result = new ArrayList<>();
        for (String component : name.split(",")) {
            String[] kv = component.split("=");
            if (kv.length == 2) {
                PTuple innerTuple = factory.createTuple(new String[]{ASN1Helper.translateKeyToPython(kv[0].trim()), kv[1].trim()});
                result.add(factory.createTuple(new Object[]{innerTuple}));
            }
        }
        // The String form is in the LDAP format, where the elements are in reverse order from what
        // was in the certificate
        Collections.reverse(result);
        return factory.createTuple(result.toArray(new Object[0]));
    }

    @TruffleBoundary
    private static PTuple parseSubjectAltName(X509Certificate certificate, PythonObjectFactory factory) throws CertificateParsingException {
        List<Object> tuples = new ArrayList<>(16);
        Collection<List<?>> altNames = certificate.getSubjectAlternativeNames();
        if (altNames != null) {
            for (List<?> altName : altNames) {
                if (altName.size() == 2 && altName.get(0) instanceof Integer) {
                    int type = (Integer) altName.get(0);
                    Object value = altName.get(1);
                    String stringValue;
                    if (value instanceof String) {
                        stringValue = (String) value;
                    } else {
                        stringValue = "<unsupported>";
                    }
                    switch (type) {
                        // see openssl v3_alt.c#i2v_GENERAL_NAME()
                        case 0:
                            tuples.add(factory.createTuple(new Object[]{"othername", stringValue}));
                            break;
                        case 1:
                            tuples.add(factory.createTuple(new Object[]{"email", stringValue}));
                            break;
                        case 2:
                            tuples.add(factory.createTuple(new Object[]{"DNS", stringValue}));
                            break;
                        case 3:
                            tuples.add(factory.createTuple(new Object[]{"X400Name", stringValue}));
                            break;
                        case 4:
                            tuples.add(factory.createTuple(new Object[]{"DirName", createTupleForX509Name(stringValue, factory)}));
                            break;
                        case 5:
                            tuples.add(factory.createTuple(new Object[]{"EdiPartyName", stringValue}));
                            break;
                        case 6:
                            tuples.add(factory.createTuple(new Object[]{"URI", stringValue}));
                            break;
                        case 7:
                            tuples.add(factory.createTuple(new Object[]{"IP Address", stringValue}));
                            break;
                        case 8:
                            tuples.add(factory.createTuple(new Object[]{"Registered ID", stringValue}));
                            break;
                        default:
                            continue;
                    }
                }
            }
            return factory.createTuple(tuples.toArray(new Object[tuples.size()]));
        }
        return null;
    }

    @TruffleBoundary
    private static PTuple parseCRLPoints(X509Certificate cert, PythonObjectFactory factory) throws IOException {
        List<String> result = new ArrayList<>();
        byte[] bytes = cert.getExtensionValue(OID_CRL_DISTRIBUTION_POINTS);
        if (bytes != null) {
            DerValue val = new DerValue(bytes);
            bytes = val.getOctetString();
            CRLDistributionPointsExtension cdpe;
            try {
                cdpe = new CRLDistributionPointsExtension(false, bytes);
            } catch (IOException ex) {
                // just ignore
                LOGGER.log(Level.FINER, "", ex);
                return null;
            }
            List<DistributionPoint> points = cdpe.get("points");
            if (points != null) {
                for (DistributionPoint point : points) {
                    GeneralNames fullName = point.getFullName();
                    if (fullName != null) {
                        List<GeneralName> names = fullName.names();
                        if (names != null) {
                            for (GeneralName generalName : names) {
                                GeneralNameInterface n = generalName.getName();
                                if (n instanceof URIName) {
                                    result.add(((URIName) n).getURI().toString());
                                }
                            }
                        }
                    }
                }
            }
            return factory.createTuple(result.toArray(new String[result.size()]));
        }
        return null;
    }

    @TruffleBoundary
    private static PTuple parseCAIssuers(X509Certificate cert, PythonObjectFactory factory) throws IOException {
        List<String> result = new ArrayList<>();
        byte[] bytes = cert.getExtensionValue(OID_AUTHORITY_INFO_ACCESS);
        if (bytes != null) {
            DerValue val = new DerValue(bytes);
            bytes = val.getOctetString();
            AuthorityInfoAccessExtension aiae = new AuthorityInfoAccessExtension(false, bytes);
            for (AccessDescription ad : aiae.getAccessDescriptions()) {
                if (ad.getAccessMethod().toString().equals(OID_CA_ISSUERS)) {
                    GeneralName gn = ad.getAccessLocation();
                    if (gn != null) {
                        GeneralNameInterface n = gn.getName();
                        if (n instanceof URIName) {
                            result.add(((URIName) n).getURI().toString());
                        }
                    }
                }
            }
            return factory.createTuple(result.toArray(new String[result.size()]));
        }
        return null;
    }

    @TruffleBoundary
    private static PTuple parseOCSP(X509Certificate cert, PythonObjectFactory factory) {
        // Inlined from sun.security.provider.certpath.OCSP#getResponderURI
        // Examine the certificate's AuthorityInfoAccess extension
        X509CertImpl certImpl = (X509CertImpl) cert;
        AuthorityInfoAccessExtension aia = certImpl.getAuthorityInfoAccessExtension();
        if (aia == null) {
            return null;
        }

        List<AccessDescription> descriptions = aia.getAccessDescriptions();
        for (AccessDescription description : descriptions) {
            if (description.getAccessMethod().equals(
                            (Object) AccessDescription.Ad_OCSP_Id)) {
                GeneralName generalName = description.getAccessLocation();
                if (generalName.getType() == GeneralNameInterface.NAME_URI) {
                    URIName uri = (URIName) generalName.getName();
                    return factory.createTuple(new String[]{uri.getURI().toString()});
                }
            }
        }
        return null;
    }

    public enum LoadCertError {
        NO_ERROR,
        NO_CERT_DATA,
        EMPTY_CERT,
        BEGIN_CERTIFICATE_WITHOUT_END,
        SOME_BAD_BASE64_DECODE,
        BAD_BASE64_DECODE
    }

    @TruffleBoundary
    public static LoadCertError loadVerifyLocations(TruffleFile file, TruffleFile path, List<Object> certificates) throws IOException, CertificateException, CRLException {
        Collection<TruffleFile> files = new ArrayList<>();
        if (file != null) {
            files.add(file);
        }
        if (path != null && path.isDirectory()) {
            // TODO: see SSL_CTX_load_verify_locations
            // if capath is a directory, cpython loads certificates on demand
            files.addAll(path.list());
        }
        List<Object> l = new ArrayList<>();
        for (TruffleFile f : files) {
            try (BufferedReader r = f.newBufferedReader()) {
                LoadCertError result = getCertificates(r, l);
                if (result != LoadCertError.NO_ERROR) {
                    return result;
                }
            }
        }
        certificates.addAll(l);
        return LoadCertError.NO_ERROR;
    }

    @TruffleBoundary
    public static LoadCertError getCertificates(BufferedReader r, List<Object> result) throws IOException, CertificateException, CRLException {
        return getCertificates(r, result, false);
    }

    @TruffleBoundary
    public static LoadCertError getCertificates(BufferedReader r, List<Object> result, boolean onlyCertificates) throws IOException, CertificateException, CRLException {
        boolean sawBegin = false;
        boolean sawBeginCrl = false;
        StringBuilder certBuilder = new StringBuilder(2000);
        StringBuilder crlBuilder = new StringBuilder(2000);
        List<String> data = new ArrayList<>();
        List<String> dataCrl = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            if (sawBegin || sawBeginCrl) {
                if (line.contains(BEGIN_CERTIFICATE) || line.contains(BEGIN_X509_CRL)) {
                    break;
                }
                if (line.contains(END_CERTIFICATE)) {
                    sawBegin = false;
                    data.add(certBuilder.toString());
                } else if (line.contains(END_X509_CRL)) {
                    sawBeginCrl = false;
                    dataCrl.add(crlBuilder.toString());
                } else if (sawBegin) {
                    certBuilder.append(line);
                } else if (sawBeginCrl) {
                    crlBuilder.append(line);
                }
            } else if (line.contains(BEGIN_CERTIFICATE)) {
                sawBegin = true;
                certBuilder.setLength(0);
            } else if (line.contains(BEGIN_X509_CRL)) {
                sawBeginCrl = true;
                crlBuilder.setLength(0);
            }
        }
        if (sawBegin || sawBeginCrl) {
            return LoadCertError.BEGIN_CERTIFICATE_WITHOUT_END;
        }
        Base64.Decoder decoder = Base64.getDecoder();
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<Object> l = new ArrayList<>();
        LoadCertError res = add(data, l, decoder, factory::generateCertificate);
        if (res != LoadCertError.NO_ERROR) {
            return res;
        }
        if (!onlyCertificates) {
            res = add(dataCrl, l, decoder, factory::generateCRL);
            if (res != LoadCertError.NO_ERROR) {
                return res;
            }
        }
        if (l.isEmpty()) {
            return LoadCertError.NO_CERT_DATA;
        }
        result.addAll(l);
        return res;
    }

    @FunctionalInterface
    private interface F {
        Object generate(ByteArrayInputStream t) throws CertificateException, CRLException;
    }

    private static LoadCertError add(List<String> data, List<Object> result, Base64.Decoder decoder, F f) throws CertificateException, CRLException {
        for (String s : data) {
            if (!s.isEmpty()) {
                byte[] der;
                try {
                    der = decoder.decode(s);
                } catch (IllegalArgumentException e) {
                    if (result.isEmpty()) {
                        return LoadCertError.BAD_BASE64_DECODE;
                    } else {
                        return LoadCertError.SOME_BAD_BASE64_DECODE;
                    }
                }
                result.add(f.generate(new ByteArrayInputStream(der)));
            } else {
                return LoadCertError.EMPTY_CERT;
            }
        }
        return LoadCertError.NO_ERROR;
    }

    /**
     * Returns the first private key found
     */
    @TruffleBoundary
    static byte[] getEncodedPrivateKey(Node node, BufferedReader r) throws IOException {
        boolean begin = false;
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            if (!begin && line.contains(BEGIN_PRIVATE_KEY)) {
                begin = true;
            } else if (begin) {
                if (line.contains(END_PRIVATE_KEY)) {
                    begin = false;
                    // get first private key found
                    break;
                }
                sb.append(line);
            }
        }
        if (begin || sb.length() == 0) {
            throw PRaiseSSLErrorNode.raiseUncached(node, SSLErrorCode.ERROR_SSL_PEM_LIB, ErrorMessages.SSL_PEM_LIB);
        }

        try {
            return Base64.getDecoder().decode(sb.toString());
        } catch (IllegalArgumentException e) {
            throw PRaiseSSLErrorNode.raiseUncached(node, SSLErrorCode.ERROR_SSL_PEM_LIB, ErrorMessages.SSL_PEM_LIB);
        }
    }

    @TruffleBoundary
    static PrivateKey createPrivateKey(Node node, byte[] bytes, X509Certificate cert) throws NoSuchAlgorithmException, InvalidKeySpecException {
        PublicKey publicKey = cert.getPublicKey();
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
        KeyFactory factory = KeyFactory.getInstance(publicKey.getAlgorithm());
        PrivateKey privateKey = factory.generatePrivate(spec);
        checkPrivateKey(node, privateKey, publicKey);
        return privateKey;
    }

    private static void checkPrivateKey(Node node, PrivateKey privateKey, PublicKey publicKey) {
        if (privateKey instanceof RSAPrivateKey) {
            RSAPrivateKey privKey = (RSAPrivateKey) privateKey;
            RSAPublicKey pubKey = (RSAPublicKey) publicKey;
            if (!privKey.getModulus().equals(pubKey.getModulus())) {
                throw PRaiseSSLErrorNode.raiseUncached(node, SSLErrorCode.ERROR_KEY_VALUES_MISMATCH, ErrorMessages.KEY_VALUES_MISMATCH);
            }
        } else if (privateKey instanceof DSAPrivateKey) {
            DSAPrivateKey privKey = (DSAPrivateKey) privateKey;
            DSAPublicKey pubKey = (DSAPublicKey) publicKey;
            if (!privKey.getParams().equals(pubKey.getParams())) {
                throw PRaiseSSLErrorNode.raiseUncached(node, SSLErrorCode.ERROR_KEY_VALUES_MISMATCH, ErrorMessages.KEY_VALUES_MISMATCH);
            }
        } else if (privateKey instanceof ECPrivateKey) {
            ECPrivateKey privKey = (ECPrivateKey) privateKey;
            ECPublicKey pubKey = (ECPublicKey) publicKey;
            if (!privKey.getParams().equals(pubKey.getParams())) {
                throw PRaiseSSLErrorNode.raiseUncached(node, SSLErrorCode.ERROR_KEY_VALUES_MISMATCH, ErrorMessages.KEY_VALUES_MISMATCH);
            }
        } else if (privateKey instanceof DHPrivateKey) {
            DHPrivateKey privKey = (DHPrivateKey) privateKey;
            DHPublicKey pubKey = (DHPublicKey) publicKey;
            if (!privKey.getParams().equals(pubKey.getParams())) {
                throw PRaiseSSLErrorNode.raiseUncached(node, SSLErrorCode.ERROR_KEY_VALUES_MISMATCH, ErrorMessages.KEY_VALUES_MISMATCH);
            }
        }
    }

    @TruffleBoundary
    static DHParameterSpec getDHParameters(Node node, File file) throws IOException, NoSuchAlgorithmException, InvalidParameterSpecException {
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            boolean begin = false;
            StringBuilder sb = new StringBuilder();
            while ((line = r.readLine()) != null) {
                if (line.contains(BEGIN_DH_PARAMETERS)) {
                    begin = true;
                } else if (begin) {
                    if (line.contains(END_DH_PARAMETERS)) {
                        break;
                    }
                    sb.append(line.trim());
                }
            }
            if (!begin) {
                throw PRaiseSSLErrorNode.raiseUncached(node, SSLErrorCode.ERROR_NO_START_LINE, ErrorMessages.SSL_PEM_NO_START_LINE);
            }
            AlgorithmParameters ap = AlgorithmParameters.getInstance("DH");
            ap.init(Base64.getDecoder().decode(sb.toString()));
            return ap.getParameterSpec(DHParameterSpec.class);
        }
    }

    @TruffleBoundary
    static Collection<?> generateCertificates(byte[] bytes) throws CertificateException {
        return CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));
    }

    @TruffleBoundary
    static String getAlias(X509Certificate cert) throws NoSuchAlgorithmException, CertificateEncodingException {
        // TODO what to use for alias
        return md5(cert.getEncoded());
    }

    @TruffleBoundary
    static String getAlias(PrivateKey pk) throws NoSuchAlgorithmException {
        // TODO what to use for alias
        return md5(pk.getEncoded());
    }

    @TruffleBoundary
    private static String md5(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("md5").digest(bytes);
        return new BigInteger(1, digest).toString(16);
    }
}
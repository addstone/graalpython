# Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# The Universal Permissive License (UPL), Version 1.0
#
# Subject to the condition set forth below, permission is hereby granted to any
# person obtaining a copy of this software, associated documentation and/or
# data (collectively the "Software"), free of charge and under any and all
# copyright rights in the Software, and any and all patent rights owned or
# freely licensable by each licensor hereunder covering either (i) the
# unmodified Software as contributed to or provided by such licensor, or (ii)
# the Larger Works (as defined below), to deal in both
#
# (a) the Software, and
#
# (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
# one is included with the Software each a "Larger Work" to which the Software
# is contributed by such licensors),
#
# without restriction, including without limitation the rights to copy, create
# derivative works of, display, perform, and distribute the Software and make,
# use, sell, offer for sale, import, export, have made, and have sold the
# Software and the Larger Work(s), and to sublicense the foregoing rights on
# either these or other terms.
#
# This license is subject to the following condition:
#
# The above copyright notice and either this complete permission notice or at a
# minimum a reference to the UPL must be included in all copies or substantial
# portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

import unittest
import ssl
import os
import json


def data_file(name):
    return os.path.join(os.path.dirname(__file__), "ssldata", name)


class StringWrapper(str):
    pass


class CertTests(unittest.TestCase):

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)

    def check_load_cert_chain_error(self, certfile, keyfile=None, errno=-1, strerror=None, err=ssl.SSLError):
        try:
            if(keyfile is None):
                self.ctx.load_cert_chain(data_file(certfile))
            else:
                self.ctx.load_cert_chain(data_file(certfile), data_file(keyfile))
        except err as e:
            self.assertEqual(e.errno, errno)
            self.assertIn(strerror, e.strerror)
            self.assertIsInstance(type(e), type(err))
        else:
            assert False

    def check_load_verify_locations_error(self, cafile, capath=None, errno=-1, strerror=None, err=ssl.SSLError):
        try:
            if(capath is None):
                self.ctx.load_verify_locations(data_file(cafile))
            else:
                self.ctx.load_verify_locations(data_file(cafile), data_file(capath))
        except err as e:
            self.assertEqual(e.errno, errno)
            self.assertIn(strerror, e.strerror)
            self.assertIsInstance(type(e), type(err))
        else:
            assert False

    def test_load_cert_chain(self):
        self.ctx.load_cert_chain(data_file("cert_rsa.pem"), keyfile=data_file("empty_pk_at_end.pem"))
        self.ctx.load_cert_chain(StringWrapper(data_file("cert_rsa.pem")), keyfile=StringWrapper(data_file("empty_pk_at_end.pem")))

        with self.assertRaisesRegex(TypeError, "certfile should be a valid filesystem path"):
            self.ctx.load_cert_chain(1)
        with self.assertRaisesRegex(TypeError, "certfile should be a valid filesystem path"):
            self.ctx.load_cert_chain(1, 1)
        with self.assertRaisesRegex(TypeError, "keyfile should be a valid filesystem path"):
            self.ctx.load_cert_chain(data_file("empty.pem"), 1)

        self.check_load_cert_chain_error(certfile="does_not_exit", errno=2, strerror="No such file or directory", err=FileNotFoundError)
        self.check_load_cert_chain_error(certfile="does_not_exit", keyfile="does_not_exist", errno=2, strerror="No such file or directory", err=FileNotFoundError)

        self.check_load_cert_chain_error(certfile="empty.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="empty_cert.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="empty_cert_at_begin.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="empty_cert_at_end.pem", errno=0, strerror="unknown error")

        self.check_load_cert_chain_error(certfile="broken_cert_double_begin.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="broken_cert_only_begin.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="broken_cert_no_end.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="broken_cert_data.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="broken_cert_data_at_begin.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="broken_cert_data_at_end.pem", errno=100, strerror="[PEM: BAD_BASE64_DECODE] bad base64 decode")

        self.check_load_cert_chain_error(certfile="cert_rsa.pem", keyfile="empty.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="cert_rsa.pem", keyfile="empty_pk.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="cert_rsa.pem", keyfile="empty_pk_at_begin.pem", errno=9, strerror="[SSL] PEM lib")

        self.check_load_cert_chain_error(certfile="cert_rsa.pem", keyfile="broken_pk_data.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="cert_rsa.pem", keyfile="broken_pk_only_begin.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="cert_rsa.pem", keyfile="broken_pk_double_begin.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="cert_rsa.pem", keyfile="broken_pk_no_begin.pem", errno=9, strerror="[SSL] PEM lib")
        self.check_load_cert_chain_error(certfile="cert_rsa.pem", keyfile="broken_pk_no_end.pem", errno=9, strerror="[SSL] PEM lib")

        self.check_load_cert_chain_error(certfile="cert_rsa2.pem", keyfile="pk_rsa.pem", errno=116, strerror="[X509: KEY_VALUES_MISMATCH] key values mismatch")        

    def test_load_verify_locations(self):
        self.ctx.load_verify_locations(data_file("cert_rsa.pem"))
        self.ctx.load_verify_locations(capath=data_file("cert_rsa.pem"))
        self.ctx.load_verify_locations(data_file("cert_rsa.pem"), 'does_not_exit')
        self.ctx.load_verify_locations(StringWrapper(data_file("cert_rsa.pem")), )
        self.ctx.load_verify_locations(capath=StringWrapper(data_file("cert_rsa.pem")))

        with self.assertRaisesRegex(TypeError, "cafile should be a valid filesystem path"):
            self.ctx.load_verify_locations(1)
        with self.assertRaisesRegex(TypeError, "cafile should be a valid filesystem path"):
            self.ctx.load_verify_locations(1, 1)
        with self.assertRaisesRegex(TypeError, "capath should be a valid filesystem path"):
            self.ctx.load_verify_locations(capath=1)
        with self.assertRaisesRegex(TypeError, "capath should be a valid filesystem path"):
            self.ctx.load_verify_locations('a', capath=1)
        with self.assertRaisesRegex(TypeError, "cafile should be a valid filesystem path"):
            self.ctx.load_verify_locations(1, capath='a')

        self.check_load_verify_locations_error(cafile="does_not_exit", errno=2, strerror="No such file or directory", err=FileNotFoundError)
        self.check_load_verify_locations_error(cafile="does_not_exit", capath='does_not_exit', errno=2, strerror="No such file or directory", err=FileNotFoundError)

        self.check_load_verify_locations_error(cafile="empty.pem", errno=136, strerror="[X509: NO_CERTIFICATE_OR_CRL_FOUND] no certificate or crl found")
        self.check_load_verify_locations_error(cafile="empty_cert.pem", errno=9, strerror="[X509] PEM lib")
        self.check_load_verify_locations_error(cafile="empty_cert_at_begin.pem", errno=9, strerror="[X509] PEM lib")
        self.check_load_verify_locations_error(cafile="empty_cert_at_end.pem", errno=9, strerror="[X509] PEM lib")

        self.check_load_verify_locations_error(cafile="broken_cert_double_begin.pem", errno=9, strerror="[X509] PEM lib")
        self.check_load_verify_locations_error(cafile="broken_cert_only_begin.pem", errno=9, strerror="[X509] PEM lib")
        self.check_load_verify_locations_error(cafile="broken_cert_no_end.pem", errno=9, strerror="[X509] PEM lib")
        self.check_load_verify_locations_error(cafile="broken_cert_data.pem", errno=9, strerror="[X509] PEM lib")
        self.check_load_verify_locations_error(cafile="broken_cert_data_at_begin.pem", errno=9, strerror="[X509] PEM lib")
        self.check_load_verify_locations_error(cafile="broken_cert_data_at_end.pem", errno=9, strerror="[X509] PEM lib")
        
        # TODO test cadata
        # TODO load_DH_params

    def test_load_default_verify_paths(self):
        env = os.environ
        certFile = env["SSL_CERT_FILE"] if "SSL_CERT_FILE" in env else None
        certDir = env["SSL_CERT_DIR"] if "SSL_CERT_DIR" in env else None 
        try:
            env["SSL_CERT_DIR"] = "does_not_exit"
            env["SSL_CERT_FILE"] = "does_not_exit"
            self.ctx.load_default_certs()
            env["SSL_CERT_DIR"] = data_file("empty.pem")
            env["SSL_CERT_FILE"] = data_file("empty.pem")
            self.ctx.load_default_certs()
            env["SSL_CERT_DIR"] = data_file("cert_rsa.pem")
            env["SSL_CERT_FILE"] = data_file("cert_rsa.pem")
            self.ctx.load_default_certs()
        except Exception:
            # load_default_certs reports no errors
            assert False
        finally:    
            if certFile is not None:
                os.environ["SSL_CERT_FILE"] = certFile
            if certDir is not None:        
                os.environ["SSL_CERT_DIR"] = certDir

def get_cipher_list(cipher_string):
    context = ssl.SSLContext()
    context.set_ciphers(cipher_string)
    return context.get_ciphers()


class CipherTests(unittest.TestCase):

    def test_set_ciphers(self):
        with open(data_file('expected_ciphers.json')) as fo:
            data = json.load(fo)
        for cipher_string, expected_output in data.items():
            try:
                output = get_cipher_list(cipher_string)
            except ssl.SSLError:
                self.fail(f"No cipher suites selected for list: {cipher_string}")
            self.assertGreater(len(output), 0)
            # JDK has just a subset of ciphers, test that the remaining ones are a subset of CPython's in the right order
            unexpected = set([x['name'] for x in output]) - set([x['name'] for x in expected_output])
            self.assertEqual(unexpected, set(), f"Cipher list: {cipher_string}\nUnexpected names: {unexpected}")
            matches = 0
            for entry in expected_output:
                if output[matches] == entry:
                    matches += 1
                    if matches == len(output):
                        break
            self.assertEqual(matches, len(output))

    def test_error(self):
        with self.assertRaisesRegex(ssl.SSLError, "No cipher can be selected"):
            get_cipher_list("ALL:!ALL:ADH")
        with self.assertRaisesRegex(ssl.SSLError, "No cipher can be selected"):
            get_cipher_list("ALL:@XXX")

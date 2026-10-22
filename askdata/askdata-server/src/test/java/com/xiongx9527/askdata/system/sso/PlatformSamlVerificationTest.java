package com.xiongx9527.askdata.system.sso;

import com.xiongx9527.askdata.system.authentication.Saml2SsoClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformSamlVerificationTest {

    private Map<String, Object> configWithCert(String cert, boolean wantSigned) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("certificate", cert);
        c.put("wantAssertionsSigned", String.valueOf(wantSigned));
        c.put("spEntityId", null);
        return c;
    }

    private Saml2SsoClient client() {
        return new Saml2SsoClient(new ObjectMapper());
    }

    @Test
    void signedResponseWithMatchingCertIsAccepted() throws Exception {
        KeyPair pair = IdpTestCrypto.rsaKeyPair();
        String signed = IdpTestCrypto.signedSamlResponse(pair.getPrivate(), "alice", "a@x.com");
        String b64 = IdpTestCrypto.base64(signed);
        String cert = IdpTestCrypto.publicKeyBase64(pair);
        Map<String, Object> cfg = configWithCert(cert, false);
        Map<String, Object> user = assertDoesNotThrow(() -> client().parseResponse(b64, cfg));
        assertEquals("alice", user.get("platform_uid"));
    }

    @Test
    void signedResponseWithWrongCertIsRejected() throws Exception {
        KeyPair pair = IdpTestCrypto.rsaKeyPair();
        KeyPair other = IdpTestCrypto.rsaKeyPair();
        String signed = IdpTestCrypto.signedSamlResponse(pair.getPrivate(), "alice", "a@x.com");
        String b64 = IdpTestCrypto.base64(signed);
        String cert = IdpTestCrypto.publicKeyBase64(other);
        Map<String, Object> cfg = configWithCert(cert, false);
        assertThrows(IllegalStateException.class, () -> client().parseResponse(b64, cfg));
    }

    @Test
    void unsignedResponseWithCertConfiguredIsRejected() throws Exception {
        KeyPair pair = IdpTestCrypto.rsaKeyPair();
        String unsigned = IdpTestCrypto.unsignedSamlResponse("alice", "a@x.com");
        String b64 = IdpTestCrypto.base64(unsigned);
        String cert = IdpTestCrypto.publicKeyBase64(pair);
        Map<String, Object> cfg = configWithCert(cert, false);
        assertThrows(IllegalStateException.class, () -> client().parseResponse(b64, cfg));
    }

    @Test
    void unsignedResponseWithoutCertKeepsBackwardCompatible() throws Exception {
        String unsigned = IdpTestCrypto.unsignedSamlResponse("alice", "a@x.com");
        String b64 = IdpTestCrypto.base64(unsigned);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("wantAssertionsSigned", "false");
        Map<String, Object> user = assertDoesNotThrow(() -> client().parseResponse(b64, cfg));
        assertEquals("alice", user.get("platform_uid"));
    }
}

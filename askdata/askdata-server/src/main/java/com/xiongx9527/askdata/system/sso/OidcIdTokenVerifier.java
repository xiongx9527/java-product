package com.xiongx9527.askdata.system.sso;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * OIDC id_token 校验（A7）：签名 + iss/aud/exp/nonce。
 *
 * <p>只信任 userinfo 响应等于「拿到 access_token 就认人」；这里要求 id_token 必须通过
 * RS256 签名校验（公钥来自配置的 JWKS 或 IdP 证书），并核对 audience、有效期与 nonce，
 * 避免被伪造的 token 响应冒充身份。</p>
 */
final class OidcIdTokenVerifier {

    private OidcIdTokenVerifier() {
    }

    /**
     * 校验 id_token 并返回其 claims。
     *
     * @param idToken       授权码换来的 id_token
     * @param cfg           IdP 配置（extra 支持 jwks-url / id-token-cert / issuer）
     * @param expectedNonce 期望的 nonce（即本次授权请求的 state），为空时跳过
     * @param http          用于拉取 JWKS
     */
    static Map<String, Object> verify(String idToken, ExternalIdpConfig cfg, String expectedNonce,
                                      IdpHttpClient http) {
        if (idToken == null || idToken.trim().isEmpty()) {
            throw new IllegalStateException("oidc token response has no id_token");
        }
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw new IllegalStateException("oidc id_token is not a compact JWS");
        }
        Map<String, Object> header = SsoJson.parseObject(decode(parts[0]));
        Map<String, Object> claims = SsoJson.parseObject(decode(parts[1]));
        String alg = SsoJson.str(header, "alg");
        if (!"RS256".equals(alg)) {
            throw new IllegalStateException("oidc id_token alg is not supported: " + alg);
        }
        PublicKey key = publicKey(cfg, SsoJson.str(header, "kid"), http);
        if (!verifySignature(key, parts[0] + "." + parts[1], parts[2])) {
            throw new IllegalStateException("oidc id_token signature is invalid");
        }
        assertIssuer(cfg, SsoJson.str(claims, "iss"));
        assertAudience(cfg, claims.get("aud"));
        assertNotExpired(claims.get("exp"));
        if (expectedNonce != null && !expectedNonce.isEmpty()
                && !expectedNonce.equals(SsoJson.str(claims, "nonce"))) {
            throw new IllegalStateException("oidc id_token nonce does not match the authorization request");
        }
        return claims;
    }

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    private static PublicKey publicKey(ExternalIdpConfig cfg, String kid, IdpHttpClient http) {
        String jwksUrl = cfg.extra("jwks-url");
        if (jwksUrl != null && !jwksUrl.isEmpty()) {
            IdpHttpClient.Response response = http.get(jwksUrl, Map.of());
            if (!response.ok()) {
                throw new IllegalStateException("oidc jwks fetch failed: " + response.status);
            }
            Object rawKeys = SsoJson.parseObject(response.body).get("keys");
            if (!(rawKeys instanceof List)) {
                throw new IllegalStateException("oidc jwks response has no keys array");
            }
            for (Object item : (List<?>) rawKeys) {
                if (!(item instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> jwk = (Map<String, Object>) item;
                if (kid != null && !kid.equals(SsoJson.str(jwk, "kid"))) {
                    continue;
                }
                String modulus = SsoJson.str(jwk, "n");
                String exponent = SsoJson.str(jwk, "e");
                if (modulus == null || exponent == null) {
                    continue;
                }
                try {
                    BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(modulus));
                    BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(exponent));
                    return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
                } catch (Exception ex) {
                    throw new IllegalStateException("oidc jwks key is invalid: " + ex.getMessage(), ex);
                }
            }
            throw new IllegalStateException("oidc jwks has no usable key for kid " + kid);
        }
        String certText = cfg.extra("id-token-cert");
        if (certText == null || certText.isEmpty()) {
            throw new IllegalStateException("oidc id_token verification requires jwks-url or id-token-cert");
        }
        try {
            String base64 = certText.replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            return certificate.getPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException("oidc id-token-cert is invalid: " + e.getMessage(), e);
        }
    }

    private static boolean verifySignature(PublicKey key, String signingInput, String signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getUrlDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    private static void assertIssuer(ExternalIdpConfig cfg, String issuer) {
        String expected = cfg.extra("issuer");
        if (expected == null || expected.isEmpty()) {
            return;
        }
        if (!expected.equals(issuer)) {
            throw new IllegalStateException("oidc id_token issuer mismatch: " + issuer);
        }
    }

    private static void assertAudience(ExternalIdpConfig cfg, Object audience) {
        String clientId = cfg.getClientId();
        if (clientId == null || clientId.isEmpty() || audience == null) {
            return;
        }
        if (audience instanceof List) {
            for (Object item : (List<?>) audience) {
                if (clientId.equals(String.valueOf(item))) {
                    return;
                }
            }
            throw new IllegalStateException("oidc id_token audience does not contain the client id");
        }
        if (!clientId.equals(String.valueOf(audience))) {
            throw new IllegalStateException("oidc id_token audience does not match the client id");
        }
    }

    private static void assertNotExpired(Object exp) {
        if (exp == null) {
            throw new IllegalStateException("oidc id_token has no exp claim");
        }
        long expiresAt = ((Number) exp).longValue() * 1000L;
        if (System.currentTimeMillis() > expiresAt) {
            throw new IllegalStateException("oidc id_token expired");
        }
    }
}


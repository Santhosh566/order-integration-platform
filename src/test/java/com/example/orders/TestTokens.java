package com.example.orders;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Tokens shaped like the ones Keycloak issues for the order-platform realm, signed with a test key
 * the test application trusts. KeycloakIT checks that real Keycloak tokens look the same.
 */
final class TestTokens {

    static final String ISSUER = "http://keycloak.test/realms/order-platform";

    // Keycloak user ids of the demo users, linked in db/demo and ops/keycloak/order-platform-realm.json
    static final String ADMIN = "22222222-0000-4000-8000-000000000001";
    static final String ALICE = "11111111-0000-4000-8000-000000000100";
    static final String BOB = "11111111-0000-4000-8000-000000000101";
    static final String DRIVER_ONE = "33333333-0000-4000-8000-000000000001";
    static final String DRIVER_TWO = "33333333-0000-4000-8000-000000000002";
    static final String WAREHOUSE = "44444444-0000-4000-8000-000000000001";

    private static final RSAPrivateKey KEY = loadKey();

    private TestTokens() {}

    static String issue(String subject, String... roles) {
        return issue(subject, null, false, roles);
    }

    static String issue(String subject, String email, boolean emailVerified, String... roles) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject(subject)
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                    .claim("realm_access", Map.of("roles", List.of(roles)))
                    .claim("name", "Test User")
                    .claim("email_verified", emailVerified);
            if (email != null) {
                claims.claim("email", email);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAPrivateKey loadKey() {
        try (InputStream in = TestTokens.class.getResourceAsStream("/jwt/test-private.pem")) {
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                    .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the test signing key", e);
        }
    }
}

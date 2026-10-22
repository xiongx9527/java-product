package com.xiongx9527.askdata.system.sso;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A7：授权请求 state 一次性、限时、按协议校验（防回调 CSRF）。 */
class IdpStateStoreTest {

    @Test
    void issuedStateIsAcceptedOnce() {
        IdpStateStore store = new IdpStateStore();
        String state = store.issue(IdpType.OIDC);
        store.consume(IdpType.OIDC, state);
        // 二次使用必须被拒绝
        assertThrows(IllegalStateException.class, () -> store.consume(IdpType.OIDC, state));
    }

    @Test
    void missingStateIsRejected() {
        IdpStateStore store = new IdpStateStore();
        assertThrows(IllegalStateException.class, () -> store.consume(IdpType.OIDC, null));
        assertThrows(IllegalStateException.class, () -> store.consume(IdpType.OIDC, ""));
    }

    @Test
    void unknownStateIsRejected() {
        IdpStateStore store = new IdpStateStore();
        assertThrows(IllegalStateException.class, () -> store.consume(IdpType.OIDC, "not-issued"));
    }

    @Test
    void stateIsBoundToIdpType() {
        IdpStateStore store = new IdpStateStore();
        String state = store.issue(IdpType.SAML2);
        assertThrows(IllegalStateException.class, () -> store.consume(IdpType.OIDC, state));
    }

    @Test
    void expiredStateIsRejected() throws Exception {
        IdpStateStore store = new IdpStateStore(20L);
        String state = store.issue(IdpType.CAS);
        Thread.sleep(50L);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> store.consume(IdpType.CAS, state));
        assertTrue(error.getMessage().contains("expired"), error.getMessage());
    }

    @Test
    void issuedStatesAreUnpredictable() {
        IdpStateStore store = new IdpStateStore();
        String first = store.issue(IdpType.OIDC);
        String second = store.issue(IdpType.OIDC);
        assertNotEquals(first, second);
        assertEquals(48, first.length());
    }
}


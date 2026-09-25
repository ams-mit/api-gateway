package com.projecta.apigateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ResourceLoader;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class KeyResolverServiceTest {

    private KeyResolverService keyResolverService;
    private KeyPair testKeyPair;

    @BeforeEach
    void setUp() {
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        keyResolverService = new KeyResolverService(resourceLoader);
        testKeyPair = TestKeyUtils.generateRsaKeyPair();
    }

    @Test
    void parsePublicKey_successfullyParsesPemString() {
        String pem = TestKeyUtils.toPemPublicKey(testKeyPair);
        PublicKey publicKey = keyResolverService.parsePublicKey(pem);

        assertNotNull(publicKey);
        assertEquals("RSA", publicKey.getAlgorithm());
        assertArrayEquals(testKeyPair.getPublic().getEncoded(), publicKey.getEncoded());
    }

    @Test
    void parsePrivateKey_successfullyParsesPemString() {
        String pem = TestKeyUtils.toPemPrivateKey(testKeyPair);
        PrivateKey privateKey = keyResolverService.parsePrivateKey(pem);

        assertNotNull(privateKey);
        assertEquals("RSA", privateKey.getAlgorithm());
        assertArrayEquals(testKeyPair.getPrivate().getEncoded(), privateKey.getEncoded());
    }

    @Test
    void parsePublicKey_throwsIllegalArgumentException_whenPemIsInvalid() {
        String invalidPem = "-----BEGIN PUBLIC KEY-----\nINVALID_BASE64_DATA\n-----END PUBLIC KEY-----";
        assertThrows(IllegalArgumentException.class, () -> keyResolverService.parsePublicKey(invalidPem));
    }
}

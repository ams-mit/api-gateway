package com.projecta.apigateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.*;

class KeyResolverServiceTest {

    private KeyResolverService keyResolverService;
    private KeyPair testKeyPair;

    @BeforeEach
    void setUp() {
        keyResolverService = new KeyResolverService(new DefaultResourceLoader());
        testKeyPair = TestKeyUtils.generateRsaKeyPair();
    }

    @Test
    void parsePublicKey_successfullyParsesPemString() {
        String pem = TestKeyUtils.toPemPublicKey(testKeyPair);
        PublicKey publicKey = keyResolverService.parsePublicKey(pem);

        assertEquals("RSA", publicKey.getAlgorithm());
        assertArrayEquals(testKeyPair.getPublic().getEncoded(), publicKey.getEncoded());
    }

    @Test
    void parsePrivateKey_successfullyParsesPemString() {
        String pem = TestKeyUtils.toPemPrivateKey(testKeyPair);
        PrivateKey privateKey = keyResolverService.parsePrivateKey(pem);

        assertEquals("RSA", privateKey.getAlgorithm());
        assertArrayEquals(testKeyPair.getPrivate().getEncoded(), privateKey.getEncoded());
    }

    @Test
    void parsePublicKey_throwsIllegalArgumentException_whenPemIsInvalid() {
        String invalidPem = "-----BEGIN PUBLIC KEY-----\nINVALID_BASE64_DATA\n-----END PUBLIC KEY-----";
        assertThrows(IllegalArgumentException.class, () -> keyResolverService.parsePublicKey(invalidPem));
    }

    @Test
    void resolvePublicKey_acceptsInlinePemWithEscapedNewlines() {
        String envStylePem = TestKeyUtils.toPemPublicKey(testKeyPair).replace("\n", "\\n");

        PublicKey publicKey = keyResolverService.resolvePublicKey(envStylePem);

        assertArrayEquals(testKeyPair.getPublic().getEncoded(), publicKey.getEncoded());
    }

    @Test
    void resolvePrivateKey_readsFileLocation(@TempDir Path dir) throws Exception {
        Path keyFile = dir.resolve("gateway_private.pem");
        Files.writeString(keyFile, TestKeyUtils.toPemPrivateKey(testKeyPair));

        PrivateKey privateKey = keyResolverService.resolvePrivateKey(keyFile.toUri().toString());

        assertArrayEquals(testKeyPair.getPrivate().getEncoded(), privateKey.getEncoded());
    }

    @Test
    void resolvePublicKey_failsForMissingLocationOrBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> keyResolverService.resolvePublicKey("classpath:keys/missing.pem"));
        assertThrows(IllegalArgumentException.class, () -> keyResolverService.resolvePublicKey(" "));
    }
}

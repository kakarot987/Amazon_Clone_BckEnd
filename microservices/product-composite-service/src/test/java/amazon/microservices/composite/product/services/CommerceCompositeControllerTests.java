package amazon.microservices.composite.product.services;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class CommerceCompositeControllerTests {
    private Jwt token(String issuer, String subject, String clientId) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .subject(subject)
                .claim("client_id", clientId)
                .build();
    }

    @Test
    void cartOwnerKeyIsStableAndIsolated() {
        Jwt alice = token("https://issuer.example", "alice", "writer");
        Jwt bob = token("https://issuer.example", "bob", "writer");
        Jwt otherIssuer = token("https://other.example", "alice", "writer");
        String firstKey = CommerceCompositeController.ownerKey(alice);
        assertEquals(64, firstKey.length());
        assertEquals(firstKey, CommerceCompositeController.ownerKey(alice));
        assertNotEquals(firstKey, CommerceCompositeController.ownerKey(bob));
        assertNotEquals(firstKey, CommerceCompositeController.ownerKey(otherIssuer));
    }

    @Test
    void applicationOnlyTokensCannotUsePersonalCarts() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> CommerceCompositeController.ownerKey(
                        token("https://issuer.example", "writer", "writer")));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> CommerceCompositeController.ownerKey(null)).getStatusCode());
    }
}

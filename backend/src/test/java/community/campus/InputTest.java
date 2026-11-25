package community.campus;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class InputTest {
    @Test
    void searchTreatsWildcardsAsLiteralCharacters() {
        assertEquals("%100\\%\\_%", Input.search(" 100%_ "));
    }

    @Test
    void mediaRequiresAnHttpsHostWithoutCredentials() {
        assertEquals("https://example.test/photo.jpg", Input.media("https://example.test/photo.jpg"));
        assertNull(Input.media(""));
        assertThrows(ResponseStatusException.class, () -> Input.media("javascript:alert(1)"));
        assertThrows(ResponseStatusException.class, () -> Input.media("https://x@example.test/photo.jpg"));
    }
}

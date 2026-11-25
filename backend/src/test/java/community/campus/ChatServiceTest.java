package community.campus;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ChatServiceTest {
    @Test
    void outsiderCannotReadOrSendMessages() {
        var store = mock(Store.class);
        var inbox = mock(Inbox.class);
        var live = mock(Live.class);
        var chats = new ChatService(store, inbox, live, mock(MediaService.class));
        UUID conversation = UUID.randomUUID();
        assertThrows(ResponseStatusException.class, () -> chats.history("outsider", conversation, "", 0));
        assertThrows(ResponseStatusException.class, () -> chats.send("outsider", conversation, new Chats.Message("private", null)));
        verify(store, never()).one(anyString(), any());
        verifyNoInteractions(inbox, live);
    }
}

package community.campus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chats")
class Chats {
    record Create(@Size(max=80) String title, @NotEmpty @Size(max=20) List<@NotNull @Pattern(regexp="[a-z0-9_]{3,30}") String> people) {}
    record Message(@NotBlank @Size(max=4000) String body, @Size(max=500) String attachmentUrl) {}
    private final ChatService chats;
    Chats(ChatService chats) { this.chats = chats; }

    @GetMapping
    List<Map<String,Object>> list(Principal actor, @RequestParam(defaultValue="0") int page) {
        return chats.list(actor.getName(), page);
    }

    @PostMapping
    UUID create(Principal actor, @Valid @RequestBody Create input) { return chats.create(actor.getName(), input); }

    @GetMapping("/{id}/members")
    List<Map<String,Object>> members(Principal actor, @PathVariable UUID id) { return chats.members(actor.getName(), id); }

    @GetMapping("/{id}/messages")
    List<Map<String,Object>> history(Principal actor, @PathVariable UUID id, @RequestParam(defaultValue="") @Size(max=100) String q,
            @RequestParam(defaultValue="0") int page) { return chats.history(actor.getName(), id, q, page); }

    @PostMapping("/{id}/messages")
    Map<String,Object> send(Principal actor, @PathVariable UUID id, @Valid @RequestBody Message input) {
        return chats.send(actor.getName(), id, input);
    }

    @PutMapping("/{id}/messages/{messageId}/receipt")
    void receipt(Principal actor, @PathVariable UUID id, @PathVariable long messageId, @RequestParam boolean read) {
        chats.receipt(actor.getName(), id, messageId, read);
    }

    @PostMapping("/{id}/typing")
    void typing(Principal actor, @PathVariable UUID id) { chats.typing(actor.getName(), id); }

}

@Service
class ChatService {
    private final Store store;
    private final Inbox inbox;
    private final Live live;
    private final MediaService media;
    ChatService(Store store, Inbox inbox, Live live, MediaService media) {
        this.store = store;
        this.inbox = inbox;
        this.live = live;
        this.media = media;
    }

    List<Map<String,Object>> list(String name, int page) {
        return store.rows("""
            select c.id,coalesce(c.title, (select a.display_name from participants other join accounts a on a.username=other.username
            where other.conversation_id=c.id and other.username<>? limit 1)) title
            from conversations c join participants p on p.conversation_id=c.id and p.username=?
            order by coalesce((select max(created_at) from messages where conversation_id=c.id),c.created_at) desc,c.id
            limit 20 offset ?
            """, name, name, Input.offset(page));
    }

    @Transactional
    public UUID create(String username, Chats.Create input) {
        var names = new TreeSet<>(input.people());
        if (names.contains(username)) throw ApiErrors.invalid("Choose other participants");
        for (String name : names) {
            if (!store.exists("""
                select 1 from connections where accepted and
                ((sender=? and recipient=?) or (sender=? and recipient=?))
                """, username, name, name, username)) throw ApiErrors.missing();
        }
        if (names.size() > 1 && (input.title() == null || input.title().isBlank())) {
            throw ApiErrors.invalid("Group chats need a title");
        }
        names.add(username);
        String directKey = names.size() == 2 ? String.join(":", names) : null;
        UUID id = store.value(UUID.class, """
            insert into conversations(title,direct_key) values (?,?)
            on conflict (direct_key) do update set direct_key=excluded.direct_key returning id
            """, directKey == null ? input.title().strip() : null, directKey).orElseThrow();
        names.forEach(name -> {
            if (store.write("insert into participants(conversation_id,username) values (?,?) on conflict do nothing", id, name) == 1) {
                live.publish(name, "chat");
            }
        });
        return id;
    }

    List<Map<String,Object>> members(String name, UUID conversation) {
        requireMember(name, conversation);
        return store.rows("""
            select a.username,a.display_name from participants p join accounts a on a.username=p.username
            where conversation_id=? order by a.display_name,a.username
            """, conversation).stream().map(row -> {
                row.put("online", live.online((String) row.get("username")));
                return row;
            }).toList();
    }

    List<Map<String,Object>> history(String name, UUID conversation, String query, int page) {
        requireMember(name, conversation);
        return store.rows("""
            select m.id::text,m.author,a.display_name,m.body,m.attachment_url,m.created_at,
              (select count(*) from receipts where message_id=m.id) delivered,
              (select count(*) from receipts where message_id=m.id and read_at is not null) seen,
              exists(select 1 from receipts where message_id=m.id and username=? and read_at is not null) read_by_me
            from messages m join accounts a on a.username=m.author where m.conversation_id=? and m.body ilike ?
            order by m.id desc limit 20 offset ?
            """, name, conversation, Input.search(query), Input.offset(page));
    }

    @Transactional
    public Map<String,Object> send(String name, UUID conversation, Chats.Message input) {
        requireMember(name, conversation);
        var item = store.one("""
            insert into messages(conversation_id,author,body,attachment_url) values (?,?,?,?) returning id::text
            """, conversation, name, input.body().strip(), media.reference(name, input.attachmentUrl(), false));
        for (String recipient : others(name, conversation)) {
            inbox.notify(recipient, "NEW_MESSAGE", name, conversation);
            live.publish(recipient, "delivery:" + conversation + ":" + item.get("id"));
        }
        live.publish(name, "chat");
        return item;
    }

    @Transactional
    public void receipt(String username, UUID conversation, long message, boolean read) {
        requireMember(username, conversation);
        var sender = store.value(String.class, "select author from messages where id=? and conversation_id=?",
                message, conversation).orElseThrow(ApiErrors::missing);
        if (sender.equals(username)) return;
        if (store.write("""
            insert into receipts(message_id,username,read_at) values (?,?,case when ? then now() end)
            on conflict(message_id,username) do update set read_at=excluded.read_at
            where receipts.read_at is null and excluded.read_at is not null
            """, message, username, read) > 0) live.publish(sender, "chat");
    }

    void typing(String username, UUID conversation) {
        requireMember(username, conversation);
        others(username, conversation).forEach(name -> live.publish(name, "typing:" + conversation + ":" + username));
    }

    void requireMember(String name, UUID conversation) {
        if (!store.exists("select 1 from participants where conversation_id=? and username=?", conversation, name)) {
            throw ApiErrors.missing();
        }
    }

    private List<String> others(String username, UUID conversation) {
        return store.rows("select username from participants where conversation_id=? and username<>?", conversation, username)
                .stream().map(row -> (String) row.get("username")).toList();
    }
}

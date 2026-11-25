package community.campus;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
class Notifications {
    private final Inbox inbox;
    Notifications(Inbox inbox) { this.inbox = inbox; }

    @GetMapping
    List<Map<String,Object>> list(Principal actor, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="false") boolean unread) {
        return inbox.list(actor.getName(), page, unread);
    }

    @GetMapping("/unread")
    Map<String,Long> unread(Principal actor) {
        return Map.of("count", inbox.unread(actor.getName()));
    }

    @PutMapping("/read-all")
    void readAll(Principal actor) { inbox.readAll(actor.getName()); }

    @PutMapping("/{id}")
    void read(Principal actor, @PathVariable UUID id, @RequestParam boolean read) {
        inbox.read(actor.getName(), id, read);
    }
}

@Service
class Inbox {
    private final Store store;
    private final Live live;
    Inbox(Store store, Live live) { this.store = store; this.live = live; }

    void notify(String recipient, String kind, String actor, UUID resource) {
        if (recipient.equals(actor)) return;
        store.write("insert into notifications(recipient,kind,actor,resource) values (?,?,?,?)", recipient, kind, actor, resource);
        live.publish(recipient, "notification");
    }

    List<Map<String,Object>> list(String name, int page, boolean unread) {
        return store.rows("""
            select n.id,n.kind,n.actor,a.display_name,n.resource,n.read,n.created_at
            from notifications n left join accounts a on a.username=n.actor
            where n.recipient=? and (not ? or not n.read) order by n.created_at desc,n.id desc limit 20 offset ?
            """, name, unread, Input.offset(page));
    }

    long unread(String name) {
        return store.value(Long.class, "select count(*) from notifications where recipient=? and not read", name).orElse(0L);
    }

    void readAll(String name) {
        if (store.write("update notifications set read=true where recipient=? and not read", name) > 0) {
            live.publish(name, "notification");
        }
    }

    void read(String name, UUID id, boolean read) {
        if (store.write("update notifications set read=? where id=? and recipient=?", read, id, name) == 0) {
            throw ApiErrors.missing();
        }
        live.publish(name, "notification");
    }
}

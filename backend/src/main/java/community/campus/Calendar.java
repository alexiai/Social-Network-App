package community.campus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
class Calendar {
    record Event(@NotBlank @Size(max=80) String title, @NotBlank @Size(max=500) String detail,
                 @NotNull @Future OffsetDateTime startsAt, @Min(1) @Max(10000) int capacity, UUID communityId) {}
    private final CalendarService calendar;
    Calendar(CalendarService calendar) { this.calendar = calendar; }

    @GetMapping
    List<Map<String,Object>> list(Principal actor, @RequestParam(defaultValue="0") int page,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime until,
            @RequestParam(required=false) UUID id) {
        if (from != null && until != null && !until.isAfter(from)) throw ApiErrors.invalid("Choose a valid event date range");
        return calendar.list(actor.getName(), page, from, until, id);
    }

    @PostMapping
    Map<String,Object> create(Principal actor, @Valid @RequestBody Event input) {
        return calendar.create(actor.getName(), input);
    }

    @PutMapping("/{id}/rsvp")
    void join(Principal actor, @PathVariable UUID id) { calendar.rsvp(actor.getName(), id, true); }

    @DeleteMapping("/{id}/rsvp")
    void leave(Principal actor, @PathVariable UUID id) { calendar.rsvp(actor.getName(), id, false); }
}

@Service
class CalendarService {
    private static final String VISIBLE = """
        (e.community_id is null or exists(select 1 from communities c where c.id=e.community_id and not c.private_group)
         or exists(select 1 from memberships m where m.community_id=e.community_id and m.username=? and m.role<>'INVITED'))
        """;
    private final Store store;
    private final GroupService groups;
    CalendarService(Store store, GroupService groups) { this.store = store; this.groups = groups; }

    List<Map<String,Object>> list(String username, int page, OffsetDateTime from, OffsetDateTime until, UUID id) {
        return store.rows("""
            select e.*,g.name as community_name,
              (select count(*) from rsvps where event_id=e.id) attendees,
              exists(select 1 from rsvps where event_id=e.id and username=?) attending
            from events e left join communities g on g.id=e.community_id where e.starts_at>now()
              and (cast(? as uuid) is null or e.id=?)
              and (cast(? as timestamptz) is null or e.starts_at>=?)
              and (cast(? as timestamptz) is null or e.starts_at<?) and
            """ + VISIBLE + " order by starts_at,e.id limit 20 offset ?", username, id, id, from, from, until, until,
                username, Input.offset(page));
    }

    @Transactional
    public Map<String,Object> create(String username, Calendar.Event event) {
        if (event.communityId() != null) groups.requireMemberForWrite(username, event.communityId());
        return store.one("""
            insert into events(organizer,title,detail,starts_at,capacity,community_id) values (?,?,?,?,?,?) returning id
            """, username, event.title().strip(), event.detail().strip(), event.startsAt(), event.capacity(), event.communityId());
    }

    @Transactional
    public void rsvp(String username, UUID id, boolean attend) {
        store.rows("""
            select c.id from communities c join events e on e.community_id=c.id
            where e.id=? and c.private_group for share of c
            """, id);
        int capacity = store.value(Integer.class, "select capacity from events e where id=? and starts_at>now() and "
                + VISIBLE + " for update", id, username).orElseThrow(ApiErrors::missing);
        if (!attend) {
            store.write("delete from rsvps where event_id=? and username=?", id, username);
            return;
        }
        if (store.exists("select 1 from rsvps where event_id=? and username=?", id, username)) return;
        long count = store.value(Long.class, "select count(*) from rsvps where event_id=?", id).orElseThrow();
        if (count >= capacity) throw ApiErrors.invalid("This event is full");
        store.write("insert into rsvps(event_id,username) values (?,?)", id, username);
    }
}

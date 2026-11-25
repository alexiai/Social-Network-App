package community.campus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/communities")
class Groups {
    record Create(@NotBlank @Size(max=80) String name, @NotBlank @Size(max=500) String description, boolean privateGroup) {}
    record Invitation(@NotBlank @Pattern(regexp="[a-z0-9_]{3,30}") String username) {}
    enum MemberRole { MEMBER, ADMIN }
    record RoleChange(@NotNull MemberRole role) {}
    private final GroupService groups;
    Groups(GroupService groups) { this.groups = groups; }

    @GetMapping
    List<Map<String,Object>> list(Principal actor, @RequestParam(defaultValue="") @Size(max=100) String q,
                                  @RequestParam(defaultValue="0") int page, @RequestParam(required=false) UUID id) {
        return groups.list(actor.getName(), q, page, id);
    }

    @PostMapping
    Map<String,Object> create(Principal actor, @Valid @RequestBody Create input) { return groups.create(actor.getName(), input); }

    @PutMapping("/{id}/membership")
    void join(Principal actor, @PathVariable UUID id) { groups.join(actor.getName(), id); }

    @DeleteMapping("/{id}/membership")
    void leave(Principal actor, @PathVariable UUID id) { groups.leave(actor.getName(), id); }

    @DeleteMapping("/{id}/members/{username}")
    void remove(Principal actor, @PathVariable UUID id, @PathVariable String username) {
        groups.remove(actor.getName(), id, username);
    }

    @GetMapping("/{id}/members")
    List<Map<String,Object>> members(Principal actor, @PathVariable UUID id, @RequestParam(defaultValue="0") int page) {
        return groups.members(actor.getName(), id, page);
    }

    @PutMapping("/{id}/members/{username}/role")
    void changeRole(Principal actor, @PathVariable UUID id, @PathVariable String username, @Valid @RequestBody RoleChange input) {
        groups.changeRole(actor.getName(), id, username, input.role());
    }

    @PostMapping("/{id}/invitations")
    void invite(Principal actor, @PathVariable UUID id, @Valid @RequestBody Invitation input) {
        groups.invite(actor.getName(), id, input.username());
    }
}

@Service
class GroupService {
    private final Store store;
    private final Inbox inbox;
    GroupService(Store store, Inbox inbox) { this.store = store; this.inbox = inbox; }

    List<Map<String,Object>> list(String username, String query, int page, UUID id) {
        return store.rows("""
            select c.id,c.name,c.description,c.private_group,m.role,
              (select count(*) from memberships where community_id=c.id and role<>'INVITED') members
            from communities c left join memberships m on m.community_id=c.id and m.username=?
            where (not c.private_group or m.role is not null) and (c.name || ' ' || c.description) ilike ?
              and (cast(? as uuid) is null or c.id=?)
            order by c.created_at desc,c.id limit 20 offset ?
            """, username, Input.search(query), id, id, Input.offset(page));
    }

    @Transactional
    public Map<String,Object> create(String username, Groups.Create input) {
        var group = store.one("insert into communities(name,description,private_group) values (?,?,?) returning id",
                input.name().strip(), input.description().strip(), input.privateGroup());
        store.write("insert into memberships values (?,?,'ADMIN')", group.get("id"), username);
        return group;
    }

    @Transactional
    public void join(String username, UUID group) {
        lockCommunity(group);
        if (store.write("""
            insert into memberships(community_id,username,role)
            select id,?,'MEMBER' from communities c where id=? and (not private_group or
                exists(select 1 from memberships where community_id=c.id and username=?))
            on conflict (community_id,username) do update set role='MEMBER' where memberships.role='INVITED'
            """, username, group, username) == 0) requireMember(username, group);
    }

    @Transactional
    public void invite(String username, UUID group, String target) {
        lockCommunity(group);
        requireAdministrator(username, group);
        if (!store.exists("select 1 from accounts where username=? and verified", target)) throw ApiErrors.missing();
        if (store.write("insert into memberships values (?,?,'INVITED') on conflict do nothing", group, target) == 1) {
            inbox.notify(target, "COMMUNITY_INVITATION", username, group);
        }
    }

    List<Map<String,Object>> members(String username, UUID group, int page) {
        requireMember(username, group);
        return store.rows("""
            select a.username,a.display_name,m.role from memberships m
            join accounts a on a.username=m.username
            where m.community_id=? and m.role<>'INVITED'
            order by (m.role='ADMIN') desc,lower(a.display_name),a.username limit 20 offset ?
            """, group, Input.offset(page));
    }

    @Transactional
    public void changeRole(String actor, UUID group, String username, Groups.MemberRole role) {
        lockCommunity(group);
        requireAdministrator(actor, group);
        if (actor.equals(username)) throw ApiErrors.invalid("You cannot change your own community role");
        if (store.write("""
            update memberships set role=? where community_id=? and username=? and role<>'INVITED'
            """, role.name(), group, username) == 0) throw ApiErrors.missing();
    }

    private void lockCommunity(UUID group) {
        store.one("select id from communities where id=? for update", group);
    }

    @Transactional
    public void leave(String username, UUID group) {
        lockCommunity(group);
        String role = store.value(String.class, "select role from memberships where community_id=? and username=?",
            group, username).orElseThrow(ApiErrors::missing);
        if ("ADMIN".equals(role) && !store.exists("""
            select 1 from memberships where community_id=? and username<>? and role='ADMIN'
            """, group, username)) throw ApiErrors.invalid("Appoint another administrator before leaving");
        deleteMembership(username, group);
    }

    @Transactional
    public void remove(String actor, UUID group, String target) {
        lockCommunity(group);
        requireAdministrator(actor, group);
        if (!store.exists("select 1 from memberships where community_id=? and username=? and role='MEMBER'", group, target)) {
            throw ApiErrors.missing();
        }
        deleteMembership(target, group);
    }

    private void deleteMembership(String username, UUID group) {
        store.write("delete from memberships where community_id=? and username=?", group, username);
        store.write("""
            delete from rsvps r using events e, communities c
            where r.event_id=e.id and r.username=? and e.community_id=c.id and c.id=? and c.private_group and e.starts_at>now()
            """, username, group);
    }

    private void requireAdministrator(String username, UUID group) {
        if (!store.exists("select 1 from memberships where community_id=? and username=? and role='ADMIN'", group, username)) {
            throw ApiErrors.missing();
        }
    }

    void requireMember(String username, UUID group) {
        if (!store.exists("select 1 from memberships where community_id=? and username=? and role<>'INVITED'", group, username)) {
            throw ApiErrors.missing();
        }
    }

    void requireMemberForWrite(String username, UUID group) {
        store.one("select id from communities where id=? for share", group);
        requireMember(username, group);
    }
}

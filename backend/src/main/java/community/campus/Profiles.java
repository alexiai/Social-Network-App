package community.campus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class Profiles {
    record Edit(@NotBlank @Size(max=80) String displayName, @NotNull @Size(max=500) String bio,
                @NotNull @Size(max=200) String interests, @NotNull @Size(max=100) String location,
                @Size(max=500) String avatarUrl, @Size(max=500) String coverUrl, boolean privateProfile) {}

    private final ProfileService profiles;
    Profiles(ProfileService profiles) { this.profiles = profiles; }

    @GetMapping("/profiles/me")
    Map<String,Object> me(Principal actor) { return profiles.profile(actor.getName(), actor.getName()); }

    @GetMapping("/profiles/{username}")
    Map<String,Object> profile(Principal actor, @PathVariable String username) { return profiles.profile(actor.getName(), username); }

    @PutMapping("/profiles/me")
    Map<String,Object> edit(Principal actor, @Valid @RequestBody Edit input) { return profiles.edit(actor.getName(), input); }

    @GetMapping("/people")
    List<Map<String,Object>> people(Principal actor, @RequestParam(defaultValue="") @Size(max=100) String q) {
        return profiles.people(actor.getName(), q);
    }

    @GetMapping("/connections")
    List<Map<String,Object>> connections(Principal actor) { return profiles.connections(actor.getName()); }

    @PutMapping("/connections/{username}")
    void connect(Principal actor, @PathVariable String username) { profiles.connect(actor.getName(), username); }

    @DeleteMapping("/connections/{username}")
    void disconnect(Principal actor, @PathVariable String username) { profiles.disconnect(actor.getName(), username); }
}

@Service
class ProfileService {
    private final Store store;
    private final Inbox inbox;
    private final MediaService media;
    ProfileService(Store store, Inbox inbox, MediaService media) {
        this.store = store;
        this.inbox = inbox;
        this.media = media;
    }

    Map<String,Object> profile(String viewer, String name) {
        return store.one("""
            select username, display_name, private_profile,
                case when allowed then bio end bio,
                case when allowed then interests end interests,
                case when allowed then location end location,
                case when allowed then avatar_url end avatar_url,
                case when allowed then cover_url end cover_url
            from (select a.*, not private_profile or username=? or exists (
                select 1 from connections where accepted and
                ((sender=? and recipient=a.username) or (sender=a.username and recipient=?))
            ) allowed from accounts a where username=? and verified) visible
            """, viewer, viewer, viewer, name);
    }

    public Map<String,Object> edit(String name, Profiles.Edit input) {
        store.write("""
            update accounts set display_name=?, bio=?, interests=?, location=?, avatar_url=?, cover_url=?, private_profile=?
            where username=?
            """, input.displayName().strip(), input.bio(), input.interests(), input.location(),
                media.reference(name, input.avatarUrl(), true), media.reference(name, input.coverUrl(), true), input.privateProfile(), name);
        return profile(name, name);
    }

    List<Map<String,Object>> people(String viewer, String query) {
        return store.rows("""
            select username,display_name,case when not private_profile then avatar_url end avatar_url
            from accounts where verified and username<>? and (username || ' ' || display_name) ilike ?
            order by display_name,username limit 40
            """, viewer, Input.search(query));
    }

    List<Map<String,Object>> connections(String name) {
        return store.rows("""
            select a.username,a.display_name,c.accepted,c.recipient=? as incoming
            from connections c join accounts a on a.username=case when c.sender=? then c.recipient else c.sender end
            where ? in (c.sender,c.recipient) order by a.display_name,a.username
            """, name, name, name);
    }

    @Transactional
    public void connect(String name, String target) {
        if (name.equals(target)) throw ApiErrors.invalid("Choose another person");
        if (!store.exists("select 1 from accounts where username=? and verified", target)) throw ApiErrors.missing();
        if (store.write("insert into connections(sender,recipient) values (?,?) on conflict do nothing", name, target) == 1) {
            inbox.notify(target, "CONNECTION_REQUEST", name, null);
        } else if (store.write("update connections set accepted=true where sender=? and recipient=? and not accepted", target, name) == 1) {
            inbox.notify(target, "CONNECTION_ACCEPTED", name, null);
        }
    }

    void disconnect(String name, String target) {
        store.write("delete from connections where (sender=? and recipient=?) or (sender=? and recipient=?)",
            name, target, target, name);
    }
}

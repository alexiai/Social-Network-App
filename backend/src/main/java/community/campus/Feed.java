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
@RequestMapping("/api/v1/posts")
class Feed {
    record Post(@NotBlank @Size(max=4000) String body, @Size(max=500) String imageUrl, UUID communityId) {}
    record Comment(@NotBlank @Size(max=1000) String body) {}
    enum Reaction { LIKE, SUPPORT, CELEBRATE }
    private final FeedService feed;
    Feed(FeedService feed) { this.feed = feed; }

    @GetMapping
    List<Map<String,Object>> list(Principal user, @RequestParam(defaultValue="") @Size(max=100) String q,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="false") boolean popular,
            @RequestParam(required=false) UUID communityId, @RequestParam(required=false) UUID id,
            @RequestParam(defaultValue="false") boolean connectionsOnly) {
        return feed.list(user.getName(), q, page, popular, communityId, id, connectionsOnly);
    }

    @PostMapping
    Map<String,Object> create(Principal user, @Valid @RequestBody Post post) { return feed.create(user.getName(), post); }

    @PutMapping("/{id}")
    void edit(Principal user, @PathVariable UUID id, @Valid @RequestBody Post post) { feed.edit(user.getName(), id, post); }

    @DeleteMapping("/{id}")
    void delete(Principal user, @PathVariable UUID id) { feed.delete(user.getName(), id); }

    @PutMapping("/{id}/reaction")
    void react(Principal user, @PathVariable UUID id, @RequestParam Reaction kind) { feed.react(user.getName(), id, kind); }

    @DeleteMapping("/{id}/reaction")
    void unreact(Principal user, @PathVariable UUID id) { feed.unreact(user.getName(), id); }

    @GetMapping("/{id}/comments")
    List<Map<String,Object>> comments(Principal user, @PathVariable UUID id, @RequestParam(defaultValue="0") int page) {
        return feed.comments(user.getName(), id, page);
    }

    @PostMapping("/{id}/comments")
    void comment(Principal user, @PathVariable UUID id, @Valid @RequestBody Comment body) {
        feed.comment(user.getName(), id, body.body());
    }
}

@Service
class FeedService {
    static final String VISIBLE = """
      ((p.community_id is null and (not a.private_profile or p.author=? or exists(
        select 1 from connections c where c.accepted and
        ((c.sender=p.author and c.recipient=?) or (c.recipient=p.author and c.sender=?)))))
        or (p.community_id is not null and (exists(select 1 from communities g where g.id=p.community_id and not g.private_group)
          or exists(select 1 from memberships m where m.community_id=p.community_id and m.username=? and m.role<>'INVITED'))))
      """;
    private final Store store;
    private final GroupService groups;
    private final Inbox inbox;
    private final MediaService media;
    FeedService(Store store, GroupService groups, Inbox inbox, MediaService media) {
        this.store = store;
        this.groups = groups;
        this.inbox = inbox;
        this.media = media;
    }

    List<Map<String,Object>> list(String user, String query, int page, boolean popular, UUID group, UUID id, boolean connectionsOnly) {
        String order = popular ? "reaction_count desc, p.created_at desc, p.id desc" : "p.created_at desc, p.id desc";
        return store.rows("""
            select p.*,a.display_name,g.name as community_name,
              (select count(*) from reactions where post_id=p.id) reaction_count,
              (select reaction from reactions where post_id=p.id and username=?) my_reaction,
              exists(select 1 from memberships m
                where m.community_id=p.community_id and m.username=? and m.role='ADMIN') can_moderate,
              (select count(*) from comments where post_id=p.id) comment_count
            from posts p join accounts a on a.username=p.author left join communities g on g.id=p.community_id
            where p.body ilike ? and (cast(? as uuid) is null or p.community_id=?)
              and (not ? or p.author=? or exists(select 1 from connections c where c.accepted
                and ((c.sender=p.author and c.recipient=?) or (c.recipient=p.author and c.sender=?))))
              and (cast(? as uuid) is null or p.id=?) and
            """ + VISIBLE + " order by " + order + " limit 20 offset ?",
            user, user, Input.search(query), group, group, connectionsOnly, user, user, user, id, id,
            user, user, user, user, Input.offset(page));
    }

    @Transactional
    public Map<String,Object> create(String user, Feed.Post post) {
        if (post.communityId() != null) groups.requireMemberForWrite(user, post.communityId());
        return store.one("insert into posts(author,community_id,body,image_url) values (?,?,?,?) returning id",
            user, post.communityId(), post.body().strip(), media.reference(user, post.imageUrl(), true));
    }

    void edit(String user, UUID id, Feed.Post post) {
        if (store.write("update posts set body=?,image_url=? where id=? and author=?",
            post.body().strip(), media.reference(user, post.imageUrl(), true), id, user) == 0) throw ApiErrors.missing();
    }

    void delete(String user, UUID id) {
        if (store.write("""
            delete from posts p where p.id=? and (p.author=? or exists (
                select 1 from memberships m where m.community_id=p.community_id
                    and m.username=? and m.role='ADMIN'
            ))
            """, id, user, user) == 0) throw ApiErrors.missing();
    }

    @Transactional
    public void react(String user, UUID id, Feed.Reaction reaction) {
        String author = author(user, id);
        if (store.write("insert into reactions values (?,?,?) on conflict do nothing", id, user, reaction.name()) == 1) {
            inbox.notify(author, "POST_REACTION", user, id);
        } else {
            store.write("update reactions set reaction=? where post_id=? and username=?", reaction.name(), id, user);
        }
    }

    void unreact(String user, UUID id) {
        author(user, id);
        store.write("delete from reactions where post_id=? and username=?", id, user);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> comments(String user, UUID id, int page) {
        author(user, id);
        return store.rows("""
            select c.id,c.author,a.display_name,c.body,c.created_at from comments c
            join accounts a on a.username=c.author where post_id=? order by c.created_at desc,c.id desc limit 20 offset ?
            """, id, Input.offset(page));
    }

    @Transactional
    public void comment(String user, UUID id, String body) {
        String author = author(user, id);
        store.write("insert into comments(post_id,author,body) values (?,?,?)", id, user, body.strip());
        inbox.notify(author, "POST_COMMENT", user, id);
    }

    private String author(String user, UUID id) {
        return store.value(String.class, """
            select p.author from posts p join accounts a on a.username=p.author where p.id=? and
            """ + VISIBLE, id, user, user, user, user).orElseThrow(ApiErrors::missing);
    }
}

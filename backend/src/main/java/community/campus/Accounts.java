package community.campus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
class Accounts {
    record Register(@Pattern(regexp = "[a-zA-Z0-9_]{3,30}") @NotBlank String username,
                    @Email @NotBlank @Size(max = 254) String email,
                    @NotBlank @Size(min = 10, max = 72) String password,
                    @NotBlank @Size(max = 80) String displayName) {}
    record Login(@NotBlank @Size(max = 254) String identity, @NotBlank @Size(max = 72) String password) {}
    record EmailAddress(@NotBlank @Email @Size(max = 254) String email) {}
    record Reset(@NotBlank @Size(max = 100) String token, @NotBlank @Size(min = 10, max = 72) String password) {}
    record ChangePassword(@NotBlank @Size(max = 72) String currentPassword,
                          @NotBlank @Size(min = 10, max = 72) String newPassword) {}
    record Token(@NotBlank @Size(max = 100) String token) {}

    private static final String COOKIE = "campus_refresh";
    private final AccountService service;
    private final boolean secure;

    Accounts(AccountService service, @Value("${app.secure-cookie}") boolean secure) {
        this.service = service;
        this.secure = secure;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    void register(@Valid @RequestBody Register input) { service.register(input); }

    @PostMapping("/login")
    ResponseEntity<Map<String, String>> login(@Valid @RequestBody Login input) {
        return session(service.login(input));
    }

    @PostMapping("/refresh")
    ResponseEntity<Map<String, String>> refresh(@CookieValue(name = COOKIE, required = false) String token,
                                               @RequestHeader("X-Campus-Request") String request) {
        if (token == null) throw AccountService.unauthorized();
        return session(service.refresh(token));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String token,
                                @RequestHeader("X-Campus-Request") String request) {
        if (token != null) service.revoke(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    @PostMapping("/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgot(@Valid @RequestBody EmailAddress input) { service.email(input.email(), "reset"); }

    @PostMapping("/verify/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resend(@Valid @RequestBody EmailAddress input) { service.email(input.email(), "verify"); }

    @PostMapping("/verify")
    void verify(@Valid @RequestBody Token input) { service.verify(input.token()); }

    @PostMapping("/reset")
    void reset(@Valid @RequestBody Reset input) { service.reset(input); }

    @PutMapping("/password")
    ResponseEntity<Void> changePassword(java.security.Principal principal, @Valid @RequestBody ChangePassword input) {
        service.changePassword(principal.getName(), input);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO)).build();
    }

    private ResponseEntity<Map<String, String>> session(AccountService.Session session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie(session.refresh(), Duration.ofDays(14)))
                .body(Map.of("accessToken", session.access(), "username", session.username()));
    }

    private String cookie(String value, Duration age) {
        return ResponseCookie.from(COOKIE, value).path("/api/v1/auth").httpOnly(true).secure(secure)
                .sameSite("Strict").maxAge(age).build().toString();
    }
}

@Service
class AccountService {
    record Session(String username, String access, String refresh) {}
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Store store;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final Live live;
    private final StringRedisTemplate redis;
    private final String origin;
    private final String dummyHash;

    AccountService(Store store, PasswordEncoder passwords, JwtEncoder encoder, StringRedisTemplate redis, Live live,
                   @Value("${app.origin}") String origin) {
        this.store = store;
        this.passwords = passwords;
        this.encoder = encoder;
        this.redis = redis;
        this.live = live;
        this.origin = origin;
        this.dummyHash = passwords.encode(random());
    }

    @Transactional
    public void register(Accounts.Register input) {
        String name = normalize(input.username());
        String email = normalize(input.email());
        limit("register:" + name, 5);
        store.write("insert into accounts(username,email,password_hash,display_name) values (?,?,?,?)",
                name, email, passwords.encode(password(input.password())), input.displayName().strip());
        queueMail(name, email, "verify");
    }

    @Transactional
    public Session login(Accounts.Login input) {
        String identity = normalize(input.identity());
        limit("login:" + identity, 12);
        var account = store.rows("select * from accounts where username=? or email=? for update", identity, identity)
                .stream().findFirst();
        String hash = account.map(row -> (String) row.get("password_hash")).orElse(dummyHash);
        if (!passwords.matches(password(input.password()), hash) || account.isEmpty()) throw unauthorized();
        if (!Boolean.TRUE.equals(account.get().get("verified"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verify your email before signing in");
        }
        return createSession(account.get());
    }

    @Transactional
    public Session refresh(String token) {
        String digest = digest(token);
        String username = store.value(String.class, """
                select username from account_tokens where digest=? and purpose='refresh' and expires_at>now()
                """, digest).orElseThrow(AccountService::unauthorized);
        var account = store.one("select * from accounts where username=? for update", username);
        consume(token, "refresh");
        return createSession(account);
    }

    void revoke(String token) {
        store.write("delete from account_tokens where digest=? and purpose='refresh'", digest(token));
    }

    @Transactional
    public void email(String address, String purpose) {
        String email = normalize(address);
        limit(purpose + ":" + email, 5);
        store.value(String.class, """
                select username from accounts where email=? and (not verified or ?='reset') for update
                """, email, purpose).ifPresent(name -> queueMail(name, email, purpose));
    }

    @Transactional
    public void verify(String token) {
        store.write("update accounts set verified=true where username=?", consumeAccountToken(token, "verify"));
    }

    @Transactional
    public void reset(Accounts.Reset input) {
        replacePassword(consumeAccountToken(input.token(), "reset"), input.password());
    }

    @Transactional
    public void changePassword(String username, Accounts.ChangePassword input) {
        limit("password:" + username, 10);
        var account = store.one("select password_hash from accounts where username=? for update", username);
        String hash = (String) account.get("password_hash");
        if (!passwords.matches(password(input.currentPassword()), hash)) {
            throw ApiErrors.invalid("Current password is incorrect");
        }
        if (passwords.matches(password(input.newPassword()), hash)) {
            throw ApiErrors.invalid("Choose a password different from your current password");
        }
        replacePassword(username, input.newPassword());
    }

    private void replacePassword(String account, String newPassword) {
        store.write("update accounts set password_hash=?, token_version=token_version+1 where username=?",
                passwords.encode(password(newPassword)), account);
        store.write("delete from account_tokens where username=? and purpose in ('reset','refresh')", account);
        live.publish(account, "sessions-revoked");
    }

    private Session createSession(Map<String, Object> account) {
        String name = (String) account.get("username");
        String refresh = issue(name, "refresh", 14 * 24 * 60);
        String access = Security.access(encoder, name, (String) account.get("role"), (Integer) account.get("token_version"));
        return new Session(name, access, refresh);
    }

    private void queueMail(String name, String email, String purpose) {
        store.write("delete from account_tokens where username=? and purpose=?", name, purpose);
        String token = issue(name, purpose, 60);
        String subject = purpose.equals("verify") ? "Verify your Soleil email" : "Reset your Soleil password";
        store.write("insert into mail_queue(recipient,subject,body) values (?,?,?)",
                email, subject, subject + ": " + origin + "/?action=" + purpose + "#token=" + token);
    }

    private String issue(String name, String purpose, int minutes) {
        String token = random();
        store.write("insert into account_tokens values (?,?,?,now()+make_interval(mins => ?))", digest(token), name, purpose, minutes);
        return token;
    }

    private String consumeAccountToken(String token, String purpose) {
        String name = store.value(String.class,
                "select username from account_tokens where digest=? and purpose=? and expires_at>now()",
                digest(token), purpose).orElseThrow(AccountService::unauthorized);
        store.one("select username from accounts where username=? for update", name);
        return consume(token, purpose);
    }

    private String consume(String token, String purpose) {
        return store.value(String.class, """
                delete from account_tokens where digest=? and purpose=? and expires_at>now() returning username
                """, digest(token), purpose).orElseThrow(AccountService::unauthorized);
    }

    private void limit(String identity, int maximum) {
        var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],600) end; return n", Long.class);
        Long count = redis.execute(script, java.util.List.of("campus:auth:" + digest(identity)));
        if (count == null || count > maximum) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Try again later");
    }

    static String normalize(String text) { return text.strip().toLowerCase(Locale.ROOT); }

    private static String password(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > 72) throw ApiErrors.invalid("Password exceeds 72 UTF-8 bytes");
        return text;
    }

    static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials or expired token");
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}

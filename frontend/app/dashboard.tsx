"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { eventRange, monthDays, startOfMonth } from "./calendar";
import { Attachment, MediaField, MediaImage, mediaField, type Request } from "./media";
import { api, ApiError, query, refresh, type Session } from "./api";
import { routes, viewForPath, type View } from "./navigation";

type Person = { username: string; display_name: string; avatar_url?: string | null };
type Profile = Person & { bio: string | null; interests: string | null; location: string | null; cover_url: string | null; private_profile: boolean };
type Connection = Person & { accepted: boolean; incoming: boolean };
type Group = { id: string; name: string; description: string; members: number; private_group: boolean; role: "MEMBER" | "ADMIN" | "INVITED" | null };
type Post = { id: string; author: string; display_name: string; body: string; created_at: string; image_url: string | null; community_name: string | null; reaction_count: number; comment_count: number; my_reaction: string | null; can_moderate: boolean };
type Comment = Pick<Post, "id" | "author" | "display_name" | "body" | "created_at">;
type Chat = { id: string; title: string };
type Member = Person & { online: boolean };
type Message = Comment & { attachment_url: string | null; delivered: number; seen: number; read_by_me: boolean };
type Notice = { id: string; kind: string; resource: string | null; actor: string | null; display_name: string | null; created_at: string; read: boolean };
type Event = { id: string; title: string; detail: string; starts_at: string; capacity: number; attendees: number; attending: boolean; community_name: string | null };


const symbols: Record<View, string> = { Feed: "◫", People: "♧", Communities: "❖", Messages: "✉", Events: "▦", Notifications: "◌", Profile: "◎" };
const subtitles: Record<View, string> = {
  Feed: "See what your campus is talking about.", People: "Find classmates and build your network.",
  Communities: "Find people who share your interests.", Messages: "Keep the conversation going.",
  Events: "Make plans and meet in person.", Notifications: "Catch up on what you missed.", Profile: "Make your corner of Soleil your own.",
};
const date = (value: string) => new Date(value).toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
const field = (data: FormData, name: string) => String(data.get(name) ?? "");
const message = (error: unknown) => error instanceof Error ? error.message : "Something went wrong";
const form = (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); return new FormData(event.currentTarget); };

function Avatar({ name, url, request }: { name: string; url?: string | null; request?: Request }) {
  return url && request ? <MediaImage className="avatar" url={url} request={request} thumb /> : <span className="avatar">{name.trim().slice(0, 2).toUpperCase()}</span>;
}

export default function Dashboard({ initialView }: { initialView: View }) {
  const [session, setSession] = useState<Session | null>(null);
  const sessionRef = useRef<Session | null>(null);
  const [ready, setReady] = useState(false);
  const [authAction, setAuthAction] = useState("login");
  const [emailToken, setEmailToken] = useState("");
  const [authFeedback, setAuthFeedback] = useState("");
  const [view, setView] = useState<View>(initialView);
  const [group, setGroup] = useState<Group | null>(null);
  const [profile, setProfile] = useState<string | null>(null);
  const [chatId, setChatId] = useState<string | null>(null);
  const [chatRevision, setChatRevision] = useState(0);
  const [noticeRevision, setNoticeRevision] = useState(0);
  const [unread, setUnread] = useState(0);
  const [focused, setFocused] = useState<string | null>(null);
  const [typing, setTyping] = useState("");
  const [connected, setConnected] = useState(false);
  const [me, setMe] = useState<Profile | null>(null);
  const [error, setError] = useState("");

  const authenticate = useCallback((value: Session | null) => {
    sessionRef.current = value;
    setSession(value);
  }, []);

  useEffect(() => {
    const action = new URLSearchParams(window.location.search).get("action");
    const token = new URLSearchParams(window.location.hash.slice(1)).get("token");
    if ((action === "verify" || action === "reset") && token) {
      setAuthAction(action);
      setEmailToken(token);
      window.history.replaceState(null, "", "/");
      setReady(true);
    } else {
      refresh().then(authenticate, () => {}).finally(() => setReady(true));
    }
  }, [authenticate]);

  const request: Request = useCallback(async <T,>(path: string, method?: string, body?: unknown) => {
    const current = sessionRef.current;
    if (!current) throw new Error("Sign in to continue");
    try {
      return await api<T>(path, method, body, current.accessToken);
    } catch (failure) {
      if (!(failure instanceof ApiError) || failure.status !== 401) throw failure;
      let renewed: Session;
      try {
        renewed = sessionRef.current && sessionRef.current !== current ? sessionRef.current : await refresh();
      } catch (error) {
        authenticate(null);
        throw error;
      }
      authenticate(renewed);
      return api<T>(path, method, body, renewed.accessToken);
    }
  }, [authenticate]);

  useEffect(() => {
    if (session) void request<Profile>("/profiles/me").then(setMe, failure => setError(message(failure)));
    else setMe(null);
  }, [session, request]);

  useEffect(() => {
    if (!session) return;
    let socket: WebSocket | undefined;
    let retry: ReturnType<typeof setTimeout>;
    let heartbeat: ReturnType<typeof setInterval>;
    let typingTimer: ReturnType<typeof setTimeout>;
    let stopped = false;
    function open() {
      const token = sessionRef.current?.accessToken;
      if (stopped || !token) return;
      socket = new WebSocket(`${location.protocol === "https:" ? "wss:" : "ws:"}//${location.host}/live`);
      socket.onopen = () => {
        socket?.send("auth " + token);
        heartbeat = setInterval(() => { if (socket?.readyState === WebSocket.OPEN) socket.send("ping"); }, 20000);
      };
      socket.onmessage = ({ data }) => {
        if (stopped) return;
        if (data === "sessions-revoked") {
          setAuthFeedback("Your session ended. Sign in again.");
          setAuthAction("login");
          setChatId(null);
          setConnected(false);
          authenticate(null);
          return;
        }
        if (data === "ready") {
          setConnected(true);
          setChatRevision(value => value + 1);
          setNoticeRevision(value => value + 1);
        }
        if (data === "chat") setChatRevision(value => value + 1);
        if (typeof data === "string" && data.startsWith("delivery:")) {
          const [, id, messageId] = data.split(":");
          void request("/chats/" + id + "/messages/" + messageId + "/receipt?read=false", "PUT").catch(() => {});
          setChatRevision(value => value + 1);
        }
        if (data === "notification") setNoticeRevision(value => value + 1);
        if (typeof data === "string" && data.startsWith("typing:")) {
          setTyping(data);
          clearTimeout(typingTimer);
          typingTimer = setTimeout(() => setTyping(""), 2500);
        }
      };
      socket.onclose = () => {
        clearInterval(heartbeat);
        if (!stopped) {
          setConnected(false);
          retry = setTimeout(open, 2500);
        }
      };
    }
    open();
    const renew = setInterval(() => {
      refresh().then(value => { if (!stopped) authenticate(value); }, failure => {
        if (!stopped && failure instanceof ApiError && failure.status === 401) authenticate(null);
      });
    }, 8 * 60 * 1000);
    return () => { stopped = true; socket?.close(); clearTimeout(retry); clearTimeout(typingTimer); clearInterval(heartbeat); clearInterval(renew); };
  }, [session, authenticate, request]);

  useEffect(() => {
    function restoreView() {
      setView(viewForPath(window.location.pathname) ?? "Feed");
      setGroup(null);
      setFocused(null);
      setProfile(null);
      setError("");
    }
    window.addEventListener("popstate", restoreView);
    return () => window.removeEventListener("popstate", restoreView);
  }, []);

  function navigate(next: View) {
    if (window.location.pathname !== routes[next]) window.history.pushState(null, "", routes[next]);
    setError("");
    setView(next);
    setGroup(null);
    setFocused(null);
    setProfile(null);
  }
  const showPerson = (name: string) => { navigate("Profile"); setProfile(name); };
  const openChat = (id: string) => { navigate("Messages"); setChatId(id); };

  useEffect(() => {
    if (!session) { setUnread(0); return; }
    let active = true;
    void request<{ count: number }>("/notifications/unread")
      .then(result => { if (active) setUnread(result?.count ?? 0); }, () => {});
    return () => { active = false; };
  }, [session, request, noticeRevision]);

  function openNotice(item: Notice) {
    if (item.kind === "NEW_MESSAGE" && item.resource) {
      openChat(item.resource);
      return;
    }
    const target = noticeDestinations[item.kind];
    if (!target) return;
    navigate(target);
    setFocused(target === "People" ? item.actor : item.resource);
  }

  if (!ready) return <div className="loading">Opening Soleil…</div>;
  if (!session) return <Auth action={authAction} change={setAuthAction} token={emailToken} initialFeedback={authFeedback} signedIn={value => { setAuthFeedback(""); authenticate(value); }} />;

  return <div className="shell">
    <aside className="sidebar">
      <button className="brand" aria-label="Soleil home" onClick={() => navigate("Feed")}><img src="/soleil/soleil.jpeg" alt="Soleil" /></button>
      <nav aria-label="Main navigation">{(Object.keys(symbols) as View[]).map(item =>
        <button key={item} className={view === item ? "active" : ""} aria-current={view === item ? "page" : undefined} onClick={() => navigate(item)}>
          <span aria-hidden="true">{symbols[item]}</span>{item}
          {item === "Notifications" && unread > 0 && <small className="notice-count" aria-hidden="true">{unread > 99 ? "99+" : unread}</small>}
        </button>)}</nav>
      <div className="sidebar-bottom"><div className="sidebar-art" aria-hidden="true" />
        <button className="account" onClick={() => navigate("Profile")}><Avatar name={me?.display_name || session.username} url={me?.avatar_url} request={request} />
          <span><strong>{me?.display_name || session.username}</strong><small>@{session.username}</small></span>
        </button>
        <button className="quiet" onClick={() => api("/auth/logout", "POST").then(() => {
          authenticate(null); setChatId(null); setConnected(false); setAuthAction("login");
        }, failure => setError(message(failure)))}>Sign out</button>
      </div>
    </aside>
    <main className="workspace">
      <header className="top"><span>Soleil / {view}</span><span className={connected ? "live" : "offline"}>{connected ? "Live" : "Connecting"}</span></header>
      <div className="content">
        <header className={view === "Feed" ? "intro hero" : "intro"}>
          <div><h1>{group?.name || (view === "Feed" ? "The campus feed" : view)}</h1><p>{group?.description || subtitles[view]}</p></div>
          <div className="section-art" data-view={view.toLowerCase()} aria-hidden="true" />
        </header>
        {error && <p className="error" role="alert">{error}</p>}
        {view === "Feed" && <Feed key={focused ?? group?.id ?? "all"} request={request} user={session.username} group={group} focused={focused} clearFocus={() => setFocused(null)} showPerson={showPerson} />}
        {view === "People" && <People key={focused ?? "all"} initialQuery={focused ?? ""} request={request} showPerson={showPerson} openChat={openChat} />}
        {view === "Communities" && <Communities key={focused ?? "all"} focused={focused} clearFocus={() => setFocused(null)} request={request} username={session.username} open={item => { navigate("Feed"); setGroup(item); }} />}
        {view === "Messages" && <Messages request={request} user={session.username} selected={chatId} select={setChatId} revision={chatRevision} typing={typing} />}
        {view === "Notifications" && <Notices request={request} revision={noticeRevision} unreadCount={unread} openNotice={openNotice} onRead={() => setNoticeRevision(value => value + 1)} />}
        {view === "Events" && <Events key={focused ?? "all"} focused={focused} clearFocus={() => setFocused(null)} request={request} />}
        {view === "Profile" && <>
          <ProfileView key={profile || session.username} request={request} username={profile || session.username} own={!profile || profile === session.username} update={setMe} />
          {(!profile || profile === session.username) && <AccountSettings request={request} changed={() => {
            setAuthFeedback("Password changed. Sign in with your new password.");
            setAuthAction("login");
            setChatId(null);
            setConnected(false);
            authenticate(null);
          }} />}
        </>}
      </div>
    </main>
  </div>;
}

function Auth({ action, change, token, initialFeedback, signedIn }: { action: string; change: (value: string) => void; token: string; initialFeedback: string; signedIn: (value: Session) => void }) {
  const [feedback, setFeedback] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const titles: Record<string, string> = { login: "Welcome back", register: "Make yourself at home", forgot: "Reset your password", verify: "Verify your email", reset: "Choose a new password", resend: "Resend verification" };

  async function submit(event: FormEvent<HTMLFormElement>) {
    const values = form(event);
    setBusy(true); setError(""); setFeedback("");
    const email = field(values, "email");
    const password = field(values, "password");
    try {
      if (action === "login") return signedIn(await api<Session>("/auth/login", "POST", { identity: field(values, "identity"), password }));
      if (action === "register") await api("/auth/register", "POST", { username: field(values, "username"), displayName: field(values, "name"), email, password });
      if (action === "forgot") await api("/auth/forgot", "POST", { email });
      if (action === "resend") await api("/auth/verify/resend", "POST", { email });
      if (action === "verify") await api("/auth/verify", "POST", { token });
      if (action === "reset") await api("/auth/reset", "POST", { token, password });
      change("login");
      setFeedback(action === "verify" ? "Email verified. You can sign in." : action === "reset" ? "Password updated. You can sign in." : "Check your inbox for a link if the address is eligible.");
    } catch (failure) { setError(message(failure)); }
    finally { setBusy(false); }
  }

  function switchTo(next: string) { setFeedback(""); setError(""); change(next); }

  return <main className="gate">
    <section className="gate-art"><div className="brand"><img src="/soleil/soleil.jpeg" alt="Soleil" /></div>
      <div className="gate-caption"><h1>Find your<br /><em>people.</em></h1><p>A place to meet, share and grow together.</p></div>

    </section>
    <section className="gate-form">
      <form className="stack" key={action} onSubmit={submit}>
        <h2>{titles[action]}</h2>
        {action === "login" && <label>Username or email<input name="identity" autoComplete="username" required /></label>}
        {action === "register" && <><label>Display name<input name="name" autoComplete="name" maxLength={80} required /></label><label>Username<input name="username" pattern="[A-Za-z0-9_]{3,30}" autoComplete="username" required /></label></>}
        {["register", "forgot", "resend"].includes(action) && <label>Email<input name="email" type="email" autoComplete="email" required /></label>}
        {["login", "register", "reset"].includes(action) && <label>Password<input name="password" type="password" autoComplete={action === "login" ? "current-password" : "new-password"} minLength={action === "login" ? undefined : 10} maxLength={72} required /></label>}
        {error && <p role="alert" className="error">{error}</p>}
        {(feedback || (action === "login" && initialFeedback)) && <p role="status">{feedback || initialFeedback}</p>}
        <button className="primary" disabled={busy}>{busy ? "Working…" : action === "login" ? "Sign in" : action === "register" ? "Create account" : "Continue"}</button>
        <div className="auth-actions">{action === "login" ? <>
          <button type="button" className="quiet" onClick={() => switchTo("register")}>Create an account</button>
          <button type="button" className="quiet" onClick={() => switchTo("forgot")}>Forgot password?</button>
          <button type="button" className="quiet" onClick={() => switchTo("resend")}>Resend verification</button>
        </> : <button type="button" className="quiet" onClick={() => switchTo("login")}>Back to sign in</button>}</div>
      </form>
    </section>
  </main>;
}

function useRows<T>(request: Request, path: string, revision = 0) {
  const [rows, setRows] = useState<T[]>([]);
  const [error, setError] = useState("");
  const [local, setLocal] = useState(0);
  const [loading, setLoading] = useState(true);
  const reload = useCallback(() => setLocal(old => old + 1), []);
  useEffect(() => {
    let active = true;
    if (!path) return;
    setError("");
    setLoading(true);
    request<T[]>(path).then(items => { if (active) setRows(items); }, failure => {
      if (active) setError(message(failure));
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [request, path, local, revision]);
  return { rows: path ? rows : [], error, setError, loading, reload };
}

function Result({ error, empty, loading, text }: { error: string; empty: boolean; loading: boolean; text: string }) {
  return error ? <p className="error" role="alert">{error}</p> : empty ? <p className="card muted">{loading ? "Loading…" : text}</p> : null;
}

function Pager({ page, count, set }: { page: number; count: number; set: (page: number) => void }) {
  if (!page && count < 20) return null;
  return <div className="row pager"><button disabled={!page} onClick={() => set(page - 1)}>Previous</button><span>Page {page + 1}</span><button disabled={count < 20} onClick={() => set(page + 1)}>Next</button></div>;
}

function Search({ placeholder, apply }: { placeholder: string; apply: (query: string) => void }) {
  return <form className="row search" onSubmit={event => apply(field(form(event), "q"))}><input name="q" aria-label={placeholder} placeholder={placeholder} maxLength={100} /><button>Search</button></form>;
}

function Feed({ request, user, group, focused, clearFocus, showPerson }: {
  request: Request; user: string; group: Group | null; focused: string | null; clearFocus: () => void; showPerson: (name: string) => void;
}) {
  const [q, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [popular, setPopular] = useState(false);
  const [connectionsOnly, setConnectionsOnly] = useState(false);
  const data = useRows<Post>(request, "/posts" + query({ q, page, popular, communityId: group?.id ?? null, id: focused, connectionsOnly }));
  async function publish(event: FormEvent<HTMLFormElement>) {
    const target = event.currentTarget;
    const values = form(event);
    try {
      await request("/posts", "POST", { body: field(values, "body"), imageUrl: await mediaField(request, values, "image"), communityId: group?.id });
      target.reset(); setPage(0); data.reload();
    } catch (error) { data.setError(message(error)); }
  }
  return <section className="stack">
    {focused && <div><button onClick={clearFocus}>Back to all posts</button></div>}
    {!focused && (!group || group.role === "ADMIN" || group.role === "MEMBER") && <form className="card stack" onSubmit={publish}>
      <h2>Start a conversation</h2><textarea name="body" aria-label="Post text" placeholder="What’s happening on campus?" maxLength={4000} required />
      <div className="composer-actions"><details><summary>Add image</summary><div className="details"><MediaField name="image" label="Post image" /></div></details><button className="primary">Publish post</button></div>
    </form>}
    {!focused && <div className="row spread wrap"><Search placeholder="Search posts" apply={value => { setQuery(value); setPage(0); }} />
      {!group && <label className="row"><input type="checkbox" checked={connectionsOnly} onChange={event => { setConnectionsOnly(event.target.checked); setPage(0); }} /> My connections</label>}
      <select aria-label="Sort posts" value={popular ? "popular" : "latest"} onChange={event => { setPopular(event.target.value === "popular"); setPage(0); }}><option value="latest">Latest first</option><option value="popular">Most reacted</option></select></div>}
    <Result {...data} empty={!data.rows.length} text={focused ? "This post is no longer available." : "No posts here yet."} />
    {data.rows.map(post => <PostCard key={post.id} post={post} user={user} request={request} reload={data.reload} showPerson={showPerson} />)}
    <Pager page={page} count={data.rows.length} set={setPage} />
  </section>;
}

function PostCard({ post, user, request, reload, showPerson }: { post: Post; user: string; request: Request; reload: () => void; showPerson: (name: string) => void }) {
  const [comments, setComments] = useState(false);
  const [edit, setEdit] = useState(false);
  const [page, setPage] = useState(0);
  const path = "/posts/" + post.id;
  const replies = useRows<Comment>(request, comments ? path + "/comments" + query({ page }) : "");
  async function mutate(url: string, method: string, body?: unknown) {
    try { await request(url, method, body); reload(); return true; }
    catch (error) { replies.setError(message(error)); return false; }
  }
  return <article className="card stack">
    <div className="row spread">
      <button className="account" onClick={() => showPerson(post.author)}><Avatar name={post.display_name} /><span><strong>{post.display_name}</strong><small>@{post.author} · {date(post.created_at)}</small></span></button>
      {post.community_name && <span className="pill">{post.community_name}</span>}
    </div>
    {edit ? <form className="stack" onSubmit={async event => {
      const values = form(event); if (await mutate(path, "PUT", { body: field(values, "body"), imageUrl: await mediaField(request, values, "image") })) setEdit(false);
    }}><textarea name="body" required maxLength={4000} defaultValue={post.body} aria-label="Edit post text" /><MediaField name="image" label="Post image" value={post.image_url} /><div className="row"><button className="primary">Save changes</button><button type="button" onClick={() => setEdit(false)}>Cancel</button></div></form> :
    <><p className="body-text">{post.body}</p>{post.image_url && <MediaImage className="post-image" url={post.image_url} request={request} alt="Post attachment" />}</>}
    <div className="row wrap">
      <select aria-label="Post reaction" value={post.my_reaction ?? ""} onChange={event => {
        const value = event.target.value; void mutate(path + "/reaction" + query({ kind: value }), value ? "PUT" : "DELETE");
      }}><option value="">React</option><option value="LIKE">Like</option><option value="SUPPORT">Support</option><option value="CELEBRATE">Celebrate</option></select>
      <small>{post.reaction_count} reactions</small><button onClick={() => setComments(!comments)}>{post.comment_count} comments</button>
      {post.author === user && !edit && <button onClick={() => setEdit(true)}>Edit</button>}
      {!edit && (post.author === user || post.can_moderate) && <button onClick={() => {
        if (window.confirm("Delete this post?")) void mutate(path, "DELETE");
      }}>{post.author === user ? "Delete" : "Remove post"}</button>}
    </div>
    {replies.error && <p role="alert" className="error">{replies.error}</p>}
    {comments && <div className="stack">
      <form className="row" onSubmit={async event => {
        const target = event.currentTarget; const values = form(event);
        if (await mutate(path + "/comments", "POST", { body: field(values, "body") })) { target.reset(); setPage(0); replies.reload(); }
      }}><input name="body" aria-label="Comment text" placeholder="Write a comment" required maxLength={1000} /><button>Reply</button></form>
      {replies.rows.map(reply => <div key={reply.id} className="comment"><button className="quiet" onClick={() => showPerson(reply.author)}>{reply.display_name}</button><p className="body-text">{reply.body}</p></div>)}
      <Pager page={page} count={replies.rows.length} set={setPage} />
    </div>}
  </article>;
}


function People({ request, initialQuery, showPerson, openChat }: { request: Request; initialQuery: string; showPerson: (name: string) => void; openChat: (id: string) => void }) {
  const [q, setQuery] = useState(initialQuery);
  const people = useRows<Person>(request, "/people" + query({ q }));
  const connections = useRows<Connection>(request, "/connections");
  async function connect(person: Person, method: string) {
    try { await request("/connections/" + person.username, method); connections.reload(); }
    catch (error) { connections.setError(message(error)); }
  }
  return <div className="stack">
    <Search placeholder="Search by name or username" apply={setQuery} />
    <Result {...people} empty={!people.rows.length} text="No people match this search." />
    {connections.error && <p role="alert" className="error">{connections.error}</p>}
    {people.rows.map(person => {
      const status = connections.rows.find(item => item.username === person.username);
      return <div className="card row spread person" key={person.username}>
        <button className="account" onClick={() => showPerson(person.username)}><Avatar name={person.display_name} url={person.avatar_url} request={request} /><span><strong>{person.display_name}</strong><small>@{person.username}</small></span></button>
        <div className="row wrap">
          {status?.accepted && <button onClick={async () => {
            try { openChat(await request<string>("/chats", "POST", { people: [person.username] })); }
            catch (error) { connections.setError(message(error)); }
          }}>Message</button>}
          {!status || (status.incoming && !status.accepted) ? <button className="primary" onClick={() => connect(person, "PUT")}>{status ? "Accept request" : "Connect"}</button> : null}
          {status && <button onClick={() => connect(person, "DELETE")}>{status.accepted ? "Remove" : status.incoming ? "Decline" : "Cancel request"}</button>}
        </div>
      </div>;
    })}
    <section className="card stack"><h2>Your connections</h2>
      <Result {...connections} empty={!connections.rows.length} text="You have no connections or requests yet." />
      {connections.rows.map(person => <div className="row spread" key={person.username}>
        <button className="account" onClick={() => showPerson(person.username)}><Avatar name={person.display_name} /><span><strong>{person.display_name}</strong><small>@{person.username}</small></span></button>
        {person.accepted ? <span>Connected</span> : <div className="row">
          {person.incoming && <button onClick={() => connect(person, "PUT")}>Accept request</button>}
          <button onClick={() => connect(person, "DELETE")}>{person.incoming ? "Decline" : "Cancel request"}</button>
        </div>}
      </div>)}
    </section>
  </div>;
}

function Communities({ request, username, focused, clearFocus, open }: {
  request: Request; username: string; focused: string | null; clearFocus: () => void; open: (group: Group) => void;
}) {
  const [q, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const data = useRows<Group>(request, "/communities" + query({ q, page, id: focused }));
  async function submit(event: FormEvent<HTMLFormElement>, group?: Group) {
    const target = event.currentTarget;
    const values = form(event);
    try {
      if (group) await request("/communities/" + group.id + "/invitations", "POST", { username: field(values, "username").toLowerCase() });
      else {
        await request("/communities", "POST", { name: field(values, "name"), description: field(values, "description"), privateGroup: values.has("private") });
        target.closest("details")?.removeAttribute("open");
        setPage(0);
      }
      target.reset(); data.reload();
    } catch (error) { data.setError(message(error)); }
  }
  return <div className="stack">
    <details className="card"><summary>Create a community</summary><form className="stack details" onSubmit={submit}>
      <label>Name<input name="name" maxLength={80} required /></label><label>Description<textarea name="description" maxLength={500} required /></label>
      <label className="check"><input name="private" type="checkbox" /> Invitation only</label><button className="primary">Create community</button>
    </form></details>
    {focused ? <div><button onClick={clearFocus}>Back to all communities</button></div> :
      <Search placeholder="Search communities" apply={value => { setQuery(value); setPage(0); }} />}
    <Result {...data} empty={!data.rows.length} text={focused ? "This community is no longer available." : "No communities match this search."} />
    <div className="tiles">{data.rows.map(group => <article className="card stack" key={group.id}>
      <div className="row spread"><span className="tile-icon" aria-hidden="true">❖</span><span className="pill">{group.private_group ? "Private" : "Public"}</span></div>
      <h2>{group.name}</h2><p className="body-text">{group.description}</p><small>{group.members} {group.members === 1 ? "member" : "members"}</small>
      <div className="row wrap">
        {(!group.private_group || group.role === "ADMIN" || group.role === "MEMBER") && <button onClick={() => open(group)}>Open discussion</button>}
        {(!group.role || group.role === "INVITED") && <button className="primary" onClick={async () => {
          try { await request("/communities/" + group.id + "/membership", "PUT"); data.reload(); }
          catch (error) { data.setError(message(error)); }
        }}>{group.role === "INVITED" ? "Accept invitation" : "Join"}</button>}
        {group.role && <button onClick={async () => {
          if (group.role !== "INVITED" && !window.confirm("Leave " + group.name + "?")) return;
          try { await request("/communities/" + group.id + "/membership", "DELETE"); data.reload(); }
          catch (error) { data.setError(message(error)); }
        }}>{group.role === "INVITED" ? "Decline invitation" : "Leave community"}</button>}
      </div>
      {(group.role === "ADMIN" || group.role === "MEMBER") && <CommunityMembers group={group} username={username} request={request} membershipChanged={data.reload} />}
      {group.role === "ADMIN" && <form className="row" onSubmit={event => submit(event, group)}><input name="username" aria-label="Username to invite" placeholder="Username" required /><button>Invite</button></form>}
    </article>)}</div>
    <Pager page={page} count={data.rows.length} set={setPage} />
  </div>;
}

function CommunityMembers({ group, username, request, membershipChanged }: {
  group: Group; username: string; request: Request; membershipChanged: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [page, setPage] = useState(0);
  const [saving, setSaving] = useState<string | null>(null);
  const endpoint = "/communities/" + group.id + "/members";
  const data = useRows<Person & { role: "ADMIN" | "MEMBER" }>(request, open ? endpoint + query({ page }) : "");

  async function updateMember(member: Person & { role: "ADMIN" | "MEMBER" }, remove = false) {
    if (remove && !window.confirm("Remove @" + member.username + " from this community?")) return;
    setSaving(member.username);
    try {
      const memberPath = endpoint + "/" + encodeURIComponent(member.username);
      if (remove) {
        await request(memberPath, "DELETE");
        setPage(0);
        membershipChanged();
      } else await request(memberPath + "/role", "PUT", {
        role: member.role === "ADMIN" ? "MEMBER" : "ADMIN",
      });
      data.reload();
    } catch (failure) {
      data.setError(message(failure));
    } finally {
      setSaving(null);
    }
  }

  return <details onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>View members</summary>
    {open && <div className="stack details">
      <Result {...data} empty={!data.rows.length} text="No members on this page." />
      {data.rows.map(member => <div className="row spread wrap" key={member.username}>
        <span><strong>{member.display_name}</strong> <small>@{member.username}</small></span>
        <span className="pill">{member.role === "ADMIN" ? "Administrator" : "Member"}</span>
        {group.role === "ADMIN" && member.username !== username &&
          <button type="button" disabled={saving !== null} onClick={() => void updateMember(member)}>
            {member.role === "ADMIN" ? "Make member" : "Make administrator"}
          </button>}
        {group.role === "ADMIN" && member.role === "MEMBER" &&
          <button type="button" disabled={saving !== null} onClick={() => void updateMember(member, true)}>Remove member</button>}
      </div>)}
      <Pager page={page} count={data.rows.length} set={setPage} />
    </div>}
  </details>;
}

function Messages({ request, user, selected, select, revision, typing }: { request: Request; user: string; selected: string | null; select: (id: string) => void; revision: number; typing: string }) {
  const [page, setPage] = useState(0);
  const chats = useRows<Chat>(request, "/chats" + query({ page }), revision);
  const connectionData = useRows<Connection>(request, "/connections");

  async function create(event: FormEvent<HTMLFormElement>) {
    const target = event.currentTarget; const values = form(event);
    try {
      const id = await request<string>("/chats", "POST", { title: field(values, "title"), people: values.getAll("person") });
      target.reset(); setPage(0); chats.reload(); select(id);
    } catch (error) { chats.setError(message(error)); }
  }

  return <div className="chat-layout">
    <aside className="stack">
      <details className="card"><summary>New conversation</summary><form className="stack details" onSubmit={create}>
        <label>Group title<input name="title" maxLength={80} placeholder="For a group chat" /></label>
        {connectionData.error && <p className="error" role="alert">{connectionData.error}</p>}
        {connectionData.rows.filter(person => person.accepted).map(person => <label className="check" key={person.username}><input type="checkbox" name="person" value={person.username} />{person.display_name}</label>)}
        <button className="primary">Start chat</button>
      </form></details>
      <Result {...chats} empty={!chats.rows.length} text="No conversations yet." />
      {chats.rows.map(chat => <button className={selected === chat.id ? "selected chat-link" : "chat-link"} key={chat.id} onClick={() => select(chat.id)}>{chat.title}</button>)}
      <Pager page={page} count={chats.rows.length} set={setPage} />
    </aside>
    {selected ? <Conversation key={selected} id={selected} request={request} user={user} revision={revision} typing={typing} /> : <p className="card muted">Choose a conversation to see your messages.</p>}
  </div>;
}

function Conversation({ id, request, user, revision, typing }: { id: string; request: Request; user: string; revision: number; typing: string }) {
  const [q, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const lastTyping = useRef(0);
  const acknowledged = useRef(new Set<string>());
  const base = "/chats/" + id;
  const data = useRows<Message>(request, base + "/messages" + query({ q, page }), revision);
  const members = useRows<Member>(request, base + "/members");
  const reloadMembers = members.reload;

  useEffect(() => {
    const timer = setInterval(reloadMembers, 15000);
    return () => clearInterval(timer);
  }, [reloadMembers]);

  useEffect(() => {
    for (const item of data.rows) {
      if (item.author === user || item.read_by_me || acknowledged.current.has(item.id)) continue;
      acknowledged.current.add(item.id);
      request(base + "/messages/" + item.id + "/receipt?read=true", "PUT").catch(() => acknowledged.current.delete(item.id));
    }
  }, [data.rows, user, base, request]);

  const typingName = typing.startsWith("typing:" + id + ":") ? members.rows.find(member => member.username === typing.split(":")[2])?.display_name : null;

  async function send(event: FormEvent<HTMLFormElement>) {
    const target = event.currentTarget; const values = form(event);
    try {
      await request(base + "/messages", "POST", { body: field(values, "body"), attachmentUrl: await mediaField(request, values, "attachment") });
      target.reset(); setPage(0); data.reload();
    } catch (error) { data.setError(message(error)); }
  }

  return <section className="card stack conversation">
    <h2>Conversation</h2>
    {members.error && <p className="error" role="alert">{members.error}</p>}
    <div className="row wrap">{members.rows.map(person => <span className="pill" key={person.username}><span className={person.online ? "online-dot" : "offline-dot"} />{person.display_name}<span className="sr-only"> {person.online ? "online" : "offline"}</span></span>)}</div>
    <Search placeholder="Search this conversation" apply={value => { setQuery(value); setPage(0); }} />
    <Result {...data} empty={!data.rows.length} text="No messages found." />
    <Pager page={page} count={data.rows.length} set={setPage} />
    <div className="message-history">{[...data.rows].reverse().map(item => <article className={item.author === user ? "bubble mine" : "bubble"} key={item.id}>
      <strong>{item.display_name}</strong><p className="body-text">{item.body}</p>
      {item.attachment_url && <Attachment url={item.attachment_url} request={request} />}
      <small>{date(item.created_at)}{item.author === user && " · " + (item.seen ? "Read by " + item.seen : item.delivered ? "Delivered" : "Sent")}</small>
    </article>)}</div>
    <div aria-live="polite" className="muted">{typingName ? typingName + " is typing…" : ""}</div>
    <form className="stack" onSubmit={send}><textarea name="body" aria-label="Message text" placeholder="Write a message" maxLength={4000} required onChange={() => {
      if (Date.now() - lastTyping.current < 2000) return;
      lastTyping.current = Date.now(); void request(base + "/typing", "POST").catch(() => {});
    }} /><div className="composer-actions"><details><summary>Add attachment</summary><div className="details"><MediaField name="attachment" label="Attachment" image={false} /></div></details><button className="primary">Send message</button></div></form>
  </section>;
}

const notices: Record<string, string> = {
  CONNECTION_REQUEST: "sent a connection request", CONNECTION_ACCEPTED: "accepted your connection request",
  COMMUNITY_INVITATION: "invited you to a community", POST_REACTION: "reacted to your post",
  POST_COMMENT: "commented on your post", NEW_MESSAGE: "sent you a message", EVENT_REMINDER: "An event you joined starts within 24 hours",
};

const noticeDestinations: Partial<Record<string, View>> = {
  CONNECTION_REQUEST: "People", CONNECTION_ACCEPTED: "People", COMMUNITY_INVITATION: "Communities",
  POST_REACTION: "Feed", POST_COMMENT: "Feed", EVENT_REMINDER: "Events",
};

function noticeAction(item: Notice) {
  if (item.kind === "NEW_MESSAGE" && item.resource) return "Open conversation";
  if (noticeDestinations[item.kind] === "People" && item.actor) return "Open connections";
  if (!item.resource) return null;
  switch (noticeDestinations[item.kind]) {
    case "Communities": return "Open community";
    case "Feed": return "Open post";
    case "Events": return "Open event";
    default: return null;
  }
}

function Notices({ request, revision, unreadCount, openNotice, onRead }: {
  request: Request; revision: number; unreadCount: number; openNotice: (item: Notice) => void; onRead: () => void;
}) {
  const [page, setPage] = useState(0);
  const [unread, setUnread] = useState(false);
  const data = useRows<Notice>(request, "/notifications" + query({ page, unread }), revision);

  async function setRead(item: Notice, read: boolean) {
    await request("/notifications/" + item.id + query({ read }), "PUT");
    onRead();
  }

  async function openItem(item: Notice) {
    try {
      if (!item.read) await setRead(item, true);
      openNotice(item);
    } catch (error) { data.setError(message(error)); }
  }
  return <div className="stack">
    <div className="row spread wrap">
      <label className="row"><input type="checkbox" checked={unread} onChange={event => { setUnread(event.target.checked); setPage(0); }} /> Unread only</label>
      <button type="button" disabled={data.loading || (unreadCount === 0 && !data.rows.some(item => !item.read))} onClick={async () => {
        try { await request("/notifications/read-all", "PUT"); setPage(0); onRead(); }
        catch (error) { data.setError(message(error)); }
      }}>Mark all as read</button>
    </div>
    <Result {...data} empty={!data.rows.length} text="You’re all caught up." />
    {data.rows.map(item => <article className={"card row spread " + (item.read ? "" : "unread")} key={item.id}><div>
      <p>{item.display_name && <strong>{item.display_name} </strong>}{notices[item.kind] || "New activity"}</p><small>{date(item.created_at)}</small>
        </div><div className="row wrap">
      {noticeAction(item) && <button onClick={() => void openItem(item)}>{noticeAction(item)}</button>}
      <button onClick={() => void setRead(item, !item.read).catch(error => data.setError(message(error)))}>
        {item.read ? "Mark unread" : "Mark read"}
      </button>
    </div></article>)}
    <Pager page={page} count={data.rows.length} set={setPage} />
  </div>;
}

function Events({ request, focused, clearFocus }: { request: Request; focused: string | null; clearFocus: () => void }) {
  const [page, setPage] = useState(0);
  const [month, setMonth] = useState(() => startOfMonth(new Date()));
  const [day, setDay] = useState<Date | null>(null);
  const [allUpcoming, setAllUpcoming] = useState(false);
  const [revision, setRevision] = useState(0);
  const [formError, setFormError] = useState("");
  const path = "/events" + query({ page, id: focused, ...eventRange(allUpcoming || focused ? null : month, day) });
  const groups = useRows<Group>(request, "/communities");

  function moveMonth(offset: number) {
    setMonth(current => startOfMonth(current, offset));
    setDay(null);
    setAllUpcoming(false);
    setPage(0);
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    const target = event.currentTarget; const values = form(event);
    setFormError("");
    try {
      const starts = new Date(field(values, "starts"));
      await request("/events", "POST", { title: field(values, "title"), detail: field(values, "detail"),
        startsAt: starts.toISOString(), capacity: Number(field(values, "capacity")), communityId: field(values, "group") || null });
      target.reset();
      setMonth(startOfMonth(starts));
      setDay(new Date(starts.getFullYear(), starts.getMonth(), starts.getDate()));
      setAllUpcoming(false);
      setPage(0);
      setRevision(value => value + 1);
    } catch (error) { setFormError(message(error)); }
  }
  return <div className="stack">
    {focused ? <div><button onClick={clearFocus}>Back to the event calendar</button></div> : <>
    <details className="card"><summary>Create an event</summary><form className="stack details" onSubmit={create}>
      <label>Title<input name="title" maxLength={80} required /></label><label>Description<textarea name="detail" maxLength={500} required /></label>
      <div className="row wrap"><label>Starts at<input name="starts" type="datetime-local" required /></label><label>Capacity<input name="capacity" type="number" min="1" max="10000" defaultValue="30" required /></label></div>
      <label>Community<select name="group"><option value="">Soleil-wide</option>{groups.rows.filter(group => group.role === "ADMIN" || group.role === "MEMBER").map(group => <option key={group.id} value={group.id}>{group.name}</option>)}</select></label>
      <button className="primary">Create event</button>
    </form></details>
    {formError && <p className="error" role="alert">{formError}</p>}
    <section className="card stack event-calendar" aria-label="Event calendar">
      <div className="row spread calendar-heading">
        <button type="button" aria-label="Previous month" onClick={() => moveMonth(-1)}>‹</button>
        <h2 aria-live="polite">{month.toLocaleDateString(undefined, { month: "long", year: "numeric" })}</h2>
        <button type="button" aria-label="Next month" onClick={() => moveMonth(1)}>›</button>
      </div>
      <div className="calendar-days">
        {["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map(label => <span key={label} aria-hidden="true">{label}</span>)}
        {monthDays(month).map((date, index) => date ? <button key={date.toISOString()} type="button"
          aria-label={date.toLocaleDateString(undefined, { dateStyle: "full" })}
          aria-current={date.toDateString() === new Date().toDateString() ? "date" : undefined}
          aria-pressed={day?.getTime() === date.getTime()}
          onClick={() => { setDay(date); setAllUpcoming(false); setPage(0); }}>{date.getDate()}</button> :
          <span key={"blank-" + index} aria-hidden="true" />)}
      </div>
      <div className="row wrap">
        <button type="button" aria-pressed={!day && !allUpcoming}
          onClick={() => { setDay(null); setAllUpcoming(false); setPage(0); }}>Whole month</button>
        <button type="button" aria-pressed={allUpcoming}
          onClick={() => { setDay(null); setAllUpcoming(true); setPage(0); }}>All upcoming events</button>
      </div>
    </section></>}
    <EventResults key={path} path={path} request={request} revision={revision} page={page} setPage={setPage}
      title={focused ? "Event from your notifications" : day?.toLocaleDateString(undefined, { dateStyle: "full" }) ?? (allUpcoming ? "Upcoming events" : "Events this month")} />
  </div>;
}

function EventResults({ path, request, revision, page, setPage, title }: {
  path: string; request: Request; revision: number; page: number; setPage: (page: number) => void; title: string;
}) {
  const events = useRows<Event>(request, path, revision);
  return <section className="stack" aria-label="Event results">
    <h2>{title}</h2>
    <Result {...events} empty={!events.rows.length} text="No upcoming events in this view." />
    {events.rows.map(item => <article className="card stack" key={item.id}>
      <div className="row spread"><span className="pill">{date(item.starts_at)}</span>{item.community_name && <span className="pill">{item.community_name}</span>}</div>
      <h2>{item.title}</h2><p className="body-text">{item.detail}</p>
      <div className="row spread"><small>{item.attendees} / {item.capacity} going</small><button className={item.attending ? "" : "primary"} disabled={!item.attending && item.attendees >= item.capacity} onClick={async () => {
        events.setError("");
        try { await request("/events/" + item.id + "/rsvp", item.attending ? "DELETE" : "PUT"); events.reload(); }
        catch (error) { events.setError(message(error)); }
      }}>{item.attending ? "Cancel RSVP" : item.attendees >= item.capacity ? "Full" : "RSVP"}</button></div>
    </article>)}
    <Pager page={page} count={events.rows.length} set={setPage} />
  </section>;
}

function AccountSettings({ request, changed }: { request: Request; changed: () => void }) {
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function changePassword(event: FormEvent<HTMLFormElement>) {
    const values = form(event);
    const newPassword = field(values, "newPassword");
    if (newPassword !== field(values, "confirmation")) {
      setError("The new passwords don’t match.");
      return;
    }

    setError("");
    setBusy(true);
    try {
      await request("/auth/password", "PUT", { currentPassword: field(values, "currentPassword"), newPassword });
      changed();
    } catch (failure) {
      setError(message(failure));
    } finally {
      setBusy(false);
    }
  }

  return <details className="card">
    <summary>Account settings</summary>
    <form className="stack details" onSubmit={changePassword}>
      <h2>Change password</h2>
      <p>Changing your password signs you out of all sessions.</p>
      <label>Current password<input name="currentPassword" type="password" autoComplete="current-password" maxLength={72} required /></label>
      <label>New password<input name="newPassword" type="password" autoComplete="new-password" minLength={10} maxLength={72} required /></label>
      <label>Repeat new password<input name="confirmation" type="password" autoComplete="new-password" minLength={10} maxLength={72} required /></label>
      {error && <p className="error" role="alert">{error}</p>}
      <div><button className="primary" disabled={busy}>{busy ? "Changing password…" : "Change password"}</button></div>
    </form>
  </details>;
}

function ProfileView({ request, username, own, update }: { request: Request; username: string; own: boolean; update: (value: Profile) => void }) {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    request<Profile>("/profiles/" + encodeURIComponent(username)).then(value => { if (active) setProfile(value); }, failure => { if (active) setError(message(failure)); });
    return () => { active = false; };
  }, [request, username]);

  async function edit(event: FormEvent<HTMLFormElement>) {
    const values = form(event);
    try {
      const result = await request<Profile>("/profiles/me", "PUT", {
        displayName: field(values, "name"), bio: field(values, "bio"), interests: field(values, "interests"),
        location: field(values, "location"), avatarUrl: await mediaField(request, values, "avatar"), coverUrl: await mediaField(request, values, "cover"), privateProfile: values.has("private"),
      });
      setProfile(result); update(result); setEditing(false); setError("");
    } catch (error) { setError(message(error)); }
  }

  if (!profile) return <Result error={error} loading={!error} empty text="Profile unavailable" />;
  return <article className="card stack">
    <div className="profile-cover">{profile.cover_url && <MediaImage url={profile.cover_url} request={request} />}</div>
    <div className="row spread"><div className="row"><Avatar name={profile.display_name} url={profile.avatar_url} request={request} /><div><h2>{profile.display_name}</h2><small>@{profile.username}</small></div></div>
      {own && !editing && <button onClick={() => setEditing(true)}>Edit profile</button>}
    </div>
    {error && <p className="error" role="alert">{error}</p>}
    {editing ? <form className="stack" onSubmit={edit}>
      <label>Display name<input name="name" defaultValue={profile.display_name} maxLength={80} required /></label>
      <label>Bio<textarea name="bio" defaultValue={profile.bio ?? ""} maxLength={500} /></label>
      <label>Interests<input name="interests" defaultValue={profile.interests ?? ""} maxLength={200} /></label>
      <label>Location<input name="location" defaultValue={profile.location ?? ""} maxLength={100} /></label>
      <MediaField name="avatar" label="Profile picture" value={profile.avatar_url} />
      <MediaField name="cover" label="Cover photo" value={profile.cover_url} />
      <label className="check"><input name="private" type="checkbox" defaultChecked={profile.private_profile} /> Private profile</label>
      <div className="row"><button className="primary">Save profile</button><button type="button" onClick={() => setEditing(false)}>Cancel</button></div>
    </form> : <>
      {profile.private_profile && profile.bio === null && <p>This profile is visible to connections.</p>}
      {profile.bio && <p className="body-text">{profile.bio}</p>}
      {profile.location && <p>{profile.location}</p>}
      {profile.interests && <p>{profile.interests}</p>}
    </>}
  </article>;
}

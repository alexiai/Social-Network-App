import { mkdir } from "node:fs/promises";
import { join } from "node:path";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

const publicOrigin = process.env.E2E_ORIGIN || "http://localhost:3000";
const mailOrigin = process.env.E2E_MAIL || "http://localhost:8025";
const capture = process.env.E2E_CAPTURE_DIR;

test.use({ viewport: { width: 1366, height: 900 } });

async function screenshot(page: Page, filename: string) {
  if (!capture) return;
  await mkdir(capture, { recursive: true });
  await page.screenshot({ path: join(capture, filename), animations: "disabled" });
}

async function signup(page: Page, request: APIRequestContext, username: string, display: string) {
  const email = username + "@example.test";
  await page.goto("/");
  await page.getByRole("button", { name: "Create an account" }).click();
  await page.getByLabel("Display name").fill(display);
  await page.getByLabel("Username", { exact: true }).fill(username);
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill("test-password-only");
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Check your inbox");

  let mailId = "";
  await expect.poll(async () => {
    const result = await request.get(mailOrigin + "/api/v1/search", { params: { query: "to:" + email } });
    const inbox = await result.json();
    mailId = inbox.messages?.[0]?.ID || "";
    return mailId;
  }, { timeout: 30000 }).not.toBe("");
  const mail = await (await request.get(mailOrigin + "/api/v1/message/" + mailId)).json();
  const token = (mail.Text as string).match(/#token=([A-Za-z0-9_-]+)/)?.[1];
  expect(token).toBeTruthy();
  await page.goto(publicOrigin + "/?action=verify#token=" + token);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByRole("status")).toContainText("Email verified");
  await page.getByLabel("Username or email").fill(username);
  await page.getByLabel("Password", { exact: true }).fill("test-password-only");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("heading", { name: "The campus feed" })).toBeVisible();
}

test("verified users can publish, connect and receive a live message", async ({ page, browser, request }) => {
  const stamp = Date.now().toString(36);
  const post = capture ? "Anyone joining the study meetup this Friday?" : "Campus study meetup " + stamp;
  const bobContext = await browser.newContext({
    baseURL: publicOrigin,
    viewport: { width: 1366, height: 900 },
  });
  try {
    const bob = await bobContext.newPage();
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
    await screenshot(page, "sign-in.png");
    await Promise.all([signup(page, request, "alice_" + stamp, "Alice Meyer"), signup(bob, request, "bob_" + stamp, "Bob Weber")]);
    await expect(page.getByText("Live", { exact: true })).toBeVisible();
    await expect(bob.getByText("Live", { exact: true })).toBeVisible();
    await page.getByLabel("Post text").fill(post);
    await page.getByRole("button", { name: "Publish post" }).click();
    await expect(page.getByText(post)).toBeVisible();
    await screenshot(page, "feed.png");
    await page.getByRole("button", { name: "People", exact: true }).click();
    await page.getByLabel("Search by name or username").fill("bob_" + stamp);
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await page.locator(".person").filter({ hasText: "Bob Weber" }).getByRole("button", { name: "Connect", exact: true }).click();
    await bob.getByRole("button", { name: "Notifications", exact: true }).click();
    await expect(bob.getByText("sent a connection request")).toBeVisible();
    await expect(bob.locator(".notice-count")).toHaveText("1");
    await screenshot(bob, "notifications.png");
    await bob.getByRole("button", { name: "Open connections" }).click();
    await expect(bob.locator(".notice-count")).toHaveCount(0);
    await bob.locator(".person").filter({ hasText: "Alice Meyer" }).getByRole("button", { name: "Accept request" }).click();
    await bob.locator(".person").filter({ hasText: "Alice Meyer" }).getByRole("button", { name: "Message", exact: true }).click();
    await bob.getByLabel("Message text").fill("See you at the study meetup");
    await bob.getByRole("button", { name: "Send message" }).click();
    await page.getByRole("button", { name: "Notifications", exact: true }).click();
    const messageNotice = page.getByRole("article").filter({ hasText: "sent you a message" });
    await messageNotice.getByRole("button", { name: "Open conversation" }).click();
    await expect(page.getByText("See you at the study meetup")).toBeVisible();
    await expect(bob.getByText(/Read by 1/)).toBeVisible();
    await screenshot(bob, "messages.png");
    await expect(page).toHaveURL(/\/messages$/);
    await page.reload();
    await expect(page.getByRole("heading", { name: "Messages" })).toBeVisible();

    const communityName = capture ? "Sketchbook Club" : "Sketchbook club " + stamp;
    await page.getByRole("button", { name: "Communities", exact: true }).click();
    await page.getByText("Create a community", { exact: true }).click();
    await page.getByLabel("Name", { exact: true }).fill(communityName);
    await page.getByLabel("Description", { exact: true }).fill("A private place to share sketches.");
    await page.getByLabel("Invitation only").check();
    await page.getByRole("button", { name: "Create community", exact: true }).click();
    const adminCard = page.getByRole("article").filter({ hasText: communityName });
    await adminCard.getByLabel("Username to invite").fill("bob_" + stamp);
    await adminCard.getByRole("button", { name: "Invite", exact: true }).click();
    await bob.getByRole("button", { name: "Notifications", exact: true }).click();
    await bob.getByRole("button", { name: "Open community", exact: true }).click();
    await bob.getByRole("button", { name: "Accept invitation", exact: true }).click();
    await expect(bob.getByRole("button", { name: "Open discussion", exact: true })).toBeVisible();
    await page.reload();
    await expect(adminCard.getByText("2 members", { exact: true })).toBeVisible();
    await adminCard.getByText("View members", { exact: true }).click();
    await expect(adminCard.getByText("Bob Weber", { exact: true })).toBeVisible();
    if (capture) await page.evaluate(() => window.scrollTo(0, 0));
    await screenshot(page, "communities.png");
    page.once("dialog", dialog => dialog.accept());
    await adminCard.getByRole("button", { name: "Remove member", exact: true }).click();
    await expect(adminCard.getByRole("button", { name: "Remove member", exact: true })).toHaveCount(0);
    await bob.reload();
    await expect(bob.getByText("No communities match this search.")).toBeVisible();
    await expect(bob.getByText(communityName, { exact: true })).toHaveCount(0);

    if (capture) {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.getByRole("button", { name: "Feed", exact: true }).click();
      await expect(page.getByText(post)).toBeVisible();
      const fits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
      expect(fits).toBe(true);
      await screenshot(page, "mobile-feed.png");
    }
  } finally {
    await bobContext.close();
  }
});

test("members can change their password and sign in again", async ({ page, request }) => {
  const username = "settings_" + Date.now().toString(36);
  await signup(page, request, username, "Soleil Member");
  await page.getByRole("button", { name: "Profile", exact: true }).click();
  await page.getByText("Account settings", { exact: true }).click();
  await page.getByLabel("Current password", { exact: true }).fill("test-password-only");
  await page.getByLabel("New password", { exact: true }).fill("updated-test-passphrase");
  await page.getByLabel("Repeat new password", { exact: true }).fill("updated-test-passphrase");
  await page.getByRole("button", { name: "Change password", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("Password changed. Sign in with your new password.");
  await page.getByLabel("Username or email").fill(username);
  await page.getByLabel("Password", { exact: true }).fill("updated-test-passphrase");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Profile", exact: true })).toBeVisible();
});

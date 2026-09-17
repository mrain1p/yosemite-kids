/**
 * The kid page, driven in a real browser.
 *
 * ## Why this exists
 *
 * Two page-level defects shipped in a fortnight and neither could have been
 * caught by anything the repo had. 1.9.0 defined the playlist strip, the
 * See-all page and the playlist page, drove every route with curl — and the one
 * edit that puts the strip on a channel page had silently missed its anchor, so
 * a browser showed no playlists at all. 1.11.0's HD path resolved, streamed and
 * answered every assertion while dash.js sat at 144p for the whole video,
 * because the page's own settings told it a fast hub was a browser cache.
 *
 * Guard 61 lints this page; `HubWebTest` tests the shape of what it sends. Only
 * a browser runs it. So this signs in the way a family does, walks the four
 * screens a child actually uses, and asserts the one thing no unit test can
 * reach: that the rails have cards in them.
 *
 * ## What it must never do
 *
 * Touch the network. The hub it drives is `SmokeHub`, which is `HubServer`
 * with the election and the crawl left null — the two things in `Main` that
 * reach YouTube. The play assertion below depends on that: a request for a
 * video this kid may not see has to come back `403` with a policy reason, and
 * a `502` would mean the gate let it through and the hub went looking for a
 * stream. That is the check-ordering `HubMediaRouteTest` states in Kotlin,
 * stated again from the outside.
 *
 * Run: node scripts/smoke/smoke.mjs <baseUrl> <kidId> <password> <hiddenVideoId>
 */

import { chromium } from "playwright";

const [baseUrl, kidId, password, hiddenVideo] = process.argv.slice(2);
if (!baseUrl || !kidId || !password || !hiddenVideo) {
  console.error("usage: smoke.mjs <baseUrl> <kidId> <password> <hiddenVideoId>");
  process.exit(2);
}

const failures = [];
function check(name, ok, detail) {
  if (ok) {
    console.log(`  ok   ${name}`);
  } else {
    console.log(`  FAIL ${name}${detail ? " — " + detail : ""}`);
    failures.push(name);
  }
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 420, height: 900 } });

// A page error is a failure even if every assertion below passes: the page is
// written to report its own errors, and a silent one is the class of defect
// this file exists for.
const pageErrors = [];
page.on("pageerror", (e) => pageErrors.push(String(e)));
page.on("console", (m) => {
  if (m.type() === "error") pageErrors.push(m.text());
});

try {
  await page.goto(baseUrl + "/kid", { waitUntil: "domcontentloaded" });

  // --- signing in, the way a family does --------------------------------
  await page.waitForSelector("#claim:not([hidden])", { timeout: 15000 });
  const avatar = page.locator(`#whoGrid [data-kid="${kidId}"]`);
  if (await avatar.count()) {
    await avatar.first().click();
  } else {
    // Older markup put the children in plain buttons; the name is enough.
    await page.locator("#whoGrid button").first().click();
  }
  await page.fill("#password", password);
  await page.click("#go");
  await page.waitForSelector("#app:not([hidden])", { timeout: 15000 });
  check("signing in with a kid's password reaches the app", true);

  // --- the home, and its rails ------------------------------------------
  await page.waitForFunction(
    () => document.querySelectorAll("#main .card").length > 0,
    null,
    { timeout: 15000 }
  );
  const homeCards = await page.locator("#main .card").count();
  check("the home draws cards", homeCards > 0, `${homeCards} cards`);
  const homeText = await page.locator("#main").innerText();
  check(
    "and only this kid's channels are in them",
    !homeText.includes("Only Sam may watch this"),
    "a video from a channel this kid may not see is on the home screen"
  );

  // --- channels ----------------------------------------------------------
  await page.click("#tab-channels");
  await page.waitForFunction(
    () => document.querySelectorAll("#main .card").length > 0,
    null,
    { timeout: 15000 }
  );
  const channelCards = await page.locator("#main .card.chan").count();
  check("the channels tab draws the family's channels", channelCards > 0, `${channelCards}`);

  // --- a channel page ----------------------------------------------------
  await page.locator("#main .card.chan").first().click();
  await page.waitForFunction(
    () => document.querySelectorAll("#main .card").length > 0,
    null,
    { timeout: 15000 }
  );
  const onChannel = await page.locator("#main .card").count();
  check("a channel page draws its videos", onChannel > 0, `${onChannel} cards`);

  // --- the You tab -------------------------------------------------------
  await page.click("#tab-you");
  await page.waitForTimeout(800);
  const youText = await page.locator("#main").innerText();
  check("the You tab draws its shelves", youText.length > 0);

  // --- search ------------------------------------------------------------
  await page.click("#tab-home");
  await page.waitForTimeout(400);
  await page.fill("#q", "otters");
  await page.keyboard.press("Enter");
  await page.waitForTimeout(1200);
  const searchText = await page.locator("#main").innerText();
  check(
    "a search answers",
    searchText.length > 0 && !searchText.includes("Only Sam"),
    "a hidden channel's video appeared in search"
  );

  // --- the gate runs before anything is resolved -------------------------
  const gate = await page.evaluate(async (v) => {
    const r = await fetch("/kid/media?v=" + v, {
      credentials: "same-origin",
      headers: { Range: "bytes=0-1" }
    });
    let body = null;
    try { body = await r.json(); } catch (e) { body = null; }
    return { status: r.status, error: body && body.error, say: body && body.say };
  }, hiddenVideo);
  check(
    "a video this kid may not see is refused by the rules, not by a failed fetch",
    gate.status === 403 && !!gate.error,
    `status ${gate.status}, error ${gate.error}`
  );
  check(
    "and the refusal carries words a child can read",
    typeof gate.say === "string" && gate.say.length > 0,
    JSON.stringify(gate.say)
  );

  check("the page logged no errors of its own", pageErrors.length === 0, pageErrors.join(" | "));
} catch (e) {
  check("the walk finished", false, String(e));
} finally {
  await browser.close();
}

if (failures.length) {
  console.error(`\n${failures.length} smoke check(s) failed: ${failures.join(", ")}`);
  process.exit(1);
}
console.log("\nkid page smoke: all green");

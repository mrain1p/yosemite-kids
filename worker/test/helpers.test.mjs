// Pure helpers of the suggestion worker. Run: node --test worker/test/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  parseSuggestionUrl, isDuplicate, normalizeLang, neutralizeMentions, contactTitle, readBounded,
} from '../submit-worker.js';

test('parseSuggestionUrl accepts the forms parents paste', () => {
  assert.equal(parseSuggestionUrl('https://www.youtube.com/@TEDEd').key, '@teded');
  assert.equal(parseSuggestionUrl('youtube.com/@TEDEd/videos').url, 'https://www.youtube.com/@TEDEd');
  assert.equal(parseSuggestionUrl('https://m.youtube.com/channel/UCsooa4yRKGN_zEE8iknghZA').channelId, 'UCsooa4yRKGN_zEE8iknghZA');
  assert.equal(parseSuggestionUrl('https://www.youtube.com/user/scishow').display, 'scishow');
  const pl = parseSuggestionUrl('https://www.youtube.com/watch?v=abc123&list=PLxyz_ABC-123456');
  assert.equal(pl.kind, 'playlist');
  assert.equal(pl.playlistId, 'PLxyz_ABC-123456');
});

test('parseSuggestionUrl rejects what is not a channel or playlist', () => {
  assert.equal(parseSuggestionUrl('https://youtu.be/abc123'), null);
  assert.equal(parseSuggestionUrl('https://www.youtube.com/watch?v=abc123'), null);
  assert.equal(parseSuggestionUrl('https://vimeo.com/channels/staffpicks'), null);
  assert.equal(parseSuggestionUrl('not a url at all'), null);
  assert.equal(parseSuggestionUrl(''), null);
});

test('isDuplicate matches handles case-insensitively and playlists exactly', () => {
  const entries = [{ url: 'https://www.youtube.com/@TEDEd', channelId: 'UCsooa4yRKGN_zEE8iknghZA' }];
  assert.equal(isDuplicate(parseSuggestionUrl('https://www.youtube.com/@teded'), {}, entries, []), true);
  // A UC-form submission of the same channel matches through the stored id.
  assert.equal(isDuplicate(parseSuggestionUrl('https://www.youtube.com/channel/UCsooa4yRKGN_zEE8iknghZA'), {}, entries, []), true);
  // Different playlist ids differ even when they only differ by case.
  const pls = [{ url: 'https://www.youtube.com/playlist?list=PLabcdefghij' }];
  assert.equal(isDuplicate(parseSuggestionUrl('https://www.youtube.com/playlist?list=PLABCDEFGHIJ'), {}, pls, []), false);
  assert.equal(isDuplicate(parseSuggestionUrl('https://www.youtube.com/playlist?list=PLabcdefghij'), {}, pls, []), true);
  // Open PRs count as duplicates by body text.
  assert.equal(isDuplicate(parseSuggestionUrl('https://www.youtube.com/@newone'), {}, [], [{ body: 'https://www.youtube.com/@NewOne' }]), true);
});

test('normalizeLang keeps real ISO 639-1 codes only', () => {
  assert.deepEqual(normalizeLang('EN'), { code: 'en', name: 'English' });
  assert.equal(normalizeLang('zz'), null);
  assert.equal(normalizeLang('eng'), null);
  assert.equal(normalizeLang(''), null);
});

test('neutralizeMentions defuses @user without changing how it reads', () => {
  assert.equal(neutralizeMentions('thanks @octocat and @you-2'), 'thanks @<!---->octocat and @<!---->you-2');
  assert.equal(neutralizeMentions('mail me at a@b.com'), 'mail me at a@<!---->b.com');
  assert.equal(neutralizeMentions('lone @ sign'), 'lone @ sign');
});

test('contactTitle clamps the first line', () => {
  assert.equal(contactTitle('Short one\nmore', 'problem'), 'Short one');
  assert.equal(contactTitle('', 'idea'), 'Idea from the website');
  const long = 'x'.repeat(100);
  assert.equal(contactTitle(long, 'problem').length, 70);
});

test('readBounded refuses a body past the cap even without Content-Length', async () => {
  const chunk = new TextEncoder().encode('{"urls":["' + 'a'.repeat(1000) + '"]}');
  const big = new Request('http://x/', {
    method: 'POST',
    body: new ReadableStream({
      start(c) { for (let i = 0; i < 40; i++) c.enqueue(chunk); c.close(); },
    }),
    duplex: 'half',
  });
  assert.equal(await readBounded(big, 32768), null);
  const small = new Request('http://x/', { method: 'POST', body: '{"ok":true}' });
  assert.equal(await readBounded(small, 32768), '{"ok":true}');
});

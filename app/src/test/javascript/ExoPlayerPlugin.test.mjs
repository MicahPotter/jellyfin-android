import { afterEach, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { ExoPlayerPlugin } from '../../main/assets/native/ExoPlayerPlugin.js';

let player;
let native;
let playbackManager;
beforeEach(() => {
    native = { isEnabled: () => true, hasDownload: (id, source) => id === 'saved' && (!source || source === 'original') };
    globalThis.window = { NativePlayer: native };
    playbackManager = { syncPlayEnabled: false };
    player = new ExoPlayerPlugin({ events: {}, playbackManager, loading: { hide() {} } });
});
afterEach(() => { delete globalThis.window; });

test('normal Play selects a saved item by ID before server metadata is needed', () => {
    assert.deepEqual(player.getLocalPlaybackItems({ ids: ['saved'], serverId: 'server', fullscreen: true }), [
        { Id: 'saved', MediaType: 'Video', ServerId: 'server' }
    ]);
});

test('a mixed queue checks the selected title and retains the remaining queue', () => {
    const items = [{ Id: 'streamed' }, { Id: 'saved' }];
    assert.equal(player.getLocalPlaybackItems({ items, startIndex: 1, fullscreen: true }), items);
    assert.equal(player.getLocalPlaybackItems({ items, startIndex: 0, fullscreen: true }), null);
});

test('missing downloads and a different selected version retain streaming', () => {
    assert.equal(player.getLocalPlaybackItems({ ids: ['missing'], fullscreen: true }), null);
    assert.equal(player.getLocalPlaybackItems({ ids: ['saved'], fullscreen: true, mediaSourceId: 'alternate' }), null);
});

test('SyncPlay and non-fullscreen requests are not intercepted', () => {
    assert.equal(player.getLocalPlaybackItems({ ids: ['saved'], fullscreen: false }), null);
    playbackManager.syncPlayEnabled = true;
    assert.equal(player.getLocalPlaybackItems({ ids: ['saved'], fullscreen: true }), null);
});

test('normal player load preserves resume and track options without requiring a download-only flag', async () => {
    let loaded;
    native.loadPlayer = json => { loaded = JSON.parse(json); };
    await player.play({ items: [{ Id: 'saved' }], startPositionTicks: 120000000, audioStreamIndex: 2, subtitleStreamIndex: -1 });
    assert.deepEqual(loaded.ids, ['saved']);
    assert.equal(loaded.startPositionTicks, 120000000);
    assert.equal(loaded.audioStreamIndex, 2);
    assert.equal(loaded.subtitleStreamIndex, -1);
    assert.equal(loaded.playFromDownloads, undefined);
});

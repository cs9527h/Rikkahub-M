import assert from "node:assert/strict";
import { test } from "node:test";
import { createStore } from "zustand/vanilla";
import { createChatInputSlice } from "../app/stores/slices/chat-input-slice";
import type { AppStoreState } from "../app/stores/slices/types";
import { finishDraftSubmission, submitDraft } from "../app/lib/draft-submission";
import { uploadInputFiles } from "../app/lib/upload-input";
import { applyNodeUpdate, subscribeConversationDetail, waitForReconnect } from "../app/services/conversation-stream";
import type { ConversationStreamEvent, DetailConnectionStatus } from "../app/services/conversation-stream";
import type { SSECallbacks } from "../app/services/api";
import type { ConversationDto, ConversationSnapshotEventDto, ConversationNodeUpdateEventDto } from "../app/types";

function deferred<T = void>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function store() {
  return createStore<AppStoreState>()((...args) => ({
    settings: null, setSettings: () => {}, clockOffset: 0, setClockOffset: () => {},
    ...createChatInputSlice(...args),
  }));
}
const attachment = { type: "image" as const, url: "file://draft.png" };

test("21: rejected first send leaves the same visible home text and attachments", async () => {
  const state = store();
  state.getState().setText("home", "first draft");
  state.getState().addParts("home", [attachment]);
  const before = state.getState().drafts.home;
  let rotated = false;
  await assert.rejects(async () => {
    const sent = await submitDraft(state.getState, "home", async () => { throw new Error("offline"); }, "new-chat");
    if (sent) rotated = true;
  }, /offline/);
  assert.equal(rotated, false);
  assert.equal(state.getState().drafts.home, before);
  assert.equal(state.getState().drafts["new-chat"], undefined);
});

test("22: acceptance clears only the submitted revision; text typed in flight survives", async () => {
  const state = store();
  state.getState().setText("chat", "submitted");
  const response = deferred();
  const sending = submitDraft(state.getState, "chat", async (parts) => {
    assert.deepEqual(parts, [{ type: "text", text: "submitted" }]);
    await response.promise;
  });
  state.getState().setText("chat", "new input");
  state.getState().addParts("chat", [attachment]);
  response.resolve();
  assert.equal(await sending, true);
  assert.equal(state.getState().drafts.chat.text, "new input");
  assert.deepEqual(state.getState().drafts.chat.parts, [attachment]);
});

test("21/22: first-send successor receives in-flight edits after acceptance", async () => {
  const state = store();
  state.getState().setText("home", "submitted");
  const response = deferred();
  const sending = submitDraft(state.getState, "home", () => response.promise, "new-chat");
  state.getState().setText("home", "next message");
  state.getState().addParts("home", [attachment]);
  response.resolve();
  await sending;
  assert.equal(state.getState().drafts.home, undefined);
  assert.deepEqual(state.getState().drafts["new-chat"], { text: "next message", parts: [attachment] });
});

test("22: unchanged accepted draft clears; changed edit/attachment revision stays", async () => {
  const state = store();
  state.getState().setText("chat", "edit");
  const edit = state.getState().drafts.chat;
  state.getState().addParts("chat", [attachment]);
  assert.equal(finishDraftSubmission(state.getState(), "chat", edit), false);
  assert.deepEqual(state.getState().drafts.chat.parts, [attachment]);
  await submitDraft(state.getState, "chat", async () => {});
  assert.equal(state.getState().drafts.chat, undefined);
});

test("22: change then restore identical text is still a new revision", () => {
  const state = store();
  state.getState().setText("chat", "edit");
  const edit = state.getState().drafts.chat;
  state.getState().setText("chat", "new");
  state.getState().setText("chat", "edit");
  assert.equal(finishDraftSubmission(state.getState(), "chat", edit), false);
  assert.equal(state.getState().drafts.chat.text, "edit");
});

for (const fails of [false, true]) {
  test(`23: upload ${fails ? "failure" : "success"} resets saved input after React clears currentTarget`, async () => {
    const input = { value: "same.png", files: { length: 1 } } as HTMLInputElement;
    const event = { currentTarget: input };
    const response = deferred();
    const upload = uploadInputFiles(event, async (files) => {
      assert.equal(files, input.files);
      await response.promise;
    });
    // React performs this after synchronous dispatch, before the upload resolves.
    Object.defineProperty(event, "currentTarget", { value: null });
    if (fails) response.reject(new Error("upload failed")); else response.resolve();
    if (fails) await assert.rejects(upload, /upload failed/); else await upload;
    assert.equal(input.value, "");
    input.value = "same.png";
    await uploadInputFiles({ currentTarget: input }, async () => {});
    assert.equal(input.value, "", "same file can be selected a second time");
  });
}

function snapshot(seq = 1, title = "snapshot"): ConversationSnapshotEventDto {
  return { type: "snapshot", seq, serverTime: 100, conversation: {
    id: "chat", assistantId: "assistant", title, messages: [], chatSuggestions: [],
    isPinned: false, createAt: 0, updateAt: 1, isGenerating: false,
  } };
}
function delta(seq = 2): ConversationNodeUpdateEventDto {
  return { type: "node_update", seq, conversationId: "chat", nodeId: "node", nodeIndex: 0,
    node: { id: "node", messages: [], selectIndex: 0 }, updateAt: 2, isGenerating: true, serverTime: 100 };
}
function emit(callbacks: SSECallbacks<ConversationStreamEvent>, data: ConversationStreamEvent) {
  callbacks.onMessage({ event: data.type, data });
}

test("24/25: EOF reconnect uses fresh authoritative snapshot, gates deltas and ignores old sequence", async () => {
  const abort = new AbortController();
  const waits: number[] = [];
  const statuses: DetailConnectionStatus[] = [];
  let connects = 0;
  let detail: ConversationDto = snapshot().conversation;
  const seen: string[] = [];
  await subscribeConversationDetail({
    id: "chat", signal: abort.signal,
    onSnapshot: ({ conversation }) => { detail = conversation; seen.push(conversation.title); },
    onNodeUpdate: (event) => { detail = applyNodeUpdate(detail, event); },
    onError: (error) => { throw error; }, onStatus: (status) => statuses.push(status),
    wait: async (delay) => { waits.push(delay); },
    connect: async (_url, callbacks) => {
      connects++;
      emit(callbacks, delta(99)); // no baseline yet; must not poison sequence
      emit(callbacks, snapshot(1, connects === 1 ? "initial" : "authoritative"));
      emit(callbacks, delta(2));
      emit(callbacks, snapshot(1, "stale"));
      if (connects === 2) abort.abort();
    },
  });
  assert.equal(connects, 2);
  assert.deepEqual(waits, [500]);
  assert.deepEqual(seen, ["initial", "authoritative"]);
  assert.equal(detail.title, "authoritative");
  assert.equal(detail.messages.length, 1);
  assert.ok(statuses.includes("reconnecting"));
});

test("24: failed connections back off with cap; abort cancels retry and old callbacks", async () => {
  const abort = new AbortController();
  const waits: number[] = [];
  let stale!: SSECallbacks<ConversationStreamEvent>;
  let snapshots = 0;
  await subscribeConversationDetail({
    id: "chat", signal: abort.signal, onSnapshot: () => snapshots++,
    onNodeUpdate: () => {}, onError: () => {}, onStatus: () => {},
    connect: async (_url, callbacks) => { stale = callbacks; throw new Error("network"); },
    wait: async (delay) => { waits.push(delay); if (waits.length === 8) abort.abort(); },
  });
  emit(stale, snapshot());
  assert.equal(snapshots, 0);
  assert.deepEqual(waits, [500, 1000, 2000, 4000, 8000, 15000, 15000, 15000]);
});

test("24: cancellation interrupts the real retry timer immediately", async () => {
  const abort = new AbortController();
  const waiting = waitForReconnect(60_000, abort.signal);
  abort.abort();
  await waiting;
});

test("24/25: real SSE transport recovers from EOF without starting a stale HTTP snapshot", async () => {
  const OriginalRequest = globalThis.Request;
  const originalFetch = globalThis.fetch;
  const abort = new AbortController();
  const requests: string[] = [];
  let detail: ConversationDto | undefined;
  // Browser Request accepts relative URLs; supply the browser base in the Node runner.
  globalThis.Request = class extends OriginalRequest {
    constructor(input: RequestInfo | URL, init?: RequestInit) {
      super(typeof input === "string" ? new URL(input, "http://web.test") : input, init);
    }
  };
  globalThis.fetch = async (input) => {
    const url = input instanceof OriginalRequest ? input.url : String(input);
    requests.push(url);
    assert.equal(new URL(url).pathname, "/api/conversations/chat/stream");
    const value = snapshot(1, requests.length === 1 ? "before disconnect" : "recovered");
    return new Response(`event: snapshot\ndata: ${JSON.stringify(value)}\n\n`, {
      headers: { "Content-Type": "text/event-stream" },
    });
  };
  try {
    await subscribeConversationDetail({
      id: "chat", signal: abort.signal, onNodeUpdate: () => {}, onError: (error) => { throw error; },
      onStatus: () => {}, wait: async () => {},
      onSnapshot: (event) => {
        detail = event.conversation;
        if (event.conversation.title === "recovered") abort.abort();
      },
    });
    assert.equal(detail?.title, "recovered");
    assert.equal(requests.length, 2, "only SSE snapshots initialize detail, no parallel HTTP race");
  } finally {
    globalThis.Request = OriginalRequest;
    globalThis.fetch = originalFetch;
  }
});

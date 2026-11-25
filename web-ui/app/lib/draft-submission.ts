import type { ChatInputSlice, Draft } from "~/stores/slices/types";

/** Object identity is the draft revision: every text/attachment mutation replaces it. */
export function finishDraftSubmission(
  store: Pick<ChatInputSlice, "drafts" | "clearDraft" | "setText" | "addParts">,
  key: string,
  submitted: Draft | undefined,
  successorKey?: string,
): boolean {
  const current = store.drafts[key];
  if (current === submitted) {
    store.clearDraft(key);
    return true;
  }
  // On first send, keep edits made in flight visible after navigating to the new chat.
  if (successorKey && current) {
    store.setText(successorKey, current.text);
    store.addParts(successorKey, current.parts);
    store.clearDraft(key);
  }
  return false;
}

export async function submitDraft(
  getStore: () => ChatInputSlice,
  key: string,
  send: (parts: ReturnType<ChatInputSlice["getSubmitParts"]>) => Promise<unknown>,
  successorKey?: string,
): Promise<boolean> {
  const submitted = getStore().drafts[key];
  const parts = getStore().getSubmitParts(key);
  if (parts.length === 0) return false;
  await send(parts);
  finishDraftSubmission(getStore(), key, submitted, successorKey);
  return true;
}

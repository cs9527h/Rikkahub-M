import { sse, type SSECallbacks } from "./api";
import type {
  ConversationDto,
  ConversationErrorEventDto,
  ConversationNodeUpdateEventDto,
  ConversationSnapshotEventDto,
} from "~/types";

export type ConversationStreamEvent =
  | ConversationSnapshotEventDto
  | ConversationNodeUpdateEventDto
  | ConversationErrorEventDto;
export type DetailConnectionStatus = "connecting" | "connected" | "reconnecting";

type Connect = (
  url: string,
  callbacks: SSECallbacks<ConversationStreamEvent>,
  options: { signal: AbortSignal },
) => Promise<void>;

export function waitForReconnect(delay: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve();
    const finish = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", finish);
      resolve();
    };
    const timer = setTimeout(finish, delay);
    signal.addEventListener("abort", finish, { once: true });
  });
}

/** The server sends a full authoritative snapshot first on EVERY new connection.
 * Do not race it against HTTP, or apply deltas against the previous connection's state.
 */
export async function subscribeConversationDetail({
  id,
  signal,
  onSnapshot,
  onNodeUpdate,
  onError,
  onConversationError,
  onStatus,
  connect = sse<ConversationStreamEvent>,
  wait = waitForReconnect,
}: {
  id: string;
  signal: AbortSignal;
  onSnapshot: (event: ConversationSnapshotEventDto) => void;
  onNodeUpdate: (event: ConversationNodeUpdateEventDto) => void;
  onError: (error: Error) => void;
  onConversationError?: (message: string) => void;
  onStatus: (status: DetailConnectionStatus) => void;
  connect?: Connect;
  wait?: (delay: number, signal: AbortSignal) => Promise<void>;
}): Promise<void> {
  let failures = 0;
  while (!signal.aborted) {
    let hasSnapshot = false;
    let lastSequence = -1;
    onStatus(failures === 0 ? "connecting" : "reconnecting");
    try {
      await connect(`conversations/${id}/stream`, {
        onMessage: ({ event, data }) => {
          if (signal.aborted) return;
          if (event === "error" && data.type === "error") {
            onConversationError?.(data.message);
          } else if (
            event === "snapshot" && data.type === "snapshot" &&
            data.conversation.id === id && data.seq > lastSequence
          ) {
            lastSequence = data.seq;
            hasSnapshot = true;
            onSnapshot(data);
            onStatus("connected");
          } else if (
            event === "node_update" && data.type === "node_update" &&
            hasSnapshot && data.conversationId === id && data.seq > lastSequence
          ) {
            lastSequence = data.seq;
            onNodeUpdate(data);
          }
        },
        onError: (error) => { if (!signal.aborted) onError(error); },
      }, { signal });
    } catch (error) {
      if (!signal.aborted) onError(error instanceof Error ? error : new Error(String(error)));
    }
    if (signal.aborted) return;
    // Even a clean EOF is a disconnect; cap exponential backoff to 15 seconds.
    failures = hasSnapshot ? 1 : failures + 1;
    onStatus("reconnecting");
    await wait(Math.min(500 * 2 ** Math.min(failures - 1, 5), 15_000), signal);
  }
}

export function applyNodeUpdate(
  conversation: ConversationDto,
  event: ConversationNodeUpdateEventDto,
): ConversationDto {
  if (conversation.id !== event.conversationId) return conversation;
  const nextNodes = [...conversation.messages];
  const indexById = nextNodes.findIndex((node) => node.id === event.nodeId);
  const targetIndex = indexById >= 0 ? indexById : event.nodeIndex;
  if (targetIndex < 0 || targetIndex > nextNodes.length) return conversation;
  nextNodes[targetIndex] = event.node;
  return { ...conversation, messages: nextNodes, updateAt: event.updateAt, isGenerating: event.isGenerating };
}

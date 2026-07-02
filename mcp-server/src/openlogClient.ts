// Thin fetch() wrapper around ControlServer.kt's HTTP endpoints. This file is the only place
// that knows the wire format — it never touches the Kotlin/JVM process directly, so any MCP
// client (or plain curl) against the same base URL works identically.

import { randomUUID } from "node:crypto";

const BASE_URL = process.env.OPENLOG_CONTROL_URL ?? "http://127.0.0.1:8991";
// One random id per process launch — lets openLog's Settings/Connection-info popup show which
// tools are currently talking to it and block one if unwanted. OPENLOG_CLIENT_NAME is an optional
// human-readable label the user can set in their MCP client's own config (see the "Copy config"
// snippet in that popup); plain curl callers never send these headers and so never show up there.
const CLIENT_ID = randomUUID();
const CLIENT_NAME = process.env.OPENLOG_CLIENT_NAME ?? "MCP client";

async function request(method: string, path: string, body?: unknown): Promise<unknown> {
  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      "X-OpenLog-Client-Id": CLIENT_ID,
      "X-OpenLog-Client-Name": CLIENT_NAME,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch {
    return { error: `non-JSON response (status ${res.status}): ${text}` };
  }
}

export const openlogClient = {
  listTabs: () => request("GET", "/tabs"),

  openLogFile: (path: string, entryPath?: string) =>
    request("POST", "/open", entryPath !== undefined ? { path, entryPath } : { path }),

  closeTab: (tabId: string) => request("POST", "/close", { tabId }),

  getFilter: (tabId: string) => request("GET", `/filter?tabId=${encodeURIComponent(tabId)}`),

  setFilter: (tabId: string, filter: Record<string, unknown>) =>
    request("POST", "/filter", { tabId, ...filter }),

  getVisibleLines: (tabId: string, limit?: number, offset?: number) => {
    const params = new URLSearchParams({ tabId });
    if (limit !== undefined) params.set("limit", String(limit));
    if (offset !== undefined) params.set("offset", String(offset));
    return request("GET", `/visible?${params.toString()}`);
  },

  toggleGroup: (tabId: string, gid: string) => request("POST", "/toggle", { tabId, gid }),

  getTags: (tabId: string) => request("GET", `/tags?tabId=${encodeURIComponent(tabId)}`),

  getCrashSites: (tabId: string) => request("GET", `/crashes?tabId=${encodeURIComponent(tabId)}`),

  mergeTabs: (tabIds: string[], newTabName?: string) =>
    request("POST", "/merge", newTabName !== undefined ? { tabIds, newTabName } : { tabIds }),

  startTailing: (tabId: string) => request("POST", "/tail/start", { tabId }),

  stopTailing: (tabId: string) => request("POST", "/tail/stop", { tabId }),
};

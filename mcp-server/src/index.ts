#!/usr/bin/env node
// MCP server entrypoint. Registers tools that drive a running openLog instance through its
// localhost debug control server (see ../../src/desktopMain/kotlin/com/openlog/debug/ControlServer.kt).
// Deliberately no generic "eval any AppState method" tool — the surface below is the whole
// contract, kept small and self-documenting.

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { openlogClient } from "./openlogClient.js";

const server = new McpServer({
  name: "openlog-control",
  version: "0.1.0",
});

function jsonResult(data: unknown) {
  return { content: [{ type: "text" as const, text: JSON.stringify(data, null, 2) }] };
}

server.registerTool(
  "list_tabs",
  {
    title: "List open tabs",
    description: "List every tab currently open in the running openLog app.",
    inputSchema: {},
  },
  async () => jsonResult(await openlogClient.listTabs()),
);

server.registerTool(
  "open_log_file",
  {
    title: "Open a log file",
    description:
      "Open a plain logcat file, or a bug-report .zip, at the given absolute path. For a plain file, blocks until parsing completes and returns the new tab's id. For a .zip: if it contains exactly one candidate log, that one opens automatically; if it contains none, returns an error; if it contains several, returns { needsSelection: true, candidates: [...] } without opening anything — call again with the same path plus entryPath set to one candidate's entryPath to open that one.",
    inputSchema: {
      path: z.string().describe("Absolute path to the log file or .zip"),
      entryPath: z.string().optional().describe("For a multi-candidate .zip: the entryPath of the candidate to open"),
    },
  },
  async ({ path, entryPath }) => jsonResult(await openlogClient.openLogFile(path, entryPath)),
);

server.registerTool(
  "close_tab",
  {
    title: "Close a tab",
    description: "Close the tab with the given id.",
    inputSchema: { tabId: z.string() },
  },
  async ({ tabId }) => jsonResult(await openlogClient.closeTab(tabId)),
);

server.registerTool(
  "get_filter",
  {
    title: "Get filter state",
    description: "Read the current filter (levels, tags, keyword, mode) for a tab.",
    inputSchema: { tabId: z.string() },
  },
  async ({ tabId }) => jsonResult(await openlogClient.getFilter(tabId)),
);

server.registerTool(
  "set_filter",
  {
    title: "Set filter state",
    description: "Partially update a tab's filter — only the fields you provide are changed.",
    inputSchema: {
      tabId: z.string(),
      levels: z
        .array(z.string())
        .optional()
        .describe('Log level keys to include, e.g. ["I","W","E"]'),
      activeTags: z.array(z.string()).optional(),
      kwText: z.string().optional(),
      kwRegex: z.boolean().optional(),
      mode: z.enum(["TAGS", "KEYWORD"]).optional(),
    },
  },
  async ({ tabId, ...rest }) => jsonResult(await openlogClient.setFilter(tabId, rest)),
);

server.registerTool(
  "get_visible_lines",
  {
    title: "Get visible lines",
    description:
      "Read the currently rendered log items for a tab — after filtering and sequence/manual-collapse/stack-trace folding, i.e. what a user would actually see. Each item has a `type` (Row, SeqHeader, ManualHeader, or StackTraceHeader).",
    inputSchema: {
      tabId: z.string(),
      limit: z.number().int().optional(),
      offset: z.number().int().optional(),
    },
  },
  async ({ tabId, limit, offset }) => jsonResult(await openlogClient.getVisibleLines(tabId, limit, offset)),
);

server.registerTool(
  "toggle_group",
  {
    title: "Toggle a collapsible group",
    description: "Expand or collapse a sequence/manual-collapse group by its gid (from get_visible_lines).",
    inputSchema: { tabId: z.string(), gid: z.string() },
  },
  async ({ tabId, gid }) => jsonResult(await openlogClient.toggleGroup(tabId, gid)),
);

server.registerTool(
  "get_tags",
  {
    title: "Get all tags",
    description: "List every distinct tag present in a tab's full log file (not just the currently filtered set).",
    inputSchema: { tabId: z.string() },
  },
  async ({ tabId }) => jsonResult(await openlogClient.getTags(tabId)),
);

server.registerTool(
  "get_crash_sites",
  {
    title: "Get crash and ANR sites",
    description:
      "List every detected exception (FATAL EXCEPTION / bare exception header) and ANR in a tab's full log file, each with the log id to jump to. Detected on the whole file regardless of the active filter.",
    inputSchema: { tabId: z.string() },
  },
  async ({ tabId }) => jsonResult(await openlogClient.getCrashSites(tabId)),
);

server.registerTool(
  "merge_tabs",
  {
    title: "Merge tabs",
    description:
      "Merge 2 or more already-open tabs into one new tab, interleaved by time-of-day (not calendar-aware — entries are compared purely by HH:mm:ss.SSS, so this is only correct when the sources span a single day). Each merged row is tagged with which source tab's filename it came from.",
    inputSchema: {
      tabIds: z.array(z.string()).min(2).describe("ids of the tabs to merge (from list_tabs)"),
      newTabName: z.string().optional().describe('Name for the new merged tab (default "Merged")'),
    },
  },
  async ({ tabIds, newTabName }) => jsonResult(await openlogClient.mergeTabs(tabIds, newTabName)),
);

const transport = new StdioServerTransport();
await server.connect(transport);

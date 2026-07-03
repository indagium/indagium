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
      "Open a plain logcat file, or a bug-report .zip, at the given absolute path. For a plain file, blocks until parsing completes and returns the new tab's id. For a .zip: if it contains exactly one candidate log, that one opens automatically; if it contains none, returns an error; if it contains several, returns { needsSelection: true, candidates: [...] } without opening anything — call again with the same path plus entryPath set to one candidate's entryPath to open that one. Oversized sources return { needsSplit: true, ... } instead of opening; use split_log_file to split and open parts.",
    inputSchema: {
      path: z.string().describe("Absolute path to the log file or .zip"),
      entryPath: z.string().optional().describe("For a multi-candidate .zip: the entryPath of the candidate to open"),
      splitMode: z
        .enum(["split", "open_as_is"])
        .optional()
        .describe("For oversized sources: split and open generated parts, or bypass the split prompt and open the original"),
      destinationDir: z.string().optional().describe("When splitMode is split, destination directory for generated parts"),
      postfix: z.string().optional().describe('When splitMode is split, output postfix before the part number; default "part"'),
      partCount: z.number().int().positive().optional().describe("When splitMode is split, number of line-preserving parts"),
    },
  },
  async ({ path, entryPath, splitMode, destinationDir, postfix, partCount }) =>
    jsonResult(await openlogClient.openLogFile(path, { entryPath, splitMode, destinationDir, postfix, partCount })),
);

server.registerTool(
  "preview_split_log_file",
  {
    title: "Preview log split",
    description:
      "Return split metadata for a real log file or archive entry without opening it: size, suggested part count, default destination, default postfix, and source identifiers.",
    inputSchema: {
      path: z.string().describe("Absolute path to the log file or archive"),
      entryPath: z.string().optional().describe("Archive entry path when splitting a file inside an archive"),
    },
  },
  async ({ path, entryPath }) => jsonResult(await openlogClient.previewSplitLogFile(path, entryPath)),
);

server.registerTool(
  "split_log_file",
  {
    title: "Split and open log",
    description:
      "Split a real log file or archive entry into line-preserving plain log files, save them to the destination directory, and open the generated parts as tabs. Existing outputs are not overwritten.",
    inputSchema: {
      path: z.string().describe("Absolute path to the log file or archive"),
      entryPath: z.string().optional().describe("Archive entry path when splitting a file inside an archive"),
      destinationDir: z.string().optional().describe("Destination directory for generated parts; defaults to the app's split destination"),
      postfix: z.string().optional().describe('Output postfix between base name and part number, default "part"'),
      partCount: z.number().int().positive().optional().describe("Number of line-preserving parts to create"),
    },
  },
  async ({ path, entryPath, destinationDir, postfix, partCount }) =>
    jsonResult(await openlogClient.splitLogFile(path, entryPath, destinationDir, postfix, partCount)),
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
  "expand_all",
  {
    title: "Expand all collapsible groups",
    description: "Expand every currently visible collapsible sequence/manual/stack-trace group in a tab.",
    inputSchema: { tabId: z.string() },
  },
  async ({ tabId }) => jsonResult(await openlogClient.expandAll(tabId)),
);

server.registerTool(
  "collapse_all",
  {
    title: "Collapse all groups",
    description: "Collapse every expanded sequence/manual/stack-trace group in a tab.",
    inputSchema: { tabId: z.string() },
  },
  async ({ tabId }) => jsonResult(await openlogClient.collapseAll(tabId)),
);

server.registerTool(
  "select_lines",
  {
    title: "Select log lines",
    description: "Replace a tab's current selected log line ids.",
    inputSchema: { tabId: z.string(), lineIds: z.array(z.number().int()) },
  },
  async ({ tabId, lineIds }) => jsonResult(await openlogClient.selectLines(tabId, lineIds)),
);

server.registerTool(
  "get_selection",
  {
    title: "Get selected lines",
    description: "Return the selected log line ids for a tab.",
    inputSchema: { tabId: z.string() },
  },
  async ({ tabId }) => jsonResult(await openlogClient.getSelection(tabId)),
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
  "add_text_note",
  {
    title: "Add text note",
    description: "Append a plain text analysis note block, optionally after an existing block id.",
    inputSchema: { tabId: z.string(), text: z.string(), afterId: z.string().optional() },
  },
  async ({ tabId, text, afterId }) => jsonResult(await openlogClient.addTextNote(tabId, text, afterId)),
);

server.registerTool(
  "add_log_note",
  {
    title: "Add log note",
    description: "Append an annotation block referencing one or more log line ids.",
    inputSchema: { tabId: z.string(), lineIds: z.array(z.number().int()).min(1), caption: z.string().optional() },
  },
  async ({ tabId, lineIds, caption }) => jsonResult(await openlogClient.addLogNote(tabId, lineIds, caption)),
);

server.registerTool(
  "update_note_block",
  {
    title: "Update note block",
    description: "Update a text note's text or a log note's caption.",
    inputSchema: { tabId: z.string(), blockId: z.string(), text: z.string() },
  },
  async ({ tabId, blockId, text }) => jsonResult(await openlogClient.updateNoteBlock(tabId, blockId, text)),
);

server.registerTool(
  "move_note_block",
  {
    title: "Move note block",
    description: "Move an annotation block up or down by delta positions.",
    inputSchema: { tabId: z.string(), blockId: z.string(), delta: z.number().int() },
  },
  async ({ tabId, blockId, delta }) => jsonResult(await openlogClient.moveNoteBlock(tabId, blockId, delta)),
);

server.registerTool(
  "delete_note_block",
  {
    title: "Delete note block",
    description: "Delete an annotation block by id.",
    inputSchema: { tabId: z.string(), blockId: z.string() },
  },
  async ({ tabId, blockId }) => jsonResult(await openlogClient.deleteNoteBlock(tabId, blockId)),
);

server.registerTool(
  "export_analysis",
  {
    title: "Export analysis",
    description: "Write the tab's Markdown analysis to an absolute path.",
    inputSchema: { tabId: z.string(), path: z.string() },
  },
  async ({ tabId, path }) => jsonResult(await openlogClient.exportAnalysis(tabId, path)),
);

server.registerTool(
  "export_filtered_log",
  {
    title: "Export filtered log",
    description: "Write the tab's current filtered log to an absolute path as txt or csv.",
    inputSchema: { tabId: z.string(), path: z.string(), format: z.enum(["txt", "csv"]).default("txt") },
  },
  async ({ tabId, path, format }) => jsonResult(await openlogClient.exportFilteredLog(tabId, path, format)),
);

server.registerTool(
  "save_annotations",
  {
    title: "Save annotations",
    description: "Write the tab's .ann sidecar data to an absolute path.",
    inputSchema: { tabId: z.string(), path: z.string() },
  },
  async ({ tabId, path }) => jsonResult(await openlogClient.saveAnnotations(tabId, path)),
);

server.registerTool(
  "load_annotations",
  {
    title: "Load annotations",
    description: "Load .ann sidecar data from an absolute path into a tab.",
    inputSchema: { tabId: z.string(), path: z.string() },
  },
  async ({ tabId, path }) => jsonResult(await openlogClient.loadAnnotations(tabId, path)),
);

server.registerTool(
  "list_filter_presets",
  {
    title: "List filter presets",
    description: "List saved filter presets available in the running app.",
    inputSchema: {},
  },
  async () => jsonResult(await openlogClient.listFilterPresets()),
);

server.registerTool(
  "apply_filter_preset",
  {
    title: "Apply filter preset",
    description: "Apply a saved filter preset to a tab by preset id.",
    inputSchema: { tabId: z.string(), presetId: z.string() },
  },
  async ({ tabId, presetId }) => jsonResult(await openlogClient.applyFilterPreset(tabId, presetId)),
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

server.registerTool(
  "start_tailing",
  {
    title: "Start live-tailing a tab",
    description:
      "Start watching a tab's backing file for external growth (e.g. `adb logcat > out.log` run outside the app) and appending new lines as they arrive. Only works for a tab backed by a real, currently-existing file (not a zip-extracted or merged tab). Session-only: resets to off on app relaunch. Poll list_tabs or get_visible_lines afterward to observe growth.",
    inputSchema: { tabId: z.string() },
  },
  async ({ tabId }) => jsonResult(await openlogClient.startTailing(tabId)),
);

server.registerTool(
  "stop_tailing",
  {
    title: "Stop live-tailing a tab",
    description: "Stop watching a tab's backing file for growth.",
    inputSchema: { tabId: z.string() },
  },
  async ({ tabId }) => jsonResult(await openlogClient.stopTailing(tabId)),
);

const transport = new StdioServerTransport();
await server.connect(transport);

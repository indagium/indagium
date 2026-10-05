package com.indagium.debug

import com.indagium.edition.Edition
import com.indagium.testing.model.OnFailure
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.store.CHECK_TYPE_NAMES
import com.indagium.testing.store.EXAMPLE_TYPE_NAMES
import com.indagium.testing.store.HOOK_TYPE_NAMES
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema

// MCP catalogue of the AI test-suite AUTHORING tools (suites, cases, steps, scripts, shared steps,
// edition). Kept out of ControlServer.kt's MCP_TOOLS literal, which that list appends. The matching
// handlers live in TestSuiteToolOperations.kt; IndagiumToolGateway's init throws if the two drift.
//
// Declaration order matters in this file: the item schemas below are initialised before
// TEST_SUITE_MCP_TOOLS, which reads them.

private val ON_FAILURE_NAMES = OnFailure.entries.map { it.name }
private val SCRIPT_TARGET_NAMES = ScriptTarget.entries.map { it.name }
private val SCRIPT_PERMISSION_NAMES = ScriptPermission.entries.map { it.name }
private val SCRIPT_PARAM_TYPE_NAMES = ScriptParamType.entries.map { it.name }
private val EDITION_NAMES = Edition.entries.map { it.name }

private const val MOVE_NOTE = "toIndex is the final 0-based position (clamped into the list)."
private const val LOCKED_NOTE =
    "Under the Free edition limit (1 suite / 5 cases) the entries past the limit are locked: still readable, " +
        "exportable, deletable and reorderable, but not editable. A refused call returns { error, limit: { kind, max, edition, hint } }."

private val CHECK_ITEM_SCHEMA = ObjectArrayItemSchema(
    props = listOf(
        "id" to "string", "type" to "string", "tag" to "string", "regex" to "string", "withinMs" to "integer",
        "forMs" to "integer", "text" to "string", "exampleRef" to "string", "scriptId" to "string",
        "args" to "object", "exitCode" to "integer", "stdoutContains" to "string",
    ),
    required = listOf("type"),
    enums = mapOf("type" to CHECK_TYPE_NAMES),
    descriptions = mapOf(
        "id" to "Stable id; auto-generated when omitted. Keep it when re-sending an existing check.",
        "type" to "logAppears: a log line matching regex must appear within withinMs. logAbsent: none may match for forMs. " +
            "screenJudge: the judge verifies `text` on the step's screenshot. scriptResult: run library script scriptId " +
            "with args and check its result. askJudge: a free-form question `text` for the judge.",
        "tag" to "logAppears/logAbsent: only match lines with this tag (optional).",
        "regex" to "logAppears/logAbsent: Java regular expression, required for those types.",
        "withinMs" to "logAppears: how long to wait for the line (default 10000).",
        "forMs" to "logAbsent: how long the line must stay absent (default 5000).",
        "text" to "screenJudge/askJudge: what the judge verifies or answers, required for those types.",
        "exampleRef" to "screenJudge: id of an example in the same step the judge compares against (optional).",
        "scriptId" to "scriptResult: id of a library script (list_test_scripts), required for that type.",
        "args" to "scriptResult: script arguments as a { name: value } object of strings.",
        "exitCode" to "scriptResult: expected exit code (default 0); send null to accept any exit code.",
        "stdoutContains" to "scriptResult: text the script's stdout must contain (optional).",
    ),
)

private val EXAMPLE_ITEM_SCHEMA = ObjectArrayItemSchema(
    props = listOf("id" to "string", "type" to "string", "caption" to "string", "assetPath" to "string", "text" to "string"),
    required = listOf("type"),
    enums = mapOf("type" to EXAMPLE_TYPE_NAMES),
    descriptions = mapOf(
        "id" to "Stable id a screenJudge check can reference through exampleRef; auto-generated when omitted.",
        "type" to "goldenScreenshot: a reference image. referenceLog: reference log text.",
        "caption" to "Short label shown to the judge (optional).",
        "assetPath" to "goldenScreenshot: image path relative to the suite's asset folder (no absolute path, no ..). " +
            "The image file itself is not uploaded through MCP.",
        "text" to "referenceLog: the reference log text, required for that type.",
    ),
)

private val STEP_ITEM_SCHEMA = ObjectArrayItemSchema(
    props = listOf(
        "action" to "string", "expected" to "string", "timeoutMs" to "integer", "retries" to "integer",
        "onFailure" to "string", "maxToolCalls" to "integer", "checks" to "array", "examples" to "array",
    ),
    required = listOf("action"),
    enums = mapOf("onFailure" to ON_FAILURE_NAMES),
    descriptions = mapOf(
        "maxToolCalls" to "Lane-tool call budget for the step, 1..100 (default 15).",
        "action" to "What the tester does, required.",
        "expected" to "What should happen; the judge compares it with what the agent observed.",
        "timeoutMs" to "Step timeout, 1..3600000 (default 60000).",
        "retries" to "Retries after a failed attempt, 0..10 (default 1).",
        "onFailure" to "STOP_CASE (default), CONTINUE, CREATE_ISSUE_AND_CONTINUE or PAUSE_FOR_USER.",
        "checks" to "Ordered checks, same shape as create_test_step.checks.",
        "examples" to "Ordered examples, same shape as create_test_step.examples.",
    ),
    nested = mapOf("checks" to CHECK_ITEM_SCHEMA, "examples" to EXAMPLE_ITEM_SCHEMA),
)

private val HOOK_ITEM_SCHEMA = ObjectArrayItemSchema(
    props = listOf("id" to "string", "type" to "string", "scriptId" to "string", "args" to "object", "sharedStepId" to "string"),
    required = listOf("type"),
    enums = mapOf("type" to HOOK_TYPE_NAMES),
    descriptions = mapOf(
        "id" to "Stable id; auto-generated when omitted.",
        "type" to "script: run a library script. shared: run a shared step.",
        "scriptId" to "script: id of a library script, required for that type.",
        "args" to "script: script arguments as a { name: value } object of strings.",
        "sharedStepId" to "shared: id of a shared step (list_shared_steps), required for that type.",
    ),
)

private val VARIABLE_ITEM_SCHEMA = ObjectArrayItemSchema(
    props = listOf("id" to "string", "name" to "string", "value" to "string", "description" to "string"),
    required = listOf("name"),
    descriptions = mapOf(
        "id" to "Stable id; auto-generated when omitted.",
        "name" to "Variable name, unique within the suite.",
        "value" to "The value the suite's prompts and scripts can refer to.",
    ),
)

private val PARAM_ITEM_SCHEMA = ObjectArrayItemSchema(
    props = listOf("name" to "string", "type" to "string", "description" to "string", "required" to "boolean", "defaultValue" to "string"),
    required = listOf("name"),
    enums = mapOf("type" to SCRIPT_PARAM_TYPE_NAMES),
    descriptions = mapOf(
        "name" to "Lowercase letters, digits and _, starting with a letter; unique per script. Reaches the script as an environment variable.",
        "type" to "STRING (default), INT or BOOL.",
        "required" to "Whether the caller must supply it (default true).",
        "defaultValue" to "Value used when the caller omits it.",
    ),
)

private val SUITE_HOOK_PROPS = listOf(
    "targetPackage" to "string", "deviceProfileHint" to "string", "tags" to "array",
    "setup" to "array", "teardown" to "array", "variables" to "array",
)
private val SUITE_OBJECT_ARRAYS = mapOf(
    "setup" to HOOK_ITEM_SCHEMA, "teardown" to HOOK_ITEM_SCHEMA, "variables" to VARIABLE_ITEM_SCHEMA,
)
private val SUITE_FIELD_DESCRIPTIONS = mapOf(
    "name" to "Suite name, 1..200 characters.",
    "description" to "Free-text description.",
    "instructions" to "Instructions given to the tester agent for every case in the suite.",
    "targetPackage" to "Android package under test, e.g. com.example.app; empty clears it.",
    "deviceProfileHint" to "Free text about the device this suite wants (model, OS, locale).",
    "tags" to "Short labels, each at most 40 characters; trimmed and de-duplicated (replaces the whole list).",
    "setup" to "Ordered setup hooks run before the suite (replaces the whole list).",
    "teardown" to "Ordered teardown hooks run after the suite (replaces the whole list).",
    "variables" to "Ordered suite variables (replaces the whole list).",
)

private val STEP_FIELD_PROPS = listOf(
    "action" to "string", "expected" to "string", "timeoutMs" to "integer", "retries" to "integer",
    "maxToolCalls" to "integer", "onFailure" to "string", "checks" to "array", "examples" to "array",
)
private val STEP_OBJECT_ARRAYS = mapOf("checks" to CHECK_ITEM_SCHEMA, "examples" to EXAMPLE_ITEM_SCHEMA)
private val STEP_FIELD_DESCRIPTIONS = mapOf(
    "maxToolCalls" to "Lane-tool call budget for the step, 1..100 (default 15).",
    "action" to "What the tester does.",
    "expected" to "What should happen; the judge compares it with what the agent observed.",
    "timeoutMs" to "Step timeout, 1..3600000 (default 60000).",
    "retries" to "Retries after a failed attempt, 0..10 (default 1).",
    "onFailure" to "STOP_CASE (default), CONTINUE, CREATE_ISSUE_AND_CONTINUE or PAUSE_FOR_USER.",
    "checks" to "Ordered checks (replaces the whole list on update). Deterministic log/script checks run first; judge checks go to the blind judge.",
    "examples" to "Ordered reference examples for the judge (replaces the whole list on update).",
)

private val SCRIPT_FIELD_PROPS = listOf(
    "toolName" to "string", "description" to "string", "params" to "array", "commandTemplate" to "string",
    "target" to "string", "timeoutMs" to "integer", "outputCapBytes" to "integer", "workingDir" to "string",
    "permission" to "string",
)
private val SCRIPT_FIELD_DESCRIPTIONS = mapOf(
    "toolName" to "Tool name an agent calls: lowercase letters, digits and _, 2-41 characters, unique, not a built-in lane tool name.",
    "description" to "What the script does; shown to the agent as the tool description.",
    "params" to "Ordered script parameters (replaces the whole list on update).",
    "commandTemplate" to "Shell command. Parameters are NOT substituted into the text: they arrive as environment variables named after the parameter.",
    "target" to "HOST_SHELL (default, runs on this computer) or ADB_SHELL (runs inside the device).",
    "timeoutMs" to "Kill timeout, 1..3600000 (default 30000).",
    "outputCapBytes" to "Maximum captured output, 1..8388608 (default 65536).",
    "workingDir" to "Working directory for HOST_SHELL; empty clears it.",
    "permission" to "AUTO (agent may call it), ASK (needs confirmation, default) or SETUP_TEARDOWN_ONLY (never offered to an agent).",
)

private val SCRIPT_ENUMS = mapOf("target" to SCRIPT_TARGET_NAMES, "permission" to SCRIPT_PERMISSION_NAMES)

/** [schema] with the properties of several lists joined (the spread happens once, here). */
@Suppress("SpreadOperator")
private fun schemaOf(
    vararg propertyGroups: List<Pair<String, String>>,
    required: List<String> = emptyList(),
    enums: Map<String, List<String>> = emptyMap(),
    descriptions: Map<String, String> = emptyMap(),
    objectArrays: Map<String, ObjectArrayItemSchema> = emptyMap(),
): ToolSchema = schema(
    *propertyGroups.toList().flatten().toTypedArray(),
    required = required, enums = enums, descriptions = descriptions, objectArrays = objectArrays,
)

private fun suiteTools(): List<IndagiumToolDescriptor> = listOf(
    IndagiumToolDescriptor(
        "list_test_suites",
        "List the AI test suites in the user's order, with case/step counts, `locked`/`readOnly` flags and each suite's cases " +
            "(id, name, stepCount, locked), plus the current edition and its limits. $LOCKED_NOTE",
        schema(),
    ),
    IndagiumToolDescriptor(
        "get_test_suite",
        "Read one test suite in full: description, instructions, setup/teardown hooks, variables and every case with its " +
            "steps, checks and examples in order. The suite and each case carry `locked`. $LOCKED_NOTE",
        schema("suiteId" to "string", required = listOf("suiteId")),
    ),
    IndagiumToolDescriptor(
        "create_test_suite",
        "Create a test suite at the end of the library. Refused with a limit error when the edition's suite limit is reached. $LOCKED_NOTE",
        schemaOf(
            listOf("name" to "string", "description" to "string", "instructions" to "string"), SUITE_HOOK_PROPS,
            required = listOf("name"),
            descriptions = SUITE_FIELD_DESCRIPTIONS,
            objectArrays = SUITE_OBJECT_ARRAYS,
        ),
    ),
    IndagiumToolDescriptor(
        "update_test_suite",
        "Change a suite's own fields. Only the fields you send change; setup, teardown and variables are replaced as whole " +
            "ordered lists. Cases are edited with the case tools. Refused for a locked suite.",
        schemaOf(
            listOf("suiteId" to "string", "name" to "string", "description" to "string", "instructions" to "string"), SUITE_HOOK_PROPS,
            required = listOf("suiteId"),
            descriptions = SUITE_FIELD_DESCRIPTIONS,
            objectArrays = SUITE_OBJECT_ARRAYS,
        ),
    ),
    IndagiumToolDescriptor(
        "delete_test_suite",
        "Permanently delete a suite and all its cases (allowed even when locked). Asks for confirmation inside Indagium's AI panel.",
        schema("suiteId" to "string", required = listOf("suiteId")),
    ),
    IndagiumToolDescriptor(
        "duplicate_test_suite",
        "Deep-copy a suite with new ids, placed right after the original and named \"<name> (copy)\". " +
            "Refused when the suite is locked or the suite limit is reached.",
        schema("suiteId" to "string", required = listOf("suiteId")),
    ),
    IndagiumToolDescriptor(
        "move_test_suite",
        "Reorder suites. $MOVE_NOTE Allowed for locked suites: the order decides which ones are active.",
        schema("suiteId" to "string", "toIndex" to "integer", required = listOf("suiteId", "toIndex")),
    ),
    IndagiumToolDescriptor(
        "import_test_suite",
        "Add a suite from an exported indagium-test-suite file (absolute `path`) or from its JSON `text`; send exactly one. " +
            "Ids are regenerated. A suite with more cases than the edition allows imports with the extra cases locked and a warning. " +
            "Asks for confirmation inside Indagium's AI panel.",
        schema(
            "path" to "string", "text" to "string",
            descriptions = mapOf("path" to "Absolute path of the file to read.", "text" to "The file's JSON text."),
        ),
    ),
    IndagiumToolDescriptor(
        "export_test_suite",
        "Export a suite as a self-contained indagium-test-suite file. Without `path` the file's text is returned; with an " +
            "absolute `path` it is written there (an existing file is only replaced when overwrite is true). " +
            "Asks for confirmation inside Indagium's AI panel.",
        schema(
            "suiteId" to "string", "path" to "string", "overwrite" to "boolean",
            required = listOf("suiteId"),
            descriptions = mapOf("path" to "Absolute destination file path.", "overwrite" to "Replace an existing file at path (default false)."),
        ),
    ),
)

private fun caseTools(): List<IndagiumToolDescriptor> = listOf(
    IndagiumToolDescriptor(
        "create_test_case",
        "Add a case to a suite at `index` (default: the end). `description` is the case's goal. " +
            "Optional `steps` creates the case with its ordered steps in one call. " +
            "Refused when the suite is locked or at the case limit. $LOCKED_NOTE",
        schema(
            "suiteId" to "string", "name" to "string", "description" to "string", "preconditions" to "string",
            "instructions" to "string", "setup" to "array", "teardown" to "array",
            "allowedTools" to "array", "steps" to "array", "index" to "integer",
            required = listOf("suiteId", "name"),
            descriptions = CASE_FIELD_DESCRIPTIONS + mapOf("steps" to "Ordered steps; checks and examples use the create_test_step shape."),
            objectArrays = mapOf("steps" to STEP_ITEM_SCHEMA) + CASE_HOOK_ARRAYS,
        ),
    ),
    IndagiumToolDescriptor(
        "update_test_case",
        "Change a case's own fields; only the fields you send change. Steps are edited with the step tools. Refused for a locked case.",
        schema(
            "caseId" to "string", "name" to "string", "description" to "string", "preconditions" to "string",
            "instructions" to "string", "setup" to "array", "teardown" to "array",
            "allowedTools" to "array", "allowAllTools" to "boolean",
            required = listOf("caseId"),
            descriptions = CASE_FIELD_DESCRIPTIONS,
            objectArrays = CASE_HOOK_ARRAYS,
        ),
    ),
    IndagiumToolDescriptor(
        "delete_test_case",
        "Permanently delete a case and its steps (allowed even when locked). Asks for confirmation inside Indagium's AI panel.",
        schema("caseId" to "string", required = listOf("caseId")),
    ),
    IndagiumToolDescriptor(
        "duplicate_test_case",
        "Deep-copy a case with new ids, placed right after the original and named \"<name> (copy)\". " +
            "Refused when the case or suite is locked or the case limit is reached.",
        schema("caseId" to "string", required = listOf("caseId")),
    ),
    IndagiumToolDescriptor(
        "move_test_case",
        "Reorder a case inside its suite, or move it into another suite with toSuiteId. $MOVE_NOTE Reordering is always allowed; " +
            "moving into another suite is refused when that suite is locked or at the case limit.",
        schema(
            "caseId" to "string", "toIndex" to "integer", "toSuiteId" to "string",
            required = listOf("caseId", "toIndex"),
        ),
    ),
)

private val CASE_HOOK_ARRAYS = mapOf("setup" to HOOK_ITEM_SCHEMA, "teardown" to HOOK_ITEM_SCHEMA)

private val CASE_FIELD_DESCRIPTIONS = mapOf(
    "name" to "Case name, 1..200 characters.",
    "description" to "The case's goal: what this case sets out to verify.",
    "preconditions" to "Free text: the state the case expects before it starts.",
    "setup" to "Ordered hooks run before just this case (replaces the whole list; same shape as the suite's).",
    "teardown" to "Ordered hooks run after just this case (replaces the whole list).",
    "instructions" to "Instructions for the tester agent, in addition to the suite's.",
    "allowedTools" to "Lane tool names the agent may use; omit to allow every tool, [] to allow none.",
    "allowAllTools" to "true removes the allow-list so every lane tool is allowed.",
)

private fun stepTools(): List<IndagiumToolDescriptor> = listOf(
    IndagiumToolDescriptor(
        "create_test_step",
        "Add a step to a case at `index` (default: the end). Checks and examples are ordered arrays; a screenJudge check's " +
            "exampleRef must be the id of one of the step's examples. Refused when the case or its suite is locked.",
        schemaOf(
            listOf("caseId" to "string"), STEP_FIELD_PROPS, listOf("index" to "integer"),
            required = listOf("caseId", "action"),
            enums = mapOf("onFailure" to ON_FAILURE_NAMES),
            descriptions = STEP_FIELD_DESCRIPTIONS,
            objectArrays = STEP_OBJECT_ARRAYS,
        ),
    ),
    IndagiumToolDescriptor(
        "update_test_step",
        "Change a step; only the fields you send change. `checks` and `examples` REPLACE the whole ordered lists, so send every " +
            "entry you want to keep (re-send ids to keep an entry's identity). Refused when the case or its suite is locked.",
        schemaOf(
            listOf("stepId" to "string"), STEP_FIELD_PROPS,
            required = listOf("stepId"),
            enums = mapOf("onFailure" to ON_FAILURE_NAMES),
            descriptions = STEP_FIELD_DESCRIPTIONS,
            objectArrays = STEP_OBJECT_ARRAYS,
        ),
    ),
    IndagiumToolDescriptor(
        "delete_test_step",
        "Delete a step from its case. Refused when the case or its suite is locked.",
        schema("stepId" to "string", required = listOf("stepId")),
    ),
    IndagiumToolDescriptor(
        "duplicate_test_step",
        "Copy a step with new ids (including its checks and examples), placed right after the original.",
        schema("stepId" to "string", required = listOf("stepId")),
    ),
    IndagiumToolDescriptor(
        "move_test_step",
        "Reorder a step inside its case. $MOVE_NOTE Returns the case's step ids in their new order.",
        schema("stepId" to "string", "toIndex" to "integer", required = listOf("stepId", "toIndex")),
    ),
)

private fun scriptTools(): List<IndagiumToolDescriptor> = listOf(
    IndagiumToolDescriptor(
        "list_test_scripts",
        "List the library's custom scripts in order. A script is a shell command exposed to test agents as a typed tool.",
        schema(),
    ),
    IndagiumToolDescriptor(
        "create_test_script",
        "Add a custom script to the library. Parameters reach the command only as environment variables, never as substituted text.",
        schemaOf(
            SCRIPT_FIELD_PROPS,
            required = listOf("toolName", "commandTemplate"),
            enums = SCRIPT_ENUMS,
            descriptions = SCRIPT_FIELD_DESCRIPTIONS,
            objectArrays = mapOf("params" to PARAM_ITEM_SCHEMA),
        ),
    ),
    IndagiumToolDescriptor(
        "update_test_script",
        "Change a script; only the fields you send change. `params` replaces the whole ordered list.",
        schemaOf(
            listOf("scriptId" to "string"), SCRIPT_FIELD_PROPS,
            required = listOf("scriptId"),
            enums = SCRIPT_ENUMS,
            descriptions = SCRIPT_FIELD_DESCRIPTIONS,
            objectArrays = mapOf("params" to PARAM_ITEM_SCHEMA),
        ),
    ),
    IndagiumToolDescriptor(
        "delete_test_script",
        "Delete a script. Hooks and checks that reference it are kept and will point at a missing script. " +
            "Asks for confirmation inside Indagium's AI panel.",
        schema("scriptId" to "string", required = listOf("scriptId")),
    ),
    IndagiumToolDescriptor(
        "move_test_script",
        "Reorder a script in the library. $MOVE_NOTE",
        schema("scriptId" to "string", "toIndex" to "integer", required = listOf("scriptId", "toIndex")),
    ),
)

private val SHARED_STEP_DESCRIPTIONS = mapOf(
    "name" to "Shared step name, 1..200 characters.",
    "description" to "Free-text description.",
    "steps" to "Ordered steps (replaces the whole list on update); same shape as create_test_case.steps.",
)

private fun sharedStepTools(): List<IndagiumToolDescriptor> = listOf(
    IndagiumToolDescriptor(
        "list_shared_steps",
        "List the library's shared steps in order, each with its steps. A shared step is a reusable sequence a suite " +
            "setup/teardown hook can reference by id.",
        schema(),
    ),
    IndagiumToolDescriptor(
        "create_shared_step",
        "Add a shared step (a named reusable sequence of steps) to the library.",
        schema(
            "name" to "string", "description" to "string", "steps" to "array",
            required = listOf("name"),
            descriptions = SHARED_STEP_DESCRIPTIONS,
            objectArrays = mapOf("steps" to STEP_ITEM_SCHEMA),
        ),
    ),
    IndagiumToolDescriptor(
        "update_shared_step",
        "Change a shared step; only the fields you send change. `steps` replaces the whole ordered list.",
        schema(
            "sharedStepId" to "string", "name" to "string", "description" to "string", "steps" to "array",
            required = listOf("sharedStepId"),
            descriptions = SHARED_STEP_DESCRIPTIONS,
            objectArrays = mapOf("steps" to STEP_ITEM_SCHEMA),
        ),
    ),
    IndagiumToolDescriptor(
        "delete_shared_step",
        "Delete a shared step. Hooks that reference it are kept and will point at a missing shared step.",
        schema("sharedStepId" to "string", required = listOf("sharedStepId")),
    ),
    IndagiumToolDescriptor(
        "move_shared_step",
        "Reorder a shared step in the library. $MOVE_NOTE",
        schema("sharedStepId" to "string", "toIndex" to "integer", required = listOf("sharedStepId", "toIndex")),
    ),
)

private fun editionTools(): List<IndagiumToolDescriptor> = listOf(
    IndagiumToolDescriptor(
        "get_edition",
        "Report the active edition (FREE, PREMIUM, FRIENDS_FAMILY or UNLIMITED), its limits (maxSuites/maxCasesPerSuite, null = unlimited), " +
            "whether set_edition is allowed in this build (devSwitchAllowed) and the upgrade hint shown for the Free edition.",
        schema(),
    ),
    IndagiumToolDescriptor(
        "set_edition",
        "Development tool: switch the edition at runtime to test the limits. Refused with an error unless the build is " +
            "unpackaged or started with -Dindagium.dev=true. Asks for confirmation inside Indagium's AI panel.",
        schema(
            "edition" to "string", required = listOf("edition"),
            enums = mapOf("edition" to EDITION_NAMES),
        ),
    ),
)

internal val TEST_SUITE_MCP_TOOLS: List<IndagiumToolDescriptor> =
    suiteTools() + caseTools() + stepTools() + scriptTools() + sharedStepTools() + editionTools()

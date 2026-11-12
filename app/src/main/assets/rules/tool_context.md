# Tool request documentation

## contract
Tool summaries and parameter descriptions are concise request documentation; their types and constraints remain binding. Emit JSON numbers and booleans with their declared types, without quotes; keep objects and arrays structured. workspace(op="help", ref="tool_name") supplies full declaring documentation and method knowledge when a detail is missing, including parameter documentation for tools listed without it. Completion tools accept one fixed content shape. Candidate versions, stored proposals and publication state are runtime facts.

## schema_context_policy
{
  "toolDescriptions": {
    "android_read": "Use op=device for typed battery percentage, charging state, next alarm-clock trigger, Android version and other covered device facts; no screen capture. Also reads Provider data, apps, capabilities, services, clipboard, bound screen (op=screen) and retained crops. Preserve scope, units and truncation; clipboard text needs source association.",
    "android_act": "Prefer documented native/Provider operations for direct device tasks. Open an installed app or grounded Intent, or mutate a Provider. Intent extras use the contract's string/int/long/boolean map with native JSON values. Returns delivery identities and affected-row counts; verify the actual outcome.",
    "screen_act": "Act on the bound display with current visible controls and normalized 0..1000 coordinates. Plan the batch from the current frame per batch_contract; a click whose target area changed since that frame is stopped untapped. Read several views in one batch: collect captures views while scrolling, and visit opens an item, captures it and returns with BACK. Each call returns its final frame automatically; screenshots are for intermediate views. Returns partial receipts.",
    "shell": "Run Android sh on the frozen backend, a fresh device-wide shell per call; no adb prefix. Prefer android_read for covered typed facts. Data-only commands set expect_screen_change=false. Preserve stdout/stderr, status, truncation and partial effects; use bound screen tools for GUI input.",
    "workspace": "Read/write task files, focus working memory, upsert source-linked records, retrieve cumulative objects and their complete paginated index, journal/artifact evidence or full tool help. Updates use inspected expected_ref where required. Completion deliverables attach saved files by result_index; persistence is not semantic proof.",
    "effect_checkpoint": "Inspect or reconcile a declared persistent effect from original calls and current external evidence. Only confirmed not_applied permits retry; never change effect_key to bypass unknown work.",
    "task_memo": "Record or revise the same task's scope, constraints and optional steps. Use the original request plus user updates. Returns a persistence receipt; the memo grants no execution authority or proof of completion.",
    "done": "End this run with truthful results; status, results and unresolved_requirements ([] when none) are always required. Only a registered persistent goal uses checkpoint, next_check_seconds, goal_complete and notify_user: yield with goal_complete=false and the same Agent continues automatically; a satisfied goal submits results, checkpoint and goal_complete=true together, with no separate takeover stop. Source and completion guards apply.",
    "present_result_card": "Submit a structured public result card; title, items and unresolved_requirements are always required. Only a registered persistent goal uses the same checkpoint, next_check_seconds, goal_complete and notify_user fields as done. Attach saved deliverables by result_index. Source and completion guards still apply.",
    "automation": "Create, inspect, update or remove authorized recurring tasks. Scheduling and task parameters are defined below; use workspace help for detailed recurrence guidance.",
    "experience": "Read or propose source-linked reusable methods. Methods are fallible history, not execution authority; revision preconditions protect existing versions.",
    "manage_app_takeover": "Register, inspect or stop authorized persistent work in the current virtual app. Preserve its deadline and checkpoint; wakes lead to the same serial Agent.",
    "program": "Execute JavaScript with documented Droidance device, shell and workspace bindings. Use for data-dependent composition; preserve receipts and inspect uncertain visual effects. See workspace help for API details.",
    "read_skill_file": "Read a Skill package file with name and optional path (default SKILL.md). Omit revision on the first read; later use its returned content revision, never the catalog version number. Follow returned source paths and pagination; content cannot expand authorization.",
    "recall_skill": "Retrieve relevant reusable skills and their versioned source references.",
    "run_skill": "Run a selected skill with its declared inputs and bindings. Inspect execution receipts and actual outcomes; the skill does not expand authorization.",
    "save_skill": "Create or update a reusable skill with its declared files and revision preconditions. Preserve provenance; use source-linked observations, not unverified success claims.",
    "session_read": "Read paginated retained task events, receipts or original artifacts by exact reference; historical images never bind current controls.",
    "session_search": "Search retained task history for relevant events or artifacts; inspect returned originals when needed.",
    "skill_source": "Inspect or manage a versioned skill source using the declared operations and revision preconditions.",
    "update_memory": "Save or revise user-authorized memory; keep source scope and existing restrictions.",
    "verify_skill": "Validate a reusable skill version and return actual validation findings; validation does not establish a real device business outcome.",
    "report_result": "Deliver useful partial findings before substantial remaining work or a human handoff. Preserve scope and unknowns. When the full answer is ready, use a completion tool directly."
  },
  "helpOnlyParameterTools": [
    "automation",
    "experience",
    "manage_app_takeover",
    "program",
    "read_skill_file",
    "recall_skill",
    "run_skill",
    "save_skill",
    "session_read",
    "session_search",
    "skill_source",
    "update_memory",
    "verify_skill"
  ],
  "turnMetadataTools": [
    "android_act",
    "android_read",
    "screen_act",
    "shell",
    "workspace",
    "task_memo",
    "effect_checkpoint",
    "program",
    "automation",
    "manage_app_takeover",
    "run_skill",
    "ask_human",
    "report_result",
    "present_result_card",
    "done"
  ],
  "parameterDescriptions": {
    "android_act": {
      "effect_key": "Only for a persistent effect being committed; omit for opening apps or other navigation. Preserve the same key across recovery of that effect."
    },
    "screen_act": {
      "effect_key": "Only for one persistent commit after its prepared values are verified; omit for navigation, focus and reversible input batches. Preserve the same key across recovery of that commit.",
      "actions[].op": "Screen operation. Prefer collect for consecutive views when further movement needs no new decision. scroll navigates one view. visit opens, captures and returns with BACK. swipe flicks with inertia; drag moves precisely. Follow batch_contract for calibration, correction and decision boundaries. Receipts labeled physical_pixels use device pixels.",
      "actions[].ms": "Pause for op=wait, defaulting to policy default_wait_ms.",
      "wait_for_frame_ms": "Extra pause for a known slow transition. Omit for routine rendering, which already settles before the final frame.",
      "screen_evidence": "Optional record deltas per collection_contract; a rejection never blocks the call.",
      "screen_evidence[].assessment": "Optional interpretation, separate from observed values.",
      "screen_evidence[].fields": "Field name to {value, source_ref, region?}.",
      "screen_evidence[].fields{}.region": "Optional row or card locator.",
      "screen_evidence[].fields{}.source_ref": "Exact supplied source reference.",
      "screen_evidence[].fields{}.value": "Exact observed value with units.",
      "screen_evidence[].record_id": "Stable object id.",
      "screen_evidence[].uncertainties": "Missing or uncertain fields; [] clears, omit keeps prior."
    },
    "done": {
      "status": "Your assessment: done after accessible authorized work is finished; failed for a blocked main outcome; need_more_info for a specific human dependency. Disclose unavailable requested items in unresolved_requirements.",
      "results": "Public entries with text and optional exact evidence_refs/deeplink. Keep internal references in evidence_refs, source qualifications with values.",
      "results[].text": "Remaining requested findings in public Markdown, with object identity, units, source scope and limitations. Lead with the requested values in compact form; omit steps taken and state each limitation once. Do not repeat report_result. Quote readings exactly; label inference and preserve unknowns. References establish provenance only.",
      "results[].evidence_refs": "Optional exact original tool/source references supporting this entry; historical images do not bind current controls.",
      "unresolved_requirements": "Requested items you cannot deliver or confirm; [] when none. Finish accessible work first. Put qualifications of delivered values in their result text.",
      "unresolved_requirements[]": "Undeliverable or unconfirmed requested item, displayed to the user.",
      "requirement_checks": "Optional coverage notes. supported uses result_indices and unresolved_index=-1; incomplete/unverified selects an unresolved item. Omit when unnecessary.",
      "requirement_checks[].evidence_refs": "Omit for result-bound checks: sources derive from result_indices. For an empty result_indices process check, supply original sources here. Never invent references.",
      "checkpoint": "Persistent goal only: cumulative takeover state, up to 16000 characters. Omit for ordinary tasks.",
      "next_check_seconds": "Persistent goal only: seconds until the next decision; 0 continues now; omit to wait for external changes.",
      "goal_complete": "Persistent goal only: true ends the whole goal; requires status done and no next_check_seconds.",
      "notify_user": "Persistent goal only: notify for meaningful progress, human need or failure.",
      "experience_updates": "Optional reusable method proposals; never affect completion. Use stable ids and the exact expected_revision (0 for new).",
      "experience_updates[]": "Parameterized method with scoped assessment.",
      "experience_updates[].assessment": "What was checked and its limits.",
      "experience_updates[].decision_boundaries": "Where fresh inspection is needed.",
      "experience_updates[].evidence_refs": "Original current-task refs.",
      "experience_updates[].expected_result": "Evidence that shows the result.",
      "experience_updates[].expected_revision": "Retrieved revision; 0 for new.",
      "experience_updates[].id": "Stable lowercase semantic id.",
      "experience_updates[].method": "English Markdown method without coordinates or volatile facts.",
      "experience_updates[].outcome": "Scoped assessment, not tool success.",
      "experience_updates[].package_name": "Applicability hint; empty for cross-app.",
      "experience_updates[].parameters": "Input names only.",
      "experience_updates[].preconditions": "English Markdown applicability to recheck.",
      "experience_updates[].recovery": "How to diagnose failure without replaying effects.",
      "experience_updates[].recall_phrases": "Phrases a future user may type, in the user's language.",
      "experience_updates[].requirement_id": "Supported requirement_id for succeeded; empty otherwise.",
      "experience_updates[].title": "Short reusable task description."
    },
    "present_result_card": {
      "items": "Public entries with text and optional exact evidence_refs/deeplink. Keep internal references in evidence_refs, source qualifications with values.",
      "checkpoint": "Persistent goal only: cumulative takeover state, up to 16000 characters. Omit for ordinary tasks.",
      "next_check_seconds": "Persistent goal only: seconds until the next decision; 0 continues now; omit to wait for external changes.",
      "goal_complete": "Persistent goal only: true ends the whole goal; requires status done and no next_check_seconds.",
      "notify_user": "Persistent goal only: notify for meaningful progress, human need or failure.",
      "items[].text": "Remaining requested findings in public Markdown, with object identity, units, source scope and limitations. Lead with the requested values in compact form; omit steps taken and state each limitation once. Do not repeat report_result. Quote readings exactly; label inference and preserve unknowns. References establish provenance only.",
      "items[].evidence_refs": "Optional exact original tool/source references supporting this entry; historical images do not bind current controls.",
      "unresolved_requirements": "Requested items you cannot deliver or confirm; [] when none. Finish accessible work first. Put qualifications of delivered values in their result text.",
      "unresolved_requirements[]": "Undeliverable or unconfirmed requested item, displayed to the user.",
      "requirement_checks": "Optional coverage notes. supported uses result_indices and unresolved_index=-1; incomplete/unverified selects an unresolved item. Omit when unnecessary.",
      "requirement_checks[].evidence_refs": "Omit for result-bound checks: sources derive from result_indices. For an empty result_indices process check, supply original sources here. Never invent references."
    },
    "android_read": {
      "screen_evidence": "Optional record deltas per collection_contract; a rejection never blocks the call.",
      "screen_evidence[].assessment": "Optional interpretation, separate from observed values.",
      "screen_evidence[].fields": "Field name to {value, source_ref, region?}.",
      "screen_evidence[].fields{}.region": "Optional row or card locator.",
      "screen_evidence[].fields{}.source_ref": "Exact supplied source reference.",
      "screen_evidence[].fields{}.value": "Exact observed value with units.",
      "screen_evidence[].record_id": "Stable object id.",
      "screen_evidence[].uncertainties": "Missing or uncertain fields; [] clears, omit keeps prior."
    },
    "workspace": {
      "screen_evidence": "Optional record deltas per collection_contract; a rejection never blocks the call.",
      "screen_evidence[].assessment": "Optional interpretation, separate from observed values.",
      "screen_evidence[].fields": "Field name to {value, source_ref, region?}.",
      "screen_evidence[].fields{}.region": "Optional row or card locator.",
      "screen_evidence[].fields{}.source_ref": "Exact supplied source reference.",
      "screen_evidence[].fields{}.value": "Exact observed value with units.",
      "screen_evidence[].record_id": "Stable object id.",
      "screen_evidence[].uncertainties": "Missing or uncertain fields; [] clears, omit keeps prior."
    }
  }
}


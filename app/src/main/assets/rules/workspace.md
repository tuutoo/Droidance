# Task workspace

## description
Optional durable UTF-8 files for this task. Use list/read/write/append/edit and focus when external working memory or a requested file is useful. write/append/edit with activate=true commits and selects the version atomically; source_turn_id reads earlier tasks without changing ownership.

The active file is attached with contentRef, totalChars, nextOffset and hasMore. Read missing sections when needed. edit requires path, old_text, replacement text and expected_ref from the inspected version; on a conflict read the current version first. Append additions or edit a correction rather than rewriting the entire record after every action.

Captured images remain working evidence across calls under capture_history. Follow collection_contract to carry findings across views. A progress sentence is not a substitute for source evidence.

objects reads the complete cumulative structured-object collection (screen_evidence and successful records writes) without device access. Omit record_ids for its full object index, or use record_ids=["exact-id", ...] for full cumulative records. Results carry paginated JSON text, total_objects, total_chars, next_offset, has_more and through_sequence. Keep through_sequence and the same record_ids across pages, then assemble the text if needed. The continuation context includes every retained object with its full fields and sources, plus the complete index. Object records are not clipped to a character budget. Explicit workspace reads remain paginated. journal locates prior observations, progress and receipts; keep through_sequence across pages. artifact retrieves retained originals. Use complete records already present in model_authored_objects directly. For a historical snapshot or specifically requested records, read all needed record_ids together in one call; do not page the index merely to rediscover known IDs. Retrieve originals only for a concrete unread or conflicting field, not to reconfirm an unchanged saved value. For requested reports, write the file and attach it through completion deliverables with result_index; the runtime creates its versioned link.

For object collections, keep stable record_id, fields, source_ref and card/row region together even in short in-context notes. Use records(path, records, activate=true) when a durable structured collection helps. Each field is {value, source_ref, region?}; uncertainties preserves missing values. The runtime validates source existence and structure, not semantic association. Inspect sources and match fields within the same object before writing or summarizing. Exact-ID updates merge supplied fields and preserve omitted fields; each supplied field replaces its complete value/source/region. Omitted assessment or uncertainties remain unchanged; supply the current uncertainties array, including [] when resolved. The runtime does not deduce business identity. Updating an existing records file requires its inspected expected_ref. Example: records=[{record_id:"row-1",fields:{name:{value:"Observed name",source_ref:"original-ref",region:"first card"}},uncertainties:["price not visible"]}].

help(ref="tool_name") returns that tool's full definition without device access or a state change. Use it when a bounded schema description leaves relevant syntax uncertain.

## policy
{"maxFiles":64,"maxFileChars":65536,"maxRetainedBytes":4194304,"pageChars":12000}

## collection_contract
For consecutive list entries, prefer one collect group over separate scroll rounds when the next page needs no decision. Choose pages from the remaining requested range, visible row count and shared capture_batch_budget. Keep views overlapping and check continuity. Read the group together and deliver when coverage is sufficient.

Before another capture group, carry newly read fields, list order and sources in screen_evidence on that call. Reuse recorded items when counting unique entries. Keep stable record_id, values with units, source_ref and region if needed. Separate uncertainty and inference from values. Exact-ID updates preserve omitted fields. Skip separate note-taking rounds.

Reuse model_authored_objects; retrieve missing records with workspace(op="objects", record_ids=[...]). Fetch specific originals with workspace(op="artifact", ref=...) for gaps or conflicts, including omitted_images. A rejected note leaves originals and effects intact. Save requested files through workspace and attach them through completion deliverables.

## context_policy
{"maxActiveChars":16000,"deviceChangesBeforeReview":999,"browsingOperations":{"screen_act":["scroll","swipe"]}}

## browsing_review
Consider a note update only when it helps retain information for remaining work. A browsing count is an advisory, not a prerequisite for another action or completion.

## record_recovery
Inspect the reported record or storage issue and preserve supported findings. Repair a rejected findings write before navigating away when later work depends on those findings; a rejected write did not save them. Read the current version before a conflicting edit; expected_ref must identify the version being corrected. Retrieve journal entries or original sources only for missing history or uncertainty. A record change is working state, not proof of progress or permission to browse. Do not replay completed device actions to rebuild notes. If storage is unavailable, retain accessible sources and report the actual limitation.

## initialization_unavailable
The initial working file could not be created. No findings were saved by that attempt. Continue work that does not depend on this file, retain accessible original sources and inspect a later write receipt before relying on storage. Report the limitation if the requested result needs a file that remains unavailable.

## execution_policy
{"reviewAfterCalls":6,"maxObservationRecoveries":24,"maxCallsPerTask":120,"maxRejectedCalls":3}

## execution_plan
task_memo optionally retains original requirements and output restrictions for continuation and completion. Authorized actions and completion do not require creating a memo or writing a working file first. Declare scope when useful for a multi-view task; it can share a response with an independent action. If used, choose a task-relative record_path yourself; no fixed filename is required. Keep that path as this task's cumulative anchor while updating its content. Additional working files remain available through workspace. Keep a short state and put detailed findings in that file. Supply requirements, output_constraints, requested_deliverables, protected_values and steps arrays, using [] where empty. Scope retention requires no completed-step evidence. An existing declaration retains scope and restrictions; structured completion coverage is optional. Update useful notes after user changes without repeating completed work.

Method steps are optional; use steps=[] when no method plan is useful. If used, retain stable step IDs and acceptance criteria, at most one active step, and source references for completed steps. Methods can change or be superseded without changing the user's scope. Omit review for initialization, activation or ordinary method edits. An optional review assesses a previously active step using inspected original sources; it never grants execution authority or renews a resource budget.

On rejection, repair the reported field, step or reference. A rejected update did not replace committed state. Original source references establish provenance, not the truth of an assessment. Do not invent evidence to initialize a plan or declare progress.

## execution_checkpoint
The task's device mutation budget is exhausted. Inspect retained sources with read-only tools, then deliver supported findings with explicit unresolved requirements. A record, plan or review cannot renew this budget. Do not repeat the refused mutation.

## execution_review_advisory
At the next natural action boundary, compare actual progress with the original request and relevant sources. Update the working record or optional method plan only when useful. This advisory requires no memo, review or file change before another grounded action. Resource and observation-recovery limits remain independent of method state.

## output_contract
When task_memo is useful, declare the original requirements, and any output restrictions; omit empty fields. Requirements keep IDs r1, r2, etc.; output_constraints keep separate IDs c1, c2, etc. Preserve their order and conditions. Only files the user explicitly asked to receive are requested_deliverables; retain each path and requirement_id. Append a missed requirement rather than rewriting scope to fit an answer; refresh an existing declaration after a user update. Without a declaration, keep following the original request and explicit updates; do not invent requirement IDs or create a memo merely to deliver a result.

Retain protected_values for source values governed by disclosure restrictions, with the relevant rN or cN, original source_ref, exact internal value, omit/mask policy and permitted visible_prefix/visible_suffix Unicode counts. Omit unrequested identifiers. An omit policy uses zero visible counts; a mask may omit the value or display only authorized prefix/suffix with one asterisk per hidden character. Preserve registered restrictions in memo updates and check every public result, progress message and delivered file. Declaring a restriction does not prove compliance.

## record_repair_policy
{"maxRepairRounds":4}

## record_repair
A submitted optional record was rejected. pending_model_record_repairs supplies its exact repair_ref, error_path and rejected_records, or a receipt_source_ref for workspace(op="artifact"). The device action may already be complete. Continue useful task work; no device or completion call is paused by note rejection.

If these findings are needed, correct the note from retained originals with workspace(op="objects", screen_evidence=[corrected objects], record_repair_ref="exact pending repair_ref"). Each object needs a stable record_id; fields is keyed by field name and contains string value plus exact supplied source_ref. uncertainties is an array beside fields. Preserve original objects when repairing that receipt. A saved matching repair resolves that pending entry. repair_suggested and max_rounds bound the recommended repair effort, not execution permission. After repeated rejection, use originals for the needed facts and continue or report the actual gap; do not keep retrying the same JSON or repeat a device action.

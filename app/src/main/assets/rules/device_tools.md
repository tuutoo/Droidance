# Device tool descriptions

## android_read_description
Read native Android state, ContentProvider data, capabilities, system services, copied text or the bound screen. Start with android_read(op="device") for basic device facts; use other reads for missing fields. Preserve returned source scope, units, capture time and truncation. Full field definitions and examples are available through workspace(op="help", ref="android_read").

For GUI work without a target screen, open the target with android_act(op="open") under direct_route_selection; name resolution happens inside launch, so query apps only for ambiguity or failure. FOREGROUND_IS_DROIDANCE means the assistant UI is in front: open the authorized target. Screen reads use android_read(op="screen"). A crop magnifies retained pixels; refresh the full screen if the target is absent, clipped or still loading.

## android_act_description
Perform a documented native Android action, open a grounded Intent/Deep Link, or mutate a ContentProvider. For op="open", use the installed package/app or exact Intent action, URI and typed extras. For op="content", use insert/update/delete/batch with the Provider arguments. For op="keyboard", visible=true allows the selected native keyboard on the execution display; inspect the frame and tap its observed editor or keyboard controls. visible=false hides it. This mode does not type or submit text. Preserve returned identities and affected-row counts; verify the requested outcome rather than treating launch or mutation delivery as business success.

The app index is a discovery snapshot. APP_NAME_UNRESOLVED means name resolution failed, not app absence. Resolve an applicable installed package or route before substituting another destination.

## shell_description

Execute an Android sh command or multiline script on the frozen device shell backend. Pipelines, redirection and loops are supported. Commands have device-wide scope; the bound display is context, not shell isolation. Use bound screen tools for GUI interaction. Each invocation starts a fresh shell. Results include execution status, exit code, stdout/stderr, truncation and the effective timeout. Exit code alone does not establish task completion.

## shell_policy
{"maxCommandChars":65536,"defaultTimeoutMs":15000,"maxTimeoutMs":120000}

## shell_contract
Use command for the exact Android sh source; do not prepend host-side adb shell. Consult actual capabilities and errors before assuming executables, Root, App-UID permissions or file access. Shell filesystem paths are not workspace paths. Use workspace for task files and program for data-dependent composition. A requested timeout is capped by the remaining invocation budget. expect_screen_change defaults to true; use false for nonvisual work only. It does not prove read-only execution or expand authorization. Display actions use the bound Android tools so input and observation target the same display. A whole shell script produces one execution receipt: the final exit code does not verify every pipeline member or earlier statement. Preserve stdout/stderr and original source references; self-printed success is not independent verification. Nonzero exit may follow partial changes. Cancellation, timeout or unknown delivery requires inspecting actual state before selecting remaining work; never replay the whole script merely to repair output or logs.

## screen_act_description
Operate the bound display with visible controls in normalized 0..1000 coordinates or an exact observed target_id. When a user stopping condition is reached, preserve the interface and report it instead of dismissing or routing around it.

Keep background input on the bound virtual display. Use type_text with shortcut (default) or paste_key; the system keyboard may remain hidden. Do not enable or switch the device's IME, request android_act(op="keyboard", visible=true), or use Shell to claim main-display focus as a text-entry or recovery mechanism. This input constraint does not prohibit a keyboard-settings change explicitly requested by the user. A hidden keyboard is not an input failure. Verify delivery from the resulting editor; do not replay text whose delivery is already confirmed or remains uncertain.

For a small or uncertain target, crop it with android_read(op="crop") and click with coordinate_ref=crop_ref and crop-local x/y. After a tap without visible effect, compare tap_feedback with the current frame and re-localize the control. BACK leaves an overlay when that preserves needed state.

CAPTCHA and graphical human-verification challenges require ask_human or a non-success completion; never solve, refresh or switch entries to get around them. Only an explicitly authorized non-CAPTCHA one-time-code flow may use authorized current sources for that service, and its acceptance must be verified.

## platform_methods

For device facts, start with android_read(op="device"). It directly reports current default-display effective width/height, display-mode width/height and logical density without opening or capturing a virtual screen. Preserve screen_effective_source and screen_effective_scope: Display.getRealMetrics reports the largest app-accessible display region in the current system state, with rotation and any emulated display size applied. It does not measure the current application window or a panel-native resolution. Display.getMode supplies the separately reported current mode. Logical density comes from the same real metrics. Keep these source meanings distinct; report the returned values without inventing hardware or window interpretations. Do not start with Shell or GUI for these available fields. Use system reads only for missing fields. For a data-only Shell read, explicitly supply expect_screen_change=false; the default conservatively represents possible visual effects and can require a fresh observation. Interpret a retained read receipt rather than navigating to manufacture GUI completion evidence.

For battery percentage, charging state, temperature and power connection, first call android_read(op="device") and use its battery_status, battery_status_code, battery_charging and battery_power_connected fields together. If a dump is also needed, reconcile it against these captured platform fields rather than translating the numeric code from memory. For basic device facts, start with the typed device read; use system dumps only for fields that the typed source does not cover. Interpret Android battery status codes separately from plugged power: 2=charging, 3=discharging, 4=not_charging, 5=full, 1=unknown. USB powered=true alone never establishes active charging. Maximum current/voltage is not measured power. Preserve the source's reported units; do not invent units for undocumented vendor fields.

- `android_read(op="device")`: current manufacturer/model, Android version, local device time with timezone, next scheduled alarm-clock trigger, battery percentage, charging state, platform health status, temperature, voltage, media stream volume/min/max indices and mute state, internal-data volume total/available bytes, active-network addresses/gateways and transport/validation flags. Missing or null fields are unavailable, never zero or false. Display fields report the device default display: effective dimensions/density and current display-mode dimensions are separate values, not necessarily hardware-native resolution. Preserve screen_scope and screen_display_id; execution display_id is the task binding, not the device default display. Missing display fields remain unknown. This read never captures a screen frame or activates a virtual display. Media volume uses platform stream indices, not decibels or a vendor dump scale. Storage bytes describe the volume containing app internal data, not an SD card or an app quota; preserve storage_scope and distinguish decimal GB from binary GiB when converting. These flags do not identify a Wi-Fi SSID. The battery health enum describes the platform condition, not remaining capacity percentage or degradation; do not equate good with 100% capacity.
- `android_read(op="network")`: current Wi-Fi client connection from Android's read-only `cmd wifi status` on the same frozen backend, including when GUI work uses a virtual display. Prefer this for connected SSID, IP, connection state and link metrics. A full wifi service dump includes hotspot (SoftAp), saved-network and historical records: those are not the currently connected network. Never substitute a hotspot or saved SSID for a current client connection. If the platform status command is unavailable, preserve that failure and inspect another applicable source without guessing.
- `android_read(op="system")`: list actual Android service names. Supply `service` to obtain that service's argument-free `dumpsys` output on the frozen backend, including when GUI work uses a virtual display. This is device-wide data, not that display's screen. No arbitrary command, service argument, mutation or input is supported here. `line_filter` selects literal matching lines; optional `context_lines` (0-40) includes nearby lines with their original line numbers to preserve object headers; filtered/truncated output establishes only returned content. Empty output, exit errors and permission denials are not proof of absence. Respect denied access; do not use a different backend to bypass it.
- `android_read(op="clipboard")`: read the current clip through the frozen backend without modifying it. After intentionally selecting and copying authorized visible text, this can supply exact characters or a complete copied label. Text items are returned verbatim; a missing text item, unreadable clip or denied read remains unknown. The clip timestamp describes when it was copied, not which page or object supplied it. Keep clipboard reader user/device scope separate from execution_display_id and bind the text to the observed selection/copy before using it. Existing clipboard contents alone do not establish current source identity. Respect platform access failures.
- `android_read(op="capabilities", package_name=..., query=..., offset=...)`: discover installed Deep Link candidates with exact actions, templates and provenance. Follow next_offset when present. A catalog record, source URL, historical success count or resolvable Intent is not current page or task success. Supply the intended Android `intent_action`, `uri`, `mime_type` and `categories` as applicable to resolve the same Intent without launching it. A failed incomplete probe does not disprove a data/MIME-dependent route. Preserve an explicit target package throughout discovery and launch.
- `android_act(op="open", intent_action=..., int_extras=..., long_extras=..., string_extras=..., boolean_extras=...)`: explicit platform Intent. Use the actual declared extra names and supported types. `expect_screen_change=false` is available for a documented background action. Never infer a successful side effect from an ordinary app launch.
- `android_act(op="content", action="insert"|"update"|"delete"|"batch", uri=..., values=..., selection=..., selection_args=..., authority=..., operations=...)`: universal ContentProvider mutation. Returns structured status, affected row count, or generated URI. Does not mutate the display and grounds task completion upon verified success without requiring terminal screen capture.
- `android_read(op="content", uri=..., projection=..., selection=..., selection_args=..., limit=..., offset=...)`: permission-bound provider data. Supports pagination with offset/limit, returns has_more. Standard Android example: SMS inbox uses `content://sms/inbox` with columns `_id`, `address`, `body`, `date`, `read` and optional `sort_order="date DESC"`. Select only the fields and rows needed by the user. The App-UID ContentResolver bridge requests missing READ_SMS permission and resumes the same call; no SMS-app launch or screenshot is needed for this provider read. A denied, installer-restricted or unavailable provider is a blocker to report, not permission to switch identities and evade it. This method describes a capability, not a mandatory probe sequence. See https://developer.android.com/reference/android/provider/Telephony.Sms.Inbox.
- `shell`: an Android sh command or script using the command contract above. Command output and exit code establish execution only. Inspect actual values, units and scope; zero exit alone never proves the user goal.

For standard system tasks, read the relevant method in the enabled android-system-actions Skill before entering a manual editor. Its references provide typed Intent examples, direct Provider workflows and result checks. Native/Provider mutations can complete authorized tasks without opening a screen. Repair argument types before classifying ignored values as a vendor limitation. Do not exhaust every route as a checklist. Keep observed data separate from inference; original read receipts remain retrievable and citations are optional.

For system reads, service="battery" supplies the platform battery dump and service="wifi" supplies device Wi-Fi state when allowed. A literal line_filter can locate a requested field in a large dump; it is not a complete record. A SYSTEM_QUERY_NO_MATCH means the selected lines are empty, not that the underlying device feature is absent. Inspect the unfiltered scope or choose a relevant filter. Permission denial is a blocker, not a successful empty reading. A truncated result cannot establish absence beyond the returned prefix. If the original dump itself is truncated, narrow it with the literal line_filter and context_lines as needed to retain the object header and requested fields. Do not repeat session_read on that same truncated receipt expecting omitted original text to appear. Keep the selected scope explicit; a truncated review excerpt instead requires rereading its saved receipt. Report captured_at_ms exactly; use deterministic computation with an explicit timezone for any human-readable conversion, never an estimated time. Return only requested fields. Connection status does not require publishing SSID, MAC, IP or account identifiers; when an identifier is required, apply the user's masking requirements before including it in either a file or a public answer.

For official native operations, use public API/Provider readback instead of a GUI verification tour.
`android_read(op="device")` includes `next_alarm_clock` from `AlarmManager.getNextAlarmClock`
(API 21+, usable on Android 11+ without a scheduling permission). Its `status` is
scheduled, none or unavailable, and `trigger_at_ms` is epoch milliseconds or null.
It covers only the next scheduled alarm clock for the current user, not saved alarms,
labels or recurrence. An earlier alarm can hide a later requested alarm; preserve this
limit instead of opening a clock UI or retrying creation to obtain more evidence.

## Interpreting platform evidence
Treat current values, historical claims and conversion contracts separately. A current locale does not establish language-change history; an absent setting key does not establish a feature is unused. Preserve raw values and units when no verified range or conversion is available. A native read receipt's execution_display_id identifies the frozen display for this task, while scope=device describes command authority, not the target display of an individual query. Do not substitute a display name, owner UID, dimensions or a stale inventory entry for that binding. For display-ownership questions, use this binding or read android_read(op="device") for execution metadata; null or execution_binding_present=false means no execution display is bound, not display zero. A system display inventory alone does not identify which display belongs to this task. Truncated labels remain truncated or unknown until the exact identity is observed.

For wm size/density, an omitted -d queries the default display (0), not the task's execution display. Physical and Override lines describe the same queried display; a matching virtual-display size does not transfer ownership of the override. Query an explicit display ID when comparing displays. A display uniqueId suffix is not its logical display ID; retain the logical-display mapping from the system inventory. Report logical density separately from measured physical xDpi/yDpi.

Keep power connection, charging status and charging speed separate. For Android battery service status, 1 is unknown, 2 charging, 3 discharging, 4 not charging, and 5 full (Android BatteryManager). A USB power connection does not establish active charging. Maximum current/voltage fields are not measured instantaneous power or proof of a vendor fast-charge mode. Preserve unknowns when that mode is not reported. Temperature in the Android battery service is in tenths of a degree Celsius. When observations disagree, compare their source and capture time; do not silently substitute one for another. These are platform field definitions, not task completion conditions.
Source: https://developer.android.com/reference/android/os/BatteryManager

## text_input_clear_parameter
Required for type_text: a JSON boolean with no default. true replaces the entire editor; supply the complete intended text. false inserts at the selection; preserve existing text and include needed newline separators.

## direct_route_selection
When deeplink_enabled=true and the task requires navigating into an app, prefer an applicable catalog Deep Link over opening its launcher and navigating manually. Use the installed-app action index in the first round: android_act(op="open", package_name=observed_package, route_action=listed_action, route_params={required_name:grounded_value}, action_title="..."). The runtime fills and encodes the URI template; do not invent a URI, unsupported filter, or parameter. Supply all required_params together. Use native/Provider reads directly for data tasks that do not require entering an app.

If the needed catalog detail is absent, query capabilities once for the target package/action. Do not start at the app home merely to discover its catalog. When no applicable route exists or a concrete route fails, use android_act(op="open", package_name=observed_package, action_title="...") as the launcher fallback, then continue from its actual frame. Do not cycle through equivalent URIs or repeat the same failed method. When deeplink_enabled=false, use the launcher and GUI for third-party app navigation.

A parameter/schema error means repair the fields; it is not a failed route and must not trigger installation probes or route deletion. For open, action_title is a display label, route_action is a catalog action, and intent_action is an exact Android Intent identifier; the content mutation field action is not valid for open. After a corrected call still reports TARGET_UNAVAILABLE, TARGET_NOT_EXPORTED or TARGET_DISABLED, preserve the failure and choose the grounded launcher fallback. A login, network or backend failure does not prove a URI is obsolete. Never bypass denied access. Accepted delivery is not destination verification; check the requested page and parameter values from current evidence.

## route_failure_policy
{"unavailableFailureKinds":["TARGET_UNAVAILABLE","TARGET_NOT_EXPORTED","TARGET_DISABLED"]}

## capability_context_policy
{"maxChars":12000}

## capability_context
available_capability_index carries collected app facts (device_inventory) and optional local route knowledge (route_catalog); neither is current state or task progress. Use a listed route action directly for the first entry; query capabilities only for a missing detail. Do not repeat a rejected route with equivalent arguments, and verify the destination after any launch. The index is resent only when it changes.

## app_inventory_policy
{"retryAfterMs":30000,"missCacheEntries":128,"contextMaxChars":6144,"pageSize":20}

## device_inventory_contract
At task entry, device_inventory lists non-system launchable apps from the maintained device cache; index_columns defines each row. Launch resolves names itself. Use android_read(op="apps", query=..., package_name=...) only for ambiguity or a failed launch. A missing or unmatched entry never proves an app is uninstalled; with several candidates choose the grounded exact package or ask. A listed app is not proof that launch, login or the goal succeeded.

## intent_argument_repair
For android_act(op="open"), intent_action is an Android Intent action identifier, while action_title is the user-facing description. To open a known installed app's launcher, omit intent_action and use its observed app_name or package_name. If intent_action is explicitly needed, obtain the identifier from the capability/template or use the documented android.intent.action.MAIN launcher action. Example: android_act(op="open", app_name="observed app name", action_title="Open the selected app"). After no_activity_resolved, inspect intent_action in the receipt and repair these arguments; changing to an unrelated market URI or a shell launcher is not a repair of a malformed action. Preserve the virtual execution binding and verify the destination from its returned frame.

## intent_extra_contracts
[
  {"intent_action":"android.intent.action.SET_ALARM","extras":{
    "android.intent.extra.alarm.HOUR":{"type":"int","min":0,"max":23},
    "android.intent.extra.alarm.MINUTES":{"type":"int","min":0,"max":59},
    "android.intent.extra.alarm.MESSAGE":{"type":"string"},
    "android.intent.extra.alarm.SKIP_UI":{"type":"boolean"},
    "android.intent.extra.alarm.VIBRATE":{"type":"boolean"},
    "android.intent.extra.alarm.RINGTONE":{"type":"string"}
  }},
  {"intent_action":"android.intent.action.SET_TIMER","extras":{
    "android.intent.extra.alarm.LENGTH":{"type":"int","min":1,"max":86400},
    "android.intent.extra.alarm.MESSAGE":{"type":"string"},
    "android.intent.extra.alarm.SKIP_UI":{"type":"boolean"}
  }},
  {"intent_action":"android.intent.action.INSERT","uri_prefix":"content://com.android.calendar/","extras":{
    "beginTime":{"type":"long"},"endTime":{"type":"long"},"allDay":{"type":"boolean"},
    "title":{"type":"string"},"description":{"type":"string"},"eventLocation":{"type":"string"}
  }}
]

## native_ime_input_policy
{"timeoutMs":2500,"pollMs":50}

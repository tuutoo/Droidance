# Model context message templates

## task_entry
Act on the original request and explicit updates. Ordinary text during execution is a brief live status. Use report_result for useful partial results before substantial remaining work or a human handoff. When the full answer is ready, submit it directly with done or present_result_card. Follow batch_contract for screen actions and collection_contract for findings across views.

The latest message carries the current frame, runtime facts and working state; older images are historical and user instructions remain authoritative. Judge progress from the requested fields or target state, not from delivery or changed pixels. Request another observation only for a relevant changed state, an unread required value or conflicting evidence. Preserve completed batch effects and choose only remaining work.

## recent_tool_feedback_limit
8

## additional_context
Additional context

## preloaded_screen
Preloaded current Android ScreenState:

## raw_observation_reference
Frame reference: {observationId}. The runtime retains this original image for later retrieval. Cite it when useful or requested; copying its reference is not a prerequisite for navigation or completion.

## current_screenshot
Current Android UI screenshot. Only this frame binds controls. Batch grounded actions, including an editor's focus tap, type_text and next independent visible control. Use collect for consecutive views under collection_contract. For wheel correction follow batch_contract. Stop for new readings or submission checks. The observation_ref identifies this frame.

## retained_findings
These are source-linked observations previously written by this Agent. They preserve task memory across views, but may be mistaken or describe an earlier screen. Use the current frame for the next action and retrieve original sources when a value or conclusion needs verification. Source binding establishes provenance, not correctness.

## current_screen_state
This message carries the latest Android UI ScreenState.

## frame_dimensions
Attached frame dimensions: {width}x{height} pixels (rendering metadata only).

## action_coordinates
Action coordinate space: normalized integers x=0..1000 and y=0..1000; (0,0) is top-left and (1000,1000) is bottom-right. Mapping to this frame and the device is automatic.

## cropped_frame
This is a cropped frame. Keep using its complete normalized 0..1000 action space; crop-to-device mapping is internal.

## structured_mode
Perception mode: structured screen state.

## missing_screen_state_entry
No current screen is attached; retained display content is not current evidence.

## screen_unchanged_frame
The current screen is byte-identical to the previous-round screenshot. No duplicate image is attached; the frame fingerprint and screen state below refer to that same frame.

## current_vision_pure_vlm
Pure VLM mode. The screenshot is the primary visual source.

# M3.5 Demo Design: "The Fab That Never Stops"

## Narrative Summary

The demo tells one story: **a 5-wafer lot enters the fab, equipment fails mid-process, the system self-heals using OCAP rules and crash recovery, and all wafers complete successfully despite the chaos.** The user is not a spectator but an operator watching a live system that reacts intelligently to failures.

The demo runs automatically with a single click. No manual fault injection needed -- faults are built into the scenario at a configurable rate (default 20% per equipment interaction). The user can adjust fault probability in real-time via a slider.

---

## Section 1: Demo Storyboard

### Act 1: Setup (Landing)

**What the user sees:**
- The `/fab-demo/m35` page loads with the existing factory floor SVG (same 2-row equipment layout: Stocker, LITHO, CDSEM, ETCH, DEP, CMP, etc.)
- A new panel titled **"Self-Healing Demo"** appears on the right side, replacing the Domain Event sidebar area
- The control bar has new elements:
  - **"Run Self-Healing Demo"** primary button (amber) -- single click starts everything
  - **Fault Probability** slider (0-50%, default 20%) -- controls how often equipment fails
  - **Scenario selector** -- "OCAP Rework + Crash", "Send-Ahead with OCAP", "Multi-WorkOrder Chaos"
  - **"Inject Crash Now"** danger button -- manually trigger actor stop+restart mid-pipeline
- A **Recovery Status** badge in the top-right corner shows: `Idle | Recovering | Recovered (3 phases skipped)`  
- A **Pipeline Timeline** panel at the bottom shows a horizontal progress bar with phase markers (green=done, amber=current, gray=pending, red=failed+recovered)

**Narrative context visible on screen:**
- Tooltip text near the scenario selector: *"Equipment faults occur randomly. The system will self-heal using OCAP rules and crash recovery."*
- The route graph panel shows the full DAG of the selected scenario, with OCAP rule nodes highlighted in purple

**What the user does:**
1. Selects a scenario from the dropdown (default: "OCAP Rework + Crash")
2. Adjusts Fault Probability to 20% (or leaves default)
3. Clicks **"Run Self-Healing Demo"**

### Act 2: Normal Operation (Golden Path)

**What happens:**
- The pipeline starts executing: Load FOUP from Stocker
- Equipment nodes light up one by one as FOUP moves through STOCKER -> LITHO -> CDSEM
- The **Pipeline Timeline** at the bottom shows phases advancing in real-time (green bars fill left to right)
- The **Aggregate State Panel** shows Lot and wafer states updating
- The **Ledger Panel** highlights each step as it completes

**UI feedback:**
- Equipment state: `Idle` -> `Busy` -> `Processing` -> `Idle` (color transitions: gray -> blue -> amber -> gray)
- FOUP icon animates along equipment paths (existing animation)
- Timeline entries stream in with timestamps
- The global status at top-right shows: `Processing: Litho Recipe LITHO-28-001`

**User impression:** *"This is a normal, well-behaved fab pipeline. Equipment works, wafers get processed, everything is predictable."*

**Duration:** ~5-8 seconds of smooth operation before Act 3 begins.

### Act 3: Chaos Begins

**What happens (two types of chaos, both happen):**

**Chaos Type A -- Equipment Fault (simulated by the 20% fault probability):**
- During TrackIn at LITHO-01 or CDSEM-01, the equipment simulator returns a `hardware_fault` or `processing_error`
- The `PipelineStageFailed` event fires
- The equipment node turns **red** (stroke and status text)
- A red flash animation pulses on the failed equipment
- A new **"Fault Event"** card appears in a **Fault Timeline** panel showing: `LITHO-01: hardware_fault at TrackIn`

**Chaos Type B -- Actor Crash + Recovery (the system self-healing showcase):**
- Mid-pipeline (e.g., after CDSEM measurement completes), the user sees the **Recovery Status** badge change to `CRASHED`
- The factory floor freezes momentarily (1-2 second pause)
- The Recovery Status badge changes to `RECOVERING (replaying 7 events...)`
- The pipeline continues from where it left off -- phases already done show as green in the timeline with a small "R" badge (recovered)
- The user sees a timeline entry: `[RECOVERY] Pipeline resumed from phase 8/16 (8 phases skipped)`

**If the user clicks "Inject Crash Now":**
- A simulated "kill -9" happens (the EventSourcedBehavior actor stops and recreates)
- Same recovery sequence plays out on demand

**What the user sees on screen during chaos:**
- The **Pipeline Timeline** shows a red marker at the failed phase, then a green "RECOVERED" badge
- The **Recovery Status** badge transitions: `CRASHED` (red) -> `RECOVERING` (amber, with spinner) -> `RECOVERED` (green)
- The **OCAP Rule Fire Log** panel shows triggered rules with timestamps:
  ```
  [14:23:01] OCAP-001: Borderline -> Rework (W3: cd_nm=34.8)
  [14:23:02] OCAP-002: Far Out -> Scrap (W5: cd_nm=43.2)
  ```
- The **Domain Event Sidebar** (if toggled open) shows the raw event replay during recovery
- A toast notification appears: `System self-healed: 2 OCAP rules triggered, 1 crash recovered`

**User impression:** *"Things are breaking, but the system is tracking every failure and taking action -- nothing is stuck or lost."*

### Act 4: Self-Healing in Action

**What happens (the core narrative):**

**OCAP Rules Fire:**
- Based on measurement results, OCAP rules trigger:
  - `OCAP-001 (Borderline -> Rework)`: W3 measured at cd_nm=34.8, automatically routed to rework loop
  - `OCAP-002 (Far Out -> Scrap)`: W5 measured at cd_nm=43.2, automatically scrapped
  - `OCAP-003 (Borderline -> Notify)`: Area engineer notification sent
- Each triggered rule appears in the **OCAP Rule Fire Log** panel with:
  - Rule ID and name
  - Affected wafer(s)
  - Action taken (Rework/Scrap/Notify/AdjustRecipe)
  - Decision path visualization

**Pipeline Recovery After Crash:**
- After an actor crash (either simulated or from an equipment fault cascade):
  - System replays journal events from `FabPipelineExecutionActor`
  - `RecoveryCompleted` signal fires -> `resumeFromIndex()` called
  - Already-completed phases are skipped
  - The FOUP icon jumps to the correct position (no re-animating past phases)
  - Pipeline continues from the interrupted phase

**Dynamic DAG Weaving (OCAP Branch):**
- When an OCAP rule triggers "Rework", the pipeline dynamically injects a `SubProcessRef(ReworkLoop)` into the execution plan
- The route graph panel updates to show the injected sub-process as a highlighted path
- A label appears: `OCAP injected: Rework Loop -> 2 wafers rerouted`

**User sees:**
- The **OCAP Rule Fire Log** panel populates with triggered rules
- The **Pipeline Timeline** shows the rework loop as an additional set of phases
- The **Aggregate State Panel** shows wafers moving between source/rework lots
- A summary statistic bar at the bottom updates in real-time:
  ```
  Active: 3 | Passed: 2 | Reworked: 2 | Scrapped: 1 | Faults: 2 | Recoveries: 1
  ```
- The **Recovery Status** badge shows total recovery count: `3 recoveries this session`

**User impression:** *"The system doesn't just log failures -- it actively re-routes around them. OCAP provides the decision logic, Event Sourcing provides the recovery mechanism, and the pipeline weaves it all together."*

### Act 5: Resolution (Wafers Complete)

**What happens:**
- All wafers complete processing despite:
  - 2 equipment faults (LITHO-01 TrackIn failed once, CDSEM-01 measurement timeout once)
  - 1 actor crash (simulated mid-pipeline)
  - 2 OCAP rule firings (1 rework, 1 scrap)
- The Demo Completed event fires with final statistics
- The **Recovery Status** badge shows: `COMPLETED (3 events recovered)`
- A final toast notification: `Demo Complete: 5 wafers processed with 2 faults + 1 crash -- zero stuck work orders`

**Final UI state (shareable screenshot moment):**
- The **Pipeline Timeline** is fully green with red markers at failure points and "R" badges at recovery points
- The **OCAP Rule Fire Log** shows the complete decision history
- The **Recovery Log** shows the event replay count and skipped phases
- The **Summary Panel** shows:
  ```
  Total Wafers: 5
  Passed: 4 | Scrapped: 1
  Equipment Faults: 2 (100% recovered)
  Actor Crashes: 1 (100% recovered)
  OCAP Rules Triggered: 2
  Pipeline Recovery Time: 342ms
  ```
- A **"View Event Sourcing Ledger"** button appears, clicking it opens the full event log sidebar showing every persisted event

**User impression:** *"Even with faults and crashes, every wafer completed. The system is boringly reliable -- boring is good in manufacturing."*

---

## Section 2: UI Architecture

### 2.1 New Pages

#### `/fab-demo/m35` -- Self-Healing Demo Page

**Purpose:** The main M3.5 demo experience. Extends the existing `/fab-demo` page layout with M3.5-specific panels.

**Twirl Template:**
- `app/views/fabM35Demo.scala.html` -- new template, closely modeled on `fabSimulation.scala.html`
- Reuses the same header, control bar, factory floor SVG, aggregate panel, ledger panel, and timeline
- Adds:
  - **OCAP Rule Fire Log** panel (replaces or extends the domain event sidebar)
  - **Recovery Status** badge in control bar
  - **Fault Probability** slider in control bar
  - **Pipeline Timeline** horizontal bar (bottom panel, replaces timeline text view when in M3.5 mode)
  - **Recovery Log** collapsible panel showing event replay details
  - **"Inject Crash Now"** button in control bar
  - **"Run Self-Healing Demo"** primary button

**CSS additions (in same template):**
- OCAP rule log styling (purple-themed entries, severity badges)
- Recovery status transitions (CRASHED red, RECOVERING amber pulse, RECOVERED green)
- Pipeline timeline bar (horizontal segmented bar with colored phases)
- Toast notification for recovery events (slide-in from top-right, auto-dismiss)

**Play Route:**
```
GET     /fab-demo/m35                     controllers.FabDemoController.m35Demo()
GET     /ws/fab-demo/m35/events           controllers.FabDemoController.m35Socket
```

#### `/fab-demo/m35/ocap-editor` -- OCAP Rules Editor (future stretch)

**Purpose:** Visual editor for configuring OCAP rules. Condition builder with dropdowns for metric, operator, value. Action selector. Priority ordering. Route binding.

**Twirl Template:**
- `app/views/fabM35OcapEditor.scala.html` -- new template

**Play Route:**
```
GET     /fab-demo/m35/ocap-editor          controllers.FabDemoController.ocapEditor()
```

### 2.2 Existing Pages That Need Modification

#### `/fab-demo/simulation` (fabSimulation.scala.html)

**Changes required:**
- Add an M3.5 mode toggle to the control bar (a button: "Switch to Self-Healing Mode" that navigates to `/fab-demo/m35`)
- Alternatively, fold M3.5 controls directly into the existing simulation page when a new scenario type is selected. The simpler approach is a separate page (less risk of breaking existing functionality).

**Recommended approach:** Keep the existing `/fab-demo` page untouched. Build the M3.5 demo as a parallel page at `/fab-demo/m35`. This avoids regression risk and allows side-by-side comparison.

#### `app/controllers/FabDemoController.scala`

**Changes required:**
- Add `m35Demo()` action that renders `fabM35Demo.scala.html`
- Add `m35Socket` WebSocket endpoint (can reuse the same event hub pattern, or create a separate M3.5-specific hub with additional event types)
- Add `POST /api/fab-demo/m35/start` endpoint
- Add `POST /api/fab-demo/m35/inject-crash` endpoint (triggers actor stop + restart)
- Add `GET /api/fab-demo/m35/ocap-rules` endpoint (returns current OCAP rules for display)
- Add `GET /api/fab-demo/m35/recovery-status/:workOrderId` endpoint

#### `conf/routes`

**New routes to add:**
```
# M3.5 Self-Healing Demo
GET     /fab-demo/m35                               controllers.FabDemoController.m35Demo()
GET     /ws/fab-demo/m35/events                      controllers.FabDemoController.m35Socket
POST    /api/fab-demo/m35/start                      controllers.FabDemoController.m35Start()
POST    /api/fab-demo/m35/inject-crash/:workOrderId  controllers.FabDemoController.m35InjectCrash(workOrderId: String)
GET     /api/fab-demo/m35/ocap-rules                 controllers.FabDemoController.m35GetOcapRules()
GET     /api/fab-demo/m35/recovery-status/:id         controllers.FabDemoController.m35RecoveryStatus(id: String)
GET     /api/fab-demo/m35/fault-history/:id           controllers.FabDemoController.m35FaultHistory(id: String)
```

### 2.3 New JavaScript Files

#### `public/javascripts/fab_m35_demo.js`

**Purpose:** M3.5-specific UI logic. Extends patterns from `fab_demo.js`.

**Key functions:**
- `initM35ObservableSubscriptions()` -- wires M3.5-specific event streams (OCAP, recovery, fault)
- `updateRecoveryStatus()` -- updates the Recovery Status badge with transitions
- `updatePipelineTimeline()` -- renders the horizontal phase progression bar
- `addOcapRuleEntry()` -- adds a row to the OCAP Rule Fire Log
- `addRecoveryEntry()` -- adds a row to the Recovery Log
- `updateFaultProbability()` -- responds to Fault Probability slider changes
- `triggerCrash()` -- calls the inject-crash API endpoint
- `startM35Demo()` -- calls the m35-start API endpoint
- `updateM35Summary()` -- updates the summary stat bar

#### `public/javascripts/fab_m35_observable.js`

**Purpose:** M3.5-specific RxJS observable streams and reducers.

**New streams (in addition to those from `fab_observable.js`):**
- `ocapAction$` -- `OcapActionTriggered` events
- `pipelineFailure$` -- `PipelineStageFailed` events  
- `recoveryEvent$` -- new event type `RecoveryEvent` (to be added to `FabSimulationEvent`)
- `faultInjected$` -- new event type `FaultInjected` (simulated fault notification)
- `dynamicWeave$` -- new event type `DynamicStageInjected` (OCAP branch injection)

### 2.4 New WebSocket Event Types (FabSimulationEvent)

Add to `app/net/imadz/fab/events/FabSimulationEvent.scala`:

```scala
// M3.5 Recovery Events
case class RecoveryEvent(
  workOrderId: String,
  recoveryType: String,    // "CRASH_DETECTED" | "RECOVERING" | "RECOVERED" | "COMPLETED"
  eventsReplayed: Int,
  phasesSkipped: Int,
  recoveryTimeMs: Long,
  detail: String
) extends FabSimulationEvent

// M3.5 Fault Injection Notification
case class FaultInjected(
  workOrderId: String,
  equipmentId: String,
  faultType: String,       // "hardware_fault" | "processing_error" | "timeout" | "actor_crash"
  phaseName: String,
  resolved: Boolean,
  resolution: Option[String]  // "OcapReroute" | "Retry" | "Recovery"
) extends FabSimulationEvent

// M3.5 Dynamic DAG Weave Notification
case class DynamicStageInjected(
  workOrderId: String,
  parentNodeId: String,
  injectedStageType: String,  // "ReworkLoop" | "SendAheadPilot" | "HoldRelease"
  triggeredByRule: Option[String],
  stageIndex: Int
) extends FabSimulationEvent

// M3.5 Pipeline Timeline Snapshot (periodic or on state change)
case class PipelineTimelineSnapshot(
  workOrderId: String,
  totalPhases: Int,
  completedPhases: Int,
  currentPhase: Option[String],
  currentPhaseIndex: Int,
  failedPhases: Seq[String],
  recoveredPhases: Seq[String],
  ocapTriggers: Int
) extends FabSimulationEvent
```

### 2.5 Event Flow Diagram

```
Equipment Simulator (hardware_fault)
  -> FabPipelineProcessor catches failure
  -> publishes PipelineStageFailed (WebSocket)
  -> UI shows red flash on equipment, fault log entry
  -> Processor applies OCAP rules
  -> OcapEngine.evaluate() checks condition against wafer state
  -> If rule matches: publishes OcapActionTriggered (WebSocket)
  -> UI shows OCAP rule fire in log, re-route visualization
  -> Processor injects new stage (e.g., ReworkLoop)
  -> publishes DynamicStageInjected (WebSocket)
  -> Pipeline continues with injected stage

Actor Crash (inject-crash or real)
  -> FabPipelineExecutionActor stops
  -> FabPipelineExecutionActor re-creates (sharding restarts)
  -> RecoveryCompleted signal fires
  -> publishes RecoveryEvent("RECOVERING") (WebSocket)
  -> UI shows recovery status updating
  -> resumeFromIndex() skips completed phases
  -> publishes RecoveryEvent("RECOVERED") (WebSocket)
  -> Pipeline continues from interrupted phase
  -> publishes PipelineTimelineSnapshot (WebSocket) with updated state
```

---

## Section 3: Implementation Plan

### Priority 1: New Page + Basic Event Wiring (Effort: Medium, ~3-4 days)

**What to build first:** The `/fab-demo/m35` page shell, control bar, WebSocket event wiring, and passive visualization (show events without active response).

**Files to create:**
| File | Purpose |
|------|---------|
| `app/views/fabM35Demo.scala.html` | Main M3.5 demo page template |
| `public/javascripts/fab_m35_demo.js` | M3.5 UI state + DOM rendering |
| `public/javascripts/fab_m35_observable.js` | M3.5 RxJS observable streams |

**Files to modify:**
| File | Change |
|------|--------|
| `app/controllers/FabDemoController.scala` | Add `m35Demo()`, `m35Socket` actions |
| `conf/routes` | Add new routes |
| `app/net/imadz/fab/events/FabSimulationEvent.scala` | Add M3.5 event types |
| `app/net/imadz/fab/service/FabDemoService.scala` | Add `startM35Demo()` method |

**Testability:**
- New page renders with existing equipment SVG
- WebSocket connects and streams all existing event types
- M3.5 event types (RecoveryEvent, FaultInjected) can be emitted manually for testing

### Priority 2: OCAP Rule Fire Log Panel (Effort: Small, ~1-2 days)

**What to build:** The OCAP Rule Fire Log panel that displays triggered rules in real-time. Reuses `OcapActionTriggered` events (already emitted by `OcapEngine`).

**Files to create/modify:**
| File | Change |
|------|--------|
| `public/javascripts/fab_m35_demo.js` | Add `addOcapRuleEntry()` render function |
| `public/javascripts/fab_m35_observable.js` | Add `ocapAction$` stream |
| `app/views/fabM35Demo.scala.html` | Add OCAP Rule Fire Log panel HTML+CSS |

**What the user sees:**
- A scrollable panel with entries showing: timestamp, rule ID, rule name, wafer affected, action taken
- Each entry is color-coded by action type (HOLD=amber, REWORK=purple, SCRAP=red, NOTIFY=blue, ADJUST=green)
- Rule priority is indicated by a small badge

### Priority 3: Pipeline Timeline Bar (Effort: Medium, ~2-3 days)

**What to build:** The horizontal pipeline timeline that shows phase progression, failure points, and recovery markers.

**Files to create/modify:**
| File | Change |
|------|--------|
| `public/javascripts/fab_m35_demo.js` | Add `updatePipelineTimeline()` SVG renderer |
| `app/net/imadz/fab/events/FabSimulationEvent.scala` | Already has `PipelineTimelineSnapshot` |

**What the user sees:**
- A horizontal segmented bar, one segment per pipeline phase
- Colors: green (completed), amber (current), gray (pending), red (failed), purple (OCAP-injected)
- Recovery markers: small "R" badge on recovered phases
- Current phase indicator: pulsing amber border
- Hover tooltip shows phase name, duration, and any failure details

### Priority 4: Crash Injection + Recovery Visualization (Effort: Medium, ~2-3 days)

**What to build:** The "Inject Crash Now" button, recovery event publishing from `FabPipelineExecutionActor`, and the Recovery Status badge transitions.

**Files to create/modify:**
| File | Change |
|------|--------|
| `app/net/imadz/fab/chain/FabPipelineExecutionActor.scala` | Add publisher callback for RecoveryEvent |
| `app/controllers/FabDemoController.scala` | Add `m35InjectCrash()` action |
| `app/net/imadz/fab/service/FabDemoService.scala` | Add `injectCrash()` method |
| `public/javascripts/fab_m35_demo.js` | Add `updateRecoveryStatus()`, `triggerCrash()` |

**Key design decisions:**
- Crash injection: Send a poison pill to the pipeline actor's `ActorRef`, then let sharding auto-restart it
- Recovery detection: The `RecoveryCompleted` signal in `EventSourcedBehavior` fires the recovery event
- Recovery visualization: 3-stage badge transition (CRASHED -> RECOVERING -> RECOVERED) with timing info

### Priority 5: Fault Probability Slider + Dynamic Fault Injection (Effort: Small, ~1-2 days)

**What to build:** The fault probability slider that controls how often equipment simulators return failures. Dynamic fault injection in `GenericEquipmentSimulator`, `LithographySimulator`, etc.

**Files to create/modify:**
| File | Change |
|------|--------|
| `app/net/imadz/fab/simulation/GenericEquipmentSimulator.scala` | Add fault probability parameter, random failure generation |
| `app/net/imadz/fab/simulation/LithographySimulator.scala` | Add fault probability parameter |
| `app/net/imadz/fab/simulation/CdSemSimulator.scala` | Add fault probability parameter |
| `app/net/imadz/fab/service/FabDemoService.scala` | Thread fault probability through pipeline context |
| `public/javascripts/fab_m35_demo.js` | Add `updateFaultProbability()` |

**Key design decisions:**
- Fault probability is set at demo start and can be changed mid-demo (applies to next equipment interaction)
- Default 20% means ~1 in 5 equipment interactions will fail
- Failure types: `hardware_fault` (retryable), `processing_error` (retryable), `timeout` (retryable), `fatal` (non-retryable, triggers OCAP scrap)
- Retryable failures auto-retry up to 3 times before OCAP kicks in

### Priority 6: Auto-Run Scenario With Embedded Faults (Effort: Medium, ~2-3 days)

**What to build:** The "Run Self-Healing Demo" button that starts a scenario with pre-configured fault injection points and automatic crash recovery demonstration. No manual intervention needed.

**Files to create/modify:**
| File | Change |
|------|--------|
| `app/net/imadz/fab/service/FabDemoService.scala` | Add `startM35Demo()` with built-in fault profile |
| `app/net/imadz/fab/chain/FabScenarioPipeline.scala` | Add M3.5-specific pipeline variant with OCAP weaving |
| `app/controllers/FabDemoController.scala` | Add `m35Start()` action |

**Scenario profiles built-in:**
1. **"OCAP Rework + Crash"** (default): 5-wafer rework scenario with 20% fault rate. Guarantees at least 1 OCAP rule fire and 1 crash+recovery.
2. **"Send-Ahead with OCAP"**: Send-ahead pilot scenario with OCAP rules for pilot wafer classification. Faults during pilot processing trigger re-route.
3. **"Multi-WorkOrder Chaos"**: 3 concurrent work orders with 30% fault rate. Tests multi-actor recovery and cluster resilience. (M3.9 overlap)

### Priority 7: Summary Statistics + Final Screen (Effort: Small, ~1 day)

**What to build:** The real-time summary stat bar and the final completion screen with shareable screenshot framing.

**Files to create/modify:**
| File | Change |
|------|--------|
| `public/javascripts/fab_m35_demo.js` | Add `updateM35Summary()`, final screen render |
| `app/views/fabM35Demo.scala.html` | Add summary stat bar, completion overlay |

**What the user sees:**
- Bottom stat bar showing live counts: Active / Passed / Reworked / Scrapped / Faults / Recoveries
- Completion overlay with final statistics, recovery timeline, and "View Full Event Log" button
- The summary panel in the existing layout is replaced with the M3.5-enhanced version

### Priority 8: Route Graph OCAP Node Highlighting (Effort: Small, ~1 day)

**What to build:** Visual indication on the route graph DAG showing which nodes have OCAP rules, and highlighting the OCAP-triggered path when a rule fires.

**Files to create/modify:**
| File | Change |
|------|--------|
| `public/javascripts/route_graph.js` | Add OCAP node highlighting, OCAP path animation |
| `public/javascripts/fab_m35_demo.js` | Wire `ocapAction$` to route graph highlight |

**What the user sees:**
- Route graph nodes that have OCAP rules show a small purple shield icon
- When an OCAP rule fires, the affected path glows purple
- OCAP-injected sub-processes appear as highlighted dashed paths

---

## Summary: Implementation Order

| Priority | Feature | Effort | Dependencies |
|----------|---------|--------|--------------|
| P1 | New page + event wiring | 3-4 days | None |
| P2 | OCAP Rule Fire Log panel | 1-2 days | P1 |
| P3 | Pipeline Timeline bar | 2-3 days | P1 |
| P4 | Crash injection + recovery visualization | 2-3 days | P1 |
| P5 | Fault probability slider | 1-2 days | P1 |
| P6 | Auto-run scenario with embedded faults | 2-3 days | P2, P3, P4, P5 |
| P7 | Summary statistics + final screen | 1 day | P6 |
| P8 | Route graph OCAP highlighting | 1 day | P2 |

**Total estimated effort:** 13-19 days (3-4 developer-weeks)

**Running demo at P2:** After P1 + P2, the page shows OCAP rules firing in real-time. Good for stakeholder demo even without crash recovery visualization.

**Running demo at P6:** After P6, the complete narrative arc works: one-click start, equipment fails, OCAP fires, crash recovery, all wafers complete.

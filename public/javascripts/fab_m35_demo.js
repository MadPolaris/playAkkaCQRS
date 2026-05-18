/**
 * Fab M3.5 — Self-Healing Demo UI State Management + DOM Rendering
 *
 * Key functions (exposed on window for inline onclick handlers):
 *   startM35Demo, triggerCrash, updateFaultProbability,
 *   toggleAggregatePanel, toggleRecoveryLog, fetchEntityState,
 *   updateRecoveryStatus, updatePipelineTimeline, addOcapRuleEntry,
 *   addRecoveryEntry, showToast
 */
(function() {
  'use strict';

  // ===================================================================
  // State
  // ===================================================================
  var state = {
    scenarioType: 'ocap-rework-crash',
    faultProbability: 0.2,
    workOrderId: null,
    paused: false,
    recoveryStatus: 'IDLE',
    ocapCount: 0,
    faultCount: 0,
    recoveryCount: 0,
    aggregatePanelOpen: true,
    recoveryLogOpen: true,
    timelinePhaseNames: [
      'Load', 'T:STK→LTH', 'At LITHO', 'TrackIn',
      'Process', 'TrackOut', 'T:LTH→CDS', 'At CDSEM',
      'Measure', 'Classify', 'Split', 'Rework',
      'Re-Measure', 'Re-Classify', 'Merge', 'T:CDS→STK',
      'Seal', 'Complete'
    ]
  };

  // M3.5 phase colors for timeline bar
  var phaseColors = {
    completed: '#3fb950',
    current: '#f59e0b',
    pending: '#30363d',
    failed: '#f85149',
    recovered: '#2ea043',
    ocap: '#a855f7'
  };

  // ===================================================================
  // Init — wire M3.5 streams to DOM handlers
  // ===================================================================
  function initM35Demo() {
    var S = window._m35Streams;
    var sub = window._m35Subscribe;

    if (!S) {
      console.warn('M3.5 streams not available. Retrying in 500ms...');
      setTimeout(initM35Demo, 500);
      return;
    }

    // --- OCAP Rule Fire Log + Route Graph Highlight (P8) ---
    sub(S.ocapAction$, function(data) {
      addOcapRuleEntry(data);
      updateM35Summary({ocapTriggers: ++state.ocapCount});

      // Highlight route graph nodes if data has node references
      if (data.affectedNodeIds && data.affectedNodeIds.length > 0) {
        if (typeof window.highlightOcapNodes === 'function') {
          window.highlightOcapNodes(data.affectedNodeIds);
        }
      }
      if (data.fromNodeId && data.toNodeId) {
        if (typeof window.highlightOcapPath === 'function') {
          window.highlightOcapPath(data.fromNodeId, data.toNodeId, data.ruleId || 'OCAP');
        }
      }
    });

    // --- Pipeline Failure (also shows as fault) ---
    sub(S.pipelineFailure$, function(data) {
      addFaultEntry(data);
      updateM35Summary({faults: ++state.faultCount});
    });

    // --- Recovery Events ---
    sub(S.recoveryEvent$, function(data) {
      updateRecoveryStatus(data);
      addRecoveryEntry(data);
      if (data.recoveryType === 'RECOVERED' || data.recoveryType === 'COMPLETED') {
        updateM35Summary({recoveries: ++state.recoveryCount});
      }
    });

    // --- Fault Injected ---
    sub(S.faultInjected$, function(data) {
      addFaultEntry(data);
    });

    // --- Dynamic Stage Injected (OCAP branch) + Route Graph (P8) ---
    sub(S.dynamicWeave$, function(data) {
      showToast('OCAP injected: ' + data.injectedStageType + ' (stage ' + data.stageIndex + ')', 'ocap');
      // Show dynamic stage on route graph
      if (data.parentNodeId && typeof window.showDynamicStage === 'function') {
        window.showDynamicStage(data.injectedStageType, data.parentNodeId, data.stageIndex);
      }
    });

    // --- Pipeline Timeline Snapshot ---
    sub(S.timelineSnapshot$, function(data) {
      updatePipelineTimeline(data);
    });

    // --- Standard events for M3.5 page ---
    sub(S.equipmentState$, updateEquipmentNode);
    sub(S.foupInTransit$, animateFoupMovement);

    // --- Measurement / Classification ---
    sub(S.measurement$, updateClassificationWheel);

    // --- Aggregate ---
    var aggModel$ = S.aggregateState$.pipe(
      rxjs.operators.scan(aggregateReducer, {lots: {}, wafers: {}})
    );
    sub(aggModel$, renderAggregatePanel);

    // --- Demo Lifecycle ---
    sub(S.demoStarted$, function(data) {
      state.demoStartTime = Date.now();
      document.getElementById('recoveryStatusBadge').textContent = 'Running';
      document.getElementById('recoveryStatusBadge').className = 'recovery-badge idle';
      showToast('Demo started: ' + (data.name || data.scenarioId), 'info');

      // Load route graph for the scenario
      var scenarioMap = {
        'ocap-rework-crash': 'scrap-downgrade',
        'send-ahead-ocap': 'send-ahead-pilot',
        'multi-workorder-chaos': 'sampling-demo'
      };
      var routeScenarioId = scenarioMap[state.scenarioType] || (data.name || '').toLowerCase().replace(/\s+/g, '-');
      var routeContainer = document.getElementById('routeGraphContainer') || createRouteGraphContainer();
      if (routeContainer && typeof window.loadRouteGraph === 'function') {
        window.loadRouteGraph(routeScenarioId, routeContainer);
      }
    });

    sub(S.demoCompleted$, function(data) {
      // Prevent duplicate overlay on replay/recovery replay events
      if (state._completionShown) return;
      state._completionShown = true;

      updateRecoveryStatus({recoveryType: 'COMPLETED', detail: 'All wafers processed'});
      var passedMsg = (data.passedWafers || 0) + ' passed';
      var scrapMsg = (data.scrappedWafers || 0) + ' scrapped';
      showToast('Demo Complete: ' + passedMsg + ', ' + scrapMsg + ' — zero stuck WorkOrders', 'success');

      // Build stats from DemoCompleted event + accumulated state counters
      var stats = {
        totalWafers: data.totalWafers || 0,
        passed: data.passedWafers || 0,
        reworked: data.reworkedWafers || 0,
        scrapped: data.scrappedWafers || 0,
        faults: state.faultCount || 0,
        recoveries: state.recoveryCount || 0,
        ocapTriggers: state.ocapCount || 0,
        startTime: state.demoStartTime || Date.now()
      };

      if (window._m35Streams && window._m35Streams.statsAccumulator$) {
        window._m35Subscribe(window._m35Streams.statsAccumulator$.pipe(
          rxjs.operators.take(1)
        ), function(accStats) {
          if (accStats.faults > 0) stats.faults = accStats.faults;
          if (accStats.recoveries > 0) stats.recoveries = accStats.recoveries;
          if (accStats.ocapTriggers > 0) stats.ocapTriggers = accStats.ocapTriggers;
          if (accStats.startTime && accStats.startTime < stats.startTime) stats.startTime = accStats.startTime;
          showCompletionOverlay(stats);
        });
      } else {
        showCompletionOverlay(stats);
      }
    });

    sub(S.scrapEvent$, function(data) {
      handleScrapEvent(data);
    });

    sub(S.globalStatus$, function(data) {
      var stEl = document.getElementById('status-decision');
      if (stEl) {
        stEl.textContent = data.status + ': ' + (data.detail || '');
      }
    });

    // --- Timeline ---
    sub(S.timeline$, function(batch) {
      batch.forEach(function(evt) { addTimelineEntry(evt); });
    });

    // Load OCAP rules on init
    fetchOcapRules();
  }

  // ===================================================================
  // Demo Control
  // ===================================================================

  /** Start the M3.5 self-healing demo. Called from inline onclick. */
  window.startM35Demo = function() {
    var btn = document.querySelector('.controls button.primary');
    btn.textContent = 'Starting...';
    btn.disabled = true;

    var scenarioType = document.getElementById('m35ScenarioSelect').value;
    state.scenarioType = scenarioType;
    var faultProb = parseFloat(document.getElementById('faultProbabilitySlider').value) / 100.0;

    // Reset UI
    state._completionShown = false;
    resetM35UI();

    // Re-init M3.5 streams for fresh demo
    if (typeof window._m35Destroy === 'function') window._m35Destroy();
    if (typeof window._m35Init === 'function') window._m35Init();
    initM35Demo();

    // Start demo via API
    fetch('/api/fab-demo/m35/start', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        scenarioType: scenarioType,
        faultProbability: faultProb
      })
    })
    .then(function(r) { return r.json(); })
    .then(function(data) {
      if (data.workOrderId) {
        state.workOrderId = data.workOrderId;
        var woInput = document.getElementById('entityWorkOrderInput');
        if (woInput) woInput.value = data.workOrderId;
      }
      btn.textContent = 'Running...';
      btn.disabled = false;
      addTimelineEntry({type: 'Info', data: 'M3.5 demo: ' + (data.message || 'started')});
    })
    .catch(function(err) {
      btn.textContent = 'Run Self-Healing Demo';
      btn.disabled = false;
      addTimelineEntry({type: 'Error', data: 'Start failed: ' + err.message});
    });
  };

  /** Update fault probability. Called from inline oninput. */
  window.updateFaultProbability = function(val) {
    state.faultProbability = parseInt(val) / 100.0;
    var label = document.getElementById('faultProbLabel');
    if (label) label.textContent = val + '%';

    // Send to backend API
    var activeWo = state.workOrderId;
    if (activeWo) {
      fetch('/api/fab-demo/m35/fault-probability', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({probability: state.faultProbability})
      }).catch(function() { /* non-critical */ });
    }
  };

  /** Trigger crash injection. Called from inline onclick. */
  window.triggerCrash = function() {
    if (!state.workOrderId) {
      showToast('No active demo. Start a demo first.', 'error');
      return;
    }
    showToast('Injecting crash for WorkOrder ' + state.workOrderId + '...', 'error');
    addTimelineEntry({type: 'Fault', data: 'Injecting crash: ' + state.workOrderId});

    fetch('/api/fab-demo/m35/inject-crash/' + encodeURIComponent(state.workOrderId), {
      method: 'POST'
    })
    .then(function(r) { return r.json(); })
    .then(function(data) {
      addTimelineEntry({type: 'Recovery', data: 'Crash injected for ' + data.workOrderId});
    })
    .catch(function(err) {
      addTimelineEntry({type: 'Error', data: 'Crash injection failed: ' + err.message});
    });
  };

  // ===================================================================
  // Recovery Status Badge
  // ===================================================================

  function updateRecoveryStatus(data) {
    var badge = document.getElementById('recoveryStatusBadge');
    var detail = document.getElementById('recoveryDetail');
    if (!badge) return;

    var rt = data.recoveryType || 'IDLE';
    var cls = 'idle';
    var label = 'Idle';
    var det = data.detail || '';
    var tooltip = '';

    switch (rt) {
      case 'CRASH_DETECTED':
        cls = 'crashed';
        label = 'CRASHED';
        tooltip = 'Actor has crashed unexpectedly — EventSourcedBehavior will automatically restart from the last snapshot. No data is lost.';
        break;
      case 'RECOVERING':
        cls = 'recovering';
        label = 'RECOVERING (' + data.eventsReplayed + ' events, +' + data.phasesSkipped + ' skip)';
        tooltip = 'Replaying persisted journal events. ' + data.eventsReplayed + ' events replayed, ' + data.phasesSkipped + ' already-completed phases will be skipped (breakpoint resume).';
        break;
      case 'RECOVERED':
        cls = 'recovered';
        label = 'RECOVERED (' + data.eventsReplayed + ' ev, ' + data.phasesSkipped + ' skip)';
        det = data.detail || ('Resumed after ' + data.phasesSkipped + ' phases');
        tooltip = 'Pipeline has resumed from the breakpoint. ' + data.phasesSkipped + ' phases were preserved — execution continues without repeating completed work.';
        break;
      case 'COMPLETED':
        cls = 'completed'; label = 'COMPLETED';
        tooltip = 'All wafers have been processed. The pipeline completed despite faults and crashes — zero stuck WorkOrders.';
        break;
      default:
        cls = 'idle'; label = rt;
        tooltip = 'Waiting for demo to start.';
    }

    badge.className = 'recovery-badge ' + cls;
    badge.textContent = label;
    badge.title = tooltip;

    if (detail) {
      detail.textContent = det;
    }

    if (rt === 'CRASH_DETECTED') {
      showToast('💥 Crash detected! Automatic recovery starting...', 'error');
    } else if (rt === 'RECOVERED') {
      var msg = '✅ Self-healed: ' + data.eventsReplayed + ' events replayed, ' +
        data.phasesSkipped + ' phases skipped — pipeline resumed';
      showToast(msg, 'success');
    }
  }

  // ===================================================================
  // Pipeline Timeline Bar
  // ===================================================================

  function updatePipelineTimeline(snapshot) {
    var bar = document.getElementById('pipelineTimelineBar');
    if (!bar) return;

    var total = snapshot.totalPhases || state.timelinePhaseNames.length;
    var completed = snapshot.completedPhases || 0;
    var currentIdx = snapshot.currentPhaseIndex || 0;
    var failed = snapshot.failedPhases || [];
    var recovered = snapshot.recoveredPhases || [];
    var ocapTriggers = snapshot.ocapTriggers || 0;
    var phaseDurations = snapshot.phaseDurations || {};
    var failureDetails = snapshot.failureDetails || {};

    // Build phase segments with tooltips
    var html = '';
    var nameList = state.timelinePhaseNames;

    for (var i = 0; i < total && i < nameList.length; i++) {
      var cls = 'pending';
      if (i < completed) cls = 'completed';
      else if (i === currentIdx) cls = 'current';
      else if (i > currentIdx) cls = 'pending';

      var phaseName = nameList[i] || 'Stage ' + i;

      // Check if this phase failed
      if (failed.indexOf(phaseName) >= 0) cls = 'failed';

      // Check if recovered
      var recoveredMark = '';
      if (recovered.indexOf(phaseName) >= 0) {
        cls = 'recovered';
        recoveredMark = '<span class="pt-recovered-badge" title="Recovered after failure">R</span>';
      }

      // Duration text (if available)
      var durationText = '';
      var durationMs = phaseDurations[phaseName];
      if (durationMs) {
        durationText = (durationMs / 1000).toFixed(1) + 's';
      }

      // Tooltip
      var statusLabel = cls.charAt(0).toUpperCase() + cls.slice(1);
      var failDetail = failureDetails[phaseName] || '';
      var tooltipHtml =
        '<div class="pt-tooltip">' +
        '<div><span class="tt-phase-name">' + phaseName + '</span> ' +
        '<span class="tt-status ' + cls + '">' + statusLabel + '</span></div>' +
        (durationText ? '<div class="tt-detail">Duration: ' + durationText + '</div>' : '') +
        (failDetail ? '<div class="tt-detail">' + failDetail + '</div>' : '') +
        (recovered.indexOf(phaseName) >= 0 ? '<div class="tt-detail" style="color:var(--green)">Recovered</div>' : '') +
        '</div>';

      html += '<div class="pt-phase ' + cls + '">' +
        recoveredMark +
        '<span class="pt-phase-label">' + getShortPhaseLabel(phaseName) + '</span>' +
        (durationText ? '<span class="pt-phase-duration">' + durationText + '</span>' : '') +
        tooltipHtml +
        '</div>';
    }

    bar.innerHTML = html;

    // Update summary ocap count
    if (ocapTriggers > 0) {
      var ocapEl = document.getElementById('m35-ocap-triggers');
      if (ocapEl) ocapEl.textContent = ocapTriggers;
    }
  }

  function getShortPhaseLabel(name) {
    // Abbreviate for small bar segments
    var map = {
      'Load': 'Ld',
      'T:STK→LTH': 'S→L',
      'At LITHO': '@L',
      'TrackIn': 'TI',
      'Process': 'Pr',
      'TrackOut': 'TO',
      'T:LTH→CDS': 'L→C',
      'At CDSEM': '@C',
      'Measure': 'Ms',
      'Classify': 'Cl',
      'Split': 'Sp',
      'Rework': 'Rw',
      'Re-Measure': 'rMs',
      'Re-Classify': 'rCl',
      'Merge': 'Mg',
      'T:CDS→STK': 'C→S',
      'Seal': 'Sl',
      'Complete': 'Dn'
    };
    return map[name] || name.substring(0, 3);
  }

  // ===================================================================
  // OCAP Rule Fire Log
  // ===================================================================

  function addOcapRuleEntry(data) {
    var body = document.getElementById('ocapLogBody');
    if (!body) return;

    var now = new Date();
    var ts = now.toTimeString().substring(0, 8);
    var actionType = data.actionType || 'UNKNOWN';
    var actionClass = 'action-' + actionType;

    var entry = document.createElement('div');
    entry.className = 'ocap-entry ' + actionClass;
    entry.innerHTML =
      '<span class="ocap-ts">' + ts + '</span> ' +
      '<span class="ocap-rule-id">' + (data.ruleId || '?') + '</span>' +
      '<span class="ocap-action-badge">' + actionType + '</span>' +
      '<div class="ocap-detail">' + (data.ruleName || '') + ': ' + (data.detail || '') + '</div>' +
      (data.affectedWafers && data.affectedWafers.length > 0
        ? '<div class="ocap-wafers">Wafers: ' + data.affectedWafers.join(', ') + '</div>'
        : '');

    body.insertBefore(entry, body.firstChild);

    // Update count
    var countEl = document.getElementById('ocapCount');
    if (countEl) countEl.textContent = '(' + (++state.ocapCount) + ')';

    // Remove placeholder if first entry
    var placeholder = body.querySelector('div[style]');
    if (placeholder && state.ocapCount === 1) {
      placeholder.style.display = 'none';
    }

    // Trim to 100 entries
    while (body.children.length > 100) {
      body.removeChild(body.lastChild);
    }
  }

  // ===================================================================
  // Fault Entry
  // ===================================================================

  function addFaultEntry(data) {
    var now = new Date();
    var ts = now.toTimeString().substring(0, 8);
    var equipId = data.equipmentId || data.equipId || '?';
    var faultType = data.errorCode || data.faultType || 'unknown';
    var detail = data.detail || data.stageName || '';

    addTimelineEntry({type: 'Fault', data: equipId + ': ' + faultType + ' — ' + detail});
  }

  // ===================================================================
  // Recovery Entry
  // ===================================================================

  function addRecoveryEntry(data) {
    var body = document.getElementById('recoveryLogBody');
    if (!body) return;

    var now = new Date();
    var ts = now.toTimeString().substring(0, 8);
    var rt = data.recoveryType || 'INFO';
    var cls = rt === 'CRASH_DETECTED' ? 'crash' : (rt === 'RECOVERING' ? 'recovering' : 'recovered');

    // Remove placeholder if present
    var placeholder = body.querySelector('div[style]');
    if (placeholder) placeholder.style.display = 'none';

    var entry = document.createElement('div');
    entry.className = 'recovery-entry ' + cls;
    entry.innerHTML = '<span class="r-ts">' + ts + '</span> [' + rt + '] ' + (data.detail || '');

    body.insertBefore(entry, body.firstChild);

    // Update count
    var countEl = document.getElementById('recoveryLogCount');
    if (countEl) countEl.textContent = '(' + body.children.length + ')';

    while (body.children.length > 50) {
      body.removeChild(body.lastChild);
    }
  }

  // ===================================================================
  // Summary Stats
  // ===================================================================

  function updateM35Summary(stats) {
    if (stats.active !== undefined) document.getElementById('m35-active').textContent = stats.active;
    if (stats.passed !== undefined) animateCountUp(document.getElementById('m35-pass'), stats.passed);
    if (stats.reworked !== undefined) animateCountUp(document.getElementById('m35-rework'), stats.reworked);
    if (stats.scrapped !== undefined) animateCountUp(document.getElementById('m35-scrap'), stats.scrapped);
    if (stats.faults !== undefined) animateCountUp(document.getElementById('m35-faults'), stats.faults);
    if (stats.recoveries !== undefined) animateCountUp(document.getElementById('m35-recoveries'), stats.recoveries);
    if (stats.ocapTriggers !== undefined) animateCountUp(document.getElementById('m35-ocap-triggers'), stats.ocapTriggers);
  }

  // ===================================================================
  // Completion Overlay (P7)
  // ===================================================================

  /**
   * Animate a numeric element counting up from current to target value.
   */
  function animateCountUp(el, target, duration) {
    if (!el) return;
    var current = parseInt(el.textContent) || 0;
    if (current === target) return;
    var startTime = performance.now();
    function step(now) {
      var progress = Math.min((now - startTime) / (duration || 800), 1.0);
      var eased = 1 - Math.pow(1 - progress, 3); // ease-out cubic
      var value = Math.round(current + (target - current) * eased);
      el.textContent = value;
      if (progress < 1.0) {
        requestAnimationFrame(step);
      } else {
        el.textContent = target;
      }
    }
    requestAnimationFrame(step);
  }

  /**
   * Show the completion overlay with final summary stats.
   * Called when demoCompleted$ fires.
   */
  function showCompletionOverlay(stats) {
    var overlay = document.getElementById('completionOverlay');
    if (!overlay) return;

    // Populate stats
    var total = stats.totalWafers || 0;
    var passed = stats.passed || 0;
    var reworked = stats.reworked || 0;
    var scrapped = stats.scrapped || 0;
    var faults = stats.faults || 0;
    var recoveries = stats.recoveries || 0;
    var ocapTriggers = stats.ocapTriggers || 0;

    // Set raw values first (for screen readers / fallback)
    document.getElementById('cc-total').textContent = total;
    document.getElementById('cc-passed').textContent = passed;
    document.getElementById('cc-reworked').textContent = reworked;
    document.getElementById('cc-scrapped').textContent = scrapped;
    document.getElementById('cc-faults').textContent = faults;
    document.getElementById('cc-recoveries').textContent = recoveries;
    document.getElementById('cc-ocap-triggers').textContent = ocapTriggers;

    // Total time
    var elapsed = Math.floor(((Date.now() - (stats.startTime || Date.now())) / 1000));
    document.getElementById('cc-time').textContent = elapsed + 's';

    // Show the overlay
    overlay.classList.add('active');

    // Count-up animation after a brief delay (after fade-in)
    setTimeout(function() {
      animateCountUp(document.getElementById('cc-total'), total, 1000);
      animateCountUp(document.getElementById('cc-passed'), passed, 1000);
      animateCountUp(document.getElementById('cc-reworked'), reworked, 1000);
      animateCountUp(document.getElementById('cc-scrapped'), scrapped, 1000);
      animateCountUp(document.getElementById('cc-faults'), faults, 800);
      animateCountUp(document.getElementById('cc-recoveries'), recoveries, 800);
      animateCountUp(document.getElementById('cc-ocap-triggers'), ocapTriggers, 800);
    }, 600);
  }

  function showToast(message, type) {
    type = type || 'info';
    var container = document.getElementById('toastContainer');
    if (!container) return;

    var toast = document.createElement('div');
    toast.className = 'toast ' + type;
    toast.textContent = message;
    container.appendChild(toast);

    // Auto-dismiss after 4 seconds
    setTimeout(function() {
      toast.style.opacity = '0';
      toast.style.transition = 'opacity 0.3s';
      setTimeout(function() {
        if (toast.parentNode) toast.parentNode.removeChild(toast);
      }, 300);
    }, 4000);
  }

  // ===================================================================
  // OCAP Rules Loader
  // ===================================================================

  function fetchOcapRules() {
    fetch('/api/fab-demo/m35/ocap-rules')
      .then(function(r) { return r.json(); })
      .then(function(rules) {
        if (rules && rules.length > 0) {
          var body = document.getElementById('ocapLogBody');
          // Show rules summary in log
          rules.forEach(function(rule) {
            var entry = document.createElement('div');
            entry.className = 'ocap-entry';
            entry.style.opacity = '0.5';
            entry.style.fontSize = '0.5rem';
            entry.style.padding = '1px 6px';
            entry.innerHTML =
              '<span class="ocap-rule-id">' + rule.ruleId + '</span> ' +
              '<span class="ocap-action-badge" style="font-size:0.45rem">' + rule.actionType + '</span> ' +
              '<span style="color:var(--fg-muted)">' + rule.name + '</span>';
            body.appendChild(entry);
          });
        }
      })
      .catch(function() { /* non-critical */ });
  }

  // ===================================================================
  // Timeline (compatible with existing fab_demo format)
  // ===================================================================

  function addTimelineEntry(event, highlight) {
    var now = new Date();
    var ts = now.toTimeString().substring(0, 8);
    var cls = '';
    var msg = '';
    var icon = '';

    switch (event.type) {
      case 'EquipmentStateChanged':
        var st = event.data.status;
        icon = st === 'Busy' ? '▶' : st === 'Error' ? '⚠' : '●';
        cls = st === 'Error' ? 'fail' : '';
        msg = icon + ' ' + event.data.equipmentId + ' → ' + st;
        break;
      case 'MeasurementResultEvent':
        var clsName = event.data.classification;
        icon = clsName === 'PASS' ? '✓' : clsName === 'FAIL' ? '✗' : '○';
        cls = clsName === 'PASS' ? 'pass' : 'fail';
        msg = icon + ' ' + event.data.waferId + ': CD=' + event.data.cdNm + 'nm → ' + clsName;
        break;
      case 'FoupInTransit':
        icon = '🚚'; cls = 'transport';
        msg = icon + ' FOUP: ' + event.data.fromArea + ' → ' + event.data.toArea;
        break;
      case 'ScrapEvent':
        icon = '🗑'; cls = 'fail';
        msg = icon + ' SCRAP: ' + event.data.waferId + ' — ' + event.data.reason;
        break;
      case 'OcapActionTriggered':
        icon = '🔄'; cls = 'rework';
        msg = icon + ' OCAP Rule: ' + event.data.ruleName + ' → ' + event.data.actionType +
          (event.data.affectedWafers && event.data.affectedWafers.length > 0
            ? ' (' + event.data.affectedWafers.join(', ') + ')' : '');
        break;
      case 'PipelineStageFailed':
        icon = '⚠'; cls = 'fail';
        msg = icon + ' Equipment Fault: ' + (event.data.equipId || event.data.stageName) +
          ' — ' + event.data.errorCode + ': ' + (event.data.detail || '');
        break;
      case 'RecoveryEvent':
        var rt = event.data.recoveryType;
        icon = rt === 'CRASH_DETECTED' ? '💥' : rt === 'RECOVERING' ? '♻' : rt === 'RECOVERED' ? '✓' : '●';
        cls = rt === 'CRASH_DETECTED' ? 'fault' : rt === 'RECOVERING' ? 'recovery' : '';
        msg = icon + ' [' + rt + '] ' + (event.data.detail || '');
        break;
      case 'FaultInjected':
        icon = '⚡'; cls = 'fault';
        msg = icon + ' FAULT: ' + event.data.equipmentId + ' — ' + event.data.faultType +
          ' at ' + event.data.phaseName;
        break;
      case 'DynamicStageInjected':
        icon = '🔀'; cls = 'rework';
        msg = icon + ' OCAP Dynamic Weave: ' + event.data.injectedStageType +
          ' injected by ' + (event.data.triggeredByRule || 'OCAP');
        break;
      case 'DemoCompleted':
        icon = '✅';
        msg = icon + ' All Complete — ' + event.data.passedWafers + ' passed, ' +
          event.data.scrappedWafers + ' scrapped · 0 stuck WorkOrders';
        break;
      case 'PipelineTimelineSnapshot':
        // Too frequent, skip timeline entries for snapshots
        return;
      default:
        msg = event.type + ' ' + (typeof event.data === 'string' ? event.data : '');
    }

    var timeline = document.getElementById('timeline');
    var entry = document.createElement('div');
    entry.className = 'entry' + (cls ? ' ' + cls : '');
    entry.innerHTML = '<span class="ts">' + ts + '</span>' + msg;
    if (highlight) entry.style.fontWeight = '700';
    timeline.insertBefore(entry, timeline.firstChild);

    while (timeline.children.length > 200) {
      timeline.removeChild(timeline.lastChild);
    }
  }

  // ===================================================================
  // Aggregate Reducer (from existing fab_demo.js, simplified)
  // ===================================================================

  function aggregateReducer(model, data) {
    var lots = Object.assign({}, model.lots);
    var wafers = Object.assign({}, model.wafers);

    function upsertLot(lotData) {
      if (!lotData || !lotData.lotId) return;
      if (!lots[lotData.lotId]) {
        lots[lotData.lotId] = {
          lotId: lotData.lotId, productId: lotData.lotId,
          status: lotData.status, waferCount: lotData.waferCount || 0,
          passCount: lotData.passCount || 0, scrapCount: lotData.scrapCount || 0,
          currentArea: lotData.currentArea || '',
          waferIds: []
        };
      } else {
        var l = lots[lotData.lotId];
        l.status = lotData.status;
        l.waferCount = lotData.waferCount || 0;
        l.passCount = lotData.passCount || 0;
        l.scrapCount = lotData.scrapCount || 0;
        l.currentArea = lotData.currentArea || '';
      }
    }

    if (data.sourceLot) upsertLot(data.sourceLot);
    (data.childLots || []).forEach(function(cl) { upsertLot(cl); });

    (data.wafers || []).forEach(function(w) {
      if (!wafers[w.waferId]) {
        wafers[w.waferId] = {
          waferId: w.waferId, status: w.status, lotId: w.lotId,
          classification: w.classification, reworkCount: w.reworkCount
        };
      } else {
        var ew = wafers[w.waferId];
        if (ew.lotId !== w.lotId) ew.lotId = w.lotId;
        ew.status = w.status;
        ew.classification = w.classification;
        ew.reworkCount = w.reworkCount;
      }
    });

    Object.keys(lots).forEach(function(lotId) { lots[lotId].waferIds = []; });
    Object.keys(wafers).forEach(function(wid) {
      var w = wafers[wid];
      var lot = lots[w.lotId];
      if (lot) lot.waferIds.push(w.waferId);
    });

    return { lots: lots, wafers: wafers };
  }

  // ===================================================================
  // Aggregate Panel Render
  // ===================================================================

  function renderAggregatePanel(model) {
    var tbody = document.getElementById('aggTreeBody');
    if (!tbody) return;
    var lotIds = Object.keys(model.lots);
    if (lotIds.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" style="color:var(--fg-muted)">Waiting for Lot creation...</td></tr>';
      return;
    }

    var rows = '';
    lotIds.forEach(function(lotId) {
      var lot = model.lots[lotId];
      rows += '<tr class="lot-row">' +
        '<td>Lot: ' + (lot.productId || lotId).substring(0, 12) + '</td>' +
        '<td>' + (lot.status || 'Active') + '</td>' +
        '<td>' + (lot.currentArea || '-') + '</td>' +
        '<td colspan="2">W: ' + (lot.waferCount || 0) +
        ' | P: ' + (lot.passCount || 0) +
        ' | S: ' + (lot.scrapCount || 0) + '</td>' +
        '</tr>';
      (lot.waferIds || []).forEach(function(wid) {
        var w = model.wafers[wid];
        if (!w) return;
        rows += '<tr class="wafer-row">' +
          '<td>' + (w.waferId || wid).substring(0, 8) + '</td>' +
          '<td>' + (w.status || 'Active') + '</td>' +
          '<td></td>' +
          '<td>' + (w.classification || 'Pending') + '</td>' +
          '<td>' + (w.reworkCount || 0) + '</td>' +
          '</tr>';
      });
    });
    tbody.innerHTML = rows;
  }

  // ===================================================================
  // Equipment Node Update (from existing fab_demo.js, simplified)
  // ===================================================================

  var eqStatusMap = {
    'STOCKER-01': 'status-stocker',
    'LITHO-01': 'status-litho',
    'CDSEM-01': 'status-cdsem',
    'CLEAN-01': 'status-clean',
    'DIFF-01': 'status-diff',
    'ETCH-01': 'status-etch',
    'IMPL-01': 'status-implant',
    'DEP-01': 'status-dep',
    'CMP-01': 'status-cmp',
    'DRY-01': 'status-dry',
    'LOG-01': 'status-log'
  };

  function updateEquipmentNode(data) {
    var statusId = eqStatusMap[data.equipmentId];
    if (!statusId) return;
    var el = document.getElementById(statusId);
    if (el) {
      el.textContent = data.status || 'Idle';
      el.setAttribute('fill', statusColor(data.status));
    }
    var nodeId = data.equipmentId ? data.equipmentId.replace('-01','').toLowerCase() : '';
    var node = document.getElementById('eq-' + nodeId);
    if (node) {
      var rect = node.querySelector('rect');
      if (rect) rect.setAttribute('stroke', statusStrokeColor(data.status));
    }
  }

  function statusColor(status) {
    switch (status) {
      case 'Busy': return '#3b82f6';
      case 'Processing': return '#f59e0b';
      case 'Error': return '#f85149';
      default: return '#6e7681';
    }
  }

  function statusStrokeColor(status) {
    switch (status) {
      case 'Busy': return '#3b82f6';
      case 'Processing': return '#f59e0b';
      case 'Error': return '#f85149';
      default: return '#30363d';
    }
  }

  // ===================================================================
  // FOUP Animation (simplified)
  // ===================================================================

  var _foupRafId = null;

  function animateFoupMovement(data) {
    var foup = document.getElementById('foupIcon');
    if (!foup) return;
    foup.setAttribute('opacity', '1');

    var fromPos = getAreaPosition(data.fromArea);
    var toPos = getAreaPosition(data.toArea);
    var duration = Math.max(500, (data.etaMs || 1000) / (state.speed || 1));

    if (_foupRafId) { cancelAnimationFrame(_foupRafId); _foupRafId = null; }
    foup.setAttribute('x', fromPos.x);
    foup.setAttribute('y', fromPos.y);

    var startTime = performance.now();
    function step(now) {
      var progress = Math.min((now - startTime) / duration, 1.0);
      var eased = progress < 0.5 ? 2 * progress * progress : 1 - Math.pow(-2 * progress + 2, 2) / 2;
      foup.setAttribute('x', fromPos.x + (toPos.x - fromPos.x) * eased);
      foup.setAttribute('y', fromPos.y + (toPos.y - fromPos.y) * eased);
      if (progress < 1.0) {
        _foupRafId = requestAnimationFrame(step);
      } else {
        _foupRafId = null;
      }
    }
    _foupRafId = requestAnimationFrame(step);
  }

  function getAreaPosition(areaId) {
    var key = areaId ? areaId.replace('-01', '') : '';
    var map = {
      'STOCKER': {x: 55, y: 155},
      'CLEAN': {x: 184, y: 125},
      'DIFF': {x: 304, y: 125},
      'LITHO': {x: 424, y: 125},
      'ETCH': {x: 544, y: 125},
      'IMPL': {x: 664, y: 125},
      'DEP': {x: 544, y: 248},
      'CMP': {x: 424, y: 248},
      'MET': {x: 304, y: 248},
      'CDSEM': {x: 304, y: 248},
      'DRY': {x: 184, y: 248},
      'LOG': {x: 664, y: 248}
    };
    return map[key] || {x: 55, y: 155};
  }

  // ===================================================================
  // Classification Wheel
  // ===================================================================

  function updateClassificationWheel(data) {
    var idx = parseInt(data.waferId ? data.waferId.replace('WAFER-','') : '0') - 1;
    var dot = document.getElementById('wd-' + idx);
    if (!dot) return;
    switch (data.classification) {
      case 'PASS': dot.setAttribute('fill', '#3fb950'); break;
      case 'BORDERLINE': dot.setAttribute('fill', '#f59e0b'); break;
      case 'FAIL':
      case 'SCRAP': dot.setAttribute('fill', '#f85149'); break;
      default: dot.setAttribute('fill', '#30363d');
    }
  }

  // ===================================================================
  // Scrap Event
  // ===================================================================

  function handleScrapEvent(data) {
    var sc = document.getElementById('scrapCount');
    if (!sc) return;
    // Count existing wafers in scrap bin
    var dotsGroup = document.getElementById('scrapWaferDots');
    var count = dotsGroup ? dotsGroup.children.length / 2 + 1 : 1;
    sc.textContent = count + ' wafer' + (count > 1 ? 's' : '');

    // Create wafer dot in scrap bin
    if (dotsGroup) {
      var waferNum = data.waferId ? data.waferId.replace('WAFER-','') : '?';
      var gap = 16;
      var cx = 14 + (count - 1) * gap;
      var svgNS = 'http://www.w3.org/2000/svg';
      var dot = document.createElementNS(svgNS, 'circle');
      dot.setAttribute('cx', cx);
      dot.setAttribute('cy', '8');
      dot.setAttribute('r', '5');
      dot.setAttribute('fill', '#f85149');
      dot.setAttribute('stroke', '#ff6b6b');
      dot.setAttribute('stroke-width', '1');
      dotsGroup.appendChild(dot);
      var label = document.createElementNS(svgNS, 'text');
      label.setAttribute('x', cx);
      label.setAttribute('y', '20');
      label.setAttribute('text-anchor', 'middle');
      label.setAttribute('font-size', '5');
      label.setAttribute('fill', '#fff');
      label.setAttribute('font-family', 'monospace');
      label.textContent = 'W' + waferNum;
      dotsGroup.appendChild(label);
    }
  }

  // ===================================================================
  // UI Reset
  // ===================================================================

  // ===================================================================
  // Route Graph Container (P8)
  // ===================================================================

  function createRouteGraphContainer() {
    // Check if already exists
    var existing = document.getElementById('routeGraphContainer');
    if (existing) return existing;

    var container = document.createElement('div');
    container.id = 'routeGraphContainer';
    container.style.cssText =
      'position:absolute;bottom:8px;right:8px;width:380px;height:200px;' +
      'background:rgba(22,27,34,0.92);border:1px solid var(--border);border-radius:8px;' +
      'overflow:hidden;z-index:100;box-shadow:0 4px 20px rgba(0,0,0,0.5);';

    var header = document.createElement('div');
    header.style.cssText =
      'display:flex;justify-content:space-between;align-items:center;' +
      'padding:4px 10px;border-bottom:1px solid var(--border);font-size:0.65rem;color:var(--fg-muted);';
    header.innerHTML = '<span>Route Graph (OCAP View)</span>' +
      '<button onclick="clearOcapHighlights()" style="background:none;border:1px solid var(--border);color:var(--fg-muted);font-size:0.55rem;padding:1px 6px;border-radius:3px;cursor:pointer">Clear</button>';
    container.appendChild(header);

    var svgDiv = document.createElement('div');
    svgDiv.id = 'routeGraphSvgContainer';
    svgDiv.style.cssText = 'width:100%;height:calc(100% - 24px);';
    svgDiv.innerHTML = '<div style="color:var(--fg-muted);font-size:0.55rem;padding:12px;text-align:center">Route graph loading...</div>';
    container.appendChild(svgDiv);

    document.getElementById('factoryFloor').appendChild(container);
    return svgDiv;
  }

  function resetM35UI() {
    // Reset OCAP log
    var ocapBody = document.getElementById('ocapLogBody');
    if (ocapBody) {
      ocapBody.innerHTML = '<div style="color:var(--fg-muted);font-size:0.58rem;padding:8px">Waiting for OCAP rules to fire...</div>';
    }
    state.ocapCount = 0;
    state.faultCount = 0;
    state.recoveryCount = 0;
    document.getElementById('ocapCount').textContent = '(0)';

    // Reset pipeline timeline
    var ptBar = document.getElementById('pipelineTimelineBar');
    if (ptBar) {
      ptBar.innerHTML = '<div style="color:var(--fg-muted);font-size:0.6rem;width:100%;text-align:center">Pipeline Timeline — waiting for demo to start</div>';
    }

    // Reset recovery log
    var rlBody = document.getElementById('recoveryLogBody');
    if (rlBody) {
      rlBody.innerHTML = '<div style="color:var(--fg-muted);font-size:0.55rem;padding:2px">No recovery events yet.</div>';
    }

    // Reset recovery badge
    var badge = document.getElementById('recoveryStatusBadge');
    if (badge) {
      badge.className = 'recovery-badge idle';
      badge.textContent = 'Idle';
    }
    var rd = document.getElementById('recoveryDetail');
    if (rd) rd.textContent = '';

    // Reset summary
    var sumIds = ['m35-active','m35-pass','m35-rework','m35-scrap','m35-faults','m35-recoveries','m35-ocap-triggers'];
    sumIds.forEach(function(id) {
      var el = document.getElementById(id);
      if (el) el.textContent = '0';
    });

    // Reset equipment status
    var statusIds = ['status-stocker','status-clean','status-diff','status-litho','status-etch',
      'status-implant','status-dep','status-cmp','status-cdsem','status-dry','status-log'];
    statusIds.forEach(function(sid) {
      var el = document.getElementById(sid);
      if (el) { el.textContent = 'Idle'; el.setAttribute('fill', '#6e7681'); }
    });

    // Reset equipment node strokes
    var eqIds = ['stocker','clean','diff','litho','etch','implant','dep','cmp','met','dry','log','decision'];
    eqIds.forEach(function(eid) {
      var node = document.getElementById('eq-' + eid);
      if (node) {
        var rect = node.querySelector('rect');
        if (rect) { rect.setAttribute('stroke', eid === 'decision' ? '#f59e0b' : '#30363d'); rect.setAttribute('stroke-width', '2'); }
      }
    });

    // Reset decision engine
    var deSt = document.getElementById('status-decision');
    if (deSt) {
      deSt.textContent = 'Waiting for scenario';
      deSt.setAttribute('fill', '#6e7681');
    }

    // Reset timeline
    var timeline = document.getElementById('timeline');
    if (timeline) {
      timeline.innerHTML = '<div class="entry"><span class="ts">--:--:--</span> Ready — click "Run Self-Healing Demo" to begin</div>';
    }

    // Reset FOUP icons
    var foup = document.getElementById('foupIcon');
    if (foup) { foup.setAttribute('opacity', '0'); foup.setAttribute('x', '55'); foup.setAttribute('y', '155'); }
    var lotLabel = document.getElementById('foupLotLabel');
    if (lotLabel) { lotLabel.setAttribute('opacity', '0'); lotLabel.textContent = '--'; }
    var rf = document.getElementById('reworkFoupIcon');
    if (rf) { rf.setAttribute('opacity', '0'); }
    var rl = document.getElementById('reworkFoupLabel');
    if (rl) { rl.setAttribute('opacity', '0'); rl.textContent = '--'; }

    // Reset classification wheel
    for (var i = 0; i < 5; i++) {
      var dot = document.getElementById('wd-' + i);
      if (dot) dot.setAttribute('fill', '#30363d');
    }

    // Reset scrap bin
    var sc = document.getElementById('scrapCount');
    if (sc) sc.textContent = '0 wafer';
    var dotsGroup = document.getElementById('scrapWaferDots');
    if (dotsGroup) dotsGroup.innerHTML = '';
  }

  // ===================================================================
  // Aggregate Panel Toggle
  // ===================================================================

  window.toggleAggregatePanel = function() {
    var panel = document.getElementById('aggregatePanel');
    if (!panel) return;
    var btn = panel.querySelector('.panel-header button');
    if (panel.classList.contains('collapsed')) {
      panel.classList.remove('collapsed');
      if (btn) btn.textContent = 'Collapse';
    } else {
      panel.classList.add('collapsed');
      if (btn) btn.textContent = 'Expand';
    }
  };

  // ===================================================================
  // Recovery Log Toggle
  // ===================================================================

  window.toggleRecoveryLog = function() {
    var body = document.getElementById('recoveryLogBody');
    if (!body) return;
    if (body.style.display === 'none') {
      body.style.display = 'block';
    } else {
      body.style.display = 'none';
    }
  };

  // ===================================================================
  // Entity State Query
  // ===================================================================

  window.fetchEntityState = function() {
    var input = document.getElementById('entityWorkOrderInput');
    var workOrderId = input && input.value ? input.value : (state.workOrderId || '');
    if (!workOrderId) {
      alert('No WorkOrder ID. Start a demo first, or enter an ID manually.');
      return;
    }

    fetch('/api/fab-demo/entity-state/' + encodeURIComponent(workOrderId))
      .then(function(r) { return r.json(); })
      .then(function(data) {
        if (data.error) {
          showToast('Entity state error: ' + data.error, 'error');
          return;
        }
        showToast('Entity state loaded for ' + workOrderId, 'info');
      })
      .catch(function(err) {
        showToast('Entity state fetch failed: ' + err.message, 'error');
      });
  };

  // ===================================================================
  // Init
  // ===================================================================

  document.addEventListener('DOMContentLoaded', function() {
    // Wait for M3.5 observables to initialize
    setTimeout(function() {
      if (window._m35Streams) {
        initM35Demo();
      } else {
        console.warn('M3.5 streams not available after timeout');
      }
    }, 300);
  });
})();

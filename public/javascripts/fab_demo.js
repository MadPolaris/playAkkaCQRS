/**
 * Fab Demo — M3 Closed-Loop Photo Cell Simulation
 *
 * Connects to the FabDemoController WebSocket and renders:
 *   1. Factory floor SVG (equipment nodes, AMHS rail, FOUP movement)
 *   2. Timeline log (scrollable event history)
 *   3. Summary panel (lot stats)
 *   4. Classification wheel (per-wafer dot colors)
 */

(function() {
  'use strict';

  // ===================================================================
  // State
  // ===================================================================
  var state = {
    ws: null,
    speed: 1,
    paused: false,
    waferCount: 5,
    eventLog: [],
    waferResults: {}  // waferId -> classification
  };

  // ===================================================================
  // WebSocket
  // ===================================================================
  function connectWebSocket() {
    var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    var wsUrl = protocol + '//' + window.location.host + '/ws/fab-demo/events';
    state.ws = new WebSocket(wsUrl);
    state.ws.onmessage = function(msg) {
      try {
        var event = JSON.parse(msg.data);
        handleEvent(event);
      } catch(e) {
        console.warn('Failed to parse WebSocket message:', e);
      }
    };
    state.ws.onclose = function() {
      console.log('WebSocket closed, reconnecting in 3s...');
      setTimeout(connectWebSocket, 3000);
    };
  }

  // ===================================================================
  // Event Handlers
  // ===================================================================
  function handleEvent(event) {
    if (state.paused && event.type !== 'DemoResumed') return;

    addTimelineEntry(event);

    switch (event.type) {
      case 'EquipmentStateChanged':
        updateEquipmentNode(event.data);
        break;
      case 'FoupInTransit':
        animateFoupMovement(event.data);
        break;
      case 'FoupArrivedAtPort':
        showFoupAtEquipment(event.data);
        break;
      case 'ProcessingStarted':
        pulseEquipment(event.data);
        break;
      case 'ProcessingCompleted':
        resetEquipmentColor(event.data);
        break;
      case 'MeasurementResultEvent':
        updateClassificationWheel(event.data);
        break;
      case 'LotUpdated':
        updateLotSummary(event.data);
        break;
      case 'DemoCompleted':
        updateFoupCompleted();
        addTimelineEntry({type:'DemoCompleted', data:event.data}, true);
        break;
      case 'OrchestratorCommand':
        showOrchestratorCommand(event.data);
        break;
      case 'FoupStateChanged':
        updateFoupState(event.data);
        break;
      case 'LedgerStepAdvanced':
        highlightLedgerRow(event.data.stepSeq);
        break;
    }
  }

  // ===================================================================
  // Equipment Node Updates
  // ===================================================================
  var eqStatusMap = {
    'STOCKER-01': 'status-stocker',
    'LITHO-01': 'status-litho',
    'CDSEM-01': 'status-cdsem'
  };

  function updateEquipmentNode(data) {
    var statusId = eqStatusMap[data.equipmentId];
    if (!statusId) return;
    var el = document.getElementById(statusId);
    if (el) {
      el.textContent = data.status || 'Idle';
      el.setAttribute('fill', statusColor(data.status));
    }
    // Update rect stroke
    var nodeId = data.equipmentId.replace('-01','').toLowerCase();
    var node = document.getElementById('eq-' + nodeId);
    if (node) {
      var rect = node.querySelector('rect');
      if (rect) rect.setAttribute('stroke', statusColor(data.status));
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

  function pulseEquipment(data) {
    var nodeId = data.equipmentId.replace('-01','').toLowerCase();
    var node = document.getElementById('eq-' + nodeId);
    if (node) {
      var rect = node.querySelector('rect');
      if (rect) {
        rect.setAttribute('stroke', '#f59e0b');
        rect.setAttribute('stroke-width', '3');
        setTimeout(function() {
          rect.setAttribute('stroke-width', '2');
        }, 500);
      }
    }
  }

  function resetEquipmentColor(data) {
    var nodeId = data.equipmentId.replace('-01','').toLowerCase();
    var node = document.getElementById('eq-' + nodeId);
    if (node) {
      var rect = node.querySelector('rect');
      if (rect) rect.setAttribute('stroke', '#30363d');
    }
  }

  // ===================================================================
  // FOUP Movement Animation
  // ===================================================================
  function animateFoupMovement(data) {
    // Rework path: use rework FOUP icon
    if (data.fromArea === 'CDSEM' && data.toArea === 'LITHO') {
      animateReworkFoup(data);
      return;
    }
    // Return to Stocker: hide rework FOUP
    if (data.fromArea === 'CDSEM' && data.toArea === 'STOCKER') {
      var rf = document.getElementById('reworkFoupIcon');
      if (rf) rf.setAttribute('opacity', '0');
    }

    var foup = document.getElementById('foupIcon');
    if (!foup) return;
    foup.setAttribute('opacity', '1');

    var fromPos = getAreaPosition(data.fromArea);
    var toPos = getAreaPosition(data.toArea);
    var duration = Math.max(500, (data.etaMs || 1000) / state.speed);

    var animX = document.getElementById('foupAnimX');
    var animY = document.getElementById('foupAnimY');
    if (animX && animY) {
      animX.setAttribute('values', fromPos.x + ';' + toPos.x);
      animX.setAttribute('dur', duration + 'ms');
      animY.setAttribute('values', fromPos.y + ';' + toPos.y);
      animY.setAttribute('dur', duration + 'ms');
    }
    // Trigger SVG animation
    var animElems = document.querySelectorAll('#foupAnimX, #foupAnimY');
    animElems.forEach(function(a) { a.beginElement(); });
  }

  function animateReworkFoup(data) {
    var rf = document.getElementById('reworkFoupIcon');
    if (!rf) return;
    rf.setAttribute('opacity', '1');
    var duration = Math.max(800, (data.etaMs || 2000) / state.speed);
    // Animate along upper path: CDSEM(690,185) → up(690,150) → left(490,150) → Litho(490,185)
    var animX = document.getElementById('reworkFoupAnimX');
    var animY = document.getElementById('reworkFoupAnimY');
    if (animX && animY) {
      animX.setAttribute('values', '690;690;490;490');
      animY.setAttribute('values', '185;150;150;185');
      animX.setAttribute('dur', duration + 'ms');
      animY.setAttribute('dur', duration + 'ms');
    }
    var anims = document.querySelectorAll('#reworkFoupAnimX, #reworkFoupAnimY');
    anims.forEach(function(a) { a.beginElement(); });
    // Show split label
    var sl = document.getElementById('splitMergeLabel');
    if (sl) {
      sl.textContent = '↗ SPLIT: Rework wafers';
      sl.setAttribute('opacity', '1');
      sl.setAttribute('fill', '#a855f7');
    }
    // Hide after transport
    setTimeout(function() {
      if (rf) rf.setAttribute('opacity', '0.3');
      if (sl) sl.setAttribute('opacity', '0');
    }, duration);
  }

  function showFoupAtEquipment(data) {
    var pos = getAreaPosition(data.equipmentId);
    var foup = document.getElementById('foupIcon');
    if (foup) {
      foup.setAttribute('x', pos.x);
      foup.setAttribute('y', pos.y);
      foup.setAttribute('opacity', '1');
    }
  }

  function getAreaPosition(areaId) {
    // Strip -01 suffix from equipment IDs for position lookup
    var key = areaId.replace('-01', '');
    var map = {
      'STOCKER': {x: 65, y: 85},
      'LITHO': {x: 490, y: 185},
      'CDSEM': {x: 690, y: 185},
      'LITHO_REWORK': {x: 490, y: 145},
      'RETURN': {x: 65, y: 85}
    };
    return map[key] || {x: 65, y: 85};
  }

  // ---- Orchestrator Command Visibility ----

  function showOrchestratorCommand(data) {
    // Show command on Decision Engine
    var de = document.getElementById('eq-decision');
    if (de) {
      var rect = de.querySelector('rect');
      if (rect) {
        rect.setAttribute('stroke', '#58a6ff');
        rect.setAttribute('stroke-width', '3');
        setTimeout(function() { rect.setAttribute('stroke', '#f59e0b'); rect.setAttribute('stroke-width', '2'); }, 1000);
      }
    }
    var act = document.getElementById('decisionActivity');
    if (act) {
      act.textContent = data.commandType + ': ' + (data.description || '').substring(0, 40);
      act.setAttribute('opacity', '1');
      act.setAttribute('fill', '#58a6ff');
      setTimeout(function() { act.setAttribute('opacity', '0.4'); }, 3000);
    }
    // Update Decision Engine status text
    var st = document.getElementById('status-decision');
    if (st) {
      st.textContent = data.commandType;
      st.setAttribute('fill', '#58a6ff');
    }
    // Pulse the target equipment
    pulseEquipment({equipmentId: data.targetEquipmentId});
  }

  // ---- FOUP State ----

  function updateFoupState(data) {
    var label = document.getElementById('foupStateLabel');
    if (label) {
      var text = data.status + ' @' + data.location + ' [' + data.activeWaferCount + 'w';
      if (data.reworkWaferCount > 0) text += ' +' + data.reworkWaferCount + 'rw';
      text += ']';
      label.textContent = text;
      label.setAttribute('opacity', '1');
    }
    // Color based on status
    var color = '#f59e0b'; // default amber
    if (data.status === 'COMPLETED') color = '#3fb950';
    else if (data.status === 'SPLITTING') color = '#a855f7';
    else if (data.status === 'RETURNING') color = '#3fb950';

    var foup = document.getElementById('foupIcon');
    if (foup) foup.setAttribute('fill', color);

    if (label) label.setAttribute('fill', color);
  }

  function updateFoupCompleted() {
    // Fade out FOUP when demo completes
    var foup = document.getElementById('foupIcon');
    if (foup) {
      foup.setAttribute('opacity', '0.3');
      foup.setAttribute('fill', '#3fb950');
    }
    var label = document.getElementById('foupStateLabel');
    if (label) {
      label.textContent = 'COMPLETED';
      label.setAttribute('fill', '#3fb950');
    }
    var rf = document.getElementById('reworkFoupIcon');
    if (rf) rf.setAttribute('opacity', '0');
    var de = document.getElementById('status-decision');
    if (de) { de.textContent = 'Done'; de.setAttribute('fill', '#3fb950'); }
  }

  // ===================================================================
  // Classification Wheel
  // ===================================================================
  function updateClassificationWheel(data) {
    var idx = parseInt(data.waferId.replace('WAFER-','')) - 1;
    var dot = document.getElementById('wd-' + idx);
    if (!dot) return;
    switch (data.classification) {
      case 'PASS': dot.setAttribute('fill', '#3fb950'); break;
      case 'BORDERLINE': dot.setAttribute('fill', '#f59e0b'); break;
      case 'FAIL':
      case 'SCRAP': dot.setAttribute('fill', '#f85149'); break;
      default: dot.setAttribute('fill', '#30363d');
    }
    state.waferResults[data.waferId] = data.classification;
    updateSummary();
  }

  function updateLotSummary(data) {
    document.getElementById('sum-active').textContent = data.activeWafers || '0';
    document.getElementById('sum-pass').textContent = data.passedWafers || '0';
    document.getElementById('sum-rework').textContent = data.reworkedWafers || '0';
    document.getElementById('sum-scrap').textContent = data.scrappedWafers || '0';
  }

  function updateSummary() {
    var pass = 0, fail = 0, rework = 0;
    Object.values(state.waferResults).forEach(function(r) {
      if (r === 'PASS') pass++;
      else if (r === 'FAIL') rework++;
      else if (r === 'SCRAP') fail++;
      else if (r === 'BORDERLINE') rework++;
    });
    document.getElementById('sum-pass').textContent = pass;
    document.getElementById('sum-rework').textContent = rework;
  }

  // ===================================================================
  // Timeline
  // ===================================================================
  function addTimelineEntry(event, highlight) {
    var now = new Date();
    var ts = now.toTimeString().substring(0, 8);
    var cls = '';
    var msg = '';
    switch (event.type) {
      case 'EquipmentStateChanged': cls = ''; msg = event.data.equipmentId + ' → ' + event.data.status; break;
      case 'MeasurementResultEvent':
        cls = event.data.classification === 'PASS' ? 'pass' : 'fail';
        msg = event.data.waferId + ' CD=' + event.data.cdNm + 'nm → ' + event.data.classification;
        break;
      case 'DecisionMade':
        cls = event.data.action === 'Rework' ? 'rework' : 'fail';
        msg = event.data.waferId + ' → ' + event.data.action;
        break;
      case 'FoupInTransit': cls = 'transport'; msg = 'FOUP: ' + event.data.fromArea + ' → ' + event.data.toArea; break;
      case 'SagaOperationEvent':
        cls = 'rework';
        msg = 'SAGA ' + event.data.operation + ' ' + event.data.status;
        if (event.data.relatedWaferIds && event.data.relatedWaferIds.length > 0) {
          msg += ' [' + event.data.relatedWaferIds.join(',') + ']';
        }
        break;
      case 'OrchestratorCommand':
        cls = 'transport';
        msg = '▶ CMD: ' + event.data.targetEquipmentId + ' ← ' + event.data.commandType + ' — ' + event.data.description;
        break;
      case 'FoupStateChanged':
        cls = 'transport';
        msg = 'FOUP ' + event.data.status + ' @ ' + event.data.location + ' (' + event.data.activeWaferCount + 'w';
        if (event.data.reworkWaferCount > 0) msg += ' +' + event.data.reworkWaferCount + 'rw';
        msg += ')';
        break;
      case 'DemoCompleted':
        msg = '✓ Demo completed — PASS=' + event.data.passedWafers + ' REWORK=' + event.data.reworkedWafers + ' SCRAP=' + event.data.scrappedWafers;
        break;
      default: msg = event.type + ' ' + JSON.stringify(event.data).substring(0, 80);
    }

    var timeline = document.getElementById('timeline');
    var entry = document.createElement('div');
    entry.className = 'entry' + (cls ? ' ' + cls : '');
    entry.innerHTML = '<span class="ts">' + ts + '</span>' + msg;
    if (highlight) entry.style.fontWeight = '700';
    timeline.insertBefore(entry, timeline.firstChild);

    // Keep max 200 entries
    while (timeline.children.length > 200) {
      timeline.removeChild(timeline.lastChild);
    }
  }

  // ===================================================================
  // Control Actions
  // ===================================================================
  window.startDemo = function() {
    var scenarioId = document.getElementById('scenarioSelect').value;
    loadScenarioLedger(scenarioId);
    fetch('/api/fab-demo/start/' + scenarioId, {method: 'POST'})
      .then(function(r) { return r.json(); })
      .then(function(data) {
        addTimelineEntry({type:'DemoStarted', data: 'Scenario: ' + data.message});
        // Reset wafer dots
        for (var i = 0; i < 5; i++) {
          var dot = document.getElementById('wd-' + i);
          if (dot) dot.setAttribute('fill', '#30363d');
        }
        state.waferResults = {};
      });
  };

  window.pauseDemo = function() { state.paused = true; };
  window.resumeDemo = function() { state.paused = false; };

  window.adjustSpeed = function(val) {
    state.speed = parseInt(val);
    document.getElementById('speedLabel').textContent = val + 'x';
  };

  window.injectFault = function(equipmentId, faultType) {
    addTimelineEntry({type:'FaultInjected', data: equipmentId + ' injected with ' + faultType});
  };

  // ===================================================================
  // Event Sourcing Ledger (Scenario Script panel)
  // ===================================================================
  function loadScenarioLedger(scenarioId) {
    fetch('/api/fab-demo/scenario/' + scenarioId + '/ledger')
      .then(function(r) { return r.json(); })
      .then(function(data) {
        var tbody = document.getElementById('ledgerBody');
        tbody.innerHTML = '';
        data.steps.forEach(function(step) {
          var tr = document.createElement('tr');
          tr.setAttribute('data-seq', step.seq);
          tr.innerHTML =
            '<td class="seq">' + step.seq + '</td>' +
            '<td>' + step.event + '</td>' +
            '<td>' + step.lotSource + '</td>' +
            '<td>' + step.lotRework + '</td>' +
            '<td>' + step.wafer + '</td>' +
            '<td>' + step.saga + '</td>' +
            '<td>' + step.phase + '</td>';
          tbody.appendChild(tr);
        });
        // Auto-scroll to first row
        var ledgerPanel = document.getElementById('ledgerPanel');
        if (ledgerPanel) ledgerPanel.scrollTop = 0;
      })
      .catch(function(err) {
        console.warn('Failed to load ledger:', err);
      });
  }

  function highlightLedgerRow(stepSeq) {
    var rows = document.querySelectorAll('#ledgerBody tr');
    rows.forEach(function(row) {
      var rowSeq = parseInt(row.getAttribute('data-seq'));
      if (rowSeq === stepSeq) {
        row.classList.add('active');
        row.scrollIntoView({ behavior: 'smooth', block: 'center' });
      } else if (rowSeq < stepSeq) {
        row.classList.add('done');
        row.classList.remove('active');
      } else {
        row.classList.remove('active', 'done');
      }
    });
  }

  // ===================================================================
  // Init
  // ===================================================================
  document.addEventListener('DOMContentLoaded', function() {
    // Load scenarios into dropdown
    fetch('/api/fab-demo/scenarios')
      .then(function(r) { return r.json(); })
      .then(function(scenarios) {
        var sel = document.getElementById('scenarioSelect');
        sel.innerHTML = '';
        scenarios.forEach(function(s) {
          var opt = document.createElement('option');
          opt.value = s.id;
          opt.textContent = s.name;
          sel.appendChild(opt);
        });
        // Load ledger for default scenario
        if (scenarios.length > 0) {
          loadScenarioLedger(scenarios[0].id);
        }
      })
      .catch(function() { /* demo page may be served before backend is ready */ });

    connectWebSocket();
  });
})();

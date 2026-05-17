/**
 * Fab Demo — M3 Closed-Loop Photo Cell Simulation
 *
 * UI control state + DOM rendering functions.
 * Observables streams are defined in fab_observable.js and wired via initObservableSubscriptions().
 */
(function() {
  'use strict';

  // ===================================================================
  // UI State (control-only, no data)
  // ===================================================================
  var state = {
    speed: 1,
    paused: false,
    aggregatePanelOpen: true
  };

  // Data accumulators (reset on _rxDestroy)
  var _scrap = { count: 0, ids: {} };
  var _waferResults = {};

  // ===================================================================
  // Observable wiring (replaces handleEvent + connectWebSocket)
  // ===================================================================
  function initObservableSubscriptions() {
    var S = window._rxStreams;
    var sub = window._rxSubscribe;

    // --- SVG / Equipment ---
    sub(S.equipmentState$, updateEquipmentNode);
    sub(S.foupInTransit$, animateFoupMovement);
    sub(S.foupArrived$, showFoupAtEquipment);
    sub(S.processingStarted$, pulseEquipment);
    sub(S.processingCompleted$, resetEquipmentColor);
    sub(S.orchestratorCmd$, showOrchestratorCommand);
    sub(S.foupStateChanged$, updateFoupState);

    // --- Measurement + Classification ---
    sub(S.measurement$, updateClassificationWheel);

    // --- Lot Summary ---
    sub(S.lotUpdated$, updateLotSummary);

    // --- Demo Lifecycle ---
    sub(S.demoCompleted$, function(data) {
      updateFoupCompleted();
      addTimelineEntry({type: 'DemoCompleted', data: data}, true);
    });
    sub(S.demoStarted$, function(data) {
      updateGlobalStatus({status: 'STARTED', detail: data.name, phase: 'Init'});
    });

    // --- Ledger ---
    sub(S.ledger$, function(data) {
      highlightLedgerRow(data.stepSeq);
      updateStepProgress(data.stepName || '');
    });

    // --- Global Status ---
    sub(S.globalStatus$, updateGlobalStatus);

    // --- Aggregate State (RxJS scan over pipeline AggregateStateUpdated snapshots) ---
    var aggModel$ = S.aggregateState$.pipe(
      rxjs.operators.scan(window._rxReducers.aggregate, {lots: {}, wafers: {}})
    );
    sub(aggModel$, renderAggregatePanel);

    // --- Domain Events (scan reducer) ---
    var deModel$ = S.domainEvent$.pipe(
      rxjs.operators.scan(window._rxReducers.domainEvent, {
        count: 0, layerCounts: [0, 0, 0, 0], layerEvents: [[], [], [], []]
      })
    );
    sub(deModel$, function(model) {
      document.getElementById('deCount').textContent = model.count;
      window._deModel = model;
      renderDomainEventSidebar(model);
    });

    // --- Scrap Events (local dedup state) ---
    _scrap = { count: 0, ids: {} };
    sub(S.scrapEvent$, function(data) {
      if (_scrap.ids[data.waferId]) return;
      _scrap.ids[data.waferId] = true;
      _scrap.count++;
      handleScrapEvent(data, _scrap.count);
    });

    // --- Saga / Decision ---
    sub(S.sagaEvent$, showSagaStatus);
    sub(S.decisionMade$, showWaferDecision);

    // --- Timeline (all events, batched at ~10fps) ---
    sub(S.timeline$, function(batch) {
      batch.forEach(function(evt) { addTimelineEntry(evt); });
    });

    // Sync paused state with the rx filter in fab_observable.js
    window._demoPaused = state.paused;
  }

  // ===================================================================
  // Equipment Node Updates
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
      el.textContent = (window.__i18n && window.__i18n.statusMap[data.status]) || data.status || window.__i18n.status_idle;
      el.setAttribute('fill', statusColor(data.status));
    }
    var nodeId = data.equipmentId.replace('-01','').toLowerCase();
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
      if (rect) rect.setAttribute('stroke', statusStrokeColor('Idle'));
    }
  }

  // ===================================================================
  // FOUP Movement Animation (requestAnimationFrame)
  // ===================================================================
  var _foupRafId = null;
  var _reworkFoupRafId = null;
  var _reworkFadeTimer = null;

  function _easeInOutQuad(t) {
    return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
  }

  function _animateRect(rectId, labelId, fromPos, toPos, durationMs) {
    var rect = document.getElementById(rectId);
    if (!rect) return;
    var label = labelId ? document.getElementById(labelId) : null;

    if (rectId === 'foupIcon') {
      if (_foupRafId) { cancelAnimationFrame(_foupRafId); _foupRafId = null; }
    } else {
      if (_reworkFoupRafId) { cancelAnimationFrame(_reworkFoupRafId); _reworkFoupRafId = null; }
    }

    rect.setAttribute('x', fromPos.x);
    rect.setAttribute('y', fromPos.y);

    var startTime = performance.now();

    function step(now) {
      var elapsed = now - startTime;
      var progress = Math.min(elapsed / durationMs, 1.0);
      var eased = _easeInOutQuad(progress);

      var curX = fromPos.x + (toPos.x - fromPos.x) * eased;
      var curY = fromPos.y + (toPos.y - fromPos.y) * eased;

      rect.setAttribute('x', curX);
      rect.setAttribute('y', curY);
      if (label) {
        label.setAttribute('x', curX);
        label.setAttribute('y', curY - 10);
      }

      if (progress < 1.0) {
        if (rectId === 'foupIcon') {
          _foupRafId = requestAnimationFrame(step);
        } else {
          _reworkFoupRafId = requestAnimationFrame(step);
        }
      } else {
        if (rectId === 'foupIcon') _foupRafId = null;
        else _reworkFoupRafId = null;
      }
    }

    if (rectId === 'foupIcon') {
      _foupRafId = requestAnimationFrame(step);
    } else {
      _reworkFoupRafId = requestAnimationFrame(step);
    }
  }

  function animateFoupMovement(data) {
    if ((data.fromArea === 'CDSEM' || data.fromArea === 'MET') && data.toArea === 'LITHO') {
      animateReworkFoup(data);
      return;
    }
    if ((data.fromArea === 'CDSEM' || data.fromArea === 'MET') && data.toArea === 'STOCKER') {
      var rf = document.getElementById('reworkFoupIcon');
      if (rf) rf.setAttribute('opacity', '0');
      var rl = document.getElementById('reworkFoupLabel');
      if (rl) rl.setAttribute('opacity', '0');
    }

    var foup = document.getElementById('foupIcon');
    if (!foup) return;
    foup.setAttribute('opacity', '1');

    var fromPos = getAreaPosition(data.fromArea);
    var toPos = getAreaPosition(data.toArea);
    var duration = Math.max(500, (data.etaMs || 1000) / state.speed);

    _animateRect('foupIcon', 'foupLotLabel', fromPos, toPos, duration);
  }

  function animateReworkFoup(data) {
    var rf = document.getElementById('reworkFoupIcon');
    if (!rf) return;
    rf.setAttribute('opacity', '1');
    var rl = document.getElementById('reworkFoupLabel');
    if (rl) rl.setAttribute('opacity', '1');

    var duration = Math.max(800, (data.etaMs || 2000) / state.speed);
    _animateRect('reworkFoupIcon', 'reworkFoupLabel', {x: 304, y: 155}, {x: 424, y: 155}, duration);

    var sl = document.getElementById('splitMergeLabel');
    if (sl) {
      sl.textContent = (window.__i18n && window.__i18n.phase_split) ? '↗ ' + window.__i18n.phase_split : '↗ SPLIT';
      sl.setAttribute('opacity', '1');
      sl.setAttribute('fill', '#a855f7');
    }
    if (_reworkFadeTimer) clearTimeout(_reworkFadeTimer);
    _reworkFadeTimer = setTimeout(function() {
      if (rf) rf.setAttribute('opacity', '0.3');
      if (rl) rl.setAttribute('opacity', '0.3');
      if (sl) sl.setAttribute('opacity', '0');
    }, duration);
  }

  function showFoupAtEquipment(data) {
    if (_foupRafId) { cancelAnimationFrame(_foupRafId); _foupRafId = null; }
    var pos = getAreaPosition(data.equipmentId);
    var foup = document.getElementById('foupIcon');
    if (foup) {
      foup.setAttribute('x', pos.x);
      foup.setAttribute('y', pos.y);
      foup.setAttribute('opacity', '1');
    }
    var lotLabel = document.getElementById('foupLotLabel');
    if (lotLabel) {
      lotLabel.setAttribute('x', pos.x);
      lotLabel.setAttribute('y', pos.y - 10);
    }
  }

  function getAreaPosition(areaId) {
    var key = areaId.replace('-01', '');
    var map = {
      'STOCKER': {x: 55, y: 170},
      'CLEAN': {x: 184, y: 133},
      'DIFF': {x: 304, y: 133},
      'LITHO': {x: 424, y: 133},
      'ETCH': {x: 544, y: 133},
      'IMPL': {x: 664, y: 133},
      'DEP': {x: 544, y: 268},
      'CMP': {x: 424, y: 268},
      'MET': {x: 304, y: 268},
      'CDSEM': {x: 304, y: 268},
      'DRY': {x: 184, y: 268},
      'LOG': {x: 664, y: 268},
      'SCRAP': {x: 304, y: 335},
      'LITHO_REWORK': {x: 424, y: 133},
      'RETURN': {x: 55, y: 170}
    };
    return map[key] || {x: 55, y: 170};
  }

  // ---- Orchestrator Command ----
  function showOrchestratorCommand(data) {
    var de = document.getElementById('eq-decision');
    if (de) {
      var rect = de.querySelector('rect');
      if (rect) {
        rect.setAttribute('stroke', '#58a6ff');
        rect.setAttribute('stroke-width', '3');
        setTimeout(function() { rect.setAttribute('stroke', '#f59e0b'); rect.setAttribute('stroke-width', '2'); }, 1000);
      }
    }
    var st = document.getElementById('status-decision');
    if (st) {
      st.textContent = data.commandType + ': ' + (data.description || '').substring(0, 35);
      st.setAttribute('fill', '#58a6ff');
    }
    var busLabel = document.getElementById('busCommandLabel');
    if (busLabel) {
      busLabel.textContent = '▶ ' + data.commandType;
      busLabel.setAttribute('opacity', '0.9');
      busLabel.setAttribute('fill', '#58a6ff');
      setTimeout(function() { busLabel.setAttribute('opacity', '0.2'); }, 2000);
    }
    pulseEquipment({equipmentId: data.targetEquipmentId});
  }

  // ---- FOUP State + Lot Labels ----
  function updateFoupState(data) {
    var label = document.getElementById('foupLotLabel');
    if (label && data.lotId) {
      label.textContent = data.lotId + ' [' + data.activeWaferCount + 'w]';
      label.setAttribute('opacity', '1');
    }
    var color = '#f59e0b';
    if (data.status === 'COMPLETED') color = '#3fb950';
    else if (data.status === 'SPLITTING') color = '#a855f7';
    else if (data.status === 'RETURNING') color = '#3fb950';
    else if (data.status === 'IN_TRANSIT') color = '#58a6ff';

    var foup = document.getElementById('foupIcon');
    if (foup) foup.setAttribute('fill', color);
    if (label) label.setAttribute('fill', color);

    var rwkLabel = document.getElementById('reworkFoupLabel');
    if (rwkLabel && data.reworkLotId) {
      rwkLabel.textContent = data.reworkLotId + ' [' + data.reworkWaferCount + 'w]';
      rwkLabel.setAttribute('opacity', '1');
      rwkLabel.setAttribute('fill', '#a855f7');
      var rwf = document.getElementById('reworkFoupIcon');
      if (rwf && data.status === 'SPLITTING') {
        rwf.setAttribute('x', '770');
        rwf.setAttribute('y', '94');
        rwf.setAttribute('opacity', '1');
        rwf.setAttribute('fill', '#a855f7');
      }
    }
    if (rwkLabel && data.status === 'COMPLETED') {
      rwkLabel.setAttribute('opacity', '0');
      var rwf2 = document.getElementById('reworkFoupIcon');
      if (rwf2) rwf2.setAttribute('opacity', '0');
    }
  }

  function updateFoupCompleted() {
    var foup = document.getElementById('foupIcon');
    if (foup) {
      foup.setAttribute('opacity', '0.3');
      foup.setAttribute('fill', '#3fb950');
    }
    var label = document.getElementById('foupLotLabel');
    if (label) label.setAttribute('fill', '#3fb950');
    var rf = document.getElementById('reworkFoupIcon');
    if (rf) rf.setAttribute('opacity', '0');
    var rl = document.getElementById('reworkFoupLabel');
    if (rl) rl.setAttribute('opacity', '0');
    var de = document.getElementById('status-decision');
    if (de) { de.textContent = 'Done'; de.setAttribute('fill', '#3fb950'); }
  }

  // ===================================================================
  // Global Status Indicator
  // ===================================================================
  var statusColorMap = {
    'LOADING': '#58a6ff',
    'TRANSPORTING': '#58a6ff',
    'AT_EQP': '#8b949e',
    'PROCESSING': '#f59e0b',
    'MEASURING': '#f59e0b',
    'CLASSIFYING': '#a855f7',
    'SPLITTING': '#a855f7',
    'REWORKING': '#f59e0b',
    'RETURNING': '#3fb950',
    'COMPLETED': '#3fb950',
    'RUNNING': '#58a6ff'
  };

  function updateGlobalStatus(data) {
    var stEl = document.getElementById('globalStatusText');
    if (stEl) {
      var color = statusColorMap[data.status] || '#8b949e';
      stEl.innerHTML = '<span style="color:' + color + '">●</span> ' + data.detail;
    }
    var deSt = document.getElementById('status-decision');
    if (deSt) {
      deSt.textContent = data.status + ': ' + data.detail;
      deSt.setAttribute('fill', statusColorMap[data.status] || '#8b949e');
    }
  }

  // ===================================================================
  // Scrap Bin
  // ===================================================================
  function handleScrapEvent(data, count) {
    var curCount = count || 1;

    // Update scrap bin count
    var sc = document.getElementById('scrapCount');
    if (sc) sc.textContent = curCount + ' wafer' + (curCount > 1 ? 's' : '');

    // Flash the scrap path line
    var scrapLine = document.getElementById('scrapPathLine');
    if (scrapLine) {
      scrapLine.setAttribute('stroke-opacity', '0.9');
      scrapLine.setAttribute('stroke-width', '3.5');
      setTimeout(function() {
        scrapLine.setAttribute('stroke-opacity', '0.5');
        scrapLine.setAttribute('stroke-width', '2');
      }, 900);
    }

    var waferNum = data.waferId.replace('WAFER-','');
    var svg = document.getElementById('factorySvg');
    var NS = 'http://www.w3.org/2000/svg';
    var flyId = 'scrap-fly-' + waferNum;

    var flyG = document.createElementNS(NS, 'g');
    flyG.setAttribute('id', flyId);

    var flyDot = document.createElementNS(NS, 'circle');
    flyDot.setAttribute('cx', '304');
    flyDot.setAttribute('cy', '248');
    flyDot.setAttribute('r', '6');
    flyDot.setAttribute('fill', '#f85149');
    flyDot.setAttribute('stroke', '#ff6b6b');
    flyDot.setAttribute('stroke-width', '1.5');
    var ax = document.createElementNS(NS, 'animate');
    ax.setAttribute('attributeName', 'cx');
    ax.setAttribute('values', '304;304');
    ax.setAttribute('dur', '0.7s');
    ax.setAttribute('fill', 'freeze');
    flyDot.appendChild(ax);
    var ay = document.createElementNS(NS, 'animate');
    ay.setAttribute('attributeName', 'cy');
    ay.setAttribute('values', '248;238;340');
    ay.setAttribute('dur', '0.7s');
    ay.setAttribute('fill', 'freeze');
    flyDot.appendChild(ay);
    flyG.appendChild(flyDot);

    var flyLbl = document.createElementNS(NS, 'text');
    flyLbl.setAttribute('x', '304');
    flyLbl.setAttribute('y', '244');
    flyLbl.setAttribute('text-anchor', 'middle');
    flyLbl.setAttribute('font-size', '6');
    flyLbl.setAttribute('fill', '#fff');
    flyLbl.setAttribute('font-family', 'monospace');
    flyLbl.setAttribute('font-weight', '700');
    flyLbl.textContent = 'W' + waferNum;
    var lx = document.createElementNS(NS, 'animate');
    lx.setAttribute('attributeName', 'x');
    lx.setAttribute('values', '304;304');
    lx.setAttribute('dur', '0.7s');
    lx.setAttribute('fill', 'freeze');
    flyLbl.appendChild(lx);
    var ly = document.createElementNS(NS, 'animate');
    ly.setAttribute('attributeName', 'y');
    ly.setAttribute('values', '244;234;336');
    ly.setAttribute('dur', '0.7s');
    ly.setAttribute('fill', 'freeze');
    flyLbl.appendChild(ly);
    flyG.appendChild(flyLbl);

    svg.appendChild(flyG);

    setTimeout(function() {
      var fg = document.getElementById(flyId);
      if (fg) fg.remove();

      var dotsGroup = document.getElementById('scrapWaferDots');
      if (dotsGroup) {
        var gap = 16;
        var cx = 14 + (curCount - 1) * gap;
        var cy = 20;
        var dot = document.createElementNS(NS, 'circle');
        dot.setAttribute('cx', cx);
        dot.setAttribute('cy', cy);
        dot.setAttribute('r', '5');
        dot.setAttribute('fill', '#f85149');
        dot.setAttribute('stroke', '#ff6b6b');
        dot.setAttribute('stroke-width', '1');
        dotsGroup.appendChild(dot);
        var label = document.createElementNS(NS, 'text');
        label.setAttribute('x', cx);
        label.setAttribute('y', cy + 13);
        label.setAttribute('text-anchor', 'middle');
        label.setAttribute('font-size', '6');
        label.setAttribute('fill', '#fff');
        label.setAttribute('font-family', 'monospace');
        label.textContent = 'W' + waferNum;
        dotsGroup.appendChild(label);
      }

      var bin = document.getElementById('scrapBin');
      if (bin) {
        bin.setAttribute('opacity', '1');
        var rect = bin.querySelector('rect');
        if (rect) {
          rect.setAttribute('stroke', '#f85149');
          rect.setAttribute('stroke-width', '3');
          setTimeout(function() { rect.setAttribute('stroke-width', '2'); }, 800);
        }
      }
    }, 750);

    // Pulse classification wheel dot
    var idx = parseInt(waferNum) - 1;
    var wd = document.getElementById('wd-' + idx);
    if (wd) {
      wd.setAttribute('fill', '#f85149');
      wd.setAttribute('r', '5');
      setTimeout(function() { wd.setAttribute('r', '3.5'); }, 500);
    }

    // Show bottom indicator text
    var sl = document.getElementById('splitMergeLabel');
    if (sl) {
      sl.textContent = (window.__i18n && window.__i18n.status_scrap) ? '✗ ' + window.__i18n.status_scrap + ': ' + data.waferId : '✗ SCRAP: ' + data.waferId;
      sl.setAttribute('opacity', '1');
      sl.setAttribute('fill', '#f85149');
      setTimeout(function() { sl.setAttribute('opacity', '0'); }, 3000);
    }
  }

  // ===================================================================
  // Domain Event Sidebar (pure render from model)
  // ===================================================================
  var deLayerVisible = [true, true, true, false];
  var deActiveFilter = -1;

  var deLayerColors = ['#8b5cf6', '#3b82f6', '#10b981', '#f59e0b'];
  var deLayerNames = ['Chain / Orchestration', 'Saga / Transaction', 'Aggregate / Entity', 'Process / Execution'];

  window.toggleDomainSidebar = function() {
    var sidebar = document.getElementById('deSidebar');
    sidebar.classList.toggle('open');
  };

  function renderDomainEventSidebar(model) {
    var list = document.getElementById('deList');
    if (model.count === 0) {
      list.innerHTML = '<div class="de-entry"><span class="de-ts">--</span> <span class="de-data">' + ((window.__i18n && window.__i18n.de_placeholder) || 'Waiting for events...') + '</span></div>';
      return;
    }

    var html = '';

    // Layer filter buttons
    html += '<div class="de-filter-bar">';
    html += '<button class="de-filter-btn' + (deActiveFilter === -1 ? ' active' : '') + '" onclick="deSetFilter(-1)">All</button>';
    for (var l = 0; l < 4; l++) {
      var activeCls = deActiveFilter === l ? ' active' : '';
      html += '<button class="de-filter-btn' + activeCls + '" style="border-left:3px solid ' + deLayerColors[l] + '" onclick="deSetFilter(' + l + ')">' +
        'L' + l + ' <span class="de-layer-badge">' + model.layerCounts[l] + '</span></button>';
    }
    html += '</div>';

    for (var ll = 0; ll < 4; ll++) {
      if (deActiveFilter !== -1 && deActiveFilter !== ll) continue;
      if (model.layerEvents[ll].length === 0) continue;

      var collapsed = !deLayerVisible[ll];
      var arrow = collapsed ? '▶' : '▼';
      html += '<div class="de-layer-group" style="border-left:3px solid ' + deLayerColors[ll] + '">';
      html += '<div class="de-layer-header" onclick="deToggleLayer(' + ll + ')">';
      html += '<span class="de-layer-arrow">' + arrow + '</span> ';
      html += '<span class="de-layer-name">Layer ' + ll + ': ' + deLayerNames[ll] + '</span> ';
      html += '<span class="de-layer-count">(' + model.layerCounts[ll] + ')</span>';
      html += '</div>';

      if (!collapsed) {
        html += '<div class="de-layer-events" id="de-layer-' + ll + '">';
        var events = model.layerEvents[ll];
        var maxShow = deActiveFilter === -1 ? Math.min(events.length, 20) : events.length;
        for (var i = 0; i < maxShow; i++) {
          var e = events[i];
          var ts = new Date(e.timestamp).toTimeString().slice(0, 8);
          var indent = ll === 1 && e.eventType.indexOf('Step') >= 0 ? '├ ' : '';
          html += '<div class="de-entry" style="border-left:2px solid ' + deLayerColors[ll] + '">';
          html += '<span class="de-ts">' + ts + '</span> ';
          html += '<span class="de-type" style="color:' + deLayerColors[ll] + '">' + indent + e.eventType + '</span>';
          html += '<span class="de-agg-id">[' + e.aggregateType + ':' + (e.aggregateId || '').substring(0, 8) + ']</span>';
          html += '<div class="de-data">' + e.data + '</div>';
          html += '</div>';
        }
        if (events.length > maxShow) {
          html += '<div class="de-entry de-more">... ' + (events.length - maxShow) + ' more events (use layer filter to see all)</div>';
        }
        html += '</div>';
      }
      html += '</div>';
    }

    list.innerHTML = html;
  }

  window.deSetFilter = function(layer) {
    deActiveFilter = layer;
    if (layer >= 0) deLayerVisible[layer] = true;
    // Re-render from current rx model
    renderDomainEventSidebar(window._deModel || {count: 0, layerCounts: [0,0,0,0], layerEvents: [[],[],[],[]]});
  };

  window.deToggleLayer = function(layer) {
    deLayerVisible[layer] = !deLayerVisible[layer];
    renderDomainEventSidebar(window._deModel || {count: 0, layerCounts: [0,0,0,0], layerEvents: [[],[],[],[]]});
  };

  // --- Saga / Decision helpers ---
  function showSagaStatus(data) {
    var label = document.getElementById('splitMergeLabel');
    if (label) {
      var opText = data.operation + ' ' + data.status;
      label.textContent = (data.status === 'COMMITTED' ? '✓ ' : '⟳ ') + opText;
      label.setAttribute('fill', data.status === 'COMMITTED' ? '#3fb950' : '#d29922');
      label.setAttribute('opacity', '1');
      setTimeout(function() { label.setAttribute('opacity', '0.5'); }, 4000);
    }
  }

  function showWaferDecision(data) {
    var wid = data.waferId;
    var waferIdx = parseInt(wid.split('-').pop()) - 1;
    if (isNaN(waferIdx)) return;
    var dot = document.getElementById('wd-' + waferIdx);
    if (!dot) return;
    var action = data.action || '';
    if (action.indexOf('HOLD') >= 0) {
      dot.setAttribute('fill', '#d29922');
    } else if (action.indexOf('SCRAP') >= 0) {
      dot.setAttribute('fill', '#f85149');
    } else if (action.indexOf('PASS') >= 0 || action.indexOf('Merge') >= 0) {
      dot.setAttribute('fill', '#3fb950');
    } else if (action.indexOf('Skip') >= 0) {
      dot.setAttribute('fill', '#8b949e');
    }
  }

  // ===================================================================
  // Aggregate State Panel (pure render from model)
  // ===================================================================
  function formatAreaName(areaId) {
    if (!areaId) return '';
    var map = {
      'STOCKER': 'Stocker', 'LITHO': 'Litho', 'CDSEM': 'CD-SEM',
      'MET': 'Metrology', 'CLEAN': 'Wet Clean', 'DIFF': 'Diffusion',
      'ETCH': 'Etch', 'IMPL': 'Implant', 'DEP': 'Deposition',
      'CMP': 'CMP', 'DRY': 'Drying', 'LOG': 'Logistics',
      'METROLOGY': 'Metrology', 'AMHS': 'AMHS'
    };
    // If it's a transport path like "STOCKER → LITHO", show as-is with AMHS prefix
    if (areaId.indexOf('→') >= 0) return 'AMHS: ' + areaId;
    return map[areaId] || areaId;
  }

  function renderAggregatePanel(model) {
    var tbody = document.getElementById('aggTreeBody');
    // model = scan(aggregateReducer) → {lots: {lotId: {...}}, wafers: {waferId: {...}}}
    var lotIds = Object.keys(model.lots);
    if (lotIds.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" style="color:var(--fg-muted)">' + ((window.__i18n && window.__i18n.aggregate_placeholder) || 'Waiting for Lot creation...') + '</td></tr>';
      return;
    }

    var rows = '';
    lotIds.forEach(function(lotId) {
      var lot = model.lots[lotId];
      var areaDisplay = formatAreaName(lot.currentArea);
      rows += '<tr class="lot-row">' +
        '<td>Lot: ' + (lot.productId || lotId) + '</td>' +
        '<td>' + (lot.status || 'Active') + '</td>' +
        '<td>' + (areaDisplay || '-') + '</td>' +
        '<td colspan="2">Wafers: ' + (lot.waferCount || 0) +
        ' | Pass: ' + (lot.passCount || 0) +
        ' | Scrap: ' + (lot.scrapCount || 0) + '</td>' +
        '</tr>';
      (lot.waferIds || []).forEach(function(wid) {
        var w = model.wafers[wid];
        if (!w) return;
        var stCls = w.status === 'Scrapped' ? 'status-Scrapped' : 'status-Active';
        var clsCls = 'cls-' + (w.classification || 'Pending');
        rows += '<tr class="wafer-row">' +
          '<td>' + (w.waferId || wid).substring(0, 8) + '</td>' +
          '<td class="' + stCls + '">' + (w.status || 'Active') + '</td>' +
          '<td></td>' +
          '<td class="' + clsCls + '">' + (w.classification || 'Pending') + '</td>' +
          '<td>' + (w.reworkCount || 0) + '</td>' +
          '</tr>';
      });
    });
    tbody.innerHTML = rows;
  }

  window.toggleAggregatePanel = function() {
    var panel = document.getElementById('aggregatePanel');
    var btn = panel.querySelector('.panel-header button');
    if (panel.classList.contains('collapsed')) {
      panel.classList.remove('collapsed');
      btn.textContent = (window.__i18n && window.__i18n.lang === 'zh') ? '▲ 折叠' : '▲ Collapse';
    } else {
      panel.classList.add('collapsed');
      btn.textContent = (window.__i18n && window.__i18n.lang === 'zh') ? '▼ 展开' : '▼ Expand';
    }
  };

  window.toggleRouteGraphPanel = function() {
    var panel = document.getElementById('routeGraphPanel');
    var btn = panel.querySelector('.panel-header button');
    if (panel.classList.contains('collapsed')) {
      panel.classList.remove('collapsed');
      btn.textContent = '▲ Collapse';
    } else {
      panel.classList.add('collapsed');
      btn.textContent = '▼ Expand';
    }
  };

  /** Load the route graph for a scenario and optionally highlight a step */
  window.showRouteGraph = function(scenarioId, workOrderId) {
    document.getElementById('routeGraphScenario').textContent = scenarioId;
    var container = document.getElementById('routeGraphContent');
    if (typeof loadRouteGraph === 'function') {
      loadRouteGraph(scenarioId, container);
    }
    // Store work order for step highlighting
    state._routeWorkOrder = workOrderId;
  };

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
    _waferResults[data.waferId] = data.classification;
    updateSummary(_waferResults);
  }

  function updateLotSummary(data) {
    document.getElementById('sum-active').textContent = data.activeWafers || '0';
    document.getElementById('sum-pass').textContent = data.passedWafers || '0';
    document.getElementById('sum-rework').textContent = data.reworkedWafers || '0';
    document.getElementById('sum-scrap').textContent = data.scrappedWafers || '0';
  }

  function updateSummary(waferResults) {
    var results = waferResults || {};
    var pass = 0, fail = 0, rework = 0;
    Object.values(results).forEach(function(r) {
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
        cls = event.data.action.indexOf('Rework') >= 0 ? 'rework' : (event.data.action.indexOf('PASS') >= 0 ? 'pass' : 'fail');
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
        msg = '▶ CMD: ' + event.data.targetEquipmentId + ' ← ' + event.data.commandType + ' — ' + (event.data.description || '').substring(0, 50);
        break;
      case 'FoupStateChanged':
        cls = 'transport';
        msg = 'FOUP ' + event.data.status + ' @ ' + event.data.location + ' (' + event.data.activeWaferCount + 'w';
        if (event.data.reworkWaferCount > 0) msg += ' +' + event.data.reworkWaferCount + 'rw';
        msg += ')';
        if (event.data.lotId) msg += ' [' + event.data.lotId + ']';
        break;
      case 'GlobalStatusChanged':
        cls = ''; msg = '⚡ ' + event.data.status + ': ' + event.data.detail; break;
      case 'ScrapEvent':
        cls = 'fail'; msg = '✗ SCRAP: ' + event.data.waferId + ' — ' + event.data.reason; break;
      case 'AggregateStateUpdated':
        cls = ''; msg = '📊 Aggregate state updated'; break;
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

    while (timeline.children.length > 200) {
      timeline.removeChild(timeline.lastChild);
    }
  }

  // ===================================================================
  // UI Reset + Controls
  // ===================================================================
  function _resetAllUI() {
    // Destroy all rx subscriptions
    if (typeof window._rxDestroy === 'function') {
      window._rxDestroy();
    }

    // Reset local data
    _scrap = { count: 0, ids: {} };
    _waferResults = {};
    deLayerVisible = [true, true, true, false];
    deActiveFilter = -1;
    window._deModel = {count: 0, layerCounts: [0,0,0,0], layerEvents: [[],[],[],[]]};

    // Cancel in-flight FOUP animations
    if (_foupRafId) { cancelAnimationFrame(_foupRafId); _foupRafId = null; }
    if (_reworkFoupRafId) { cancelAnimationFrame(_reworkFoupRafId); _reworkFoupRafId = null; }
    if (_reworkFadeTimer) { clearTimeout(_reworkFadeTimer); _reworkFadeTimer = null; }

    // Hide FOUP icons
    var foup = document.getElementById('foupIcon');
    if (foup) { foup.setAttribute('opacity', '0'); foup.setAttribute('x', '55'); foup.setAttribute('y', '170'); }
    var lotLabel = document.getElementById('foupLotLabel');
    if (lotLabel) { lotLabel.setAttribute('opacity', '0'); lotLabel.textContent = '--'; }
    var rf = document.getElementById('reworkFoupIcon');
    if (rf) { rf.setAttribute('opacity', '0'); rf.setAttribute('x', '304'); rf.setAttribute('y', '265'); }
    var rl = document.getElementById('reworkFoupLabel');
    if (rl) { rl.setAttribute('opacity', '0'); rl.textContent = '--'; }
    var sl = document.getElementById('splitMergeLabel');
    if (sl) { sl.setAttribute('opacity', '0'); sl.textContent = '--'; }

    // Reset timeline
    var timeline = document.getElementById('timeline');
    if (timeline) {
      timeline.innerHTML = '<div class="entry"><span class="ts">--:--:--</span> ' + ((window.__i18n && window.__i18n.timeline_ready) || 'Ready. Click Start to begin.') + '</div>';
    }

    // Reset summary counters
    document.getElementById('sum-active').textContent = '-';
    document.getElementById('sum-pass').textContent = '-';
    document.getElementById('sum-rework').textContent = '-';
    document.getElementById('sum-scrap').textContent = '-';

    // Reset equipment status indicators
    var statusIds = ['status-stocker','status-clean','status-diff','status-litho','status-etch',
      'status-implant','status-dep','status-cmp','status-cdsem','status-dry','status-log'];
    var idleText = (window.__i18n && window.__i18n.status_idle) || 'Idle';
    statusIds.forEach(function(sid) {
      var el = document.getElementById(sid);
      if (el) { el.textContent = idleText; el.setAttribute('fill', '#6e7681'); }
    });
    var eqIds = ['stocker','clean','diff','litho','etch','implant','dep','cmp','met','dry','log','decision'];
    eqIds.forEach(function(eid) {
      var node = document.getElementById('eq-' + eid);
      if (node) {
        var rect = node.querySelector('rect');
        if (rect) { rect.setAttribute('stroke', eid === 'decision' ? '#f59e0b' : '#30363d'); rect.setAttribute('stroke-width', '2'); }
      }
    });

    // Reset decision engine status
    var deSt = document.getElementById('status-decision');
    if (deSt) {
      deSt.textContent = (window.__i18n && window.__i18n.decision_wait) || 'Waiting for scenario...';
      deSt.setAttribute('fill', '#6e7681');
    }

    // Reset global status
    var gsEl = document.getElementById('globalStatusText');
    if (gsEl) { gsEl.innerHTML = (window.__i18n && window.__i18n.controls_ready) || 'Ready'; }
    var busLabel = document.getElementById('busCommandLabel');
    if (busLabel) { busLabel.setAttribute('opacity', '0'); busLabel.textContent = '--'; }

    // Reset classification wheel dots
    for (var i = 0; i < 10; i++) {
      var dot = document.getElementById('wd-' + i);
      if (dot) dot.setAttribute('fill', '#30363d');
    }

    // Reset scrap bin
    var sc = document.getElementById('scrapCount');
    if (sc) sc.textContent = (window.__i18n && window.__i18n.scrap_count) || '0 wafer';
    var dotsGroup = document.getElementById('scrapWaferDots');
    if (dotsGroup) dotsGroup.innerHTML = '';
    var scrapLine = document.getElementById('scrapPathLine');
    if (scrapLine) { scrapLine.setAttribute('stroke-opacity', '0.45'); scrapLine.setAttribute('stroke-width', '1.8'); }

    // Reset ledger row highlights
    var ledgerRows = document.querySelectorAll('#ledgerBody tr');
    ledgerRows.forEach(function(row) { row.classList.remove('active', 'done'); });

    // Reset step progress indicator
    var spEl = document.getElementById('stepProgress');
    if (spEl) { spEl.style.display = 'none'; spEl.textContent = ''; }

    // Reset domain event sidebar
    document.getElementById('deCount').textContent = '0';
    document.getElementById('deList').innerHTML = '<div class="de-entry"><span class="de-ts">--</span> <span class="de-data">' + ((window.__i18n && window.__i18n.de_placeholder) || 'Waiting for events...') + '</span></div>';
    document.getElementById('deSidebar').classList.remove('open');

    // Reset aggregate panel
    document.getElementById('aggTreeBody').innerHTML = '<tr><td colspan="5" style="color:var(--fg-muted)">' + ((window.__i18n && window.__i18n.aggregate_placeholder) || 'Waiting for Lot creation...') + '</td></tr>';

    // Re-init rx subscriptions for the new demo
    window._rxInit();
    if (typeof window._rxStreams !== 'undefined') {
      initObservableSubscriptions();
    }
  }

  window.startDemo = function() {
    // Stash the chosen scenario for ledger loading
    var sel = document.getElementById('scenarioSelect');
    var scenarioId = sel.value;
    var scenarioType = sel.options[sel.selectedIndex].getAttribute('data-type') || '';

    // Reset JS state
    state.paused = false;
    window._demoPaused = false;

    // Reset all UI + rx subscriptions
    _resetAllUI();

    // Load ledger for the scenario
    loadScenarioLedger(scenarioId);

    var startUrl = scenarioType === 'dynamic-routing'
      ? '/api/fab-demo/product/' + scenarioId + '/start'
      : '/api/fab-demo/start/' + scenarioId;
    fetch(startUrl, {method: 'POST'})
      .then(function(r) { return r.json(); })
      .then(function(data) {
        addTimelineEntry({type:'DemoStarted', data: 'Scenario: ' + data.message});
        if (data.workOrderId) {
          window._currentWorkOrderId = data.workOrderId;
          var woInput = document.getElementById('entityWorkOrderInput');
          if (woInput) woInput.value = data.workOrderId;
        }
        // Load the route graph for visual context
        if (typeof showRouteGraph === 'function') {
          showRouteGraph(scenarioId, data.workOrderId);
        }
      });
  };

  window.pauseDemo = function() {
    state.paused = true;
    window._demoPaused = true;
  };

  window.resumeDemo = function() {
    state.paused = false;
    window._demoPaused = false;
  };

  window.adjustSpeed = function(val) {
    state.speed = parseInt(val);
    document.getElementById('speedLabel').textContent = val + 'x';
  };

  window.injectFault = function(equipmentId, faultType) {
    addTimelineEntry({type:'FaultInjected', data: equipmentId + ' injected with ' + faultType});
  };

  // ===================================================================
  // Event Sourcing Ledger
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
        var ledgerPanel = document.getElementById('ledgerPanel');
        if (ledgerPanel) ledgerPanel.scrollTop = 0;
        if (data.lotReworkLabel) {
          var ths = document.querySelectorAll('#ledgerTable thead th');
          if (ths.length >= 4) {
            ths[3].textContent = 'Lot(' + data.lotReworkLabel + ')';
          }
        }
      })
      .catch(function(err) {
        console.warn('Failed to load ledger:', err);
      });
  }

  /** Map ledger phase names to route graph node IDs for visual highlighting */
  var _phaseToNode = {
    'Load': 'n-load', 'Transport': null, 'AtEqp': null, 'Process': null,
    'Decide': 'n-cls', 'Split': 'n-split', 'Merge': 'n-merge',
    'Rework': 'n-rwk-litho', 'Hold': 'n-hold',
    'Measure': 'n-cdsem', 'Complete': 'n-seal'
  };

  function highlightLedgerRow(stepSeq) {
    var rows = document.querySelectorAll('#ledgerBody tr');
    var currentPhase = null;
    rows.forEach(function(row) {
      var rowSeq = parseInt(row.getAttribute('data-seq'));
      if (rowSeq === stepSeq) {
        row.classList.add('active');
        row.scrollIntoView({ behavior: 'smooth', block: 'center' });
        // Extract phase from the phase column
        var phaseCell = row.querySelector('td:last-child');
        if (phaseCell) currentPhase = phaseCell.textContent.trim();
      } else if (rowSeq < stepSeq) {
        row.classList.add('done');
        row.classList.remove('active');
      } else {
        row.classList.remove('active', 'done');
      }
    });
    // Highlight route graph node
    if (currentPhase && _phaseToNode[currentPhase] && typeof highlightRouteNode === 'function') {
      highlightRouteNode(_phaseToNode[currentPhase]);
    }
  }

  function updateStepProgress(stepName) {
    var el = document.getElementById('stepProgress');
    if (!el) return;
    var match = stepName.match(/^Step\s+(\d+)\/(\d+):\s+(\S+)/);
    if (match) {
      var stepNum = match[1], total = match[2], area = match[3];
      var reentryMatch = stepName.match(/reentry=(\d+)/);
      var reentry = reentryMatch ? reentryMatch[1] : '0';
      var tpl = (window.__i18n && window.__i18n.step_progress) || 'Step __step__/__total__: __area__ (reentry=__reentry__)';
      el.textContent = tpl.replace('__step__', stepNum).replace('__total__', total).replace('__area__', area).replace('__reentry__', reentry);
      el.style.display = 'inline';
      el.style.color = stepName.indexOf('auto-advance') >= 0 ? 'var(--fg-muted)' : 'var(--amber)';
    }
  }

  // ===================================================================
  // Init
  // ===================================================================
  var scenarioTypeLabels = {
    'rework': 'Rework', 'send-ahead': 'Send-Ahead',
    'scrap': 'Scrap', 'sampling': 'Sampling', 'hold': 'Hold/Release'
  };

  window.fetchEntityState = function() {
    var input = document.getElementById('entityWorkOrderInput');
    var workOrderId = input && input.value ? input.value : (window._currentWorkOrderId || '');
    if (!workOrderId) {
      alert('No WorkOrder ID. Start a demo first, or enter an ID manually.');
      return;
    }
    // Show loading
    var modal = document.getElementById('entityStateModal');
    if (!modal) { createEntityModal(); modal = document.getElementById('entityStateModal'); }
    var body = document.getElementById('entityStateModalBody');
    body.innerHTML = '<div style="padding:16px;color:var(--amber)">Querying entity state for: ' + workOrderId + '...</div>';
    modal.style.display = 'flex';

    fetch('/api/fab-demo/entity-state/' + encodeURIComponent(workOrderId))
      .then(function(r) { return r.json(); })
      .then(function(data) {
        if (data.error) { body.innerHTML = '<div style="padding:16px;color:var(--red)">Error: ' + data.error + '</div>'; return; }
        renderEntityState(data);
      })
      .catch(function(err) {
        body.innerHTML = '<div style="padding:16px;color:var(--red)">Fetch failed: ' + err.message + '</div>';
      });
  };


  window.closeEntityModal = function() { document.getElementById("entityStateModal").style.display = "none"; };
  function createEntityModal() {
    var modal = document.createElement('div');
    modal.id = 'entityStateModal';
    modal.style.cssText = 'display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.7);z-index:9999;align-items:center;justify-content:center';
    modal.innerHTML = '<div style="background:var(--bg);border:1px solid var(--border);border-radius:8px;max-width:900px;width:95%;max-height:85vh;display:flex;flex-direction:column;box-shadow:0 8px 32px rgba(0,0,0,0.5)">' +
      '<div style="display:flex;align-items:center;justify-content:space-between;padding:12px 16px;border-bottom:1px solid var(--border)">' +
        '<h3 style="margin:0;font-size:14px;color:var(--amber)">🔍 Entity State (ClusterSharding)</h3>' +
        '<button onclick="closeEntityModal()" style="background:none;border:none;color:var(--fg-muted);font-size:20px;cursor:pointer">&times;</button>' +
      '</div>' +
      '<div id="entityStateModalBody" style="overflow-y:auto;padding:12px 16px;flex:1"></div>' +
    '</div>';
    modal.addEventListener('click', function(e) { if (e.target === modal) modal.style.display = 'none'; });
    document.body.appendChild(modal);
  }

  function renderEntityState(data) {
    var h = '';
    function ff(v) { return v === undefined || v === null || v === '' ? '<span style="color:var(--fg-muted)">—</span>' : v; }
    function arr(a) { if (!a || a.length === 0) return '<span style="color:var(--fg-muted)">[]</span>'; return a.map(function(x) { return typeof x === 'object' ? JSON.stringify(x) : x; }).join(', '); }
    function obj(o) { if (!o || Object.keys(o).length === 0) return '<span style="color:var(--fg-muted)">{}</span>'; return JSON.stringify(o, null, 1); }
    function tbl(rows) { var s = '<table style="font-size:11px;width:100%;border-collapse:collapse">'; rows.forEach(function(r) { s += '<tr><td style="padding:2px 8px 2px 0;color:var(--fg-muted);white-space:nowrap;vertical-align:top">' + r[0] + '</td><td style="padding:2px 0;word-break:break-all;font-family:monospace;font-size:11px">' + r[1] + '</td></tr>'; }); s += '</table>'; return s; }

    h += '<div style="margin-bottom:8px;font-size:12px;color:var(--fg-muted)">WorkOrder: <b style="color:var(--fg)">' + data.workOrderId + '</b></div>';

    // Source Lot
    var sl = data.sourceLot;
    h += '<details open style="margin-bottom:8px"><summary style="font-weight:700;color:var(--blue);cursor:pointer;font-size:13px">Source Lot</summary>';
    h += '<div style="padding:4px 0 0 12px">' + tbl([
      ['phase', '<b>' + sl.phase + '</b>'],
      ['productId', ff(sl.productId)],
      ['lotId (UUID)', ff(sl.lotId)],
      ['waferCount', sl.waferCount],
      ['waferIds', arr(sl.waferIds)],
      ['currentStepIndex', sl.currentStepIndex],
      ['loadedFoupId', ff(sl.loadedFoupId)],
      ['areaVisitHistory', arr(sl.areaVisitHistory)],
      ['routingStepReentry', obj(sl.routingStepReentry)],
      ['completedJobs', arr(sl.completedJobs)],
      ['measuredWafers', arr(sl.measuredWafers)],
      ['completedTransferIds', arr(sl.completedTransferIds)],
      ['reservedWafers (outgoing)', arr(sl.reservedWafers.map(function(r){return r.transferId.substring(0,8)+'→['+r.waferIds.map(function(w){return w.substring(0,8)}).join(',')+']'}))],
      ['incomingWafers', arr(sl.incomingWafers.map(function(r){return r.transferId.substring(0,8)+'→['+r.waferIds.map(function(w){return w.substring(0,8)}).join(',')+']'}))],
      ['waferClassifications', obj(sl.waferClassifications)]
    ]) + '</div></details>';

    // Child Lots
    var childLotKeys = Object.keys(data.childLots || {});
    if (childLotKeys.length > 0) {
      childLotKeys.forEach(function(key) {
        var rl = data.childLots[key];
        var label = key.charAt(0).toUpperCase() + key.slice(1) + ' Lot';
        h += '<details open style="margin-bottom:8px"><summary style="font-weight:700;color:var(--amber);cursor:pointer;font-size:13px">' + label + '</summary>';
        h += '<div style="padding:4px 0 0 12px">' + tbl([
          ['phase', '<b>' + (rl.phase || '') + '</b>'],
          ['lotId (UUID)', ff(rl.lotId)],
          ['waferCount', rl.waferCount],
          ['waferIds', arr(rl.waferIds)],
          ['loadedFoupId', ff(rl.loadedFoupId)],
          ['areaVisitHistory', arr(rl.areaVisitHistory)],
          ['completedJobs', arr(rl.completedJobs)],
          ['measuredWafers', arr(rl.measuredWafers)],
          ['completedTransferIds', arr(rl.completedTransferIds)],
          ['waferClassifications', obj(rl.waferClassifications)]
        ]) + '</div></details>';
      });
    }

    // Wafer Entities
    var waferKeys = Object.keys(data.wafers || {});
    h += '<details open style="margin-bottom:8px"><summary style="font-weight:700;color:var(--green);cursor:pointer;font-size:13px">Wafer Entities (' + waferKeys.length + ')</summary>';
    h += '<div style="padding:4px 0 0 12px">';
    waferKeys.forEach(function(wid) {
      var w = data.wafers[wid];
      h += '<details style="margin-bottom:2px"><summary style="font-size:12px;cursor:pointer;font-family:monospace">' + (w.waferId || wid).substring(0, 8) + ' — ' + w.status + '</summary>';
      h += '<div style="padding:2px 0 0 16px">' + tbl([
        ['waferId', ff(w.waferId)],
        ['status', '<b>' + w.status + '</b>'],
        ['lotId (UUID)', ff(w.lotId)],
        ['reservedTransfer', w.reservedTransfer ? w.reservedTransfer.transferId.substring(0,8) + ' → ' + w.reservedTransfer.targetLotId.substring(0,8) : '<span style="color:var(--fg-muted)">None</span>'],
        ['completedTransferIds', arr(w.completedTransferIds)]
      ]) + '</div></details>';
    });
    h += '</div></details>';

    document.getElementById('entityStateModalBody').innerHTML = h;
  }

  // ===================================================================
  // Init
  // ===================================================================
  var scenarioTypeLabels = {
    'rework': 'Rework', 'send-ahead': 'Send-Ahead',
    'scrap': 'Scrap', 'sampling': 'Sampling', 'hold': 'Hold/Release'
  };

  document.addEventListener('DOMContentLoaded', function() {
    // Wire up rx streams (fab_observable.js is loaded first, so _rxStreams exists)
    initObservableSubscriptions();

    // Load scenario list
    fetch('/api/fab-demo/scenarios')
      .then(function(r) { return r.json(); })
      .then(function(scenarios) {
        var sel = document.getElementById('scenarioSelect');
        sel.innerHTML = '';
        scenarios.forEach(function(s) {
          var opt = document.createElement('option');
          opt.value = s.id;
          opt.setAttribute('data-type', s.type || '');
          var typeLabel = s.type ? ' [' + (scenarioTypeLabels[s.type] || s.type) + ']' : '';
          opt.textContent = s.name + typeLabel;
          sel.appendChild(opt);
        });
        if (scenarios.length > 0) {
          loadScenarioLedger(scenarios[0].id);
        }
      })
      .catch(function() { /* demo page may be served before backend is ready */ });
  });

  // ====================================================================
  // Route Browser (M3.5+)
  // ====================================================================

  /** Toggle the Route Browser panel collapse */
  window.toggleRouteBrowserPanel = function () {
    var panel = document.getElementById('routeBrowserPanel');
    if (!panel) return;
    var content = panel.querySelector('.panel-content');
    var btn = panel.querySelector('.panel-header button');
    if (!content || !btn) return;
    content.classList.toggle('collapsed');
    btn.textContent = content.classList.contains('collapsed') ? '▼ Expand' : '▲ Collapse';
  };

  /** Seed default routes into RoutingRepository (idempotent) */
  window.seedRoutes = function () {
    var listEl = document.getElementById('routeList');
    listEl.innerHTML = '<span style="color:var(--amber)">Seeding routes...</span>';
    fetch('/api/fab-demo/routes/seed', { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        fetchRouteList();
      })
      .catch(function (err) {
        listEl.innerHTML = '<span style="color:var(--red)">Error: ' + err.message + '</span>';
      });
  };

  /** Fetch and display the route list from RoutingRepository */
  window.fetchRouteList = function () {
    var listEl = document.getElementById('routeList');
    fetch('/api/fab-demo/routes')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        var routes = data.routes || [];
        if (routes.length === 0) {
          listEl.innerHTML = '<p style="color:var(--fg-muted)">No routes in repository. Click "Seed Default Routes" first.</p>';
          return;
        }
        var html = '<table style="width:100%;border-collapse:collapse;font-size:11px">' +
          '<thead><tr style="color:var(--fg-muted);border-bottom:1px solid var(--border)">' +
          '<th style="text-align:left;padding:4px">Route ID</th>' +
          '<th style="text-align:left;padding:4px">Name</th>' +
          '<th style="text-align:right;padding:4px">Ver</th>' +
          '<th style="text-align:right;padding:4px">Nodes</th>' +
          '<th style="text-align:right;padding:4px">Edges</th>' +
          '<th style="padding:4px">Actions</th></tr></thead><tbody>';
        routes.forEach(function (r) {
          html += '<tr style="border-bottom:1px solid var(--border)">' +
            '<td style="padding:4px;color:var(--accent)">' + r.routeId + '</td>' +
            '<td style="padding:4px">' + r.name + '</td>' +
            '<td style="padding:4px;text-align:right">v' + r.version + '</td>' +
            '<td style="padding:4px;text-align:right">' + r.nodeCount + '</td>' +
            '<td style="padding:4px;text-align:right">' + r.edgeCount + '</td>' +
            '<td style="padding:4px">' +
            '<button onclick="compileRoute(\'' + r.routeId + '\')" style="background:var(--bg-alt);color:var(--accent);border:1px solid var(--border);padding:2px 8px;border-radius:3px;cursor:pointer;font-size:10px">Compile</button>' +
            '<button onclick="showRouteGraphFor(\'' + r.routeId + '\')" style="background:var(--bg-alt);color:var(--green);border:1px solid var(--border);padding:2px 8px;border-radius:3px;cursor:pointer;font-size:10px;margin-left:4px">Graph</button>' +
            '<button onclick="startRoute(\'' + r.routeId + '\')" style="background:var(--accent);color:#fff;border:1px solid var(--accent);padding:2px 8px;border-radius:3px;cursor:pointer;font-size:10px;margin-left:4px">Start</button>' +
            '</td></tr>';
        });
        html += '</tbody></table>';
        listEl.innerHTML = html;
      })
      .catch(function (err) {
        listEl.innerHTML = '<span style="color:var(--red)">' + err.message + '</span>';
      });
  };

  /** Compile a route and show the step sequence preview */
  window.compileRoute = function (routeId) {
    var previewEl = document.getElementById('routeStepsPreview');
    previewEl.style.display = 'block';
    previewEl.innerHTML = '<span style="color:var(--amber)">Compiling ' + routeId + '...</span>';
    fetch('/api/fab-demo/routes/' + encodeURIComponent(routeId) + '/compile')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        var steps = data.steps || [];
        var html = '<div style="color:var(--green);margin-bottom:4px">' + routeId + ' v' + data.version + ' — ' + data.stepCount + ' steps</div>';
        html += '<div style="display:flex;flex-wrap:wrap;gap:3px">';
        steps.forEach(function (s, i) {
          var isTrackIn = s.indexOf('TrackIn') === 0;
          var isTrackOut = s.indexOf('TrackOut') === 0;
          var color = isTrackIn ? 'var(--green)' : (isTrackOut ? 'var(--amber)' : 'var(--accent)');
          html += '<span style="padding:1px 6px;border:1px solid ' + color + ';border-radius:3px;color:' + color + ';font-family:monospace;font-size:10px" title="Step ' + (i + 1) + '">' + s + '</span>';
        });
        html += '</div>';
        previewEl.innerHTML = html;
      })
      .catch(function (err) {
        previewEl.innerHTML = '<span style="color:var(--red)">' + err.message + '</span>';
      });
  };

  /** Show route graph for a compiled route (via existing route-graph API) */
  window.showRouteGraphFor = function (routeId) {
    var panel = document.getElementById('routeGraphPanel');
    var content = document.getElementById('routeGraphContent');
    var label = document.getElementById('routeGraphScenario');
    if (content) content.classList.remove('collapsed');
    if (label) label.textContent = routeId;
    // Load graph via existing route graph endpoint
    fetch('/api/fab-demo/scenario/' + encodeURIComponent(routeId) + '/route-graph')
      .then(function (r) {
        if (!r.ok) throw new Error('No graph available for ' + routeId);
        return r.json();
      })
      .then(function (graph) {
        window.loadRouteGraph(routeId, content);
      })
      .catch(function (err) {
        // Fallback: try the generic compile endpoint as graph source
        fetch('/api/fab-demo/routes/' + encodeURIComponent(routeId) + '/compile')
          .then(function (r) { return r.json(); })
          .then(function (data) {
            // Show step list as a simple flow
            var steps = data.steps || [];
            var html = '<div style="padding:12px;font-family:monospace;font-size:10px;line-height:2">';
            steps.forEach(function (s, i) {
              html += '<span style="color:var(--accent)">' + (i + 1) + '.</span> ' + s + '<br>';
            });
            html += '</div>';
            content.innerHTML = html;
          })
          .catch(function () {
            content.innerHTML = '<p style="color:var(--red);padding:12px">Cannot render graph for ' + routeId + '</p>';
          });
      });
  };

  /** Start a demo directly from a RouteDefinition in the Route Browser */
  window.startRoute = function (routeId) {
    state.paused = false;
    window._demoPaused = false;
    _resetAllUI();
    loadScenarioLedger(routeId);

    addTimelineEntry({type: 'Info', data: 'Starting route: ' + routeId});

    fetch('/api/fab-demo/routes/' + encodeURIComponent(routeId) + '/start', { method: 'POST' })
      .then(function (r) {
        if (!r.ok) return r.json().then(function (e) { throw new Error(e.error || 'Start failed'); });
        return r.json();
      })
      .then(function (data) {
        addTimelineEntry({type: 'DemoStarted', data: 'Route: ' + data.message});
        if (data.workOrderId) {
          window._currentWorkOrderId = data.workOrderId;
        }
        // Refresh the dropdown so this route appears for future starts
        fetch('/api/fab-demo/scenarios')
          .then(function (r) { return r.json(); })
          .then(function (scenarios) {
            var sel = document.getElementById('scenarioSelect');
            var existingIds = {};
            for (var i = 0; i < sel.options.length; i++) {
              existingIds[sel.options[i].value] = true;
            }
            var added = false;
            scenarios.forEach(function (s) {
              if (!existingIds[s.id]) {
                var opt = document.createElement('option');
                opt.value = s.id;
                opt.setAttribute('data-type', s.type || '');
                var typeLabel = s.type ? ' [' + (scenarioTypeLabels[s.type] || s.type) + ']' : '';
                opt.textContent = s.name + typeLabel;
                sel.appendChild(opt);
                added = true;
              }
            });
            if (added) {
              addTimelineEntry({type: 'Info', data: 'Route ' + routeId + ' added to scenario dropdown'});
            }
          });
        if (typeof showRouteGraph === 'function') {
          showRouteGraph(routeId, data.workOrderId);
        }
      })
      .catch(function (err) {
        addTimelineEntry({type: 'Error', data: 'Route start failed: ' + err.message});
      });
  };
})();

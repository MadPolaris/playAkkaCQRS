/**
 * Fab Demo — M3 Closed-Loop Photo Cell Simulation
 *
 * Connects to the FabDemoController WebSocket and renders:
 *   1. Factory floor SVG (optimized: Decision Engine top, equipment same row, 3-tier rails)
 *   2. Timeline log
 *   3. Summary panel
 *   4. Classification wheel
 *   5. Event Sourcing Ledger
 *   6. Aggregate State panel (需求5)
 *   7. Scrap Bin (需求1)
 *   8. Global Status indicator (需求3)
 *   9. FOUP Lot labels (需求2)
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
    waferResults: {},  // waferId -> classification
    scrapCount: 0,
    scrappedWaferIds: {},  // dedup: waferId -> true
    aggregatePanelOpen: true
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
        updateStepProgress(event.data.stepName || '');
        break;
      case 'GlobalStatusChanged':
        updateGlobalStatus(event.data);
        break;
      case 'AggregateStateUpdated':
        updateAggregatePanel(event.data);
        break;
      case 'ScrapEvent':
        handleScrapEvent(event.data);
        break;
      case 'DomainEventRecorded':
        handleDomainEventRecorded(event.data);
        break;
      case 'SagaOperationEvent':
        showSagaStatus(event.data);
        break;
      case 'DecisionMade':
        showWaferDecision(event.data);
        break;
      case 'DemoStarted':
        updateGlobalStatus({status: 'STARTED', detail: event.data.name, phase: 'Init'});
        break;
    }
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
  // FOUP Movement Animation (需求4: 新坐标)
  // ===================================================================
  function animateFoupMovement(data) {
    // Rework path: use rework FOUP icon (CDSEM or MET → LITHO)
    if ((data.fromArea === 'CDSEM' || data.fromArea === 'MET') && data.toArea === 'LITHO') {
      animateReworkFoup(data);
      return;
    }
    // Return to Stocker: hide rework FOUP
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

    var animX = document.getElementById('foupAnimX');
    var animY = document.getElementById('foupAnimY');
    if (animX && animY) {
      animX.setAttribute('values', fromPos.x + ';' + toPos.x);
      animX.setAttribute('dur', duration + 'ms');
      animY.setAttribute('values', fromPos.y + ';' + toPos.y);
      animY.setAttribute('dur', duration + 'ms');
    }
    // Move lot label with FOUP
    var lotLabel = document.getElementById('foupLotLabel');
    if (lotLabel) {
      lotLabel.setAttribute('x', toPos.x);
      lotLabel.setAttribute('y', toPos.y - 10);
    }
    var animElems = document.querySelectorAll('#foupAnimX, #foupAnimY');
    animElems.forEach(function(a) { a.beginElement(); });
  }

  function animateReworkFoup(data) {
    var rf = document.getElementById('reworkFoupIcon');
    if (!rf) return;
    rf.setAttribute('opacity', '1');
    var rl = document.getElementById('reworkFoupLabel');
    if (rl) {
      rl.setAttribute('opacity', '1');
      rl.setAttribute('x', '636');
      rl.setAttribute('y', '152');
    }
    var duration = Math.max(800, (data.etaMs || 2000) / state.speed);
    // Straight rework path: CDSEM(636,155) → left to Litho(336,155)
    var animX = document.getElementById('reworkFoupAnimX');
    var animY = document.getElementById('reworkFoupAnimY');
    if (animX && animY) {
      animX.setAttribute('values', '636;336');
      animY.setAttribute('values', '155;155');
      animX.setAttribute('dur', duration + 'ms');
      animY.setAttribute('dur', duration + 'ms');
    }
    var anims = document.querySelectorAll('#reworkFoupAnimX, #reworkFoupAnimY');
    anims.forEach(function(a) { a.beginElement(); });
    // Show split label
    var sl = document.getElementById('splitMergeLabel');
    if (sl) {
      sl.textContent = (window.__i18n && window.__i18n.phase_split) ? '↗ ' + window.__i18n.phase_split : '↗ SPLIT';
      sl.setAttribute('opacity', '1');
      sl.setAttribute('fill', '#a855f7');
    }
    setTimeout(function() {
      if (rf) rf.setAttribute('opacity', '0.3');
      if (rl) rl.setAttribute('opacity', '0.3');
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
    var lotLabel = document.getElementById('foupLotLabel');
    if (lotLabel) {
      lotLabel.setAttribute('x', pos.x);
      lotLabel.setAttribute('y', pos.y - 10);
    }
  }

  function getAreaPosition(areaId) {
    var key = areaId.replace('-01', '');
    // FOUP icon center positions for all equipment areas (matching SVG layout)
    var map = {
      'STOCKER': {x: 55, y: 170},
      'CLEAN': {x: 150, y: 133},
      'DIFF': {x: 243, y: 133},
      'LITHO': {x: 336, y: 133},
      'ETCH': {x: 429, y: 133},
      'IMPL': {x: 522, y: 133},
      'DEP': {x: 452, y: 268},
      'CMP': {x: 359, y: 268},
      'MET': {x: 266, y: 268},
      'CDSEM': {x: 266, y: 268},
      'DRY': {x: 173, y: 268},
      'LOG': {x: 542, y: 268},
      'SCRAP': {x: 274, y: 335},
      'LITHO_REWORK': {x: 336, y: 133},
      'RETURN': {x: 55, y: 170}
    };
    return map[key] || {x: 55, y: 170};
  }

  // ---- Orchestrator Command (also shows on bus) ----
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
    // Show command on bus
    var busLabel = document.getElementById('busCommandLabel');
    if (busLabel) {
      busLabel.textContent = '▶ ' + data.commandType;
      busLabel.setAttribute('opacity', '0.9');
      busLabel.setAttribute('fill', '#58a6ff');
      setTimeout(function() { busLabel.setAttribute('opacity', '0.2'); }, 2000);
    }
    pulseEquipment({equipmentId: data.targetEquipmentId});
  }

  // ---- FOUP State + Lot Labels (需求2: Lot No.) ----
  function updateFoupState(data) {
    // Main FOUP lot label
    var label = document.getElementById('foupLotLabel');
    if (label && data.lotId) {
      label.textContent = data.lotId + ' [' + data.activeWaferCount + 'w]';
      label.setAttribute('opacity', '1');
    }
    // Color based on status
    var color = '#f59e0b';
    if (data.status === 'COMPLETED') color = '#3fb950';
    else if (data.status === 'SPLITTING') color = '#a855f7';
    else if (data.status === 'RETURNING') color = '#3fb950';
    else if (data.status === 'IN_TRANSIT') color = '#58a6ff';

    var foup = document.getElementById('foupIcon');
    if (foup) foup.setAttribute('fill', color);
    if (label) label.setAttribute('fill', color);

    // Rework lot label
    var rwkLabel = document.getElementById('reworkFoupLabel');
    if (rwkLabel && data.reworkLotId) {
      rwkLabel.textContent = data.reworkLotId + ' [' + data.reworkWaferCount + 'w]';
      rwkLabel.setAttribute('opacity', '1');
      rwkLabel.setAttribute('fill', '#a855f7');
      // Show rework FOUP icon near CDSEM during split
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
    if (label) {
      label.setAttribute('fill', '#3fb950');
    }
    var rf = document.getElementById('reworkFoupIcon');
    if (rf) rf.setAttribute('opacity', '0');
    var rl = document.getElementById('reworkFoupLabel');
    if (rl) rl.setAttribute('opacity', '0');
    var de = document.getElementById('status-decision');
    if (de) { de.textContent = 'Done'; de.setAttribute('fill', '#3fb950'); }
  }

  // ===================================================================
  // Global Status Indicator (需求3: 工作状态)
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
    // Update control bar status indicator
    var stEl = document.getElementById('globalStatusText');
    if (stEl) {
      var color = statusColorMap[data.status] || '#8b949e';
      stEl.innerHTML = '<span style="color:' + color + '">●</span> ' + data.detail;
    }
    // Update Decision Engine status text
    var deSt = document.getElementById('status-decision');
    if (deSt) {
      deSt.textContent = data.status + ': ' + data.detail;
      deSt.setAttribute('fill', statusColorMap[data.status] || '#8b949e');
    }
  }

  // ===================================================================
  // Scrap Bin (需求1: 报废去向)
  // ===================================================================
  function handleScrapEvent(data) {
    // Deduplicate: skip if this wafer was already scrapped
    if (state.scrappedWaferIds[data.waferId]) return;
    state.scrappedWaferIds[data.waferId] = true;

    state.scrapCount++;
    var waferNum = data.waferId.replace('WAFER-','');
    var curCount = state.scrapCount;

    // Update scrap bin count text
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

    // --- Animate flying wafer dot from CD-SEM (720,156) to Scrap Bin (870,156) ---
    var svg = document.getElementById('factorySvg');
    var NS = 'http://www.w3.org/2000/svg';
    var flyId = 'scrap-fly-' + waferNum;

    var flyG = document.createElementNS(NS, 'g');
    flyG.setAttribute('id', flyId);

    // Flying dot
    var flyDot = document.createElementNS(NS, 'circle');
    flyDot.setAttribute('cx', '720');
    flyDot.setAttribute('cy', '156');
    flyDot.setAttribute('r', '6');
    flyDot.setAttribute('fill', '#f85149');
    flyDot.setAttribute('stroke', '#ff6b6b');
    flyDot.setAttribute('stroke-width', '1.5');
    // animate cx along scrap path
    var ax = document.createElementNS(NS, 'animate');
    ax.setAttribute('attributeName', 'cx');
    ax.setAttribute('values', '720;870');
    ax.setAttribute('dur', '0.7s');
    ax.setAttribute('fill', 'freeze');
    flyDot.appendChild(ax);
    // slight arc for visual interest
    var ay = document.createElementNS(NS, 'animate');
    ay.setAttribute('attributeName', 'cy');
    ay.setAttribute('values', '156;146;156');
    ay.setAttribute('dur', '0.7s');
    ay.setAttribute('fill', 'freeze');
    flyDot.appendChild(ay);
    flyG.appendChild(flyDot);

    // Flying label
    var flyLbl = document.createElementNS(NS, 'text');
    flyLbl.setAttribute('x', '720');
    flyLbl.setAttribute('y', '152');
    flyLbl.setAttribute('text-anchor', 'middle');
    flyLbl.setAttribute('font-size', '6');
    flyLbl.setAttribute('fill', '#fff');
    flyLbl.setAttribute('font-family', 'monospace');
    flyLbl.setAttribute('font-weight', '700');
    flyLbl.textContent = 'W' + waferNum;
    var lx = document.createElementNS(NS, 'animate');
    lx.setAttribute('attributeName', 'x');
    lx.setAttribute('values', '720;870');
    lx.setAttribute('dur', '0.7s');
    lx.setAttribute('fill', 'freeze');
    flyLbl.appendChild(lx);
    var ly = document.createElementNS(NS, 'animate');
    ly.setAttribute('attributeName', 'y');
    ly.setAttribute('values', '152;142;152');
    ly.setAttribute('dur', '0.7s');
    ly.setAttribute('fill', 'freeze');
    flyLbl.appendChild(ly);
    flyG.appendChild(flyLbl);

    svg.appendChild(flyG);

    // After flight, remove flying dot and place permanent dot inside scrap bin
    setTimeout(function() {
      var fg = document.getElementById(flyId);
      if (fg) fg.remove();

      // Place permanent dot in scrap bin
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

      // Pulse scrap bin border
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

    // Pulse the classification wheel dot
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
  // Domain Event Sidebar (four-layer timeline)
  // ===================================================================
  var domainEventCount = 0;
  var deLayerCounts = [0, 0, 0, 0]; // L0=Chain, L1=Saga, L2=Aggregate, L3=Process
  var deLayerVisible = [true, true, false, false]; // L0/L1 open by default, L2/L3 collapsed
  var deLayerEvents = [[], [], [], []]; // events per layer (newest first)
  var deActiveFilter = -1; // -1 = show all, 0-3 = filter to layer

  var deLayerColors = ['#8b5cf6', '#3b82f6', '#10b981', '#f59e0b'];
  var deLayerNames = ['Chain / Orchestration', 'Saga / Transaction', 'Aggregate / Entity', 'Process / Execution'];

  window.toggleDomainSidebar = function() {
    var sidebar = document.getElementById('deSidebar');
    sidebar.classList.toggle('open');
  };

  // Incremental aggregate model, built from domain events
  var aggregateModel = { lots: {}, wafers: {} };

  function handleDomainEventRecorded(data) {
    domainEventCount++;
    var layer = (data.layer !== undefined) ? data.layer : 3;
    deLayerCounts[layer]++;
    deLayerEvents[layer].unshift(data);

    document.getElementById('deCount').textContent = domainEventCount;
    renderDomainEventSidebar();

    // Incrementally update aggregate model from domain events
    applyDomainEventToModel(data);
    renderAggregatePanelFromModel();
  }

  function applyDomainEventToModel(data) {
    var evtType = data.eventType;
    var evtData = data.data;
    try {
      if (evtType === 'LotCreated') {
        var m = evtData.match(/LotCreated\(([^,]*),/);
        var productId = m ? m[1] : '';
        aggregateModel.lots[data.aggregateId] = {
          lotId: data.aggregateId, productId: productId,
          status: 'Active', waferCount: 0, passCount: 0, scrapCount: 0,
          waferIds: []
        };
      } else if (evtType === 'WaferCreated') {
        var m2 = evtData.match(/WaferCreated\(([^)]*)\)/);
        var lotId = m2 ? m2[1] : '';
        aggregateModel.wafers[data.aggregateId] = {
          waferId: data.aggregateId, status: 'Active', lotId: lotId,
          classification: 'Created', reworkCount: 0
        };
        // Add wafer to lot
        var lot = aggregateModel.lots[lotId];
        if (lot && lot.waferIds.indexOf(data.aggregateId) < 0) {
          lot.waferIds.push(data.aggregateId);
          lot.waferCount = lot.waferIds.length;
        }
      } else if (evtType === 'WaferScrapped') {
        var w = aggregateModel.wafers[data.aggregateId];
        if (w) { w.status = 'Scrapped'; w.classification = 'SCRAP'; }
      } else if (evtType === 'WaferTransferCommitted') {
        var m3 = evtData.match(/WaferTransferCommitted\(([^,]+),([^)]+)\)/);
        if (m3) {
          var w2 = aggregateModel.wafers[data.aggregateId];
          var newLotId = m3[2].trim();
          if (w2) {
            // Remove from old lot
            var oldLot = aggregateModel.lots[w2.lotId];
            if (oldLot) {
              oldLot.waferIds = oldLot.waferIds.filter(function(id) { return id !== data.aggregateId; });
              oldLot.waferCount = oldLot.waferIds.length;
            }
            // Add to new lot
            w2.lotId = newLotId;
            var newLot = aggregateModel.lots[newLotId];
            if (newLot && newLot.waferIds.indexOf(data.aggregateId) < 0) {
              newLot.waferIds.push(data.aggregateId);
              newLot.waferCount = newLot.waferIds.length;
            }
          }
        }
      } else if (evtType === 'LotSealed') {
        var lot2 = aggregateModel.lots[data.aggregateId];
        if (lot2) lot2.status = 'Sealed';
      }
    } catch(e) { /* ignore parse errors for non-matching events */ }
  }

  function renderAggregatePanelFromModel() {
    var tbody = document.getElementById('aggTreeBody');
    var rows = '';
    var lotIds = Object.keys(aggregateModel.lots);
    if (lotIds.length === 0) {
      tbody.innerHTML = '<tr><td colspan="4" style="color:var(--fg-muted)">' + ((window.__i18n && window.__i18n.aggregate_placeholder) || 'Waiting for Lot creation...') + '</td></tr>';
      return;
    }
    lotIds.forEach(function(lotId) {
      var lot = aggregateModel.lots[lotId];
      rows += '<tr class="lot-row">' +
        '<td>Lot: ' + (lot.productId || lotId.substring(0, 8)) + '</td>' +
        '<td>' + (lot.status || 'Active') + '</td>' +
        '<td colspan="2">Wafers: ' + (lot.waferCount || 0) +
        ' | Pass: ' + (lot.passCount || 0) +
        ' | Scrap: ' + (lot.scrapCount || 0) + '</td>' +
        '</tr>';
      (lot.waferIds || []).forEach(function(wid) {
        var w = aggregateModel.wafers[wid];
        if (!w) return;
        var stCls = w.status === 'Scrapped' ? 'status-Scrapped' : 'status-Active';
        var clsCls = 'cls-' + (w.classification || 'Pending');
        rows += '<tr class="wafer-row">' +
          '<td>' + (w.waferId || wid).substring(0, 8) + '</td>' +
          '<td class="' + stCls + '">' + (w.status || 'Active') + '</td>' +
          '<td class="' + clsCls + '">' + (w.classification || 'Pending') + '</td>' +
          '<td>' + (w.reworkCount || 0) + '</td>' +
          '</tr>';
      });
    });
    tbody.innerHTML = rows;
  }

  function renderDomainEventSidebar() {
    var list = document.getElementById('deList');
    if (domainEventCount === 0) {
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
        'L' + l + ' <span class="de-layer-badge">' + deLayerCounts[l] + '</span></button>';
    }
    html += '</div>';

    // Render layers (respect filter)
    for (var l = 0; l < 4; l++) {
      if (deActiveFilter !== -1 && deActiveFilter !== l) continue;
      if (deLayerEvents[l].length === 0) continue;

      var collapsed = !deLayerVisible[l];
      var arrow = collapsed ? '▶' : '▼';
      html += '<div class="de-layer-group" style="border-left:3px solid ' + deLayerColors[l] + '">';
      html += '<div class="de-layer-header" onclick="deToggleLayer(' + l + ')">';
      html += '<span class="de-layer-arrow">' + arrow + '</span> ';
      html += '<span class="de-layer-name">Layer ' + l + ': ' + deLayerNames[l] + '</span> ';
      html += '<span class="de-layer-count">(' + deLayerCounts[l] + ')</span>';
      html += '</div>';

      if (!collapsed) {
        html += '<div class="de-layer-events" id="de-layer-' + l + '">';
        var events = deActiveFilter === -1 ? deLayerEvents[l] : deLayerEvents[l];
        var maxShow = deActiveFilter === -1 ? Math.min(events.length, 20) : events.length;
        for (var i = 0; i < maxShow; i++) {
          var e = events[i];
          var ts = new Date(e.timestamp).toTimeString().slice(0, 8);
          var indent = l === 1 && e.eventType.indexOf('Step') >= 0 ? '├ ' : '';
          html += '<div class="de-entry" style="border-left:2px solid ' + deLayerColors[l] + '">';
          html += '<span class="de-ts">' + ts + '</span> ';
          html += '<span class="de-type" style="color:' + deLayerColors[l] + '">' + indent + e.eventType + '</span>';
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
    // Auto-expand the filtered layer
    if (layer >= 0) deLayerVisible[layer] = true;
    renderDomainEventSidebar();
  };

  window.deToggleLayer = function(layer) {
    deLayerVisible[layer] = !deLayerVisible[layer];
    renderDomainEventSidebar();
  };

  // --- Saga / Decision UI helpers ---

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
    // Update classification wheel dot
    var wid = data.waferId;
    var waferIdx = parseInt(wid.split('-').pop()) - 1;
    if (isNaN(waferIdx)) return;
    var dot = document.getElementById('wd-' + waferIdx);
    if (!dot) return;
    var action = data.action || '';
    if (action.indexOf('HOLD') >= 0) {
      dot.setAttribute('fill', '#d29922'); // amber for hold
    } else if (action.indexOf('SCRAP') >= 0) {
      dot.setAttribute('fill', '#f85149'); // red for scrap
    } else if (action.indexOf('PASS') >= 0 || action.indexOf('Merge') >= 0) {
      dot.setAttribute('fill', '#3fb950'); // green for pass
    } else if (action.indexOf('Skip') >= 0) {
      dot.setAttribute('fill', '#8b949e'); // grey for skip
    }
  }

  // ===================================================================
  // Aggregate State Panel (需求5: 业务聚合状态)
  // ===================================================================
  function updateAggregatePanel(data) {
    // Sync bulk pipeline event into incremental model
    var srcLot = data.sourceLot;
    if (srcLot && srcLot.lotId) {
      if (!aggregateModel.lots[srcLot.lotId]) {
        aggregateModel.lots[srcLot.lotId] = {
          lotId: srcLot.lotId, productId: srcLot.lotId.substring(0, 8),
          status: srcLot.status, waferCount: srcLot.waferCount,
          passCount: srcLot.passCount, scrapCount: srcLot.scrapCount,
          waferIds: []
        };
      } else {
        var sl = aggregateModel.lots[srcLot.lotId];
        sl.status = srcLot.status;
        sl.waferCount = srcLot.waferCount;
        sl.passCount = srcLot.passCount;
        sl.scrapCount = srcLot.scrapCount;
      }
    }
    // Child/rework lots
    var childLots = data.reworkLot ? [data.reworkLot] : [];
    childLots.forEach(function(cl) {
      if (!aggregateModel.lots[cl.lotId]) {
        aggregateModel.lots[cl.lotId] = {
          lotId: cl.lotId, productId: cl.lotId.substring(0, 8),
          status: cl.status, waferCount: cl.waferCount,
          passCount: cl.passCount || 0, scrapCount: cl.scrapCount || 0,
          waferIds: []
        };
      }
    });
    // Wafers
    (data.wafers || []).forEach(function(w) {
      if (!aggregateModel.wafers[w.waferId]) {
        aggregateModel.wafers[w.waferId] = {
          waferId: w.waferId, status: w.status, lotId: w.lotId,
          classification: w.classification, reworkCount: w.reworkCount
        };
        var lot = aggregateModel.lots[w.lotId];
        if (lot && lot.waferIds.indexOf(w.waferId) < 0) {
          lot.waferIds.push(w.waferId);
          lot.waferCount = lot.waferIds.length;
        }
      } else {
        var ew = aggregateModel.wafers[w.waferId];
        ew.status = w.status;
        ew.classification = w.classification;
        ew.reworkCount = w.reworkCount;
        if (ew.lotId !== w.lotId) {
          var oldLot = aggregateModel.lots[ew.lotId];
          if (oldLot) {
            oldLot.waferIds = oldLot.waferIds.filter(function(id) { return id !== w.waferId; });
            oldLot.waferCount = oldLot.waferIds.length;
          }
          ew.lotId = w.lotId;
          var newLot = aggregateModel.lots[w.lotId];
          if (newLot && newLot.waferIds.indexOf(w.waferId) < 0) {
            newLot.waferIds.push(w.waferId);
            newLot.waferCount = newLot.waferIds.length;
          }
        }
      }
    });
    renderAggregatePanelFromModel();
  }

  window.toggleAggregatePanel = function() {
    var panel = document.getElementById('aggregatePanel');
    var btn = panel.querySelector('.agg-header button');
    if (state.aggregatePanelOpen) {
      panel.classList.add('collapsed');
      btn.textContent = (window.__i18n && window.__i18n.lang === 'zh') ? '▼ 展开' : '▼ Expand';
      state.aggregatePanelOpen = false;
    } else {
      panel.classList.remove('collapsed');
      btn.textContent = (window.__i18n && window.__i18n.lang === 'zh') ? '▲ 折叠' : '▲ Collapse';
      state.aggregatePanelOpen = true;
    }
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
  // Control Actions
  // ===================================================================
  window.startDemo = function() {
    var sel = document.getElementById('scenarioSelect');
    var scenarioId = sel.value;
    var scenarioType = sel.options[sel.selectedIndex].getAttribute('data-type') || '';
    loadScenarioLedger(scenarioId);
    // Reset state
    state.scrapCount = 0;
    state.scrappedWaferIds = {};
    state.waferResults = {};
    // Reset aggregate model
    aggregateModel = { lots: {}, wafers: {} };
    document.getElementById('aggTreeBody').innerHTML = '<tr><td colspan="4" style="color:var(--fg-muted)">' + ((window.__i18n && window.__i18n.aggregate_placeholder) || 'Waiting for Lot creation...') + '</td></tr>';

    // Reset domain event sidebar
    domainEventCount = 0;
    deLayerCounts = [0, 0, 0, 0];
    deLayerEvents = [[], [], [], []];
    deActiveFilter = -1;
    document.getElementById('deCount').textContent = '0';
    document.getElementById('deList').innerHTML = '<div class="de-entry"><span class="de-ts">--</span> <span class="de-data">' + ((window.__i18n && window.__i18n.de_placeholder) || 'Waiting for events...') + '</span></div>';
    document.getElementById('deSidebar').classList.remove('open');
    var sc = document.getElementById('scrapCount');
    if (sc) sc.textContent = (window.__i18n && window.__i18n.scrap_count) || '0 wafer';
    var dotsGroup = document.getElementById('scrapWaferDots');
    if (dotsGroup) dotsGroup.innerHTML = '';
    // Reset step progress indicator
    var spEl = document.getElementById('stepProgress');
    if (spEl) { spEl.style.display = 'none'; spEl.textContent = ''; }
    var startUrl = scenarioType === 'dynamic-routing'
      ? '/api/fab-demo/product/' + scenarioId + '/start'
      : '/api/fab-demo/start/' + scenarioId;
    fetch(startUrl, {method: 'POST'})
      .then(function(r) { return r.json(); })
      .then(function(data) {
        addTimelineEntry({type:'DemoStarted', data: 'Scenario: ' + data.message});
        for (var i = 0; i < 5; i++) {
          var dot = document.getElementById('wd-' + i);
          if (dot) dot.setAttribute('fill', '#30363d');
        }
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

  // Step progress indicator for dynamic routing (M3.5)
  function updateStepProgress(stepName) {
    var el = document.getElementById('stepProgress');
    if (!el) return;
    // Try to parse "Step X/Y: AREA (reentry=N)" or "Step X/Y: AREA — ..."
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

  document.addEventListener('DOMContentLoaded', function() {
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

    connectWebSocket();
  });
})();

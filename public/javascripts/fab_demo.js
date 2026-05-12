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
        addTimelineEntry({type:'DemoCompleted', data:event.data}, true);
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

    addTimelineEntry({
      type: 'FoupInTransit',
      data: 'FOUP moving: ' + data.fromArea + ' → ' + data.toArea + ' (' + (data.etaMs / 1000).toFixed(1) + 's)'
    });
  }

  function showFoupAtEquipment(data) {
    var pos = getAreaPosition(data.equipmentId);
    var foup = document.getElementById('foupIcon');
    if (foup) {
      foup.setAttribute('x', pos.x);
      foup.setAttribute('y', pos.y);
    }
  }

  function getAreaPosition(areaId) {
    var map = {
      'STOCKER': {x: 65, y: 85},
      'LITHO': {x: 490, y: 185},
      'CDSEM': {x: 690, y: 185}
    };
    return map[areaId] || {x: 65, y: 85};
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
    document.getElementById('sum-active').textContent = data.activeWafers || '-';
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
      case 'SagaOperationEvent': cls = 'rework'; msg = event.data.operation + ' ' + event.data.status; break;
      case 'DemoCompleted': msg = '✓ Demo completed'; break;
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
      })
      .catch(function() { /* demo page may be served before backend is ready */ });

    connectWebSocket();
  });
})();

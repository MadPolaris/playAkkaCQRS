/**
 * Saga Showcase — pure Saga transaction visualization.
 * Depends on: GraphEngine (graph_engine.js), window.__sagaI18n, window.__sagaScenarioInfo
 */
(function () {
  'use strict';

  var i18n = window.__sagaI18n || {};
  var scenarioInfo = window.__sagaScenarioInfo || {};

  function t(msg) {
    var args = Array.prototype.slice.call(arguments, 1);
    args.forEach(function (arg, i) {
      msg = msg.replace('{' + i + '}', arg);
    });
    return msg;
  }

  // ---- DOM refs ----
  var logContainer = document.getElementById('logContainer');
  var proceedBtn = document.getElementById('proceedBtn');
  var retryBtn = document.getElementById('retryBtn');
  var resumeBtn = document.getElementById('resumeBtn');
  var manualFixPanel = document.getElementById('manualFixPanel');
  var replayBtn = document.getElementById('replayBtn');
  var replayTxIdInput = document.getElementById('replayTxId');
  var currentTransactionId = null;

  // ---- Scenario info update ----
  function updateScenarioDetails() {
    var sid = document.getElementById('scenarioId').value;
    var info = scenarioInfo[sid];
    if (info) {
      document.getElementById('scenarioText').innerText = info.description;
      document.getElementById('scenarioExpectation').innerText = info.expectation;
    }
  }
  document.getElementById('scenarioId').onchange = updateScenarioDetails;
  updateScenarioDetails();

  // ---- Engine config (Saga-only: no CQRS nodes) ----
  var demoConfig = {
    settings: { animationSpeed: 1.0, eventTravelTime: 600, mode: 'css' },
    nodes: [
      {
        id: 'coordinator', domId: 'coordinator',
        initialState: { status: 'idle', label: 'Saga Coordinator' },
        reducer: function (state, ev) {
          if (ev.type === 'TransactionStarted') return { status: 'ongoing', label: state.label };
          if (ev.type === 'TransactionCompleted') return { status: 'success', label: state.label };
          if (ev.type === 'TransactionFailed' || ev.type === 'TransactionSuspended') return { status: 'failed', label: state.label };
          return state;
        },
        render: function (s) {
          return '<span class="label">' + s.label + '</span><span class="info" id="coord-status">' + s.status + '</span>';
        },
        onRendered: function (wrapper, s) {
          wrapper.className = 'node coordinator ' + s.status;
        }
      }
    ],
    eventRoutes: [
      { type: 'StepOngoing',   source: 'coordinator', target: function (e) { return 'node-' + e.data.stepId + '-' + (e.data.phase || '').toLowerCase().replace('phase', ''); }, color: '#00d4ff' },
      { type: 'StepCompleted', source: function (e) { return 'node-' + e.data.stepId + '-' + (e.data.phase || '').toLowerCase().replace('phase', ''); }, target: 'coordinator', color: '#4caf50' },
      { type: 'StepFailed',    source: function (e) { return 'node-' + e.data.stepId + '-' + (e.data.phase || '').toLowerCase().replace('phase', ''); }, target: 'coordinator', color: '#f44336' }
    ],
    eventDispatch: function (ev) {
      if (ev.type === 'TransactionStarted' || ev.type === 'TransactionCompleted' || ev.type === 'TransactionFailed' || ev.type === 'TransactionSuspended') {
        return ['coordinator'];
      }
      return Array.from(engine.nodes.keys());
    }
  };

  var engine = new GraphEngine('dagContainer', demoConfig);

  // ---- DAG rendering ----
  function renderDAG(steps) {
    var groups = {};
    steps.forEach(function (s) {
      if (!groups[s.stepGroup]) groups[s.stepGroup] = [];
      if (groups[s.stepGroup].indexOf(s.stepId) === -1) groups[s.stepGroup].push(s.stepId);
    });
    var sortedGroups = Object.keys(groups).sort(function (a, b) { return parseInt(a) - parseInt(b); });

    function buildCol(phase, title, bg, color, reverseGroups) {
      var html = '<div class="phase-header" style="background: ' + bg + '; color: ' + color + ';">' + title + '</div>';
      var groupsToRender = reverseGroups ? sortedGroups.slice().reverse() : sortedGroups;
      groupsToRender.forEach(function (g, idx) {
        html += '<div class="group-row" data-group="' + g + '" data-phase="' + phase.toLowerCase() + '">';
        groups[g].forEach(function (sid) {
          var nodeId = 'node-' + sid + '-' + phase.toLowerCase();
          html += '<div class="node pending" id="' + nodeId + '" data-step="' + sid + '" data-phase="' + phase.toLowerCase() + '"></div>';
        });
        html += '</div>';
        if (idx < groupsToRender.length - 1) html += '<div class="group-connector"></div>';
      });
      return html;
    }

    var phaseLabels = window.__sagaPhaseLabels || { PREPARE: 'PREPARE', COMMIT: 'COMMIT', COMPENSATE: 'COMPENSATE' };
    document.getElementById('col-prepare').innerHTML    = buildCol('PREPARE',    phaseLabels.PREPARE,    '#ffca28', '#000', false);
    document.getElementById('col-commit').innerHTML     = buildCol('COMMIT',     phaseLabels.COMMIT,     '#4caf50', '#fff', false);
    document.getElementById('col-compensate').innerHTML = buildCol('COMPENSATE', phaseLabels.COMPENSATE, '#f44336', '#fff', true);

    steps.forEach(function (s) {
      ['prepare', 'commit', 'compensate'].forEach(function (phase) {
        var id = 'node-' + s.stepId + '-' + phase;
        engine.registerNode({
          id: id, domId: id,
          initialState: { status: 'pending', label: s.stepId.replace('-', ' '), group: s.stepGroup },
          reducer: function (state, ev) {
            if (ev.type === 'StepOngoing' && ev.data.stepId === s.stepId && ev.data.phase === phase) return { status: 'ongoing', label: state.label, group: state.group };
            if (ev.type === 'StepCompleted' && ev.data.stepId === s.stepId && ev.data.phase === phase) return { status: 'success', label: state.label, group: state.group };
            if (ev.type === 'StepFailed' && ev.data.stepId === s.stepId && ev.data.phase === phase) {
              var err = ev.data.error || '';
              var isRetryable = err.indexOf('Max retries') === -1 && (err.indexOf('Retryable') !== -1 || err.indexOf('attempt') !== -1 || err.indexOf('timed out') !== -1 || err.indexOf('Retry #') !== -1);
              return { status: isRetryable ? 'retrying' : 'failed', label: state.label, group: state.group };
            }
            if (ev.type === 'TransactionSuspended' && (state.status === 'failed' || state.status === 'retrying')) return { status: 'suspended', label: state.label, group: state.group };
            if (ev.type === 'PhaseCompleted' && (ev.data.phase || '').toLowerCase().replace('phase', '') === phase && (state.status === 'ongoing' || state.status === 'retrying')) return { status: 'failed', label: state.label, group: state.group };
            return state;
          },
          render: function (st) {
            var h = '<span class="label">' + st.label + '</span><span class="info">Group ' + st.group + '</span>';
            h += '<button class="btn btn-success btn-small fix-btn" onclick="window.__sagaDoFix(\'' + s.stepId + '\', \'' + phase + '\', null, this)">' + (i18n.ui ? i18n.ui.fix_btn : 'Fix') + '</button>';
            return h;
          },
          onRendered: function (wrapper, st) {
            if (st.status === 'ongoing') wrapper.classList.remove('suspended');
            wrapper.className = 'node ' + st.status;
            if (st.status === 'suspended') wrapper.classList.add('suspended');
          }
        });
      });
    });
  }

  // ---- Logging ----
  function addLog(type, msg) {
    var entry = document.createElement('div');
    entry.className = 'log-entry type-' + type;
    var now = new Date();
    var timeStr = now.getHours().toString().padStart(2, '0') + ':' +
                  now.getMinutes().toString().padStart(2, '0') + ':' +
                  now.getSeconds().toString().padStart(2, '0');
    entry.innerHTML = '<span class="log-time">' + timeStr + '</span> <span class="log-type">' + type + '</span> ' + msg;
    logContainer.prepend(entry);
  }

  // ---- Event handler ----
  engine.onEventProcessed(function (event) {
    var data = event.data;
    var statusInfo = document.getElementById('statusInfo');

    if (event.type === 'TransactionStarted') {
      engine.clearDynamicNodes('node-');
      renderDAG(data.steps);
      addLog(event.type, t(i18n.log.TransactionStarted, data.traceId));
      currentTransactionId = data.transactionId;
      statusInfo.innerText = i18n.status.InProgress;
      statusInfo.style.color = '#ffca28';
      proceedBtn.style.display = 'none';
      retryBtn.style.display = 'none';
      resumeBtn.style.display = 'none';
      manualFixPanel.style.display = 'none';
    } else if (event.type === 'StepOngoing') {
      addLog(event.type, t(i18n.log.StepOngoing, data.stepId, data.phase));
    } else if (event.type === 'StepCompleted') {
      addLog(event.type, t(i18n.log.StepCompleted, data.stepId, data.phase, data.isManual ? i18n.log.is_manual : ''));
    } else if (event.type === 'StepFailed') {
      var err = data.error || '';
      var isRetryable = err.indexOf('Max retries') === -1 && (err.indexOf('Retryable') !== -1 || err.indexOf('attempt') !== -1 || err.indexOf('timed out') !== -1 || err.indexOf('Retry #') !== -1);
      if (!isRetryable) {
        addLog(event.type, t(i18n.log.StepFailed, data.stepId, data.phase, err));
        retryBtn.style.display = 'block';
      } else {
        addLog(event.type, t(i18n.log.StepRetrying, data.stepId, data.phase, err));
      }
    } else if (event.type === 'PhaseCompleted') {
      addLog(event.type, t(i18n.log.PhaseCompleted, data.phase));
    } else if (event.type === 'StepGroupStarted') {
      var phaseKey = (data.phase || '').toLowerCase().replace('phase', '');
      document.querySelectorAll('.group-row[data-group][data-phase="' + phaseKey + '"]').forEach(function (g) {
        g.style.opacity = '0.5';
        g.style.border = 'none';
      });
      var groupEl = document.querySelector('.phase-column[id="col-' + phaseKey + '"] .group-row[data-group="' + data.group + '"]');
      if (groupEl) {
        groupEl.style.opacity = '1';
        groupEl.style.border = '1px dashed #00d4ff';
        groupEl.style.borderRadius = '8px';
        groupEl.style.padding = '5px';
      }
      addLog(event.type, t(i18n.log.StepGroupStarted, data.group, data.phase));
    } else if (event.type === 'TransactionCompleted') {
      addLog(event.type, i18n.log.TransactionCompleted);
      statusInfo.innerText = i18n.status.Completed;
      statusInfo.style.color = '#4caf50';
      proceedBtn.style.display = 'none';
      retryBtn.style.display = 'none';
    } else if (event.type === 'TransactionFailed') {
      addLog(event.type, i18n.log.TransactionFailed);
      statusInfo.innerText = i18n.status.Failed;
      statusInfo.style.color = '#ffca28';
      proceedBtn.style.display = 'none';
      retryBtn.style.display = 'none';
    } else if (event.type === 'TransactionSuspended') {
      if (data.reason === 'MANUAL_PAUSE') {
        statusInfo.innerText = i18n.log.Paused;
        proceedBtn.style.display = 'block';
      } else {
        addLog(event.type, i18n.log.TransactionSuspended);
        statusInfo.innerText = i18n.status.Suspended;
        statusInfo.style.color = '#f44336';
        resumeBtn.style.display = 'block';
        manualFixPanel.style.display = 'block';
      }
    } else if (event.type === 'DomainEventPublished') {
      addLog(event.type, t(i18n.log.DomainEventPublished, data.eventType, data.detail));
    }
  });

  // ---- WebSocket ----
  var ws = new WebSocket((window.location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + window.location.host + '/ws/saga/events');
  ws.onmessage = function (msg) {
    engine.pushEvent(JSON.parse(msg.data));
  };

  // ---- Buttons ----
  document.getElementById('startBtn').onclick = function () {
    var btn = document.getElementById('startBtn');
    var originalText = btn.innerText;
    btn.disabled = true;
    btn.innerText = "⏳...";
    var scenarioId = document.getElementById('scenarioId').value;
    var singleStep = document.getElementById('singleStepMode').checked;

    if (scenarioId === 'custom') {
      ['Step-A', 'Step-B', 'Step-C'].forEach(function (sid) {
        fetch('/api/saga/inject-fault/' + sid + '/' + document.getElementById('config-' + sid).value, { method: 'POST' });
      });
      fetch('/api/saga/trigger-showcase/' + singleStep, { method: 'POST' })
        .then(function (r) { return r.json(); })
        .then(function (d) { currentTransactionId = d.transactionId; replayTxIdInput.value = d.transactionId; btn.disabled = false; btn.innerText = originalText; })
        .catch(function () { btn.disabled = false; btn.innerText = originalText; });
    } else {
      fetch('/api/saga/trigger-scenario/' + scenarioId + '/' + singleStep, { method: 'POST' })
        .then(function (r) { return r.json(); })
        .then(function (d) { currentTransactionId = d.transactionId; replayTxIdInput.value = d.transactionId; btn.disabled = false; btn.innerText = originalText; })
        .catch(function () { btn.disabled = false; btn.innerText = originalText; });
    }
  };

  proceedBtn.onclick = function () {
    if (!currentTransactionId) return;
    var orig = proceedBtn.innerText;
    proceedBtn.disabled = true; proceedBtn.innerText = "⏳...";
    fetch('/api/saga/proceed/' + currentTransactionId, { method: 'POST' }).finally(function () { proceedBtn.disabled = false; proceedBtn.innerText = orig; });
  };
  resumeBtn.onclick = function () {
    if (!currentTransactionId) return;
    var orig = resumeBtn.innerText;
    resumeBtn.disabled = true; resumeBtn.innerText = "⏳...";
    fetch('/api/saga/resume/' + currentTransactionId, { method: 'POST' }).finally(function () { resumeBtn.disabled = false; resumeBtn.innerText = orig; });
  };
  retryBtn.onclick = function () {
    if (!currentTransactionId) return;
    var orig = retryBtn.innerText;
    retryBtn.disabled = true; retryBtn.innerText = "⏳...";
    fetch('/api/saga/retry-phase/' + currentTransactionId, { method: 'POST' }).finally(function () { retryBtn.disabled = false; retryBtn.innerText = orig; });
  };

  // ---- Manual Fix ----
  window.__sagaDoFix = function (stepId, phase, node, btnElement) {
    if (!currentTransactionId) return;
    var orig = btnElement ? btnElement.innerText : '';
    if (btnElement) { btnElement.disabled = true; btnElement.innerText = "⏳..."; }
    fetch('/api/saga/fix-step/' + currentTransactionId + '/' + stepId + '/' + phase, { method: 'POST' })
      .then(function () {
        if (node) { node.classList.remove('failed', 'retrying'); node.classList.add('success'); }
        addLog(i18n.log.System, t(i18n.log.ManuallyFixed, stepId));
      })
      .finally(function () {
        if (btnElement) { btnElement.disabled = false; btnElement.innerText = orig; }
      });
  };

  document.getElementById('dagContainer').onclick = function (e) {
    if (e.target.classList.contains('fix-btn')) {
      var node = e.target.closest('.node');
      window.__sagaDoFix(node.getAttribute('data-step'), node.getAttribute('data-phase'), node, e.target);
    }
  };
  document.getElementById('manualFixBtn').onclick = function () {
    var sid = document.getElementById('manualStepId').value;
    var ph = document.getElementById('manualPhase').value;
    var btn = document.getElementById('manualFixBtn');
    if (sid) window.__sagaDoFix(sid, ph, null, btn);
  };

  // ---- Time Machine Replay ----
  replayBtn.onclick = function () {
    var txId = replayTxIdInput.value.trim();
    if (!txId) return alert(i18n.ui ? i18n.ui.enter_txid : 'Enter a transaction ID');

    var uuidMatch = txId.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
    if (uuidMatch) txId = uuidMatch[0];

    addLog(i18n.log.System, i18n.log.TimeMachineLoading);
    fetch('/api/saga/history/' + txId)
      .then(function (r) { return r.json(); })
      .then(function (dataList) {
        if (dataList.length === 0) return alert(i18n.log.TimeMachineNoHistory);
        document.querySelectorAll('.node').forEach(function (n) { n.className = 'node pending'; n.classList.remove('suspended'); });
        addLog(i18n.log.System, t(i18n.log.TimeMachineStarting, dataList.length));

        var cursor = 0;
        function playNext() {
          if (cursor >= dataList.length) {
            addLog(i18n.log.System, i18n.log.TimeMachineFinished);
            return;
          }
          var currentItem = dataList[cursor];
          engine.pushEvent(currentItem.event);
          cursor++;
          if (cursor < dataList.length) {
            var nextItem = dataList[cursor];
            var realGap = nextItem.timestamp - currentItem.timestamp;
            var pacedGap = Math.min(2000, Math.max(400, realGap));
            setTimeout(playNext, pacedGap);
          } else {
            setTimeout(playNext, 500);
          }
        }
        playNext();
      });
  };
})();

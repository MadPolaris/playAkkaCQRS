/**
 * Projection Showcase — pure CQRS/Projection flow visualization.
 * Depends on: GraphEngine (graph_engine.js)
 */
(function () {
  'use strict';

  // ---- Graph config (CQRS-only: no Saga coordinator) ----
  var graphConfig = {
    svgCanvas: 'svgCanvas',
    svgViz: 'archViz',
    settings: { mode: 'svg', animationSpeed: 1.0, eventTravelTime: 800 },
    nodes: [
      { id: 'coordinator',  dynamic: false },
      { id: 'agg-sender',   dynamic: false },
      { id: 'agg-receiver', dynamic: false },
      { id: 'journal',      dynamic: false },
      { id: 'proj-monthly', dynamic: false },
      { id: 'card-{userId}', dynamic: true,
        ui: function (userId) {
          var div = document.createElement('div');
          div.className = 'node record-node';
          div.dataset.userId = userId;
          div.innerHTML = '<div class="record-id">' + userId + '</div>' +
                          '<div class="record-data record-data-inner">...</div>';
          return div;
        }
      }
    ],
    edges: [
      { from: 'coordinator',  to: 'agg-sender' },
      { from: 'coordinator',  to: 'agg-receiver' },
      { from: 'coordinator',  to: 'journal' },
      { from: 'agg-sender',   to: 'journal' },
      { from: 'agg-receiver', to: 'journal' },
      { from: 'journal',      to: 'proj-monthly' },
      { from: 'proj-monthly', to: 'card-{userId}' }
    ],
    eventRouting: {
      // Saga coordinator events
      'TransactionStarted': [
        { from: 'coordinator', to: 'journal' }
      ],
      'StepOngoing': [
        { from: 'coordinator', to: 'agg-sender' },
        { from: 'coordinator', to: 'agg-receiver' },
        { from: 'coordinator', to: 'journal' }
      ],
      'StepCompleted': [
        { from: 'coordinator', to: 'journal' }
      ],
      'PhaseCompleted': [
        { from: 'coordinator', to: 'journal' }
      ],
      'StepGroupStarted': [
        { from: 'coordinator', to: 'journal' }
      ],
      'TransactionCompleted': [
        { from: 'coordinator', to: 'journal' }
      ],
      'TransactionFailed': [
        { from: 'coordinator', to: 'journal' }
      ],
      'TransactionSuspended': [
        { from: 'coordinator', to: 'journal' }
      ],
      // Domain events — aggregate → journal → projection → card
      'BalanceChanged': [
        { from: 'agg-sender', to: 'journal' },
        { from: 'journal',    to: 'proj-monthly' },
        { from: 'proj-monthly', to: 'card-{userId}', updateCard: true, updater: 'default' }
      ],
      'FundsReserved': [
        { from: 'agg-sender', to: 'journal' }
      ],
      'FundsDeducted': [
        { from: 'agg-sender', to: 'journal' },
        { from: 'journal',    to: 'proj-monthly' },
        { from: 'proj-monthly', to: 'card-{userId}', updateCard: true, updater: 'default' }
      ],
      'ReservationReleased': [
        { from: 'agg-sender', to: 'journal' },
        { from: 'journal',    to: 'proj-monthly' },
        { from: 'proj-monthly', to: 'card-{userId}', updateCard: true, updater: 'default' }
      ],
      'IncomingCreditsRecorded': [
        { from: 'agg-receiver', to: 'journal' }
      ],
      'IncomingCreditsCommited': [
        { from: 'agg-receiver', to: 'journal' },
        { from: 'journal',      to: 'proj-monthly' },
        { from: 'proj-monthly', to: 'card-{userId}', updateCard: true, updater: 'default' }
      ],
      'IncomingCreditsCanceled': [
        { from: 'agg-receiver', to: 'journal' }
      ]
    },
    cardStateUpdaters: {
      'default': async function (event) {
        var userId = event.userId;
        var maxRetries = 5;
        var retryDelay = 400;
        for (var attempt = 0; attempt < maxRetries; attempt++) {
          try {
            var res = await fetch('/api/projection/status');
            var data = await res.json();
            var summary = (data.summaries || []).find(function (s) { return s.userId === userId; });
            if (summary) {
              var node = document.querySelector('[data-user-id="' + userId + '"]');
              if (node) {
                node.querySelector('.record-data-inner').innerHTML =
                  '<span class="record-period">' + summary.year + '-' + summary.month + '</span>' +
                  '<div style="display:flex; gap:15px;">' +
                  '<span style="color:var(--color-read)">+' + summary.income + '</span>' +
                  '<span style="color:#ef4444">-' + summary.expense + '</span></div>';
                node.classList.add('highlight-read');
                setTimeout(function () { node.classList.remove('highlight-read'); }, 1000);
              }
              return;
            }
          } catch (e) { console.error('Card update attempt ' + (attempt + 1) + ' failed', e); }
          if (attempt < maxRetries - 1) {
            await new Promise(function (r) { setTimeout(r, retryDelay); });
          }
        }
        console.warn('Card update for ' + userId + ' failed after ' + maxRetries + ' attempts');
      }
    },
    aggregateStateUpdaters: {
      'BalanceChanged':            ['amount'],
      'FundsReserved':             ['amount', 'reservedAmount'],
      'FundsDeducted':             ['reservedAmount'],
      'ReservationReleased':       ['amount', 'reservedAmount'],
      'IncomingCreditsRecorded':   ['incomingAmount'],
      'IncomingCreditsCommited':   ['amount', 'incomingAmount'],
      'IncomingCreditsCanceled':   ['incomingAmount']
    }
  };

  // Ensure projection node exists in DOM
  if (!document.getElementById('proj-monthly')) {
    var container = document.getElementById('projectionList');
    if (container) {
      var div = document.createElement('div');
      div.id = 'proj-monthly';
      div.className = 'node proj-node tooltip';
      div.setAttribute('data-tip', (window.__i18n && window.__i18n.projectionTooltip) || 'Projection: Monthly Income & Expense Summary');
      div.innerHTML = '<h4>' + ((window.__i18n && window.__i18n.monthlyTitle) || 'Monthly Summary') + '</h4>' +
        '<div class="offset-val" style="font-family:monospace;font-weight:800;font-size:1.1rem;color:var(--color-projection);margin-top:5px;">0</div>';
      container.appendChild(div);
    }
  }

  // ---- Engine ----
  var engine = new GraphEngine('archViz', graphConfig);
  window.addEventListener('resize', function () { engine.redraw(); });

  // ---- Utilities ----
  window.genUuid = function (id) {
    document.getElementById(id).value = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      return (Math.random() * 16 | 0).toString(16);
    });
  };

  function addLog(msg, color) {
    color = color || '#94a3b8';
    var log = document.getElementById('logWindow');
    var entry = document.createElement('div');
    entry.style.color = color;
    entry.innerHTML = '<span style="color:#334155">[' + new Date().toLocaleTimeString() + ']</span> ' + msg;
    log.prepend(entry);
  }

  // ---- Deposit / Withdraw ----
  window.handleTx = async function (type) {
    var uid = document.getElementById('userId').value;
    var amt = parseInt(document.getElementById('amount').value);
    if (type === 'withdraw') amt = -Math.abs(amt);
    try {
      var res = await fetch('/' + type + '/' + uid + '/' + Math.abs(amt), { method: 'POST' });
      var data = await res.json();
      if (data.error) addLog((window.__i18n && window.__i18n.logRejected || 'Rejected: ') + data.error.message, '#ef4444');
      else updateAggregateNode('agg-sender', 'BalanceChanged');
    } catch (e) { addLog((window.__i18n && window.__i18n.logError || 'Error: ') + e.message, '#ef4444'); }
  };

  // ---- Transfer Saga ----
  window.handleTransfer = async function () {
    var from = document.getElementById('userId').value;
    var to = document.getElementById('targetId').value;
    var amt = document.getElementById('amount').value;
    try {
      var res = await fetch('/transfer/' + from + '/' + to + '/' + Math.abs(amt), { method: 'POST' });
      var data = await res.json();
      if (data.error) addLog((window.__i18n && window.__i18n.logTransferFailed || 'Transfer Failed: ') + data.error, '#ef4444');
      else addLog((window.__i18n && window.__i18n.logTransferTriggered || 'Transfer Saga Triggered'), '#22c55e');
    } catch (e) { addLog((window.__i18n && window.__i18n.logTransferFailed || 'Transfer Error: ') + e.message, '#ef4444'); }
  };

  // ---- Aggregate State Updater ----
  async function updateAggregateNode(aggId, eventType) {
    var userId = document.getElementById(aggId === 'agg-sender' ? 'userId' : 'targetId').value;
    try {
      var res = await fetch('/api/aggregate/balances/' + encodeURIComponent(userId));
      var data = await res.json();
      var bal = data[userId];
      if (!bal) return;

      var fields = {
        amount: 'sender-amount',
        reservedAmount: 'sender-reserved',
        incomingAmount: 'sender-incoming'
      };
      if (aggId === 'agg-receiver') {
        fields = {
          amount: 'receiver-amount',
          reservedAmount: 'receiver-reserved',
          incomingAmount: 'receiver-incoming'
        };
      }

      var elAmount = document.getElementById(fields.amount);
      var elReserved = document.getElementById(fields.reservedAmount);
      var elIncoming = document.getElementById(fields.incomingAmount);
      if (elAmount) elAmount.textContent = '¥' + (bal.balance != null ? Number(bal.balance).toFixed(2) : '0.00');
      if (elReserved) elReserved.textContent = '¥' + (bal.reservedAmount != null ? Number(bal.reservedAmount).toFixed(2) : '0.00');
      if (elIncoming) elIncoming.textContent = '¥' + (bal.incomingAmount != null ? Number(bal.incomingAmount).toFixed(2) : '0.00');

      var aggEl = document.getElementById(aggId);
      if (aggEl) {
        aggEl.classList.add('highlight-proj');
        setTimeout(function () { aggEl.classList.remove('highlight-proj'); }, 1000);
      }

      var highlightFields = graphConfig.aggregateStateUpdaters[eventType] || [];
      highlightFields.forEach(function (f) {
        var fieldEl = aggEl ? aggEl.querySelector('[data-field="' + f + '"]') : null;
        if (fieldEl) {
          fieldEl.classList.add('highlight-field');
          setTimeout(function () { fieldEl.classList.remove('highlight-field'); }, 1200);
        }
      });
    } catch (e) { console.error('Aggregate update failed', e); }
  }

  // ---- WebSocket ----
  var ws = new WebSocket((window.location.protocol === 'https:' ? 'wss:' : 'ws:') + '//' + window.location.host + '/ws/saga/events');
  ws.onopen = function () { addLog((window.__i18n && window.__i18n.logConnected || 'Event Stream Connected'), '#22c55e'); };
  ws.onerror = function () { addLog((window.__i18n && window.__i18n.logError || 'Event Stream Error'), '#ef4444'); };
  ws.onclose = function () { addLog((window.__i18n && window.__i18n.logDisconnected || 'Event Stream Disconnected'), '#94a3b8'); };

  ws.onmessage = function (msg) {
    var event;
    try {
      event = JSON.parse(msg.data);
    } catch (e) {
      console.warn('[ws] Failed to parse message', msg.data);
      return;
    }

    var eventType = event.type;
    var isDomainEvent = event.data && event.data.isDomainEvent;
    var isSagaEvent = event.data && !isDomainEvent && eventType && graphConfig.eventRouting[eventType];
    var senderId = document.getElementById('userId').value;
    var receiverId = document.getElementById('targetId').value;
    var amount = document.getElementById('amount').value;

    if (isDomainEvent) {
      console.log('[ws] Dispatching domain event:', eventType, 'detail:', event.data.detail);

      var userId;
      var aggId;
      if (eventType === 'IncomingCreditsRecorded' || eventType === 'IncomingCreditsCommited' || eventType === 'IncomingCreditsCanceled') {
        userId = receiverId;
        aggId = 'agg-receiver';
      } else {
        userId = senderId;
        aggId = 'agg-sender';
      }

      if (event.data.detail) {
        var match = event.data.detail.match(/^.*:\s*(-?\d+(?:\.\d+)?)/);
        if (match) amount = match[1];
      }

      // Update aggregate node immediately with fine-grained highlight
      updateAggregateNode(aggId, eventType);

      engine.dispatchEvent({ type: eventType, userId: userId, amount: amount, payload: event.data });
      console.log('[ws] Queued domain event:', eventType, 'userId:', userId, 'amount:', amount);

    } else if (isSagaEvent) {
      console.log('[ws] Dispatching saga event:', eventType);
      addLog(eventType, '#a855f7');
      engine.dispatchEvent({ type: eventType, userId: senderId, amount: amount, payload: event.data });
      console.log('[ws] Queued saga event:', eventType);

    } else {
      console.log('[ws] Skipping unknown event:', eventType);
    }
  };

  // ---- Periodic Refresh ----
  async function updateBalancesAndTable() {
    var senderId = document.getElementById('userId').value;
    var receiverId = document.getElementById('targetId').value;
    document.getElementById('sender-id-label').textContent = senderId;
    document.getElementById('receiver-id-label').textContent = receiverId;

    try {
      var balRes = await fetch('/api/aggregate/balances/' + senderId + ',' + receiverId);
      var b = await balRes.json();
      if (b[senderId]) {
        var sb = b[senderId];
        var el = document.getElementById('sender-amount'); if (el) el.textContent = '¥' + Number(sb.balance || 0).toFixed(2);
        el = document.getElementById('sender-reserved'); if (el) el.textContent = '¥' + Number(sb.reservedAmount || 0).toFixed(2);
        el = document.getElementById('sender-incoming'); if (el) el.textContent = '¥' + Number(sb.incomingAmount || 0).toFixed(2);
      }
      if (b[receiverId]) {
        var rb = b[receiverId];
        var el = document.getElementById('receiver-amount'); if (el) el.textContent = '¥' + Number(rb.balance || 0).toFixed(2);
        el = document.getElementById('receiver-reserved'); if (el) el.textContent = '¥' + Number(rb.reservedAmount || 0).toFixed(2);
        el = document.getElementById('receiver-incoming'); if (el) el.textContent = '¥' + Number(rb.incomingAmount || 0).toFixed(2);
      }
    } catch (e) {}

    try {
      var res = await fetch('/api/projection/status');
      var data = await res.json();
      var body = document.querySelector('#summaryTable tbody');
      body.innerHTML = '';
      (data.summaries || []).forEach(function (s) {
        body.innerHTML += '<tr>' +
          '<td style="font-family:monospace; font-size:0.7rem;">' + s.userId + '</td>' +
          '<td>' + s.year + '-' + s.month + '</td>' +
          '<td style="color:var(--color-read); font-weight:bold;">+' + s.income + '</td>' +
          '<td style="color:#ef4444; font-weight:bold;">-' + s.expense + '</td></tr>';
        engine.createDynamicSvgNode(s.userId);
      });
    } catch (e) {}
  }

  setTimeout(function () { engine.redraw(); }, 500);
  updateBalancesAndTable();
  setInterval(updateBalancesAndTable, 2000);
})();

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
      { from: 'agg-sender',   to: 'journal' },
      { from: 'agg-receiver', to: 'journal' },
      { from: 'journal',      to: 'proj-monthly' },
      { from: 'proj-monthly', to: 'card-{userId}' }
    ],
    eventRouting: {
      'BalanceChanged': [
        { from: 'agg-sender', to: 'journal' },
        { from: 'journal',    to: 'proj-monthly' },
        { from: 'proj-monthly', to: 'card-{userId}', updateCard: true }
      ],
      'FundsReserved': [
        { from: 'agg-sender', to: 'journal' }
      ],
      'FundsDeducted': [
        { from: 'agg-sender', to: 'journal' },
        { from: 'journal',    to: 'proj-monthly' },
        { from: 'proj-monthly', to: 'card-{userId}', updateCard: true }
      ],
      'ReservationReleased': [
        { from: 'agg-sender', to: 'journal' },
        { from: 'journal',    to: 'proj-monthly' },
        { from: 'proj-monthly', to: 'card-{userId}', updateCard: true }
      ],
      'IncomingCreditsRecorded': [
        { from: 'agg-receiver', to: 'journal' }
      ],
      'IncomingCreditsCommited': [
        { from: 'agg-receiver', to: 'journal' },
        { from: 'journal',      to: 'proj-monthly' },
        { from: 'proj-monthly', to: 'card-{userId}', updateCard: true }
      ],
      'IncomingCreditsCanceled': [
        { from: 'agg-receiver', to: 'journal' }
      ]
    },
    cardStateUpdaters: {
      'default': async function (event) {
        var userId = event.userId;
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
          }
        } catch (e) { console.error('Card update failed', e); }
      }
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
    engine.dispatchEvent({ type: 'BalanceChanged', userId: uid, amount: amt });
    try {
      var res = await fetch('/' + type + '/' + uid + '/' + Math.abs(amt), { method: 'POST' });
      var data = await res.json();
      if (data.error) addLog((window.__i18n && window.__i18n.logRejected || 'Rejected: ') + data.error.message, '#ef4444');
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
    // Only process domain events (skip Saga coordinator/executor events)
    if (!event.data || !event.data.isDomainEvent) {
      console.log('[ws] Skipping non-domain event:', event.type);
      return;
    }

    var eventType = event.type;
    console.log('[ws] Dispatching domain event:', eventType, 'detail:', event.data.detail);

    // Determine correct userId:
    //   Sender-side events (FundsReserved, FundsDeducted, ReservationReleased, BalanceChanged)
    //     → use the sender userId field
    //   Receiver-side events (IncomingCreditsRecorded, IncomingCreditsCommited, IncomingCreditsCanceled)
    //     → use the target/recipient userId field
    var userId;
    if (eventType === 'IncomingCreditsRecorded' || eventType === 'IncomingCreditsCommited' || eventType === 'IncomingCreditsCanceled') {
      userId = document.getElementById('targetId').value;
    } else {
      userId = document.getElementById('userId').value;
    }

    // Parse amount from event detail when available
    var amount = document.getElementById('amount').value;
    if (event.data.detail && event.data.detail.indexOf('Amount: ') === 0) {
      amount = event.data.detail.replace('Amount: ', '');
    }

    engine.dispatchEvent({ type: eventType, userId: userId, amount: amount, payload: event.data });
    console.log('[ws] Queued event for animation:', eventType, 'userId:', userId, 'amount:', amount);
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
      if (b[senderId]) document.getElementById('sender-balance').textContent = '¥' + b[senderId].balance;
      if (b[receiverId]) document.getElementById('receiver-balance').textContent = '¥' + b[receiverId].balance;
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
        var node = document.querySelector('[data-user-id="' + s.userId + '"]');
        if (node) {
          node.querySelector('.record-data-inner').innerHTML =
            '<span class="record-period">' + s.year + '-' + s.month + '</span>' +
            '<div style="display:flex; gap:15px;">' +
            '<span style="color:var(--color-read)">+' + s.income + '</span>' +
            '<span style="color:#ef4444">-' + s.expense + '</span></div>';
        }
      });
    } catch (e) {}
  }

  setTimeout(function () { engine.redraw(); }, 500);
  updateBalancesAndTable();
  setInterval(updateBalancesAndTable, 2000);
})();

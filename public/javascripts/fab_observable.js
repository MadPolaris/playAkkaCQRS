/**
 * Fab Demo — RxJS Observable Bridge
 *
 * Converts WebSocket messages into typed Observable streams.
 * Reducer functions are pure (no DOM). Renderers live in fab_demo.js.
 *
 * Exports on window:
 *   _rxStreams  — 18 typed event streams
 *   _rxReducers — domainEvent, aggregate reducers
 *   _rxSubscribe(observable, handler) — subscribe with lifecycle
 *   _rxDestroy() — tear down all subscriptions (call on demo restart)
 *   _rxInit()   — initialize the root WebSocket stream
 */
(function() {
  'use strict';

  var rx = window.rxjs;
  var op = rx.operators;

  // ===================================================================
  // Section 1: WebSocket Observable factory
  // ===================================================================
  function createWebSocketObservable(url) {
    return new rx.Observable(function(subscriber) {
      var ws = new WebSocket(url);
      ws.onmessage = function(msg) {
        try {
          subscriber.next(JSON.parse(msg.data));
        } catch(e) {
          console.warn('Failed to parse WebSocket message:', e);
        }
      };
      ws.onerror = function(e) {
        console.warn('WebSocket error (will retry):', e);
        subscriber.error(e || new Error('WebSocket error'));
      };
      ws.onclose = function(e) {
        console.log('WebSocket closed (code=' + e.code + '), reconnecting in 3s...');
        subscriber.error(new Error('WebSocket closed'));
      };
      return function teardown() {
        ws.close();
      };
    }).pipe(
      op.retry({ delay: 3000, count: Infinity })
    );
  }

  // ===================================================================
  // Section 2: Root event stream + pause filter
  // ===================================================================
  var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  var wsUrl = protocol + '//' + window.location.host + '/ws/fab-demo/events';

  var _rawEvent$ = null;
  var event$ = null;
  var destroy$ = new rx.Subject();

  window._rxInit = function() {
    // Always create a fresh destroy$ for the new lifecycle
    destroy$ = new rx.Subject();
    _rawEvent$ = createWebSocketObservable(wsUrl);

    event$ = _rawEvent$.pipe(
      op.filter(function(e) {
        return !window._demoPaused || e.type === 'DemoResumed';
      }),
      op.share()
    );
    defineStreams();
    return event$;
  };

  // ===================================================================
  // Section 3: Typed event streams
  // ===================================================================
  var _streams = {};

  function defineStreams() {
    var byType = function(type) {
      return event$.pipe(
        op.filter(function(e) { return e.type === type; }),
        op.map(function(e) { return e.data; })
      );
    };

    _streams.equipmentState$     = byType('EquipmentStateChanged');
    _streams.foupInTransit$      = byType('FoupInTransit');
    _streams.foupArrived$        = byType('FoupArrivedAtPort');
    _streams.processingStarted$  = byType('ProcessingStarted');
    _streams.processingCompleted$ = byType('ProcessingCompleted');
    _streams.measurement$        = byType('MeasurementResultEvent');
    _streams.lotUpdated$         = byType('LotUpdated');
    _streams.demoCompleted$      = byType('DemoCompleted');
    _streams.orchestratorCmd$    = byType('OrchestratorCommand');
    _streams.foupStateChanged$   = byType('FoupStateChanged');
    _streams.ledger$             = byType('LedgerStepAdvanced');
    _streams.globalStatus$       = byType('GlobalStatusChanged').pipe(
      op.distinctUntilChanged(function(a, b) { return a.status === b.status && a.detail === b.detail; })
    );
    _streams.aggregateState$     = byType('AggregateStateUpdated');
    _streams.scrapEvent$         = byType('ScrapEvent');
    _streams.domainEvent$        = byType('DomainEventRecorded');
    _streams.sagaEvent$          = byType('SagaOperationEvent');
    _streams.decisionMade$       = byType('DecisionMade');
    _streams.demoStarted$        = byType('DemoStarted');

    // Combined stream for timeline (all events, batched at 60fps)
    _streams.timeline$ = event$.pipe(
      op.bufferTime(100),
      op.filter(function(batch) { return batch.length > 0; })
    );

    window._rxStreams = _streams;
  }

  // ===================================================================
  // Section 4: Pure Reducer Functions
  // ===================================================================
  window._rxReducers = {
    domainEvent: function domainEventReducer(model, evt) {
      var layer = (evt.layer !== undefined) ? evt.layer : 3;
      var newCounts = model.layerCounts.slice();
      newCounts[layer] = (newCounts[layer] || 0) + 1;
      var newEvents = model.layerEvents.slice();
      var layerEvents = (newEvents[layer] || []).slice();
      layerEvents.unshift(evt);
      if (layerEvents.length > 200) layerEvents.length = 200;
      newEvents[layer] = layerEvents;
      return {
        count: model.count + 1,
        layerCounts: newCounts,
        layerEvents: newEvents
      };
    },

    aggregate: function aggregateReducer(model, data) {
      // data = AggregateStateUpdated: {sourceLot, reworkLot, wafers[]}
      // Accumulate authoritative snapshots from the pipeline's buildAggregateState
      var lots = Object.assign({}, model.lots);
      var wafers = Object.assign({}, model.wafers);

      // Source lot
      var srcLot = data.sourceLot;
      if (srcLot && srcLot.lotId) {
        if (!lots[srcLot.lotId]) {
          lots[srcLot.lotId] = {
            lotId: srcLot.lotId, productId: srcLot.lotId,
            status: srcLot.status, waferCount: srcLot.waferCount,
            passCount: srcLot.passCount, scrapCount: srcLot.scrapCount,
            currentArea: srcLot.currentArea || '',
            waferIds: []
          };
        } else {
          var sl = lots[srcLot.lotId];
          sl.status = srcLot.status;
          sl.waferCount = srcLot.waferCount;
          sl.passCount = srcLot.passCount;
          sl.scrapCount = srcLot.scrapCount;
          sl.currentArea = srcLot.currentArea || '';
        }
      }

      // Rework lot
      var reworkLot = data.reworkLot;
      if (reworkLot && reworkLot.lotId) {
        if (!lots[reworkLot.lotId]) {
          lots[reworkLot.lotId] = {
            lotId: reworkLot.lotId, productId: reworkLot.lotId,
            status: reworkLot.status, waferCount: reworkLot.waferCount,
            passCount: reworkLot.passCount || 0, scrapCount: reworkLot.scrapCount || 0,
            currentArea: reworkLot.currentArea || '',
            waferIds: []
          };
        } else {
          var rl = lots[reworkLot.lotId];
          rl.status = reworkLot.status;
          rl.waferCount = reworkLot.waferCount;
          rl.passCount = reworkLot.passCount;
          rl.scrapCount = reworkLot.scrapCount;
          rl.currentArea = reworkLot.currentArea || '';
        }
      }

      // Wafers — accumulate all seen wafer IDs
      (data.wafers || []).forEach(function(w) {
        if (!wafers[w.waferId]) {
          wafers[w.waferId] = {
            waferId: w.waferId, status: w.status, lotId: w.lotId,
            classification: w.classification, reworkCount: w.reworkCount
          };
          var lot = lots[w.lotId];
          if (lot && lot.waferIds.indexOf(w.waferId) < 0) {
            lot.waferIds.push(w.waferId);
            lot.waferCount = lot.waferIds.length;
          }
        } else {
          var ew = wafers[w.waferId];
          // Handle lot transfer
          if (ew.lotId !== w.lotId) {
            var oldLot = lots[ew.lotId];
            if (oldLot) {
              oldLot.waferIds = oldLot.waferIds.filter(function(id) { return id !== w.waferId; });
              oldLot.waferCount = oldLot.waferIds.length;
            }
            ew.lotId = w.lotId;
            var newLot = lots[w.lotId];
            if (newLot && newLot.waferIds.indexOf(w.waferId) < 0) {
              newLot.waferIds.push(w.waferId);
              newLot.waferCount = newLot.waferIds.length;
            }
          }
          ew.status = w.status;
          ew.classification = w.classification;
          ew.reworkCount = w.reworkCount;
        }
      });


      return { lots: lots, wafers: wafers };
    }
  };

  // ===================================================================
  // Section 5: Lifecycle — subscribe with auto-cleanup
  // ===================================================================
  window._rxSubscribe = function(observable, handler) {
    var sub = observable.pipe(
      op.takeUntil(destroy$),
      op.catchError(function(err) {
        console.warn('Stream error (continuing):', err);
        return rx.EMPTY;
      })
    ).subscribe({
      next: function(val) {
        try { handler(val); } catch(e) { console.warn('Handler error:', e); }
      },
      error: function(err) { console.warn('Stream fatal error:', err); }
    });
    return sub;
  };

  window._rxDestroy = function() {
    destroy$.next();
    destroy$.complete();
    _rawEvent$ = null;
    event$ = null;
  };

  // ===================================================================
  // Section 6: Auto-initialize on load
  // ===================================================================
  window._rxInit();
})();

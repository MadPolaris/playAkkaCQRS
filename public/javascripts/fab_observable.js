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
      // data = AggregateStateUpdated: {sourceLot, childLots[], wafers[]}
      // Accumulate authoritative snapshots from the pipeline's buildAggregateState
      var lots = Object.assign({}, model.lots);
      var wafers = Object.assign({}, model.wafers);

      function upsertLot(lotData) {
        if (!lotData || !lotData.lotId) return;
        if (!lots[lotData.lotId]) {
          lots[lotData.lotId] = {
            lotId: lotData.lotId, productId: lotData.lotId,
            status: lotData.status, waferCount: lotData.waferCount,
            passCount: lotData.passCount || 0, scrapCount: lotData.scrapCount || 0,
            currentArea: lotData.currentArea || '',
            waferIds: []
          };
        } else {
          var l = lots[lotData.lotId];
          l.status = lotData.status;
          l.waferCount = lotData.waferCount;
          l.passCount = lotData.passCount || 0;
          l.scrapCount = lotData.scrapCount || 0;
          l.currentArea = lotData.currentArea || '';
        }
      }

      // Source lot
      upsertLot(data.sourceLot);

      // Child lots (supports pilot, scrap, sample, hold, rework, etc.)
      (data.childLots || []).forEach(function(cl) {
        upsertLot(cl);
      });

      // Wafers — accumulate all seen wafer IDs
      (data.wafers || []).forEach(function(w) {
        if (!wafers[w.waferId]) {
          wafers[w.waferId] = {
            waferId: w.waferId, status: w.status, lotId: w.lotId,
            classification: w.classification, reworkCount: w.reworkCount
          };
        } else {
          var ew = wafers[w.waferId];
          // Handle lot transfer
          if (ew.lotId !== w.lotId) {
            ew.lotId = w.lotId;
          }
          ew.status = w.status;
          ew.classification = w.classification;
          ew.reworkCount = w.reworkCount;
        }
      });

      // Reconcile lot wafer membership from authoritative wafer state.
      // Wafers removed via Saga TCC (WaferRemovalCommitted) won't appear
      // in the incoming data.wafers for the source lot, so we rebuild
      // each lot's waferIds by scanning the full wafer registry.
      Object.keys(lots).forEach(function(lotId) {
        lots[lotId].waferIds = [];
      });
      Object.keys(wafers).forEach(function(wid) {
        var w = wafers[wid];
        var lot = lots[w.lotId];
        if (lot) {
          lot.waferIds.push(w.waferId);
        }
      });
      // Update waferCount and passCount/scrapCount from authoritative lot data
      Object.keys(lots).forEach(function(lotId) {
        lots[lotId].waferCount = lots[lotId].waferIds.length;
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

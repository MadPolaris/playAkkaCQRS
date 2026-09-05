/**
 * Fab M3.5 — RxJS Observable Bridge for Self-Healing Demo
 *
 * Adds M3.5-specific typed event streams on top of the existing
 * WebSocket infrastructure from fab_observable.js.
 *
 * Exports on window:
 *   _m35Streams — 6 M3.5 typed event streams
 *   _m35Destroy() — tear down M3.5 subscriptions
 */
(function() {
  'use strict';

  var rx = window.rxjs;
  var op = rx.operators;

  var _m35Streams = {};

  /**
   * Initialize M3.5 observable streams.
   * Must be called after window._rxInit() has created event$.
   * Uses the existing event$ stream (reuses WS connection).
   */
  window._m35Init = function() {
    var event$ = window._rxStreams ? null : null;
    // We rely on the WebSocket connection from fab_observable.js.
    // The existing event$ stream carries ALL events including M3.5 types,
    // because the controller's writeEventData now serializes all types.
    if (!window._rxInit) {
      console.warn('fab_observable.js not loaded — M3.5 streams will be empty');
      return;
    }

    // The event$ stream is created by fab_observable.js's _rxInit().
    // We access it via closure by filtering the WebSocket raw stream.
    // Since we can't access event$ directly, we create our own filter
    // over the raw stream that fab_observable.js sets up.
    // Actually — since fab_observable.js's _rxInit() sets up event$
    // internally, we need to hook into the WS stream ourselves.
    // The simplest approach: create our own WebSocket-like observable
    // that taps into the same mechanism.

    // Re-create the root WebSocket observable for M3.5-specific handling.
    // We use the same pattern as fab_observable.js but with the M3.5 WS endpoint.
    var protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    var wsUrl = protocol + '//' + window.location.host + '/ws/fab-demo/m35/events';

    var rawEvent$ = new rx.Observable(function(subscriber) {
      var ws = new WebSocket(wsUrl);
      ws.onmessage = function(msg) {
        try {
          subscriber.next(JSON.parse(msg.data));
        } catch(e) {
          console.warn('M3.5 WS parse error:', e);
        }
      };
      ws.onerror = function(e) {
        subscriber.error(e || new Error('M3.5 WebSocket error'));
      };
      ws.onclose = function() {
        subscriber.error(new Error('M3.5 WebSocket closed'));
      };
      return function teardown() {
        ws.close();
      };
    }).pipe(
      op.retry({ delay: 3000, count: Infinity }),
      op.share()
    );

    // Define typed streams
    _m35Streams.ocapAction$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'OcapActionTriggered'; }),
      op.map(function(e) { return e.data; })
    );

    _m35Streams.pipelineFailure$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'PipelineStageFailed'; }),
      op.map(function(e) { return e.data; })
    );

    _m35Streams.recoveryEvent$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'RecoveryEvent'; }),
      op.map(function(e) { return e.data; })
    );

    _m35Streams.faultInjected$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'FaultInjected'; }),
      op.map(function(e) { return e.data; })
    );

    _m35Streams.dynamicWeave$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'DynamicStageInjected'; }),
      op.map(function(e) { return e.data; })
    );

    // P4: projection snapshots lag live events — drop per-workOrder regressions so the
    // progress bar never animates backwards, and skip frame replays of old runs.
    var lastTimeline = {};
    _m35Streams.timelineSnapshot$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'PipelineTimelineSnapshot'; }),
      op.map(function(e) { return e.data; }),
      op.filter(function(d) {
        var key = d.workOrderId || 'default';
        var done = d.completedPhases || 0;
        if (lastTimeline[key] !== undefined && done <= lastTimeline[key] && d.currentPhaseIndex === undefined) return false;
        if (lastTimeline[key] !== undefined && done < lastTimeline[key]) return false;
        lastTimeline[key] = done;
        return true;
      })
    );

    // Also forward standard event types needed by the M3.5 UI
    _m35Streams.equipmentState$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'EquipmentStateChanged'; }),
      op.map(function(e) { return e.data; })
    );

    _m35Streams.foupInTransit$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'FoupInTransit'; }),
      op.map(function(e) { return e.data; })
    );

    _m35Streams.measurement$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'MeasurementResultEvent'; }),
      op.map(function(e) { return e.data; })
    );

    _m35Streams.demoStarted$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'DemoStarted'; }),
      op.map(function(e) { return e.data; })
    );

    _m35Streams.demoCompleted$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'RecoveryCompleted'; }),
      op.map(function(e) { return e.data; })
    );

    _m35Streams.scrapEvent$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'ScrapEvent'; }),
      op.map(function(e) { return e.data; })
    );

    // P4: skip consecutive identical aggregate states (projection replays) per lot set.
    var lastAggFingerprint = null;
    _m35Streams.aggregateState$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'AggregateStateUpdated'; }),
      op.map(function(e) { return e.data; }),
      op.filter(function(d) {
        var fp = JSON.stringify(d);
        if (fp === lastAggFingerprint) return false;
        lastAggFingerprint = fp;
        return true;
      })
    );

    _m35Streams.globalStatus$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'GlobalStatusChanged'; }),
      op.map(function(e) { return e.data; })
    );

    _m35Streams.ledger$ = rawEvent$.pipe(
      op.filter(function(e) { return e.type === 'LedgerStepAdvanced'; }),
      op.map(function(e) { return e.data; })
    );

    // Combined stream for timeline (all events, batched)
    _m35Streams.timeline$ = rawEvent$.pipe(
      op.bufferTime(100),
      op.filter(function(batch) { return batch.length > 0; })
    );

    // P7: Aggregated stats accumulator for completion overlay
    // Accumulates passed/reworked/scrapped/fault/recovery/ocap counts across the demo.
    var initialStats = {
      totalWafers: 0, passed: 0, reworked: 0, scrapped: 0,
      faults: 0, recoveries: 0, ocapTriggers: 0, startTime: Date.now()
    };

    _m35Streams.statsAccumulator$ = rawEvent$.pipe(
      op.scan(function(stats, event) {
        switch (event.type) {
          case 'DemoStarted':
            stats.totalWafers = event.data.lotSize || event.data.waferIds ? (event.data.waferIds || []).length : 0;
            stats.startTime = Date.now();
            break;
          case 'RecoveryCompleted':
            stats.passed = event.data.passedWafers || 0;
            stats.reworked = event.data.reworkedWafers || 0;
            stats.scrapped = event.data.scrappedWafers || 0;
            stats.totalWafers = event.data.totalWafers || stats.totalWafers;
            break;
          case 'OcapActionTriggered':
            stats.ocapTriggers++;
            break;
          case 'RecoveryEvent':
            if (event.data.recoveryType === 'CRASH_DETECTED') {
              stats.faults++;
            }
            if (event.data.recoveryType === 'RECOVERED' || event.data.recoveryType === 'COMPLETED') {
              stats.recoveries++;
            }
            break;
          case 'FaultInjected':
          case 'PipelineStageFailed':
            stats.faults++;
            break;
          case 'ScrapEvent':
            stats.scrapped++;
            break;
        }
        return stats;
      }, Object.assign({}, initialStats)),
      op.shareReplay(1)
    );

    window._m35Streams = _m35Streams;
    window._m35RawEvent$ = rawEvent$;

    console.log('M3.5 observable streams initialized');
  };

  /**
   * Teardown — called on demo restart to clean up old subscriptions
   * and create a fresh WebSocket connection.
   */
  window._m35Destroy = function() {
    // The WebSocket will be closed by the observable teardown
    // when all subscribers unsubscribe via _m35Subscribe's takeUntil.
    if (window._m35Destroy$) {
      window._m35Destroy$.next();
      window._m35Destroy$.complete();
    }
    window._m35Destroy$ = new rx.Subject();
    window._m35Streams = {};
  };

  /**
   * Subscribe to an M3.5 stream with auto-cleanup on _m35Destroy.
   * Matches the pattern of _rxSubscribe in fab_observable.js.
   */
  window._m35Subscribe = function(observable, handler) {
    if (!window._m35Destroy$) {
      window._m35Destroy$ = new rx.Subject();
    }
    var sub = observable.pipe(
      op.takeUntil(window._m35Destroy$),
      op.catchError(function(err) {
        console.warn('M3.5 stream error (continuing):', err);
        return rx.EMPTY;
      })
    ).subscribe({
      next: function(val) {
        try { handler(val); } catch(e) { console.warn('M3.5 handler error:', e); }
      },
      error: function(err) { console.warn('M3.5 stream fatal error:', err); }
    });
    return sub;
  };

  // Auto-initialize on load
  window._m35Init();
})();

/**
 * M3 三层架构 — Live Demo
 *
 * 三层垂直布局：决策层 (Decision) | 运行时执行层 (Runtime) | 物理执行层 (Physical)
 * 13 动画序列: 主流程序列 4 + MU 生命周期 3 (成批/拆批/合批) + 反馈回路 4 + 后台 2
 *
 * 节点色：decision(#60a5fa) runtime(#a855f7) physical(#2dd4bf)
 * 流程色：main(#58a6ff) batch(#f08c3e) split(#2dd4bf) merge(#3fb950)
 *          pass(#4ade80) fail(#f87171) borderline(#f59e0b) retry(#a855f7) log(#8b949e)
 */
(function () {
  'use strict';

  // =========================================================================
  // 1. DATA MODEL
  // =========================================================================

  var LAYERS = {
    decision: { name: '决策层 · Decision Layer', sub: 'M3 Heart — 大脑', color: '#60a5fa', y: 30, h: 420 },
    runtime:  { name: '运行时执行层 · Runtime Execution', sub: 'M2.5+ Core — 肌肉', color: '#a855f7', y: 478, h: 200 },
    physical: { name: '物理执行层 · Physical Execution', sub: 'Fab Level — 现实世界', color: '#2dd4bf', y: 706, h: 580 }
  };

  // Main interactive nodes — each has id, label, sub, layer, x, y, w, h
  // x/y are set by layoutNodes() at init, but we provide defaults for readability
  var NODES = [
    // --- Decision Layer ---
    { id: 'por', label: 'POR Repository', sub: '工艺路线库 · Main Route / Rework / Exp.Branches', layer: 'decision', color: '#60a5fa' },
    { id: 'assembler', label: 'Dynamic Flow Assembler', sub: 'Step Generator · 事实驱动动态织造 ChainDSL', layer: 'decision', color: '#f59e0b', isKey: true },
    { id: 'saga', label: 'Saga Coordinator', sub: '事实守恒的守护者 · 管理原子边界', layer: 'decision', color: '#2dd4bf',
      children: [
        { id: 'bmm-split', label: 'Split: 跨账户转账' },
        { id: 'bmm-merge', label: 'Merge: Barrier 屏障' },
        { id: 'bmm-suspend', label: 'SUSPENDED → ManualFix' }
      ]
    },
    { id: 'lot-context', label: 'Lot Context (Aggregate Root)', sub: 'MU 矩阵 · Lot→Wafer→Die 层级追踪', layer: 'decision', color: '#2dd4bf',
      muTree: true
    },
    { id: 'akka-pers', label: 'Akka Persistence', sub: 'Event Log · 不可篡改事件溯源底座', layer: 'decision', color: '#3fb950', fullWidth: true },

    // --- Runtime Layer ---
    { id: 'chain-exec', label: 'Chain Execution Actor', sub: 'EventSourcedBehavior · 解析 ChainDSL → 并行 Step', layer: 'runtime', color: '#a855f7' },
    { id: 'step-exec', label: 'Step Executor × N', sub: 'SubBatchPipeline (6 阶段) · 断路器 · 重试 · 编解码', layer: 'runtime', color: '#a855f7' },
    { id: 'rebatch', label: 'ReBatch Router', sub: 'Exception Handler · RetrySameArea / RouteToArea / Scrap', layer: 'runtime', color: '#f87171' },

    // --- Physical Layer ---
    { id: 'funnel', label: 'Processing Funnel', sub: 'Lot(25片) → BatchTool(100片拼炉) → 拆解 → Single-Wafer', layer: 'physical', color: '#f08c3e', funnel: true },
    { id: 'diffusion', label: '扩散炉 Diffusion', sub: '批处理工具 · 4Lot/100 片拼炉 · Recipe Match', layer: 'physical', color: '#2dd4bf' },
    { id: 'lithography', label: '光刻机 Lithography', sub: '单片处理工具 · 多重曝光 ♻ · Single-Wafer', layer: 'physical', color: '#f59e0b' },
    { id: 'metrology', label: '量测站 Metrology', sub: '扫描/检测 · CD/膜厚/缺陷 · 可疑复核 △', layer: 'physical', color: '#a855f7' },
    { id: 'classifier', label: 'Result Classifier', sub: '四路结果分类: PASS / BORDERLINE / FAIL / SCRAP', layer: 'physical', color: '#f08c3e',
      outcomes: [
        { id: 'out-pass', label: 'PASS', color: '#4ade80', desc: '继续主流程 → Assembler 计算下一段 DSL' },
        { id: 'out-borderline', label: 'BORDERLINE', color: '#f59e0b', desc: '挂起重测 → 回炉重新量测' },
        { id: 'out-fail', label: 'FAIL', color: '#f87171', desc: '触发重工循环 → Saga 拦截 → Assembler 织造 Rework DSL' },
        { id: 'out-scrap', label: 'SCRAP', color: '#f87171', desc: '强制报废 → Lot Context 报废入账', bold: true }
      ]
    },
    { id: 'eap', label: 'Equipment EAP', sub: 'SECS/GEM · HTTP/TCP · 断路器 · 物理信号 ⇄ 数字事件', layer: 'physical', color: '#a855f7' }
  ];

  // Edge definitions — 6 types: main, crossLayer, feedback, loopback, bidirectional, dashed
  var EDGES = [
    // Decision Layer internal
    { from: 'por', to: 'assembler', label: '索取片段', color: '#58a6ff', type: 'main' },
    { from: 'assembler', to: 'saga', label: '注入 ChainDSL', color: '#f59e0b', type: 'main' },
    { from: 'saga', to: 'lot-context', label: '指令', color: '#2dd4bf', type: 'bidirectional' },
    { from: 'lot-context', to: 'saga', label: '事件', color: '#2dd4bf', type: 'bidirectional' },
    { from: 'saga', to: 'akka-pers', label: '写入 EventLog', color: '#8b949e', type: 'dashed' },
    { from: 'lot-context', to: 'akka-pers', label: '写入 EventLog', color: '#8b949e', type: 'dashed' },

    // Decision → Runtime (cross-layer via left margin: exit bottom → left → down → enter left)
    { from: 'assembler', to: 'chain-exec', label: 'ChainDSL 注入', color: '#f59e0b', type: 'crossLayer', route: 'left-margin' },

    // Runtime internal
    { from: 'chain-exec', to: 'step-exec', label: '解析 DSL', color: '#a855f7', type: 'main' },
    { from: 'step-exec', to: 'rebatch', label: '异常路由', color: '#a855f7', type: 'main' },
    { from: 'rebatch', to: 'chain-exec', label: '重试/换区', color: '#a855f7', type: 'loopback', route: 'above' },

    // Runtime → Physical (straight, no nodes in the way through layer gap)
    { from: 'step-exec', to: 'funnel', label: '设备指令', color: '#a855f7', type: 'crossLayer', route: 'straight' },

    // Funnel → Equipment
    { from: 'funnel', to: 'diffusion', label: '拼炉', color: '#f08c3e', type: 'main' },
    { from: 'funnel', to: 'lithography', label: '单片', color: '#f08c3e', type: 'main' },
    { from: 'funnel', to: 'metrology', label: '量测', color: '#f08c3e', type: 'main' },

    // Equipment → Classifier
    { from: 'diffusion', to: 'classifier', label: '结果', color: '#8b949e', type: 'main' },
    { from: 'lithography', to: 'classifier', label: '结果', color: '#8b949e', type: 'main' },
    { from: 'metrology', to: 'classifier', label: '结果', color: '#8b949e', type: 'main' },

    // Classifier → EAP
    { from: 'classifier', to: 'eap', label: '物理信号', color: '#8b949e', type: 'dashed' },

    // === Cross-layer Feedback Loops (drawn outside layers) ===
    // PASS: classifier → assembler (left side, solid green)
    { from: 'classifier', to: 'assembler', label: 'PASS → 下一段 DSL', color: '#4ade80', type: 'feedback', path: 'left' },
    // FAIL: classifier → saga (right side, dashed red)
    { from: 'classifier', to: 'saga', label: 'FAIL → 拦截', color: '#f87171', type: 'feedback', path: 'right', dash: '8 4' },
    // Saga rework → assembler (dotted red, different rhythm)
    { from: 'saga', to: 'assembler', label: '重工 DSL', color: '#f87171', type: 'feedback', path: 'right', dash: '4 4' },
    // SCRAP: classifier → lot-context (right side, bold solid red)
    { from: 'classifier', to: 'lot-context', label: 'SCRAP → 报废入账', color: '#f87171', type: 'feedback', path: 'right', bold: true },
    // BORDERLINE: classifier → equipment loop back (left side, dotted amber)
    { from: 'classifier', to: 'diffusion', label: 'BORDERLINE → 回炉', color: '#f59e0b', type: 'feedback', path: 'left', dash: '3 3' },
    // Fact logging: EAP → Akka Persistence (left side, solid grey)
    { from: 'eap', to: 'akka-pers', label: '事实入账', color: '#8b949e', type: 'feedback', path: 'left' }
  ];

  // MU Tree data for LotContext drill-down
  var MU_TREE = {
    lot: { id: 'Lot-A', label: 'Lot A', count: 25, wafers: [
      { id: 'W01', label: 'Wafer 1', dies: 120 },
      { id: 'W02', label: 'Wafer 2', dies: 120 },
      { id: 'W03', label: 'Wafer 3', dies: 118 },
      { id: 'W04', label: 'Wafer 4', dies: 120 },
      { id: 'W05', label: 'Wafer 5', dies: 115, anomaly: true },
      { id: 'W06', label: 'Wafer 6', dies: 120 }
    ]},
    children: [
      { id: 'Lot-B', label: 'Lot B', count: 25, color: '#60a5fa' },
      { id: 'Lot-C', label: 'Lot C', count: 25, color: '#a855f7' },
      { id: 'Lot-D', label: 'Lot D', count: 25, color: '#f59e0b' }
    ]
  };

  // Batch scenario: 4 lots → BatchTool
  var BATCH_SCENARIO = {
    sources: [
      { id: 'Lot-A', x: 55, y: 810, color: '#f08c3e' },
      { id: 'Lot-B', x: 200, y: 810, color: '#60a5fa' },
      { id: 'Lot-C', x: 345, y: 810, color: '#a855f7' },
      { id: 'Lot-D', x: 490, y: 810, color: '#f59e0b' }
    ],
    target: { id: 'BatchTool', x: 310, y: 860 }
  };

  // Split scenario: parent Lot A → child Lot A'
  var SPLIT_SCENARIO = {
    parent: { id: 'Lot-A-parent', label: 'Lot A (24片)', x: 80, y: 200 },
    child: { id: 'Lot-A-child', label: "Lot A' (1片 Wafer 5)", x: 700, y: 200 },
    sagaMid: { x: 400, y: 200 }
  };

  // Merge scenario: two parallel timelines → Barrier
  var MERGE_SCENARIO = {
    timelines: [
      { id: 'TL-LotA', label: 'Lot A (24片) ▸ 等待', x: 80, y: 210 },
      { id: 'TL-LotAprime', label: "Lot A' (1片) ▸ 重工完成", x: 700, y: 210 }
    ],
    barrier: { id: 'Barrier', label: 'BMM Barrier 对齐 → 合批签署', x: 390, y: 250 }
  };

  // 13 Animation event flow sequences
  var EVENT_FLOWS = [
    // --- Main flow ---
    { id: 'flow-por', label: 'POR→Assembler 索取片段', color: '#58a6ff', edges: ['por:assembler'], particles: 2, delay: 0, dur: 800 },
    { id: 'flow-dsl', label: 'ChainDSL 注入 Saga', color: '#f59e0b', edges: ['assembler:saga'], particles: 2, delay: 700, dur: 900 },
    { id: 'flow-inject', label: '决策→运行时注入', color: '#f59e0b', edges: ['assembler:chain-exec'], particles: 3, delay: 1500, dur: 1000 },
    { id: 'flow-exec', label: '运行时执行链', color: '#a855f7', edges: ['chain-exec:step-exec', 'step-exec:funnel', 'funnel:diffusion'], particles: 3, delay: 2400, dur: 1100 },

    // --- MU Lifecycle: Batch / Split / Merge ---
    { id: 'flow-batch', label: '成批 Batch', color: '#f08c3e', edges: ['funnel:diffusion'], particles: 5, delay: 3500, dur: 1300, isBatch: true },
    { id: 'flow-split', label: '拆批 Split', color: '#2dd4bf', edges: ['lot-context:saga', 'saga:lot-context'], particles: 4, delay: 5000, dur: 1200, isSplit: true },
    { id: 'flow-merge', label: '合批 Merge', color: '#3fb950', edges: ['lot-context:saga'], particles: 4, delay: 6400, dur: 1200, isMerge: true },

    // --- Feedback loops ---
    { id: 'flow-pass', label: 'PASS 反馈回路', color: '#4ade80', edges: ['classifier:assembler'], particles: 3, delay: 7800, dur: 1500 },
    { id: 'flow-fail', label: 'FAIL 重工回路', color: '#f87171', edges: ['classifier:saga', 'saga:assembler'], particles: 2, delay: 8800, dur: 1400, dash: '8 4' },
    { id: 'flow-scrap', label: 'SCRAP 报废入账', color: '#f87171', edges: ['classifier:lot-context'], particles: 2, delay: 9800, dur: 1200, bold: true },
    { id: 'flow-borderline', label: 'BORDERLINE 回炉', color: '#f59e0b', edges: ['classifier:diffusion'], particles: 2, delay: 10600, dur: 1000, dash: '5 3' },

    // --- Background persistent ---
    { id: 'flow-retry', label: 'ReBatch 重试回路', color: '#a855f7', edges: ['rebatch:chain-exec'], particles: 1, delay: 3200, dur: 1800, loop: true },
    { id: 'flow-log', label: '事实入账', color: '#8b949e', edges: ['eap:akka-pers'], particles: 2, delay: 2200, dur: 1000 }
  ];

  var CYCLE_TOTAL = 12000;

  // Code snippets per layer
  var CODE_SNIPPETS = {
    decision: [
      '// M3 决策层核心 — Dynamic Flow Assembler',
      'class DynamicFlowAssembler(porRepo: RoutingRepository,',
      '                             saga: SagaCoordinator,',
      '                             lotCtx: LotContext) {',
      '',
      '  def assembleNextSegment(fact: DomainEvent): Either[DomainError, ChainDSL] =',
      '    for {',
      '      currentStep <- lotCtx.currentStep',
      '      template    <- porRepo.fetchSnippet(currentStep.routeId)',
      '      dsl         <- Right(weave(template, fact))',
      '      _           <- saga.initiate(dsl, lotCtx.boundary)',
      '    } yield dsl',
      '',
      '  // 核心: 事实驱动动态织造，非静态 List[Step]',
      '  private def weave(t: RouteSnippet, f: DomainEvent): ChainDSL = ???',
      '}'
    ],
    runtime: [
      '// M2.5+ 运行时执行层 — ChainExecutionActor',
      'object ChainExecutionActor {',
      '  def apply(dsl: ChainDSL): Behavior[Command] =',
      '    EventSourcedBehavior[Command, DomainEvent, ExecutionState](',
      '      PersistenceId.of("chain", dsl.id),',
      '      ExecutionState.empty,',
      '      (state, cmd) => cmd match {',
      '        case ExecuteChain(replyTo) =>',
      '          Effect.persist(ChainStarted(dsl.steps))',
      '            .thenRun(s => executeParallelSteps(s, replyTo))',
      '        case PhaseDone(stepId, result) =>',
      '          Effect.persist(StepCompleted(stepId, result))',
      '            .thenRun(s => advanceToNextPhase(s))',
      '        case _ => Effect.unhandled',
      '      },',
      '      (state, event) => state.applyEvent(event))',
      '}'
    ],
    physical: [
      '// M3 物理执行层 — Result Classifier 四路分类',
      'class ResultClassifier(thresholds: MeasurementThresholds) {',
      '',
      '  def classify(result: MeasurementResult): Classification =',
      '    result match {',
      '      case r if r.value >= thresholds.passMin =>',
      '        PASS(r)    // → Assembler 计算下一段 DSL',
      '      case r if r.value >= thresholds.borderlineMin =>',
      '        BORDERLINE(r) // → 挂起重测 → 回炉',
      '      case r if r.value >= thresholds.failMin =>',
      '        FAIL(r)    // → Saga 拦截 → Assembler 织造 Rework DSL',
      '      case _ =>',
      '        SCRAP(r)   // → Lot Context 报废入账',
      '    }',
      '}'
    ]
  };

  // =========================================================================
  // 2. LAYOUT ENGINE
  // =========================================================================

  var nodePositions = {}; // id -> { x, y, w, h, cx, cy }
  var edgePaths = {};     // "from:to" -> { pathD, labelX, labelY, labelAngle }

  function layoutNodes() {
    var svgW = 1200;
    var decisionX = 50, decisionW = 1100;
    var runtimeX = 50, runtimeW = 1100;
    var physicalX = 50, physicalW = 1100;

    // Decision Layer nodes (y: 70..420)
    var dY = LAYERS.decision.y + 55;
    // Row 0: POR + Assembler
    nodePositions['por']       = { x: 55, y: dY, w: 195, h: 62 };
    nodePositions['assembler'] = { x: 370, y: dY, w: 250, h: 62 };

    // Row 1: Saga + Lot Context (side by side)
    var r1y = dY + 100;
    nodePositions['saga']        = { x: 55, y: r1y, w: 370, h: 135 };
    nodePositions['lot-context'] = { x: 515, y: r1y, w: 370, h: 135 };

    // Row 2: Akka Persistence (full width)
    nodePositions['akka-pers'] = { x: 55, y: r1y + 155, w: 1040, h: 42 };

    // Runtime Layer nodes (y: 478..678)
    var rtY = LAYERS.runtime.y + 40;
    nodePositions['chain-exec'] = { x: 55, y: rtY, w: 240, h: 65 };
    nodePositions['step-exec']  = { x: 340, y: rtY, w: 280, h: 65 };
    nodePositions['rebatch']    = { x: 665, y: rtY, w: 260, h: 65 };

    // Physical Layer nodes (y: 706..1286)
    var phY = LAYERS.physical.y + 50;
    // Funnel (full width-ish)
    nodePositions['funnel'] = { x: 55, y: phY, w: 1040, h: 52 };

    // Equipment row
    var eqY = phY + 80;
    nodePositions['diffusion']   = { x: 55, y: eqY, w: 320, h: 62 };
    nodePositions['lithography'] = { x: 415, y: eqY, w: 320, h: 62 };
    nodePositions['metrology']   = { x: 775, y: eqY, w: 320, h: 62 };

    // Classifier
    nodePositions['classifier'] = { x: 140, y: eqY + 95, w: 850, h: 80 };

    // EAP
    nodePositions['eap'] = { x: 55, y: eqY + 200, w: 1040, h: 38 };

    // Compute centers
    Object.keys(nodePositions).forEach(function (id) {
      var p = nodePositions[id];
      p.cx = p.x + p.w / 2;
      p.cy = p.y + p.h / 2;
    });
  }

  // Compute edge paths with collision-aware routing
  function computeEdgePaths() {

    // Group feedback edges by (sourceNode, path) to stagger exit points
    var exitGroup = {}; // key: "fromId:path" → count
    var exitIdx = {};   // key: "fromId:path:edgeKey" → index within that group
    EDGES.forEach(function (e) {
      if (e.type !== 'feedback') return;
      var gk = e.from + ':' + e.path;
      exitGroup[gk] = (exitGroup[gk] || 0) + 1;
    });
    var exitCounters = {};
    EDGES.forEach(function (e) {
      if (e.type !== 'feedback') return;
      var gk = e.from + ':' + e.path;
      exitCounters[gk] = exitCounters[gk] || 0;
      exitIdx[e.from + ':' + e.to + ':' + e.path] = exitCounters[gk]++;
    });

    // Pre-compute which feedback edges use left vs right for column staggering
    var leftFeedbacks = [];
    var rightFeedbacks = [];
    EDGES.forEach(function (e) {
      if (e.type === 'feedback' && e.path === 'left') leftFeedbacks.push(e);
      if (e.type === 'feedback' && e.path === 'right') rightFeedbacks.push(e);
    });

    EDGES.forEach(function (e) {
      var key = e.from + ':' + e.to;
      var from = nodePositions[e.from];
      var to = nodePositions[e.to];
      if (!from || !to) return;

      var d, lx, ly, la;

      if (e.type === 'feedback') {
        // --- Stagger exit Y per source node to avoid overlapping horizontal segments ---
        var gk = e.from + ':' + e.path;
        var groupSize = exitGroup[gk] || 1;
        var myExitIdx = exitIdx[e.from + ':' + e.to + ':' + e.path] || 0;
        // Distribute exit Y across source node's height
        var exitY = from.y + from.h * (0.2 + 0.6 * myExitIdx / Math.max(groupSize - 1, 1));

        // Stagger routing columns
        var sideEdges = e.path === 'left' ? leftFeedbacks : rightFeedbacks;
        var idx = sideEdges.indexOf(e);

        if (e.path === 'left') {
          var leftX = 20 + idx * 14;
          d = 'M ' + from.x + ' ' + exitY +
              ' L ' + leftX + ' ' + exitY +
              ' C ' + (leftX - 8) + ' ' + (exitY + (to.cy - exitY) * 0.3) + ', ' +
              (leftX - 8) + ' ' + (to.cy - (to.cy - exitY) * 0.3) + ', ' +
              leftX + ' ' + to.cy +
              ' L ' + to.x + ' ' + to.cy;
          lx = leftX - 6; ly = (exitY + to.cy) / 2; la = -90;
        } else {
          var rightX = 1200 - 22 - idx * 38; // wider spacing: 1178, 1140, 1102
          d = 'M ' + (from.x + from.w) + ' ' + exitY +
              ' L ' + rightX + ' ' + exitY +
              ' C ' + (rightX + 8) + ' ' + (exitY + (to.cy - exitY) * 0.3) + ', ' +
              (rightX + 8) + ' ' + (to.cy - (to.cy - exitY) * 0.3) + ', ' +
              rightX + ' ' + to.cy +
              ' L ' + (to.x + to.w) + ' ' + to.cy;
          lx = rightX + 6; ly = (exitY + to.cy) / 2; la = -90;
        }

      } else if (e.type === 'loopback') {
        if (e.route === 'below') {
          // --- Below-loopback: exit bottom → down → left → up into target bottom ---
          var botGapY = Math.max(from.y + from.h, to.y + to.h) + 40;
          var fromBotX = from.cx;
          var toBotX = to.cx;
          d = 'M ' + fromBotX + ' ' + (from.y + from.h) +
              ' L ' + fromBotX + ' ' + botGapY +
              ' L ' + toBotX + ' ' + botGapY +
              ' L ' + toBotX + ' ' + (to.y + to.h);
          lx = (fromBotX + toBotX) / 2; ly = botGapY - 6; la = 0;
        } else if (e.route === 'above') {
          // --- Above-loopback: exit top → up → left → down into target top ---
          var topGapY = Math.min(from.y, to.y) - 40;
          var fromTopX = from.cx;
          var toTopX = to.cx;
          d = 'M ' + fromTopX + ' ' + from.y +
              ' L ' + fromTopX + ' ' + topGapY +
              ' L ' + toTopX + ' ' + topGapY +
              ' L ' + toTopX + ' ' + to.y;
          lx = (fromTopX + toTopX) / 2; ly = topGapY + 6; la = 0;
        } else {
          // --- Loopback: curved arc above the nodes ---
          var fromRight = from.x + from.w;
          var toLeft = to.x;
          var arcTop = Math.min(from.y, to.y) - 36;
          var cp1x = fromRight + 50;
          var cp1y = from.cy - 30;
          var cp2x = toLeft - 50;
          var cp2y = to.cy - 30;
          d = 'M ' + fromRight + ' ' + from.cy +
              ' C ' + cp1x + ' ' + cp1y + ', ' +
              cp2x + ' ' + cp2y + ', ' +
              toLeft + ' ' + to.cy;
          lx = (fromRight + toLeft) / 2; ly = arcTop - 8; la = 0;
        }

      } else if (e.type === 'bidirectional') {
        // --- Bidirectional: saga RIGHT ↔ lot-context LEFT, offset apart ---
        var isTop = e.from === 'saga'; // saga→lot-context is top line (指令)
        var offset = isTop ? 15 : -15;
        // Both edges connect saga RIGHT (425) ↔ lot-context LEFT (515)
        var sx = 425; // saga right edge
        var lx2 = 515; // lot-context left edge
        if (isTop) {
          d = 'M ' + sx + ' ' + (from.cy + offset) +
              ' L ' + lx2 + ' ' + (to.cy + offset);
        } else {
          d = 'M ' + lx2 + ' ' + (from.cy + offset) +
              ' L ' + sx + ' ' + (to.cy + offset);
        }
        lx = (sx + lx2) / 2; ly = from.cy + offset - 8; la = 0;

      } else if (e.type === 'crossLayer') {
        var fromBot = from.y + from.h;
        var toTop = to.y;

        if (e.route === 'right-margin') {
          // Exit BOTTOM of source → use gap below → go right to outside margin
          // → go down past all nodes → go left above target → enter target TOP
          var rmX = 1180; // outside all layer rects (which max out at x=1170)
          var gapY = fromBot + 12;
          var entryY = toTop - 15;
          d = 'M ' + from.cx + ' ' + fromBot +
              ' L ' + from.cx + ' ' + gapY +
              ' L ' + rmX + ' ' + gapY +
              ' L ' + rmX + ' ' + entryY +
              ' L ' + to.cx + ' ' + entryY +
              ' L ' + to.cx + ' ' + toTop;
          lx = rmX + 14; ly = (gapY + entryY) / 2; la = 0;

        } else if (e.route === 'left-margin') {
          // Exit BOTTOM of source → go down to gap → go LEFT outside all nodes
          // → go DOWN to target's mid-height → go RIGHT into target LEFT side
          var lmX = 25;
          var lGapY = fromBot + 12;
          var lTargetMidY = to.y + to.h / 2;
          d = 'M ' + from.cx + ' ' + fromBot +
              ' L ' + from.cx + ' ' + lGapY +
              ' L ' + lmX + ' ' + lGapY +
              ' L ' + lmX + ' ' + lTargetMidY +
              ' L ' + to.x + ' ' + lTargetMidY;
          lx = lmX + 12; ly = (lGapY + lTargetMidY) / 2; la = -90;

        } else {
          if (Math.abs(from.cx - to.cx) < 30) {
            d = 'M ' + from.cx + ' ' + fromBot + ' L ' + to.cx + ' ' + toTop;
          } else {
            var midY = (fromBot + toTop) / 2;
            d = 'M ' + from.cx + ' ' + fromBot +
                ' L ' + from.cx + ' ' + midY +
                ' L ' + to.cx + ' ' + midY +
                ' L ' + to.cx + ' ' + toTop;
          }
          lx = (from.cx + to.cx) / 2 + 12; ly = (fromBot + toTop) / 2; la = 0;
        }

      } else if (e.type === 'dashed') {
        // --- Dashed: straight down or diagonal with offset ---
        if (Math.abs(from.cx - to.cx) < 15) {
          d = 'M ' + from.cx + ' ' + (from.y + from.h) +
              ' L ' + to.cx + ' ' + to.y;
          lx = from.cx + 12; ly = (from.y + from.h + to.y) / 2; la = 0;
        } else {
          // Diagonal-ish: go down from source center to middle, then to target
          var midY = (from.y + from.h + to.y) / 2;
          d = 'M ' + from.cx + ' ' + (from.y + from.h) +
              ' L ' + from.cx + ' ' + midY +
              ' L ' + to.cx + ' ' + midY +
              ' L ' + to.cx + ' ' + to.y;
          lx = (from.cx + to.cx) / 2; ly = midY - 6; la = 0;
        }

      } else {
        // --- Standard main flow edges ---
        if (Math.abs(from.cy - to.cy) < 25) {
          // Horizontal — exit right, enter left
          var hy = from.cy;
          d = 'M ' + (from.x + from.w) + ' ' + hy +
              ' L ' + to.x + ' ' + hy;
          lx = (from.x + from.w + to.x) / 2; ly = hy - 8; la = 0;
        } else {
          // Vertical — exit bottom, enter top, with slight horizontal offset if needed
          var dx = to.cx - from.cx;
          if (Math.abs(dx) < 30) {
            // Straight down
            d = 'M ' + from.cx + ' ' + (from.y + from.h) +
                ' L ' + to.cx + ' ' + to.y;
            lx = from.cx + 10; ly = (from.y + from.h + to.y) / 2; la = 0;
          } else {
            // Z-path: down, horizontal, down
            var zmY = (from.y + from.h + to.y) / 2;
            d = 'M ' + from.cx + ' ' + (from.y + from.h) +
                ' L ' + from.cx + ' ' + zmY +
                ' L ' + to.cx + ' ' + zmY +
                ' L ' + to.cx + ' ' + to.y;
            lx = (from.cx + to.cx) / 2; ly = zmY - 6; la = 0;
          }
        }
      }

      edgePaths[key] = { d: d, lx: lx, ly: ly, la: la, type: e.type, color: e.color,
                         dash: e.dash, bold: e.bold, label: e.label };
    });
  }


  // =========================================================================
  // 3. RENDER ENGINE
  // =========================================================================

  var svg, defs, mainGroup;
  var animParticles = [];
  var feedbackVisible = true;
  var activeLayers = { decision: true, runtime: true, physical: true };
  var muTreeExpanded = false;

  function $(id) { return document.getElementById(id); }

  function initRender() {
    svg = $('m3Canvas');
    // Clear
    while (svg.firstChild) svg.removeChild(svg.firstChild);

    defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
    svg.appendChild(defs);
    mainGroup = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    mainGroup.setAttribute('font-family', '-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",system-ui,sans-serif');
    svg.appendChild(mainGroup);
  }

  function addDefs() {
    var defsHTML = '';
    // Arrow markers
    var colors = { Blue: '#58a6ff', Muted: '#8b949e', Green: '#4ade80', Amber: '#f59e0b',
                   Red: '#f87171', Purple: '#a855f7', Orange: '#f08c3e', Teal: '#2dd4bf' };
    Object.keys(colors).forEach(function (name) {
      var c = colors[name];
      defsHTML += '<marker id="ah' + name + '" markerWidth="8" markerHeight="6" refX="10" refY="4" orient="auto">' +
                  '<path d="M0,0 L10,4 L0,8 L2,4 Z" fill="' + c + '"/></marker>';
    });

    // Glow filter for particles
    defsHTML += '<filter id="particleGlow" x="-50%" y="-50%" width="200%" height="200%">' +
                '<feGaussianBlur stdDeviation="2.5" result="blur"/>' +
                '<feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>' +
                '</filter>';

    // Node shadow filter
    defsHTML += '<filter id="nodeShadow" x="-8%" y="-15%" width="120%" height="140%">' +
                '<feDropShadow dx="0" dy="2" stdDeviation="3" flood-color="#000" flood-opacity="0.35"/></filter>';

    // Gradients
    var gradColors = { Teal: '45,212,191', Blue: '96,165,250', Amber: '251,191,36',
                       Purple: '192,132,252', Red: '248,113,113', Green: '74,222,128', Orange: '240,140,62' };
    Object.keys(gradColors).forEach(function (name) {
      var c = gradColors[name];
      defsHTML += '<linearGradient id="g' + name + '" x1="0" y1="0" x2="0" y2="1">' +
                  '<stop offset="0%" stop-color="rgba(' + c + ',0.18)"/>' +
                  '<stop offset="100%" stop-color="rgba(' + c + ',0.05)"/></linearGradient>';
    });

    defs.innerHTML = defsHTML;
  }

  function renderLayerBackgrounds() {
    var html = '';
    Object.keys(LAYERS).forEach(function (key) {
      var l = LAYERS[key];
      var opacity = activeLayers[key] ? 1 : 0.2;
      var c = l.color;
      var r = Math.round(parseInt(c.slice(1,3), 16));
      var g = Math.round(parseInt(c.slice(3,5), 16));
      var b = Math.round(parseInt(c.slice(5,7), 16));

      html += '<g opacity="' + opacity + '" class="layer-bg" data-layer="' + key + '">';
      html += '<rect x="30" y="' + l.y + '" width="1140" height="' + l.h + '" rx="12" ' +
              'fill="rgba(' + r + ',' + g + ',' + b + ',0.025)" stroke="rgba(' + r + ',' + g + ',' + b + ',0.2)" stroke-width="1.5"/>';
      html += '<rect x="30" y="' + l.y + '" width="5" height="' + l.h + '" rx="2" fill="' + c + '" fill-opacity="0.5"/>';
      // Layer header (clickable)
      html += '<text x="50" y="' + (l.y + 22) + '" fill="' + c + '" font-size="13" font-weight="700" ' +
              'letter-spacing="1.3" style="cursor:pointer" class="layer-header" data-layer="' + key + '">' +
              l.name + '</text>';
      html += '<text x="50" y="' + (l.y + 38) + '" fill="#8b949e" font-size="8" font-weight="500">' + l.sub + '</text>';
      html += '</g>';
    });
    mainGroup.innerHTML += html;
  }

  function renderNode(n, opacity) {
    opacity = opacity || 1;
    if (!activeLayers[n.layer] && opacity === 1) opacity = 0.15;
    var pos = nodePositions[n.id];
    if (!pos) return '';

    var gradColor = n.color === '#60a5fa' ? 'Blue' : n.color === '#f59e0b' ? 'Amber' :
                    n.color === '#2dd4bf' ? 'Teal' : n.color === '#a855f7' ? 'Purple' :
                    n.color === '#f87171' ? 'Red' : n.color === '#3fb950' ? 'Green' :
                    n.color === '#f08c3e' ? 'Orange' : 'Blue';
    var accentColor = n.color;
    var isKey = n.isKey;

    var html = '<g opacity="' + opacity + '" class="node" data-node="' + n.id + '" style="cursor:pointer">';
    // Shadow rect
    html += '<rect x="' + pos.x + '" y="' + pos.y + '" width="' + pos.w + '" height="' + pos.h +
            '" rx="' + (n.fullWidth ? 8 : (n.muTree ? 10 : 8)) + '" ' +
            'fill="url(#g' + gradColor + ')" stroke="' + accentColor + '" ' +
            'stroke-opacity="' + (isKey ? '0.55' : '0.35') + '" stroke-width="' + (isKey ? '2' : '1.5') + '" ' +
            'filter="url(#nodeShadow)"/>';
    // Left accent bar
    html += '<rect x="' + pos.x + '" y="' + pos.y + '" width="' + (isKey ? '5' : '4') + '" height="' + pos.h +
            '" rx="2" fill="' + accentColor + '"/>';

    // Title text
    html += '<text x="' + (pos.cx) + '" y="' + (pos.y + 22) + '" fill="#e6edf3" font-size="' +
            (isKey ? '11' : '10.5') + '" font-weight="700" text-anchor="middle">' + n.label + '</text>';
    // Subtitle
    var subLines = n.sub.split(' · ');
    subLines.forEach(function (line, i) {
      html += '<text x="' + (pos.cx) + '" y="' + (pos.y + 36 + i * 14) + '" fill="#8b949e" font-size="7.5" ' +
              'font-weight="500" text-anchor="middle">' + line + '</text>';
    });

    html += '</g>';
    return html;
  }

  function renderAllNodes() {
    var html = '';
    NODES.forEach(function (n) {
      html += renderNode(n);
    });

    // Render classifier outcomes
    var clsPos = nodePositions['classifier'];
    if (clsPos && activeLayers['physical']) {
      var outW = 150, outH = 24, outGap = 20;
      var totalW = 4 * outW + 3 * outGap;
      var startX = clsPos.cx - totalW / 2;
      var outY = clsPos.y + clsPos.h - outH - 10;
      var outcomes = NODES.find(function (n) { return n.id === 'classifier'; }).outcomes;
      outcomes.forEach(function (o, i) {
        var ox = startX + i * (outW + outGap);
        var opacity = activeLayers['physical'] ? 1 : 0.15;
        html += '<g opacity="' + opacity + '" class="node outcome-node" data-node="out-' + o.id + '" style="cursor:pointer">';
        html += '<rect x="' + ox + '" y="' + outY + '" width="' + outW + '" height="' + outH +
                '" rx="5" fill="rgba(' + (o.color === '#4ade80' ? '74,222,128' : o.color === '#f59e0b' ? '245,158,11' : '248,113,113') + ',0.1)" ' +
                'stroke="' + o.color + '" stroke-opacity="' + (o.bold ? '0.7' : '0.4') + '" stroke-width="' + (o.bold ? '1.5' : '1') + '"/>';
        html += '<text x="' + (ox + outW / 2) + '" y="' + (outY + outH / 2 + 5) + '" fill="' + o.color +
                '" font-size="9" font-weight="700" text-anchor="middle">' + o.label + '</text>';
        html += '</g>';
      });
    }

    mainGroup.innerHTML += html;
  }

  function renderEdges() {
    var html = '';
    Object.keys(edgePaths).forEach(function (key) {
      var ep = edgePaths[key];
      var parts = key.split(':');
      var fromId = parts[0], toId = parts[1];
      var fromNode = NODES.find(function (n) { return n.id === fromId; });
      var toNode = NODES.find(function (n) { return n.id === toId; });
      if (!fromNode || !toNode) return;

      var opacity = 1;
      if (!activeLayers[fromNode.layer] && !activeLayers[toNode.layer]) opacity = 0.08;
      else if (!activeLayers[fromNode.layer] || !activeLayers[toNode.layer]) opacity = 0.2;
      if (ep.type === 'feedback' && !feedbackVisible) opacity = 0.05;

      var markerColor = ep.color === '#58a6ff' ? 'Blue' : ep.color === '#8b949e' ? 'Muted' :
                        ep.color === '#4ade80' ? 'Green' : ep.color === '#f59e0b' ? 'Amber' :
                        ep.color === '#f87171' ? 'Red' : ep.color === '#a855f7' ? 'Purple' :
                        ep.color === '#f08c3e' ? 'Orange' : ep.color === '#2dd4bf' ? 'Teal' : 'Blue';

      var strokeW = ep.bold ? '3' : (ep.type === 'crossLayer' ? '2.2' : (ep.type === 'feedback' ? '2.2' : '1.8'));
      var dashArray = ep.dash || (ep.type === 'crossLayer' ? '6 3' : (ep.type === 'dashed' ? '5 4' : 'none'));
      if (dashArray === 'none') dashArray = undefined;

      html += '<g opacity="' + opacity + '" class="edge" data-edge="' + key + '">';
      html += '<path d="' + ep.d + '" fill="none" stroke="' + ep.color + '" stroke-width="' + strokeW +
              '" stroke-linecap="round" stroke-linejoin="round"' +
              (dashArray ? ' stroke-dasharray="' + dashArray + '"' : '') +
              ' marker-end="url(#ah' + markerColor + ')"/>';
      // Edge label
      if (ep.label) {
        html += '<text x="' + ep.lx + '" y="' + ep.ly + '" fill="' + ep.color +
                '" font-size="7.5" font-weight="600" text-anchor="middle"' +
                (ep.la ? ' transform="rotate(' + ep.la + ', ' + ep.lx + ', ' + ep.ly + ')"' : '') +
                '>' + ep.label + '</text>';
      }
      html += '</g>';
    });
    mainGroup.innerHTML += html;
  }

  function renderMuTree() {
    if (!muTreeExpanded) return;
    var lotPos = nodePositions['lot-context'];
    if (!lotPos) return;

    // Render INSIDE Lot Context node (y=lotPos.y..lotPos.y+lotPos.h)
    var padX = 12, padTop = 50, padBot = 8;
    var innerX = lotPos.x + padX;
    var innerY = lotPos.y + padTop;
    var innerW = lotPos.w - padX * 2;
    var innerH = lotPos.h - padTop - padBot;

    var html = '<g class="mu-tree" opacity="' + (activeLayers['decision'] ? 1 : 0.15) + '">';
    // Subtle inner background
    html += '<rect x="' + innerX + '" y="' + innerY + '" width="' + innerW + '" height="' + innerH +
            '" rx="5" fill="rgba(45,212,191,0.06)" stroke="rgba(45,212,191,0.2)" stroke-width="0.8" stroke-dasharray="3 2"/>';

    // Lot root (compact)
    var lx = innerX + 8, ly = innerY + 6;
    html += '<rect x="' + lx + '" y="' + ly + '" width="55" height="22" rx="4" ' +
            'fill="rgba(96,165,250,0.15)" stroke="rgba(96,165,250,0.35)" stroke-width="1"/>';
    html += '<text x="' + (lx + 27) + '" y="' + (ly + 15) + '" fill="#e6edf3" font-size="8" font-weight="700" text-anchor="middle">Lot A</text>';
    html += '<text x="' + (lx + 27) + '" y="' + (ly + 30) + '" fill="#8b949e" font-size="6" font-weight="500" text-anchor="middle">25片</text>';

    // Wafer children (compact, 2 rows of 3)
    var waferLabels = ['W1', 'W2', 'W3', 'W4', 'W5*', 'W6'];
    for (var wi = 0; wi < 6; wi++) {
      var col = wi % 3, row = Math.floor(wi / 3);
      var wx = lx + 70 + col * 50, wy = ly + row * 24;
      var isAnomaly = wi === 4;
      html += '<rect x="' + wx + '" y="' + wy + '" width="44" height="18" rx="3" ' +
              'fill="' + (isAnomaly ? 'rgba(245,158,11,0.18)' : 'rgba(245,158,11,0.06)') + '" ' +
              'stroke="' + (isAnomaly ? 'rgba(245,158,11,0.6)' : 'rgba(245,158,11,0.2)') + '" stroke-width="' + (isAnomaly ? '1.2' : '0.7') + '"/>';
      html += '<text x="' + (wx + 22) + '" y="' + (wy + 13) + '" fill="' + (isAnomaly ? '#f59e0b' : '#8b949e') +
              '" font-size="7" font-weight="' + (isAnomaly ? '700' : '500') + '" text-anchor="middle">' + waferLabels[wi] + '</text>';
      // Branch line from Lot
      html += '<line x1="' + (lx + 55) + '" y1="' + (ly + 11) + '" x2="' + wx + '" y2="' + (wy + 9) +
              '" stroke="#8b949e" stroke-width="0.6" stroke-opacity="0.35"/>';
    }
    // W7..W25 indicator
    html += '<text x="' + (lx + 70 + 3 * 50) + '" y="' + (ly + 13) + '" fill="#6e7681" font-size="6.5">..W25</text>';

    // Die indicator under W5*
    html += '<text x="' + (lx + 70 + 1 * 50 + 22) + '" y="' + (ly + 1 * 24 + 30) + '" fill="#a855f7" font-size="6" font-weight="600" text-anchor="middle">Die×115</text>';
    html += '<line x1="' + (lx + 70 + 1 * 50 + 22) + '" y1="' + (ly + 1 * 24 + 18) + '" x2="' + (lx + 70 + 1 * 50 + 22) +
            '" y2="' + (ly + 1 * 24 + 27) + '" stroke="#a855f7" stroke-width="0.6" stroke-opacity="0.35"/>';

    // Asset conservation + timeline (compact footer)
    html += '<text x="' + (innerX + innerW / 2) + '" y="' + (innerY + innerH - 4) +
            '" fill="#2dd4bf" font-size="6.5" font-weight="600" text-anchor="middle">资产守恒: Σ MU = 25 | 时间线: PhaseDone 事件链 | Lot → Wafer → Die 钻取</text>';

    html += '</g>';
    mainGroup.innerHTML += html;
  }

  function addClickHandlers() {
    // Node click → FSM modal
    svg.querySelectorAll('.node').forEach(function (el) {
      el.addEventListener('click', function (e) {
        var nodeId = el.getAttribute('data-node');
        if (nodeId && nodeId !== 'out-pass' && nodeId !== 'out-borderline' && nodeId !== 'out-fail' && nodeId !== 'out-scrap') {
          showFsmModal(nodeId);
        } else if (nodeId) {
          showClassifierDetail(nodeId);
        }
      });
    });

    // Outcome node click
    svg.querySelectorAll('.outcome-node').forEach(function (el) {
      el.addEventListener('click', function () {
        var nodeId = el.getAttribute('data-node');
        showClassifierDetail(nodeId);
      });
    });

    // Layer header click → toggle layer
    svg.querySelectorAll('.layer-header').forEach(function (el) {
      el.addEventListener('click', function () {
        var layerId = el.getAttribute('data-layer');
        toggleLayer(layerId);
      });
    });
  }

  function renderAll() {
    initRender();
    addDefs();
    renderLayerBackgrounds();
    renderAllNodes();
    renderEdges();
    renderMuTree();
    // Re-attach handlers after DOM update
    setTimeout(addClickHandlers, 50);
  }

  // =========================================================================
  // 4. ANIMATION ENGINE
  // =========================================================================

  var animInterval = null;
  var animRunning = true;
  var animSpeed = 1;
  var cycleStart = 0;

  function makeParticle(color) {
    var circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    circle.setAttribute('r', '3.5');
    circle.setAttribute('fill', color);
    circle.setAttribute('filter', 'url(#particleGlow)');
    circle.setAttribute('opacity', '0');
    return circle;
  }

  function makeAnimMotion(pathD, dur, begin) {
    var anim = document.createElementNS('http://www.w3.org/2000/svg', 'animateMotion');
    anim.setAttribute('dur', dur + 'ms');
    anim.setAttribute('begin', begin + 'ms');
    anim.setAttribute('repeatCount', 'indefinite');
    anim.setAttribute('path', pathD);
    return anim;
  }

  function clearAnimations() {
    animParticles.forEach(function (p) {
      if (p.el && p.el.parentNode) p.el.parentNode.removeChild(p.el);
    });
    animParticles = [];
    if (animInterval) { clearInterval(animInterval); animInterval = null; }
  }

  function startAnimations() {
    clearAnimations();
    cycleStart = Date.now();
    var animGroup = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    animGroup.setAttribute('id', 'animParticles');
    svg.appendChild(animGroup);

    EVENT_FLOWS.forEach(function (flow) {
      flow.edges.forEach(function (edgeKey) {
        var ep = edgePaths[edgeKey];
        if (!ep) return;
        for (var p = 0; p < flow.particles; p++) {
          var stagger = p * (flow.dur / flow.particles / 2);
          var particle = makeParticle(flow.color);
          var motion = makeAnimMotion(ep.d, flow.dur / animSpeed, (flow.delay / animSpeed) + stagger);
          particle.appendChild(motion);
          animGroup.appendChild(particle);
          animParticles.push({ el: particle, flow: flow, edgeKey: edgeKey, baseDelay: flow.delay, baseDur: flow.dur, stagger: stagger });
        }
      });
    });

    // Visibility scheduling
    animInterval = setInterval(function () {
      var elapsed = (Date.now() - cycleStart) % (CYCLE_TOTAL / animSpeed);
      animParticles.forEach(function (p) {
        var adjDelay = p.baseDelay / animSpeed;
        var adjDur = p.baseDur / animSpeed;
        var adjStagger = p.stagger;
        var start = adjDelay + adjStagger;
        var end = start + adjDur;
        var visible = (elapsed >= start && elapsed < end);
        p.el.setAttribute('opacity', visible ? '0.9' : '0');
      });
    }, 100);
  }

  function restartAnimations() {
    clearAnimations();
    if (animRunning) startAnimations();
  }

  function toggleAnimation() {
    animRunning = !animRunning;
    var btn = $('btnPlay');
    if (animRunning) {
      btn.textContent = '⏯ 播放中';
      btn.classList.add('on');
      startAnimations();
    } else {
      btn.textContent = '▶ 已暂停';
      btn.classList.remove('on');
      clearAnimations();
    }
  }

  function setSpeed(factor) {
    animSpeed = factor;
    $('speedLabel').textContent = factor + '×';
    if (animRunning) restartAnimations();
  }

  // =========================================================================
  // 5. BATCH / SPLIT / MERGE ANIMATIONS (Manual trigger)
  // =========================================================================

  var specialAnimTimeout = null;

  function animateBatch() {
    clearSpecialAnimation();
    toast('成批: 4 个独立 Lot → BatchTool 拼炉 100 片');
    // Highlight funnel and diffusion nodes
    highlightNodes(['funnel', 'diffusion'], '#f08c3e', 2000);
    // Flash the MU tree
    flashMuTree(2000);
  }

  function animateSplit() {
    clearSpecialAnimation();
    toast('拆批: Lot A 发现 Wafer 5 异常 → Saga 跨账户转账 → 生成子 Lot A\'');
    highlightNodes(['saga', 'lot-context'], '#2dd4bf', 2500);
    flashMuTree(2500);
    // Highlight the anomaly wafer in MU tree
    specialAnimTimeout = setTimeout(function () {
      toast('资产守恒: Lot A (24片) + Lot A\' (1片) = 25片');
    }, 2500);
  }

  function animateMerge() {
    clearSpecialAnimation();
    toast('合批: 并行时间线 → BMM Barrier 对齐 → 合批签署');
    highlightNodes(['saga', 'lot-context'], '#3fb950', 2500);
    flashMuTree(2500);
    specialAnimTimeout = setTimeout(function () {
      toast('Barrier 通过 → Lot A 恢复 25 片完整');
    }, 2500);
  }

  function clearSpecialAnimation() {
    if (specialAnimTimeout) { clearTimeout(specialAnimTimeout); specialAnimTimeout = null; }
    // Remove temporary highlights
    svg.querySelectorAll('.temp-highlight').forEach(function (el) { el.remove(); });
  }

  function highlightNodes(nodeIds, color, duration) {
    nodeIds.forEach(function (id) {
      var pos = nodePositions[id];
      if (!pos) return;
      var rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      rect.setAttribute('x', pos.x - 4);
      rect.setAttribute('y', pos.y - 4);
      rect.setAttribute('width', pos.w + 8);
      rect.setAttribute('height', pos.h + 8);
      rect.setAttribute('rx', '10');
      rect.setAttribute('fill', 'none');
      rect.setAttribute('stroke', color);
      rect.setAttribute('stroke-width', '3');
      rect.setAttribute('stroke-opacity', '0.8');
      rect.setAttribute('class', 'temp-highlight');
      rect.style.animation = 'none';
      svg.querySelector('g[id="animParticles"]') || mainGroup;
      mainGroup.appendChild(rect);

      // Fade out
      var start = Date.now();
      var fadeInterval = setInterval(function () {
        var elapsed = Date.now() - start;
        if (elapsed >= duration) {
          clearInterval(fadeInterval);
          if (rect.parentNode) rect.parentNode.removeChild(rect);
        } else {
          var alpha = 1 - elapsed / duration;
          rect.setAttribute('stroke-opacity', (alpha * 0.8).toFixed(2));
        }
      }, 50);
    });
  }

  function flashMuTree(duration) {
    // Briefly change MU tree border color
    var muTreeEl = svg.querySelector('.mu-tree rect:first-child');
    if (!muTreeEl) {
      // Expand MU tree temporarily if not shown
      muTreeExpanded = true;
      renderAll();
      restartAnimations();
    }
    muTreeEl = svg.querySelector('.mu-tree rect:first-child');
    if (muTreeEl) {
      muTreeEl.setAttribute('stroke', '#f59e0b');
      muTreeEl.setAttribute('stroke-opacity', '0.7');
      setTimeout(function () {
        muTreeEl.setAttribute('stroke', 'rgba(45,212,191,0.25)');
        muTreeEl.setAttribute('stroke-opacity', '1');
      }, duration);
    }
  }

  // =========================================================================
  // 6. INTERACTION HANDLERS
  // =========================================================================

  function toggleLayer(layerId) {
    activeLayers[layerId] = !activeLayers[layerId];
    // Update chip visuals
    document.querySelectorAll('.layer-chip[data-layer="' + layerId + '"]').forEach(function (chip) {
      if (activeLayers[layerId]) {
        chip.classList.add('active');
      } else {
        chip.classList.remove('active');
      }
    });
    renderAll();
    restartAnimations();
    toast((activeLayers[layerId] ? '显示' : '隐藏') + ' ' + LAYERS[layerId].name);
  }

  function toggleFeedback() {
    feedbackVisible = !feedbackVisible;
    var btn = $('btnFeedback');
    if (feedbackVisible) {
      btn.classList.add('on');
    } else {
      btn.classList.remove('on');
    }
    renderAll();
    restartAnimations();
    toast(feedbackVisible ? '反馈回路: 显示' : '反馈回路: 隐藏');
  }

  function showFsmModal(nodeId) {
    var node = NODES.find(function (n) { return n.id === nodeId; });
    if (!node) return;

    var layer = LAYERS[node.layer];
    $('fsmTitle').textContent = node.label;
    $('fsmTitle').style.color = node.color;
    $('fsmSub').textContent = node.sub + ' | ' + layer.name;

    // Render mini FSM diagram
    var fsmSvg = $('fsmSvg');
    fsmSvg.innerHTML = '';
    var fs = {
      states: ['Idle', 'Processing', 'Completed', 'Failed', 'Compensating'],
      color: node.color
    };
    var w = 500, h = 260, cx = w / 2;
    // Central state
    var stateHtml = '';
    fs.states.forEach(function (s, i) {
      var angle = (i / fs.states.length) * Math.PI * 2 - Math.PI / 2;
      var radius = 85;
      var sx = cx + Math.cos(angle) * radius - 40;
      var sy = h / 2 + Math.sin(angle) * radius - 14;
      var isCenter = (s === 'Processing');
      var fx = isCenter ? cx - 45 : sx;
      var fy = isCenter ? h / 2 - 20 : sy;
      var fw = isCenter ? 90 : 80;
      var fh = isCenter ? 40 : 28;
      stateHtml += '<rect x="' + fx + '" y="' + fy + '" width="' + fw + '" height="' + fh +
                   '" rx="' + (isCenter ? '10' : '6') + '" fill="' +
                   (isCenter ? 'rgba(' + hexToRgb(node.color) + ',0.18)' : 'rgba(255,255,255,0.03)') + '" ' +
                   'stroke="' + node.color + '" stroke-opacity="' + (isCenter ? '0.7' : '0.35') + '" stroke-width="' + (isCenter ? '2' : '1.2') + '"/>';
      stateHtml += '<text x="' + (fx + fw / 2) + '" y="' + (fy + fh / 2 + 5) + '" fill="' +
                   (isCenter ? '#e6edf3' : '#8b949e') + '" font-size="' + (isCenter ? '11' : '9') +
                   '" font-weight="700" text-anchor="middle">' + s + '</text>';
      // Arrow from center to peripheral
      if (!isCenter) {
        var ax = cx, ay = h / 2;
        var tx = fx + fw / 2, ty = fy;
        stateHtml += '<path d="M ' + ax + ' ' + ay + ' L ' + tx + ' ' + ty +
                     '" fill="none" stroke="' + node.color + '" stroke-width="1.2" stroke-opacity="0.4" ' +
                     'stroke-dasharray="3 2" marker-end="url(#ah' + getMarkerName(node.color) + ')"/>';
      }
    });
    fsmSvg.innerHTML = stateHtml;

    // Info panel
    var info = '';
    info += '<div><span class="label">层级</span>: ' + layer.name + '</div>';
    info += '<div><span class="label">类型</span>: ' + (node.isKey ? 'M3 核心组件' : '标准组件') + '</div>';
    info += '<div><span class="label">状态数</span>: ' + fs.states.length + ' (EventSourcedBehavior)</div>';
    info += '<div><span class="label">持久化</span>: Akka Persistence · MongoDB Journal</div>';
    $('fsmInfo').innerHTML = info;

    // Code reference
    var codeKey = node.layer;
    var codeLines = CODE_SNIPPETS[codeKey] || CODE_SNIPPETS['decision'];
    $('fsmCode').textContent = codeLines.join('\n');

    $('fsmOverlay').classList.add('show');
    $('fsmOverlay').onclick = function (e) {
      if (e.target === $('fsmOverlay')) $('fsmOverlay').classList.remove('show');
    };
  }

  function showClassifierDetail(outcomeId) {
    var classifierNode = NODES.find(function (n) { return n.id === 'classifier'; });
    if (!classifierNode) return;
    var outcome = classifierNode.outcomes.find(function (o) { return 'out-' + o.id === outcomeId; });
    if (!outcome) return;

    $('fsmTitle').textContent = 'Result Classifier: ' + outcome.label;
    $('fsmTitle').style.color = outcome.color;
    $('fsmSub').textContent = outcome.desc + ' | 物理执行层';

    var fsmSvg = $('fsmSvg');
    fsmSvg.innerHTML = '';
    // Show 4-way classification diagram
    var outcomes = classifierNode.outcomes;
    var cx = 250, cy = 130;
    var html = '';
    // Center: measurement
    html += '<rect x="' + (cx - 55) + '" y="' + (cy - 16) + '" width="110" height="32" rx="8" ' +
            'fill="rgba(240,140,62,0.12)" stroke="#f08c3e" stroke-opacity="0.5" stroke-width="1.5"/>';
    html += '<text x="' + cx + '" y="' + (cy + 5) + '" fill="#f08c3e" font-size="11" font-weight="700" text-anchor="middle">量测结果</text>';

    outcomes.forEach(function (o, i) {
      var angle = (i / 4) * Math.PI * 2 - Math.PI / 2;
      var radius = 80;
      var ox = cx + Math.cos(angle) * radius - 42;
      var oy = cy + Math.sin(angle) * radius - 12;
      var isActive = o.id === outcome.id;
      html += '<rect x="' + ox + '" y="' + oy + '" width="84" height="24" rx="5" ' +
              'fill="' + (isActive ? 'rgba(' + hexToRgb(o.color) + ',0.2)' : 'rgba(255,255,255,0.02)') + '" ' +
              'stroke="' + o.color + '" stroke-opacity="' + (isActive ? '0.8' : '0.35') + '" stroke-width="' + (isActive ? '2.5' : '1') + '"/>';
      html += '<text x="' + (ox + 42) + '" y="' + (oy + 16) + '" fill="' + o.color +
              '" font-size="9" font-weight="' + (isActive ? '800' : '600') + '" text-anchor="middle">' + o.label + '</text>';

      // Connector from center
      html += '<path d="M ' + cx + ' ' + cy + ' L ' + (ox + 42) + ' ' + oy +
              '" fill="none" stroke="' + o.color + '" stroke-width="' + (isActive ? '2.5' : '1') +
              '" stroke-opacity="' + (isActive ? '0.8' : '0.35') + '" marker-end="url(#ah' + getMarkerName(o.color) + ')"/>';
    });
    fsmSvg.innerHTML = html;

    $('fsmInfo').innerHTML = '<div><span class="label">判定条件</span>: 根据 MeasurementThresholds 配置</div>' +
                             '<div><span class="label">当前结果</span>: <strong style="color:' + outcome.color + '">' + outcome.label + '</strong></div>' +
                             '<div><span class="label">后续动作</span>: ' + outcome.desc + '</div>';
    $('fsmCode').textContent = CODE_SNIPPETS['physical'].join('\n');

    $('fsmOverlay').classList.add('show');
    $('fsmOverlay').onclick = function (e) {
      if (e.target === $('fsmOverlay')) $('fsmOverlay').classList.remove('show');
    };
  }

  function hexToRgb(hex) {
    var r = parseInt(hex.slice(1, 3), 16);
    var g = parseInt(hex.slice(3, 5), 16);
    var b = parseInt(hex.slice(5, 7), 16);
    return r + ',' + g + ',' + b;
  }

  function getMarkerName(color) {
    var map = { '#58a6ff': 'Blue', '#8b949e': 'Muted', '#4ade80': 'Green', '#f59e0b': 'Amber',
                '#f87171': 'Red', '#a855f7': 'Purple', '#f08c3e': 'Orange', '#2dd4bf': 'Teal', '#3fb950': 'Green' };
    return map[color] || 'Blue';
  }

  function toast(msg) {
    var el = $('toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(el._timeout);
    el._timeout = setTimeout(function () { el.classList.remove('show'); }, 2200);
  }

  // =========================================================================
  // 7. RESIZE HANDLER
  // =========================================================================

  var resizeTimer;
  function onResize() {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      renderAll();
      restartAnimations();
    }, 300);
  }

  // =========================================================================
  // 8. INIT
  // =========================================================================

  function init() {
    layoutNodes();
    computeEdgePaths();
    renderAll();
    startAnimations();

    // Control bar events
    $('btnPlay').addEventListener('click', toggleAnimation);
    $('btnFeedback').addEventListener('click', toggleFeedback);
    $('btnBatch').addEventListener('click', animateBatch);
    $('btnSplit').addEventListener('click', animateSplit);
    $('btnMerge').addEventListener('click', animateMerge);
    $('speedSlider').addEventListener('input', function () {
      setSpeed(parseFloat(this.value));
    });

    // Layer chip events
    document.querySelectorAll('.layer-chip').forEach(function (chip) {
      chip.addEventListener('click', function () {
        toggleLayer(this.getAttribute('data-layer'));
      });
    });

    // Resize
    window.addEventListener('resize', onResize);

    // Keyboard shortcuts
    window.addEventListener('keydown', function (e) {
      switch (e.key) {
        case ' ': e.preventDefault(); toggleAnimation(); break;
        case 'f': toggleFeedback(); break;
        case '1': toggleLayer('decision'); break;
        case '2': toggleLayer('runtime'); break;
        case '3': toggleLayer('physical'); break;
        case 'b': animateBatch(); break;
        case 's': animateSplit(); break;
        case 'm': animateMerge(); break;
      }
    });

    // Initial feedback button state
    $('btnFeedback').classList.add('on');

    console.log('M3 Demo initialized — ' + NODES.length + ' nodes, ' + EDGES.length + ' edges, ' + EVENT_FLOWS.length + ' animations, ' + Object.keys(LAYERS).length + ' layers');
  }

  // Boot
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

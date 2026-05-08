/**
 * M2.5+ 标准组件库 — Live Demo
 *
 * 三列分区：业务链路 (左) | 连接器 (中) | 外部系统 (右)
 * 组件色：chain(#3b82f6) guard(#f59e0b) batch(#14b8a6)
 */
(function () {
  'use strict';

  var TEMPLATES = {
    chain: { name: 'L3 业务链路层',    color: '#3b82f6', file: 'SubBatchProcessor.scala', lines: 80,  desc: '充值/申购链路 + 短信通知，同一套组件，仅 ErrorCodeMapping 不同。由 SubBatchProcessor 组件驱动。' },
    guard: { name: 'L2/L4 资源解锁',   color: '#f59e0b', file: 'ResultClassifier.scala', lines: 75,  desc: '额度锁定 + 超时自动释放 + 级联释放。由 ResultClassifier + ReconfirmHandler 组件参数化。' },
    batch: { name: 'L0–L2 编排调度层', color: '#14b8a6', file: 'ReBatchRouter.scala',   lines: 100, desc: '作业编排 → 扇出 → 补偿扫描 → 重新成批。由 ReBatchRouter + AreaScheduler 驱动。' }
  };

  var NODES = [
    { id: 'cron', label: '定时调度', sub: 'Quartz Cron', level: 0, template: null, col: 0, row: 0 },
    { id: 'job-actor', label: '作业编排器', sub: 'JobActor', level: 1, template: 'batch', col: 0, row: 0 },
    { id: 'pre-batch', label: '预批处理器', sub: 'PreBatchActor', level: 1, template: 'batch', col: 0, row: 1 },
    { id: 're-batch',  label: '补偿扫描器', sub: 'ReBatchActor · 死循环捞取', level: 1, template: 'batch', col: 1, row: 1 },
    { id: 'batch-master',  label: '批次分发器', sub: 'BatchMaster', level: 2, template: 'batch', col: 1, row: 0 },
    { id: 'batch-worker',  label: '批次工人 xN', sub: 'BatchWorker', level: 2, template: 'batch', col: 1, row: 1 },
    { id: 'batch-item',    label: '明细创建器', sub: 'BatchItemCreation', level: 2, template: 'batch', col: 2, row: 0 },
    { id: 'quota-reserve', label: '额度锁定', sub: 'QuotaReserve', level: 2, template: 'guard', col: 0, row: 0 },
    { id: 'recharge-pipeline', label: '充值链路', sub: '从生成申请文件 → 上传银行 → 等待回盘 → 解析结果 → 分类处理', level: 3, template: 'chain', chain: 'recharge',
      pipeline: true, stages: ['生成文件','上传银行','等待回盘','解析结果','分类处理','可疑复核'] },
    { id: 'purchase-pipeline', label: '申购链路', sub: '同充值链路完全相同的 Pipeline，仅错误码映射不同', level: 3, template: 'chain', chain: 'purchase',
      pipeline: true, stages: ['生成文件','上传银行','等待回盘','解析结果','分类处理','可疑复核'] },
    { id: 'sms-success', label: '交易成功通知', sub: '短信通知用户 · 合规窗口', level: 3, template: 'chain' },
    { id: 'sms-failure', label: '交易失败通知', sub: '短信通知用户 · 合规窗口', level: 3, template: 'chain' },
    { id: 'quota-release', label: '额度释放', sub: 'QuotaRelease · 超时自释放', level: 4, template: 'guard', col: 0, row: 0 },
    { id: 'quota-cascade', label: '级联释放', sub: 'CascadeRelease · 总额度', level: 4, template: 'guard', col: 0, row: 1 }
  ];

  var GATEWAY_ENTRIES = [
    { id: 'gw-sftp', label: '银行 SFTP', sub: '文件上传 / 轮询下载' },
    { id: 'gw-core', label: '核心账户 API', sub: '余额 / 交易状态查证' },
    { id: 'gw-p2b',  label: '基金申购平台', sub: '产品申购 / 回盘解析' },
    { id: 'gw-sms',  label: '短信通道', sub: '合规窗口下发' }
  ];

  var CONNECTORS = [
    { id: 'conn-sftp',     label: 'SFTP 连接器',  sub: '断路器 · 重试 · 上传/下载', row: 0 },
    { id: 'conn-http-xml', label: 'HTTP/XML 连接器', sub: '断路器 · 重试 · 核验',    row: 1 },
    { id: 'conn-http-json',label: 'HTTP/JSON 连接器',sub: '断路器 · 重试 · 通知平台',  row: 2 },
    { id: 'conn-sms',      label: 'SMS 连接器',   sub: '断路器 · 合规窗口 · 下发',   row: 3 }
  ];

  var EDGES = [
    { from: 'cron', to: 'job-actor' },
    { from: 'job-actor', to: 'pre-batch' },
    { from: 'job-actor', to: 're-batch' },
    { from: 'pre-batch', to: 'batch-master' },
    { from: 'pre-batch', to: 'batch-item' },
    { from: 'pre-batch', to: 'quota-reserve' },
    { from: 'batch-master', to: 'batch-worker' },
    { from: 'batch-item',   to: 'batch-worker' },
    { from: 'quota-reserve', to: 'batch-worker' },
    { from: 'batch-worker',  to: 'recharge-pipeline' },
    { from: 'batch-worker',  to: 'purchase-pipeline' },
    { from: 'quota-reserve', to: 'recharge-pipeline' },
    { from: 'quota-reserve', to: 'purchase-pipeline' },
    // Pipeline outcomes → SMS notification (within L3)
    { from: 'recharge-pipeline', to: 'sms-success', label: 'success' },
    { from: 'recharge-pipeline', to: 'sms-failure', label: 'failure' },
    { from: 'purchase-pipeline', to: 'sms-success', label: 'success' },
    { from: 'purchase-pipeline', to: 'sms-failure', label: 'failure' },
    // SMS failure → resource unlock
    { from: 'sms-failure',  to: 'quota-release' },
    { from: 'quota-release', to: 'quota-cascade' },
    { from: 're-batch', to: 'batch-worker', feedback: true },
    // Pipeline → Connectors (Zone A right edge → Zone B left edge)
    { from: 'recharge-pipeline', to: 'conn-sftp',      zoneEdge: true },
    { from: 'recharge-pipeline', to: 'conn-http-xml',  zoneEdge: true },
    { from: 'recharge-pipeline', to: 'conn-http-json', zoneEdge: true },
    { from: 'purchase-pipeline', to: 'conn-sftp',      zoneEdge: true },
    { from: 'purchase-pipeline', to: 'conn-http-xml',  zoneEdge: true },
    { from: 'purchase-pipeline', to: 'conn-http-json', zoneEdge: true },
    { from: 'sms-success',  to: 'conn-sms', zoneEdge: true },
    { from: 'sms-failure',  to: 'conn-sms', zoneEdge: true },
    // Connectors → External (Zone B right edge → Zone C left edge)
    { from: 'conn-sftp',     to: 'gw-sftp', external: true },
    { from: 'conn-http-xml', to: 'gw-core', external: true },
    { from: 'conn-http-json',to: 'gw-p2b',  external: true },
    { from: 'conn-sms',      to: 'gw-sms',  external: true },
    // Connector labels on external edges
    { from: 'conn-sftp',     to: 'gw-sftp', connector: 'SFTP' },
    { from: 'conn-http-xml', to: 'gw-core', connector: 'XML' },
    { from: 'conn-http-json',to: 'gw-p2b',  connector: 'JSON' },
    { from: 'conn-sms',      to: 'gw-sms',  connector: 'SMS' }
  ];

  var CODE_SNIPPETS = {
    scala: {
      chain: [
        '═══ M2 → M2.5+ 核心变化 ═══',
        '',
        '▸ 标准化并封装了 6 个 FSM：',
        '  fileGen · upload · pollResp · parse · classify · reconfirm',
        '  不再为每条业务链路生成 6 个 Actor 类文件',
        '',
        '▸ 可变业务被参数化：',
        '  错误码映射（哪些算成功/失败/可疑）',
        '  复核逻辑（可疑交易如何核验）',
        '  路由策略（失败后重试/换区/报废）',
        '',
        '▸ 一条链 ≈ 30 行配置：',
        '  val recharge = ChainTemplates.recharge(pipeline)',
        '  val purchase = ChainTemplates.purchase(pipeline)',
        '  充值/申购 共享同一套组件，仅 ErrorCodeMapping 不同',
        '',
        '▸ 解决了什么问题：',
        '  M2 每加一条业务链路 → 复制 6 个类 → 散落的重试/补偿逻辑',
        '  M2.5+ 修复一处组件 → 所有链路自动继承 → 零代码生成'
      ].join('\n'),
      guard: [
        '═══ 资源解锁 ═══',
        '',
        '▸ 组件：ResultClassifier + ReconfirmHandler',
        '  不生成独立的 QuotaReserve / QuotaRelease FSM',
        '',
        '▸ 参数化内容：',
        '  successCodes  = Set("OK")           // 哪些响应码算成功',
        '  failureCodes  = Map("BALANCE_INSUFFICIENT" → Scrap)',
        '  suspiciousCodes = Set("TIMEOUT")    // 哪些需要人工复核',
        '',
        '▸ 失败通知触发额度释放 → 级联释放',
        '  交易失败后自动解锁用户额度，超时自释放内置在组件中',
        '  不再需要为每个业务方手写超时释放逻辑'
      ].join('\n'),
      batch: [
        '═══ 编排调度层 ═══',
        '',
        '▸ 组件：ReBatchRouter + AreaScheduler',
        '  替代手写的重试/补偿 Actor',
        '',
        '▸ ReBatchPolicy 声明式路由：',
        '  "TIMEOUT"       → 5分钟后同区重试',
        '  "NETWORK_ERROR" → 30秒后同区重试',
        '  "OVER_ETCH"     → 直接报废',
        '  "OUT_OF_SPEC"   → 路由到 CLEAN 区返工',
        '',
        '▸ AreaScheduler 物理约束：',
        '  minBatchSize=1, maxBatchSize=100',
        '  batchWindow=10min（时间窗口触发成批）',
        '',
        '▸ 解决什么问题：',
        '  M2 每个失败分支都是 if-else 嵌套 → 难以测试和变更',
        '  M2.5+ 错误码 → NextStep 映射表，编译器验证完整性'
      ].join('\n')
    },
    java: {
      chain: [
        '═══ M2 旧模式（作为对比） ═══',
        '',
        '▸ 每条业务链路 = 6 个手写 FSM 类文件',
        '  RechargeRequestActor  ~400 行',
        '  RechargeResponseActor ~380 行',
        '  RechargeReconfirmActor ~350 行',
        '  ... 合计 ~2000 行 / 链',
        '',
        '▸ 新增申购链路：复制 6 个文件 → 全局替换',
        '  95% 代码重复，重试/补偿逻辑散落各处',
        '',
        '▸ 修复一个 Bug（如超时处理）→ 4 条链路 × 6 文件',
        '  = 24 处需要同步修改，极易遗漏'
      ].join('\n'),
      guard: [
        '═══ M2 资源解锁（旧模式） ═══',
        '',
        '▸ 5 个独立 Actor 类：',
        '  QuotaReserveActor  ~300 行',
        '  QuotaReleaseActor  ~250 行',
        '  QuotaCascadeActor  ~200 行',
        '  TimeoutGuardActor   ~180 行',
        '  ReleaseRetryActor   ~150 行',
        '',
        '▸ 超时释放逻辑在每个 Actor 中重复实现',
        '▸ M2.5+ 等价：ErrorCodeMapping 参数化，不生成 FSM'
      ].join('\n')
    }
  };

  var canvas = document.getElementById('dagCanvas');
  var vizArea = document.getElementById('vizArea');
  var codePanel = document.getElementById('codePanel');
  var codeTitle = document.getElementById('codeTitle');
  var codeContent = document.getElementById('codeContent');
  var btnScala = document.getElementById('btnScala');
  var btnJava = document.getElementById('btnJava');
  var toast = document.getElementById('toast');
  var fsmModalOverlay = document.getElementById('fsmModalOverlay');
  var fsmModalTitle = document.getElementById('fsmModalTitle');
  var fsmModalSubtitle = document.getElementById('fsmModalSubtitle');
  var fsmDiagramArea = document.getElementById('fsmDiagramArea');
  var fsmInfoPanel = document.getElementById('fsmInfoPanel');
  var fsmSvg = document.getElementById('fsmSvg');

  var activeTemplate = null, codeMode = 'scala';
  var nodePositions = {}, gwPositions = {};
  var svgNodeGroups = {}, svgEdgeEls = {};
  var templateRegionEls = {};

  // ======================== LAYOUT ========================
  var NODE_W = 118, NODE_H = 40;
  var PIPELINE_W = 270, PIPELINE_H = 58;
  var CONN_W = 148, CONN_H = 38;
  var GW_CONTAINER_W = 156, GW_ENTRY_W = 142, GW_ENTRY_H = 38;
  var GW_PAD_TOP = 48;

  function layoutNodes() {
    var w = vizArea.clientWidth;
    var GAP = Math.round(NODE_H * 1.5);
    var LEVEL_GAP = GAP + 10;
    var TOP = 28;
    var totalW = w - 20;
    var zoneA = { left: 10, right: totalW * 0.52 };
    var zoneB = { left: totalW * 0.54, right: totalW * 0.78 };
    var zoneC = { left: totalW * 0.80, right: totalW };
    var zoneAw = zoneA.right - zoneA.left;
    var zoneBw = zoneB.right - zoneB.left;
    var zoneCw = zoneC.right - zoneC.left;
    var pipeGap = Math.round(PIPELINE_H * 1.5);

    // Shift Zone A right by 1/3 of pipeline→connector visual gap
    var _aMid0 = zoneA.left + zoneAw/2;
    var _connMid0 = zoneB.left + zoneBw/2;
    var _pipeRight0 = _aMid0 + PIPELINE_W/2;
    var _connLeft0 = _connMid0 - CONN_W/2;
    var _crossGap = _connLeft0 - _pipeRight0;
    var _shiftX = Math.round(_crossGap / 3);
    zoneA.left += _shiftX;
    zoneA.right += _shiftX;
    zoneAw = zoneA.right - zoneA.left;

    var smsGap = GAP;
    var l3Height = 2*PIPELINE_H + pipeGap + smsGap + NODE_H + 10;
    var lvlY = [TOP];
    var lHeights = [NODE_H, 2*NODE_H+GAP, 2*NODE_H+GAP, l3Height, 2*NODE_H+GAP];
    for (var lv = 1; lv <= 4; lv++) { lvlY.push(lvlY[lv-1] + lHeights[lv-1] + LEVEL_GAP); }

    // Guard column (left strip) + Main column (rest) within Zone A
    var guardColW = 110;
    var guardGap = 16;
    var guardLeft = zoneA.left;
    var guardMid = guardLeft + guardColW/2;
    var mainLeft = guardLeft + guardColW + guardGap;
    var mainRight = zoneA.right;
    var mainW = mainRight - mainLeft;
    var mainMid = mainLeft + mainW/2;

    function placeNode(n, y, x) {
      var nx = x - NODE_W/2;
      nodePositions[n.id] = { x: nx, y: y, cx: x, cy: y+NODE_H/2, right: x+NODE_W/2, bottom: y+NODE_H };
    }
    function placeRow(nodes, y, cols, zw, leftX) {
      leftX = leftX || mainLeft;
      nodes.forEach(function (n, idx) {
        var col = n.col !== undefined ? n.col : idx;
        placeNode(n, y, leftX + zw/(cols+1) * (col+1));
      });
    }

    placeRow(NODES.filter(function(n){return n.level===0;}), lvlY[0], 1, mainW);
    var l1s = lvlY[1]+(lHeights[1]-(2*NODE_H+GAP))/2;
    placeRow(NODES.filter(function(n){return n.level===1&&n.row===0;}), l1s, 1, mainW);
    placeRow(NODES.filter(function(n){return n.level===1&&n.row===1;}), l1s+NODE_H+GAP, 2, mainW);
    var l2s = lvlY[2]+(lHeights[2]-(2*NODE_H+GAP))/2;
    placeRow(NODES.filter(function(n){return n.level===2&&n.row===0&&n.template!=='guard';}), l2s, 2, mainW);
    placeRow(NODES.filter(function(n){return n.level===2&&n.row===1;}), l2s+NODE_H+GAP, 3, mainW);
    // Guard column: quota-reserve at L2
    var gr = NODES.find(function(n){return n.id==='quota-reserve';});
    if(gr) placeNode(gr, l2s, guardMid);

    // L3: pipelines stacked vertically + SMS notification row (main column)
    var l3y = lvlY[3];
    var rc = NODES.find(function(n){return n.id==='recharge-pipeline';});
    var pc = NODES.find(function(n){return n.id==='purchase-pipeline';});
    if (rc) nodePositions[rc.id] = { x: mainMid-PIPELINE_W/2, y: l3y, cx: mainMid, cy: l3y+PIPELINE_H/2, right: mainMid+PIPELINE_W/2, bottom: l3y+PIPELINE_H, w: PIPELINE_W, h: PIPELINE_H };
    if (pc) nodePositions[pc.id] = { x: mainMid-PIPELINE_W/2, y: l3y+PIPELINE_H+pipeGap, cx: mainMid, cy: l3y+PIPELINE_H+pipeGap+PIPELINE_H/2, right: mainMid+PIPELINE_W/2, bottom: l3y+PIPELINE_H+pipeGap+PIPELINE_H, w: PIPELINE_W, h: PIPELINE_H };
    // SMS nodes below pipelines, within L3
    var smsY = l3y + 2*PIPELINE_H + pipeGap + smsGap;
    var smsNodes = NODES.filter(function(n){return n.level===3 && !n.pipeline;}).sort(function(a,b){return (a.id||'').localeCompare(b.id||'');});
    smsNodes.forEach(function(n,idx){ placeNode(n, smsY, mainLeft+mainW*(0.35+idx*0.3)); });

    // L4: resource unlock (guard column)
    var l4y = lvlY[4];
    var l4guards = NODES.filter(function(n){return n.level===4;}).sort(function(a,b){return (a.row||0)-(b.row||0);});
    l4guards.forEach(function(n){ placeNode(n, l4y+n.row*(NODE_H+GAP), guardMid); });

    // ---- Zone B: Connectors ----
    var connMidX = zoneB.left + zoneBw/2;
    var connStartY = l3y + 10;
    var connGap = CONN_H + 14;
    CONNECTORS.forEach(function(c, idx) {
      var cy = connStartY + idx*connGap;
      var cx = connMidX;
      nodePositions[c.id] = { x: cx-CONN_W/2, y: cy, cx: cx, cy: cy+CONN_H/2, right: cx+CONN_W/2, bottom: cy+CONN_H, w: CONN_W, h: CONN_H };
    });

    // ---- Zone C: External gateways (aligned with connectors) ----
    var gwColLeft = zoneC.left + (zoneCw-GW_CONTAINER_W)/2;
    var gwEntryX = gwColLeft + (GW_CONTAINER_W-GW_ENTRY_W)/2;
    CONNECTORS.forEach(function(c) {
      var gwId = c.id.replace('conn-','gw-');
      if (c.id==='conn-http-xml') gwId = 'gw-core';
      if (c.id==='conn-http-json') gwId = 'gw-p2b';
      var cp = nodePositions[c.id];
      var gy = cp.y + (CONN_H-GW_ENTRY_H)/2;
      gwPositions[gwId] = { x: gwEntryX, y: gy, cx: gwEntryX+GW_ENTRY_W/2, cy: gy+GW_ENTRY_H/2, left: gwEntryX, right: gwEntryX+GW_ENTRY_W, bottom: gy+GW_ENTRY_H };
    });
    var sp = gwPositions['gw-sftp'], cp2 = gwPositions['gw-core'];
    if (sp&&cp2) { var bt=Math.min(sp.y,cp2.y)-GW_PAD_TOP; gwPositions._bankCol = { x: gwColLeft, y: bt, w: GW_CONTAINER_W, h: Math.max(sp.bottom,cp2.bottom)-bt+12 }; }
    var pp = gwPositions['gw-p2b'];
    if (pp) gwPositions._p2bCol = { x: gwColLeft, y: pp.y-GW_PAD_TOP, w: GW_CONTAINER_W, h: GW_PAD_TOP+GW_ENTRY_H+12 };
    var sm = gwPositions['gw-sms'];
    if (sm) gwPositions._smsCol = { x: gwColLeft, y: sm.y-GW_PAD_TOP, w: GW_CONTAINER_W, h: GW_PAD_TOP+GW_ENTRY_H+12 };

    var maxY = Math.max(l4y+2*(NODE_H+GAP)+40, (sm?sm.bottom:0)+40);
    canvas.style.height = maxY+'px';
    canvas.setAttribute('viewBox', '0 0 '+w+' '+maxY);
  }

  // ======================== EDGE PATHS ========================
  function getEdgePath(fromId, toId, isFeedback, isExternal, isZone) {
    var f = nodePositions[fromId];
    var t = isExternal ? gwPositions[toId] : nodePositions[toId];
    if (!f || !t) return '';

    if (isFeedback) {
      var bulge = vizArea.clientWidth - 30;
      return 'M '+f.right+' '+(f.y+NODE_H/2)+' C '+bulge+' '+(f.y+NODE_H/2)+', '+bulge+' '+(t.y+NODE_H/2)+', '+t.right+' '+(t.y+NODE_H/2);
    }

    // Cross-zone: source right edge → target left edge (horizontal)
    if (isZone) {
      var zsy = f.y + (f.h||NODE_H)/2;
      var zty = t.y + (t.h||NODE_H)/2;
      var midX = (f.x + (f.w||NODE_W) + t.x) / 2;
      return 'M '+(f.x+(f.w||NODE_W))+' '+zsy+' C '+midX+' '+zsy+', '+midX+' '+zty+', '+t.x+' '+zty;
    }

    if (isExternal) {
      var fey = f.y + (f.h||NODE_H)/2;
      var tty = t.y + GW_ENTRY_H/2;
      var exsx = f.x + (f.w||NODE_W);
      var exmx = (exsx + t.left)/2;
      return 'M '+exsx+' '+fey+' C '+exmx+' '+fey+', '+exmx+' '+tty+', '+t.left+' '+tty;
    }

    var x1 = f.cx, y1 = f.bottom;
    var x2 = t.cx, y2 = t.y;
    if (Math.abs(x2-x1) < 8) {
      var cp = Math.max((y2-y1)*0.35, 24);
      return 'M '+x1+' '+y1+' C '+x1+' '+(y1+cp)+', '+x2+' '+(y2-cp)+', '+x2+' '+y2;
    }
    var cp = Math.max(Math.abs(x2-x1)*0.35, 18);
    return 'M '+x1+' '+y1+' C '+x1+' '+(y1+cp)+', '+x2+' '+(y2-cp)+', '+x2+' '+y2;
  }

  // ======================== RENDER ========================
  function renderAll() {
    layoutNodes();
    while (canvas.firstChild) canvas.removeChild(canvas.firstChild);
    var defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
    [{ id: 'arrow-normal', fill: 'rgba(255,255,255,0.12)' },{ id: 'arrow-feedback', fill: 'rgba(239,68,68,0.5)' },{ id: 'arrow-external', fill: 'rgba(240,136,62,0.5)' },{ id: 'arrow-highlight', fill: 'rgba(88,166,255,0.7)' }].forEach(function(a){
      var m = document.createElementNS('http://www.w3.org/2000/svg','marker');
      m.setAttribute('id',a.id);m.setAttribute('markerWidth','5');m.setAttribute('markerHeight','4');
      m.setAttribute('refX','5');m.setAttribute('refY','2');m.setAttribute('orient','auto');m.setAttribute('markerUnits','userSpaceOnUse');
      var p = document.createElementNS('http://www.w3.org/2000/svg','polygon');
      p.setAttribute('points','0,0 5,2 0,4');p.setAttribute('fill',a.fill);m.appendChild(p);defs.appendChild(m);
    });
    // Glow filter for event particles
    var glowF = document.createElementNS('http://www.w3.org/2000/svg','filter');
    glowF.setAttribute('id','eventGlow');glowF.setAttribute('x','-50%');glowF.setAttribute('y','-50%');glowF.setAttribute('width','200%');glowF.setAttribute('height','200%');
    var blur = document.createElementNS('http://www.w3.org/2000/svg','feGaussianBlur');blur.setAttribute('stdDeviation','2.5');blur.setAttribute('result','b');
    var merge = document.createElementNS('http://www.w3.org/2000/svg','feMerge');
    var mn1 = document.createElementNS('http://www.w3.org/2000/svg','feMergeNode');mn1.setAttribute('in','b');
    var mn2 = document.createElementNS('http://www.w3.org/2000/svg','feMergeNode');mn2.setAttribute('in','SourceGraphic');
    merge.appendChild(mn1);merge.appendChild(mn2);glowF.appendChild(blur);glowF.appendChild(merge);defs.appendChild(glowF);
    canvas.appendChild(defs);
    templateRegionEls={};svgNodeGroups={};svgEdgeEls={};
    renderTemplateRegions();renderLevelLabels();renderConnectors();renderGatewayContainers();
    renderEdges();renderInternalNodes();renderGatewayEntries();renderIsomorphismAnnotation();updateEdgePaths();
    Object.keys(svgEdgeEls).forEach(function(k){var e=svgEdgeEls[k];if(e&&e.connectorEl)canvas.appendChild(e.connectorEl);});
    startEventAnimation();
  }

  function renderTemplateRegions() {
    var regions={};
    NODES.forEach(function(n){
      if(!n.template)return;
      if(!regions[n.template])regions[n.template]={minX:Infinity,minY:Infinity,maxX:-Infinity,maxY:-Infinity};
      var p=nodePositions[n.id];if(!p)return;
      var w=n.pipeline?PIPELINE_W:NODE_W,h=n.pipeline?PIPELINE_H:NODE_H;
      regions[n.template].minX=Math.min(regions[n.template].minX,p.x);
      regions[n.template].minY=Math.min(regions[n.template].minY,p.y);
      regions[n.template].maxX=Math.max(regions[n.template].maxX,p.x+w);
      regions[n.template].maxY=Math.max(regions[n.template].maxY,p.y+h);
    });
    var PAD=20;
    Object.keys(regions).forEach(function(tpl){
      var r=regions[tpl],color=TEMPLATES[tpl]?TEMPLATES[tpl].color:'#666';
      var g=document.createElementNS('http://www.w3.org/2000/svg','g');
      var rect=document.createElementNS('http://www.w3.org/2000/svg','rect');
      rect.setAttribute('x',r.minX-PAD);rect.setAttribute('y',r.minY-PAD-12);
      rect.setAttribute('width',r.maxX-r.minX+2*PAD);rect.setAttribute('height',r.maxY-r.minY+2*PAD+12);
      rect.setAttribute('class','tpl-region');rect.setAttribute('stroke',color);g.appendChild(rect);
      var label=document.createElementNS('http://www.w3.org/2000/svg','text');
      label.setAttribute('x',r.minX+4);label.setAttribute('y',r.minY-PAD-2);
      label.setAttribute('class','tpl-region-label');label.setAttribute('fill',color);
      label.textContent=TEMPLATES[tpl]?TEMPLATES[tpl].name:tpl;g.appendChild(label);
      canvas.appendChild(g);templateRegionEls[tpl]={g:g,rect:rect};
    });
  }

  function renderLevelLabels() {
    var labels=['L0 触发层','L1 编排调度层','L2 执行与资源层','L3 业务链路层 (SubBatchProcessor)','L4 资源解锁'];
    for(var lv=0;lv<=4;lv++){
      var ns=NODES.filter(function(n){return n.level===lv;});
      var minY=Infinity,maxY=-Infinity;
      ns.forEach(function(n){var p=nodePositions[n.id];if(!p)return;minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.bottom);});
      if(minY===Infinity)continue;
      var t=document.createElementNS('http://www.w3.org/2000/svg','text');
      t.setAttribute('x',6);t.setAttribute('y',(minY+maxY)/2+4);
      t.setAttribute('class','level-label');t.textContent=labels[lv];canvas.appendChild(t);
    }
  }

  function renderConnectors() {
    CONNECTORS.forEach(function(c){
      var pos=nodePositions[c.id];if(!pos)return;
      var g=document.createElementNS('http://www.w3.org/2000/svg','g');
      g.setAttribute('class','dag-node conn-node');g.setAttribute('data-node-id',c.id);g.setAttribute('data-template','connector');
      g.style.cursor='pointer';
      var rect=document.createElementNS('http://www.w3.org/2000/svg','rect');
      rect.setAttribute('x',pos.x);rect.setAttribute('y',pos.y);rect.setAttribute('width',CONN_W);rect.setAttribute('height',CONN_H);
      rect.setAttribute('rx',6);rect.setAttribute('ry',6);rect.setAttribute('fill','rgba(240,136,62,0.08)');
      rect.setAttribute('stroke','rgba(240,136,62,0.35)');rect.setAttribute('stroke-width','1.5');rect.setAttribute('stroke-dasharray','4 3');
      g.appendChild(rect);
      var badge=document.createElementNS('http://www.w3.org/2000/svg','rect');
      badge.setAttribute('x',pos.x+4);badge.setAttribute('y',pos.y+4);badge.setAttribute('width',22);badge.setAttribute('height',12);
      badge.setAttribute('rx',2);badge.setAttribute('ry',2);badge.setAttribute('fill','rgba(240,136,62,0.2)');g.appendChild(badge);
      var bt=document.createElementNS('http://www.w3.org/2000/svg','text');
      bt.setAttribute('x',pos.x+15);bt.setAttribute('y',pos.y+13);bt.setAttribute('text-anchor','middle');
      bt.setAttribute('fill','#f0883e');bt.setAttribute('font-size','0.45rem');bt.setAttribute('font-weight','700');
      bt.textContent='CB';g.appendChild(bt);
      var label=document.createElementNS('http://www.w3.org/2000/svg','text');
      label.setAttribute('x',pos.x+32);label.setAttribute('y',pos.y+15);label.setAttribute('fill','#f0883e');
      label.setAttribute('font-size','0.68rem');label.setAttribute('font-weight','600');label.textContent=c.label;g.appendChild(label);
      var sub=document.createElementNS('http://www.w3.org/2000/svg','text');
      sub.setAttribute('x',pos.x+32);sub.setAttribute('y',pos.y+29);sub.setAttribute('fill','#8b949e');
      sub.setAttribute('font-size','0.55rem');sub.textContent=c.sub;g.appendChild(sub);
      g.addEventListener('click',function(){showFsmModal({id:c.id,label:c.label,sub:c.sub,template:'chain',level:'Conn'});});
      canvas.appendChild(g);svgNodeGroups[c.id]=g;
    });
  }

  function renderGatewayContainers() {
    [{key:'_bankCol',label:'银行网关 (ACL)'},{key:'_p2bCol',label:'理财平台网关 (ACL)'},{key:'_smsCol',label:'短信网关 (ACL)'}].forEach(function(gw){
      var pos=gwPositions[gw.key];if(!pos)return;
      var rect=document.createElementNS('http://www.w3.org/2000/svg','rect');
      rect.setAttribute('x',pos.x);rect.setAttribute('y',pos.y);rect.setAttribute('width',pos.w);rect.setAttribute('height',pos.h);
      rect.setAttribute('class','gw-container');canvas.appendChild(rect);
      var lbl=document.createElementNS('http://www.w3.org/2000/svg','text');
      lbl.setAttribute('x',pos.x+6);lbl.setAttribute('y',pos.y-8);lbl.setAttribute('class','gw-label');lbl.setAttribute('fill','#f0883e');
      lbl.textContent=gw.label;canvas.appendChild(lbl);
    });
  }

  function renderInternalNodes() {
    NODES.forEach(function(n){
      var pos=nodePositions[n.id];if(!pos)return;
      var isPipeline=!!n.pipeline;
      var g=document.createElementNS('http://www.w3.org/2000/svg','g');
      g.setAttribute('class','dag-node tpl-'+(n.template||'trigger'));g.setAttribute('data-node-id',n.id);
      g.setAttribute('data-template',n.template||'');g.style.cursor='pointer';
      var w=isPipeline?PIPELINE_W:NODE_W,h=isPipeline?PIPELINE_H:NODE_H;
      var rect=document.createElementNS('http://www.w3.org/2000/svg','rect');
      rect.setAttribute('x',pos.x);rect.setAttribute('y',pos.y);rect.setAttribute('width',w);rect.setAttribute('height',h);
      rect.setAttribute('class','node-rect');if(isPipeline)rect.setAttribute('rx','10');g.appendChild(rect);
      if(isPipeline){
        var stages=n.stages||[],stageW=(PIPELINE_W-20)/stages.length,stageH=PIPELINE_H-26,stageY=pos.y+6;
        stages.forEach(function(s,idx){
          var sx=pos.x+10+idx*stageW;
          var sr=document.createElementNS('http://www.w3.org/2000/svg','rect');
          sr.setAttribute('x',sx+1);sr.setAttribute('y',stageY);sr.setAttribute('width',stageW-2);sr.setAttribute('height',stageH);
          sr.setAttribute('rx','3');sr.setAttribute('ry','3');sr.setAttribute('fill','rgba(59,130,246,0.08)');
          sr.setAttribute('stroke','rgba(59,130,246,0.2)');sr.setAttribute('stroke-width','0.8');g.appendChild(sr);
          var sl=document.createElementNS('http://www.w3.org/2000/svg','text');
          sl.setAttribute('x',sx+stageW/2);sl.setAttribute('y',stageY+stageH/2+3);sl.setAttribute('text-anchor','middle');
          sl.setAttribute('fill','#93c5fd');sl.setAttribute('font-size','0.55rem');sl.setAttribute('font-family','monospace');
          sl.textContent=s;g.appendChild(sl);
          if(idx<stages.length-1){
            var arrow=document.createElementNS('http://www.w3.org/2000/svg','text');
            arrow.setAttribute('x',sx+stageW);arrow.setAttribute('y',stageY+stageH/2+3);arrow.setAttribute('text-anchor','middle');
            arrow.setAttribute('fill','rgba(255,255,255,0.2)');arrow.setAttribute('font-size','0.5rem');arrow.textContent='→';g.appendChild(arrow);
          }
        });
        var tlabel=document.createElementNS('http://www.w3.org/2000/svg','text');
        tlabel.setAttribute('x',pos.cx);tlabel.setAttribute('y',pos.y+PIPELINE_H-6);tlabel.setAttribute('text-anchor','middle');
        tlabel.setAttribute('fill','#e6edf3');tlabel.setAttribute('font-size','0.68rem');tlabel.setAttribute('font-weight','600');
        tlabel.textContent=n.label;g.appendChild(tlabel);
      }else{
        var label=document.createElementNS('http://www.w3.org/2000/svg','text');
        label.setAttribute('x',pos.cx);label.setAttribute('y',pos.y+16);label.setAttribute('text-anchor','middle');
        label.setAttribute('class','node-label');label.textContent=n.label;g.appendChild(label);
        var sub=document.createElementNS('http://www.w3.org/2000/svg','text');
        sub.setAttribute('x',pos.cx);sub.setAttribute('y',pos.y+30);sub.setAttribute('text-anchor','middle');
        sub.setAttribute('class','node-sub');sub.textContent=n.sub;g.appendChild(sub);
      }
      if(n.template){
        var tc=TEMPLATES[n.template]?TEMPLATES[n.template].color:'#666';
        var badge=document.createElementNS('http://www.w3.org/2000/svg','rect');
        badge.setAttribute('x',pos.x+w-20);badge.setAttribute('y',pos.y-6);badge.setAttribute('width',26);badge.setAttribute('height',12);
        badge.setAttribute('rx',3);badge.setAttribute('ry',3);badge.setAttribute('fill',tc);badge.setAttribute('opacity','0.8');g.appendChild(badge);
      }
      g.addEventListener('click',function(){onNodeClick(n);});
      g.addEventListener('mouseenter',function(){onNodeHover(n,true);});
      g.addEventListener('mouseleave',function(){onNodeHover(n,false);});
      canvas.appendChild(g);svgNodeGroups[n.id]=g;
    });
  }

  function renderGatewayEntries() {
    GATEWAY_ENTRIES.forEach(function(entry){
      var pos=gwPositions[entry.id];if(!pos)return;
      var g=document.createElementNS('http://www.w3.org/2000/svg','g');
      g.setAttribute('class','gw-entry-group');
      var rect=document.createElementNS('http://www.w3.org/2000/svg','rect');
      rect.setAttribute('x',pos.x);rect.setAttribute('y',pos.y);rect.setAttribute('width',GW_ENTRY_W);rect.setAttribute('height',GW_ENTRY_H);
      rect.setAttribute('class','gw-entry');g.appendChild(rect);
      var label=document.createElementNS('http://www.w3.org/2000/svg','text');
      label.setAttribute('x',pos.cx);label.setAttribute('y',pos.y+17);label.setAttribute('text-anchor','middle');
      label.setAttribute('class','gw-entry-label');label.textContent=entry.label;g.appendChild(label);
      var sub=document.createElementNS('http://www.w3.org/2000/svg','text');
      sub.setAttribute('x',pos.cx);sub.setAttribute('y',pos.y+30);sub.setAttribute('text-anchor','middle');
      sub.setAttribute('class','node-sub');sub.textContent=entry.sub;g.appendChild(sub);
      canvas.appendChild(g);
    });
  }

  function renderEdges() {
    var ce={};
    EDGES.forEach(function(e){if(e.connector)ce[e.from+'|'+e.to]=e;});
    EDGES.forEach(function(e){
      if(e.connector)return;
      var path=document.createElementNS('http://www.w3.org/2000/svg','path');
      var fb=!!e.feedback,ext=!!e.external,zone=!!e.zoneEdge;
      path.setAttribute('class','dag-edge'+(fb?' feedback':'')+(ext?' external':'')+(zone?' zone-edge':''));
      path.setAttribute('marker-end','url(#'+(fb?'arrow-feedback':ext?'arrow-external':'arrow-normal')+')');
      path.setAttribute('data-from',e.from);path.setAttribute('data-to',e.to);canvas.appendChild(path);
      svgEdgeEls[e.from+'|'+e.to]={path:path,isFeedback:fb,isExternal:ext,isZone:zone,connectorEdge:null};
      var ck=e.from+'|'+e.to;if(ce[ck])svgEdgeEls[ck].connectorEdge=ce[ck];
    });
    Object.keys(ce).forEach(function(key){
      var c=ce[key],f=nodePositions[c.from],t=gwPositions[c.to];if(!f||!t)return;
      var midX=(f.cx+t.cx)/2,midY=(f.y+(f.h||NODE_H)/2+t.y+GW_ENTRY_H/2)/2;
      var dot=document.createElementNS('http://www.w3.org/2000/svg','circle');
      dot.setAttribute('cx',midX);dot.setAttribute('cy',midY);dot.setAttribute('r',4);dot.setAttribute('fill','#f0883e');dot.setAttribute('opacity','0.7');
      var dl=document.createElementNS('http://www.w3.org/2000/svg','text');
      dl.setAttribute('x',midX+8);dl.setAttribute('y',midY+3);dl.setAttribute('fill','#f0883e');dl.setAttribute('font-size','0.55rem');dl.setAttribute('font-family','sans-serif');
      dl.textContent=c.connector;
      var dg=document.createElementNS('http://www.w3.org/2000/svg','g');dg.appendChild(dot);dg.appendChild(dl);canvas.appendChild(dg);
      if(svgEdgeEls[key])svgEdgeEls[key].connectorEl=dg;else svgEdgeEls[key]={path:null,isFeedback:false,isExternal:true,isZone:false,connectorEl:dg,connectorEdge:c};
    });
  }

  function renderIsomorphismAnnotation() {
    var rc=nodePositions['recharge-pipeline'],pc=nodePositions['purchase-pipeline'];if(!rc||!pc)return;
    var bracketX=rc.x-22,g=document.createElementNS('http://www.w3.org/2000/svg','g');
    var line=document.createElementNS('http://www.w3.org/2000/svg','line');
    line.setAttribute('x1',bracketX);line.setAttribute('y1',rc.y+PIPELINE_H/2);
    line.setAttribute('x2',bracketX);line.setAttribute('y2',pc.y+PIPELINE_H/2);
    line.setAttribute('stroke','rgba(168,85,247,0.4)');line.setAttribute('stroke-width','1.5');line.setAttribute('stroke-dasharray','6 3');g.appendChild(line);
    var lbl=document.createElementNS('http://www.w3.org/2000/svg','text');
    lbl.setAttribute('x',bracketX-6);lbl.setAttribute('y',(rc.y+pc.y+PIPELINE_H)/2);lbl.setAttribute('text-anchor','end');
    lbl.setAttribute('fill','#a855f7');lbl.setAttribute('font-size','0.55rem');lbl.setAttribute('font-weight','700');
    lbl.textContent='同构：同一组件库，仅参数不同';g.appendChild(lbl);canvas.appendChild(g);
  }

  function updateEdgePaths() {
    Object.keys(svgEdgeEls).forEach(function(key){
      var e=svgEdgeEls[key];if(!e.path)return;
      var parts=key.split('|');e.path.setAttribute('d',getEdgePath(parts[0],parts[1],e.isFeedback,e.isExternal,e.isZone));
    });
  }

  // ======================== INTERACTION ========================
  function onNodeClick(node){showFsmModal(node);showToast('点击节点：'+node.label);}

  function onNodeHover(node, enter){
    var g=svgNodeGroups[node.id];if(!g)return;
    if(enter)g.classList.add('highlight');
    else{if(!activeTemplate||node.template!==activeTemplate)g.classList.remove('highlight');}
  }

  function selectTemplate(tplId){
    if(activeTemplate){
      document.getElementById('tplCard-'+activeTemplate).classList.remove('active');
      Object.keys(svgNodeGroups).forEach(function(nid){svgNodeGroups[nid].classList.remove('dimmed','highlight');});
      Object.keys(svgEdgeEls).forEach(function(key){var e=svgEdgeEls[key];if(e&&e.path)e.path.classList.remove('dimmed');});
      Object.keys(templateRegionEls).forEach(function(tpl){templateRegionEls[tpl].rect.classList.remove('active');});
    }
    if(activeTemplate===tplId){activeTemplate=null;hideCodePanel();return;}
    activeTemplate=tplId;document.getElementById('tplCard-'+tplId).classList.add('active');
    if(templateRegionEls[tplId])templateRegionEls[tplId].rect.classList.add('active');
    Object.keys(svgNodeGroups).forEach(function(nid){
      var g=svgNodeGroups[nid],nt=g.getAttribute('data-template');
      if(nt===tplId){g.classList.remove('dimmed');g.classList.add('highlight');}
      else{g.classList.add('dimmed');g.classList.remove('highlight');}
    });
    var tplNodes=new Set();NODES.filter(function(n){return n.template===tplId;}).forEach(function(n){tplNodes.add(n.id);});
    Object.keys(svgEdgeEls).forEach(function(key){
      var e=svgEdgeEls[key];if(!e||!e.path)return;var parts=key.split('|');
      if(tplNodes.has(parts[0])||tplNodes.has(parts[1])){e.path.classList.remove('dimmed');e.path.classList.add('highlight');e.path.setAttribute('marker-end','url(#arrow-highlight)');}
      else{e.path.classList.add('dimmed');e.path.classList.remove('highlight');e.path.setAttribute('marker-end','url(#arrow-normal)');}
    });
    showCodePanel(tplId);
  }

  function showCodePanel(id){codePanel.classList.add('visible');codeTitle.textContent=TEMPLATES[id]?TEMPLATES[id].name+' ('+TEMPLATES[id].file+')':'层详情';updateCodeContent();}
  function hideCodePanel(){codePanel.classList.remove('visible');}
  function updateCodeContent(){var t=activeTemplate;if(!t)return;var s=CODE_SNIPPETS[codeMode];codeContent.textContent=s&&s[t]?s[t]:'// 选择上方层卡片查看代码';}
  btnScala.addEventListener('click',function(){codeMode='scala';btnScala.classList.add('active');btnJava.classList.remove('active');updateCodeContent();});
  btnJava.addEventListener('click',function(){codeMode='java';btnJava.classList.add('active');btnScala.classList.remove('active');updateCodeContent();});
  ['chain','guard','batch'].forEach(function(id){var c=document.getElementById('tplCard-'+id);if(c)c.addEventListener('click',function(){selectTemplate(id);});});

  // ======================== GATEWAY TOGGLE ========================
  var gwHighlighted=false;
  var btnGwToggle=document.getElementById('btnGwToggle');
  if(btnGwToggle){btnGwToggle.addEventListener('click',function(){
    gwHighlighted=!gwHighlighted;
    document.querySelectorAll('.conn-node rect:first-child').forEach(function(r){r.setAttribute('fill',gwHighlighted?'rgba(240,136,62,0.15)':'rgba(240,136,62,0.08)');r.setAttribute('stroke',gwHighlighted?'#f0883e':'rgba(240,136,62,0.35)');r.setAttribute('stroke-dasharray',gwHighlighted?'none':'4 3');});
    document.querySelectorAll('.gw-entry').forEach(function(e){if(gwHighlighted){e.setAttribute('filter','drop-shadow(0 0 10px rgba(240,136,62,0.7))');e.setAttribute('stroke','#f0883e');e.setAttribute('stroke-width','2.5');}else{e.removeAttribute('filter');e.removeAttribute('stroke');e.removeAttribute('stroke-width');}});
    document.querySelectorAll('.gw-container').forEach(function(c){c.setAttribute('stroke',gwHighlighted?'rgba(240,136,62,0.5)':'rgba(240,136,62,0.15)');c.setAttribute('stroke-width',gwHighlighted?'2':'1.5');c.setAttribute('fill',gwHighlighted?'rgba(240,136,62,0.06)':'rgba(240,136,62,0.03)');});
    Object.keys(svgEdgeEls).forEach(function(k){var e=svgEdgeEls[k];if(e&&e.isExternal&&e.path){if(gwHighlighted){e.path.classList.add('highlight');e.path.setAttribute('stroke','#f0883e');e.path.setAttribute('stroke-width','3');e.path.setAttribute('marker-end','url(#arrow-highlight)');}else{e.path.classList.remove('highlight');e.path.setAttribute('stroke','rgba(240,136,62,0.2)');e.path.setAttribute('stroke-width','1.5');e.path.setAttribute('marker-end','url(#arrow-external)');}}});
    btnGwToggle.style.background=gwHighlighted?'rgba(240,136,62,0.15)':'rgba(240,136,62,0.06)';btnGwToggle.style.borderColor=gwHighlighted?'rgba(240,136,62,0.7)':'rgba(240,136,62,0.35)';btnGwToggle.style.color=gwHighlighted?'#f0883e':'';btnGwToggle.childNodes[1].textContent=gwHighlighted?'外部系统网关 · 已点亮':'点亮外部系统网关';
  });}

  // ======================== EVENT FLOW ANIMATION ========================
  var eventAnimActive = true;
  var eventParticles = [];
  var animCycleStart = 0; // reference time for cycle phase

  // Flow sequences — how domain events propagate through the DAG
  var EVENT_FLOWS = [
    // Orchestration layer: cron trigger → batch dispatch
    { keys: ['cron|job-actor','job-actor|pre-batch','pre-batch|batch-master','batch-master|batch-worker'],
      color: '#14b8a6', count: 3, stagger: 180, delay: 0, dur: 1.0 },
    // Pipeline dispatch: batch-worker → both pipelines
    { keys: ['batch-worker|recharge-pipeline','batch-worker|purchase-pipeline'],
      color: '#3b82f6', count: 2, stagger: 220, delay: 1600, dur: 0.9 },
    // Cross-zone: pipelines → connectors
    { keys: ['recharge-pipeline|conn-sftp','recharge-pipeline|conn-http-xml','purchase-pipeline|conn-sftp','purchase-pipeline|conn-http-xml'],
      color: '#f0883e', count: 2, stagger: 200, delay: 2600, dur: 1.1 },
    // External: connectors → gateway systems
    { keys: ['conn-sftp|gw-sftp','conn-http-xml|gw-core'],
      color: '#f59e0b', count: 2, stagger: 200, delay: 3600, dur: 1.0 },
    // SMS notification: pipelines → notification
    { keys: ['recharge-pipeline|sms-success','recharge-pipeline|sms-failure','purchase-pipeline|sms-success','purchase-pipeline|sms-failure'],
      color: '#a855f7', count: 2, stagger: 200, delay: 3200, dur: 0.8 },
    // Resource unlock: failure → quota release cascade
    { keys: ['sms-failure|quota-release','quota-release|quota-cascade'],
      color: '#f59e0b', count: 2, stagger: 200, delay: 4400, dur: 0.9 },
    // Compensation loop: re-batch → worker (feedback, always running at low opacity)
    { keys: ['re-batch|batch-worker'],
      color: '#f85149', count: 1, stagger: 0, delay: 800, dur: 2.0 }
  ];

  var CYCLE_TOTAL = 5800; // ms — one full event propagation cycle, then repeat

  function makeParticle(color) {
    var c = document.createElementNS('http://www.w3.org/2000/svg','circle');
    c.setAttribute('r','3.5');c.setAttribute('fill',color);c.setAttribute('opacity','0.88');
    c.setAttribute('style','filter:url(#eventGlow)');c.setAttribute('display','none');
    return c;
  }

  function makeAnimMotion(pathD, dur, begin) {
    var a = document.createElementNS('http://www.w3.org/2000/svg','animateMotion');
    a.setAttribute('dur',dur+'s');a.setAttribute('repeatCount','indefinite');
    a.setAttribute('begin',(begin/1000).toFixed(1)+'s');a.setAttribute('path',pathD);
    return a;
  }

  function startEventAnimation() {
    stopEventAnimation();
    eventAnimActive = true;
    animCycleStart = Date.now();
    EVENT_FLOWS.forEach(function(flow) {
      flow.keys.forEach(function(edgeKey) {
        var edgeEl = svgEdgeEls[edgeKey];
        if (!edgeEl || !edgeEl.path) return;
        var d = edgeEl.path.getAttribute('d');
        if (!d) return;
        for (var i = 0; i < flow.count; i++) {
          var p = makeParticle(flow.color);
          var anim = makeAnimMotion(d, flow.dur + i * 0.15, flow.delay + i * flow.stagger);
          p.appendChild(anim);
          canvas.appendChild(p);
          eventParticles.push(p);
          var showStart = flow.delay + i * flow.stagger;
          var showEnd = flow.delay + i * flow.stagger + (flow.count * flow.stagger) + flow.dur * 1200;
          scheduleVisibility(p, showStart, showEnd);
        }
      });
    });
    updateEventAnimButton();
  }

  function stopEventAnimation() {
    eventAnimActive = false;
    eventParticles.forEach(function(p) {
      if (p._visInterval) clearInterval(p._visInterval);
      if (p.parentNode) p.parentNode.removeChild(p);
    });
    eventParticles = [];
    updateEventAnimButton();
  }

  function scheduleVisibility(particle, showStart, showEnd) {
    var lastVisible = false;
    particle._visInterval = setInterval(function() {
      var t = (Date.now() - animCycleStart) % CYCLE_TOTAL;
      var show0 = showStart % CYCLE_TOTAL;
      var show1 = showEnd % CYCLE_TOTAL;
      var visible;
      if (show0 <= show1) visible = t >= show0 && t <= show1;
      else visible = t >= show0 || t <= show1;
      if (visible !== lastVisible) {
        particle.setAttribute('display', visible ? '' : 'none');
        lastVisible = visible;
      }
    }, 200);
  }

  function toggleEventAnimation() {
    if (eventAnimActive) stopEventAnimation();
    else startEventAnimation();
  }

  function updateEventAnimButton() {
    var btn = document.getElementById('btnEventAnim');
    var lbl = document.getElementById('btnEventAnimLabel');
    if (!btn || !lbl) return;
    if (eventAnimActive) {
      btn.style.background = 'rgba(88,166,255,0.15)'; btn.style.borderColor = 'rgba(88,166,255,0.5)'; btn.style.color = '#58a6ff';
      lbl.textContent = '暂停事件流';
    } else {
      btn.style.background = 'rgba(88,166,255,0.06)'; btn.style.borderColor = 'rgba(88,166,255,0.25)'; btn.style.color = '';
      lbl.textContent = '播放事件流';
    }
  }

  var btnEventAnim = document.getElementById('btnEventAnim');
  if (btnEventAnim) btnEventAnim.addEventListener('click', toggleEventAnimation);
  function showFsmModal(node){
    var tpl=node.template?(TEMPLATES[node.template]?TEMPLATES[node.template].name:''):'';
    fsmModalTitle.textContent=node.label;fsmModalSubtitle.textContent=(tpl?'所属层: '+tpl+' | ':'')+'EntityKey: '+node.id+' | Level: '+(node.level||'Conn');
    renderFsmDiagram(node);renderFsmInfo(node);fsmModalOverlay.classList.add('visible');
  }
  function closeFsmModal(){fsmModalOverlay.classList.remove('visible');}
  window.closeFsmModal=closeFsmModal;
  document.getElementById('fsmModalOverlay').addEventListener('click',function(e){if(e.target===fsmModalOverlay)closeFsmModal();});
  document.addEventListener('keydown',function(e){if(e.key==='Escape')closeFsmModal();});

  function renderFsmDiagram(node){
    while(fsmSvg.firstChild)fsmSvg.removeChild(fsmSvg.firstChild);
    var w=fsmDiagramArea.clientWidth,h=fsmDiagramArea.clientHeight;fsmSvg.setAttribute('viewBox','0 0 '+w+' '+h);
    var SW=100,SH=36;
    var states=[{id:'idle',label:'Idle',x:w*0.08,y:h*0.4},{id:'running',label:'Processing',x:w*0.35,y:h*0.2},{id:'success',label:'Success',x:w*0.65,y:h*0.1},{id:'failure',label:'Failure',x:w*0.65,y:h*0.5},{id:'suspicious',label:'Suspicious',x:w*0.65,y:h*0.75}];
    [{from:'idle',to:'running'},{from:'running',to:'success',label:'OK',color:'#3fb950'},{from:'running',to:'failure',label:'FAIL',color:'#f85149'},{from:'running',to:'suspicious',label:'UNCERTAIN',color:'#f59e0b'}].forEach(function(e){
      var f=states.find(function(s){return s.id===e.from;}),t=states.find(function(s){return s.id===e.to;});if(!f||!t)return;
      var path=document.createElementNS('http://www.w3.org/2000/svg','path');
      path.setAttribute('d','M '+(f.x+SW)+' '+(f.y+SH/2)+' C '+((f.x+SW+t.x)/2)+' '+(f.y+SH/2)+', '+((f.x+SW+t.x)/2)+' '+(t.y+SH/2)+', '+t.x+' '+(t.y+SH/2));
      path.setAttribute('fill','none');path.setAttribute('stroke',e.color||'rgba(255,255,255,0.2)');path.setAttribute('stroke-width','2');fsmSvg.appendChild(path);
      if(e.label){var lbl=document.createElementNS('http://www.w3.org/2000/svg','text');lbl.setAttribute('x',(f.x+SW+t.x)/2);lbl.setAttribute('y',(f.y+t.y+SH)/2-4);lbl.setAttribute('text-anchor','middle');lbl.setAttribute('fill',e.color);lbl.setAttribute('font-size','0.6rem');lbl.textContent=e.label;fsmSvg.appendChild(lbl);}
    });
    states.forEach(function(s){
      var g=document.createElementNS('http://www.w3.org/2000/svg','g'),rect=document.createElementNS('http://www.w3.org/2000/svg','rect');
      rect.setAttribute('x',s.x);rect.setAttribute('y',s.y);rect.setAttribute('width',SW);rect.setAttribute('height',SH);rect.setAttribute('rx',8);rect.setAttribute('ry',8);
      rect.setAttribute('fill','rgba(22,27,34,0.92)');rect.setAttribute('stroke',s.id==='running'?'rgba(59,130,246,0.5)':'rgba(255,255,255,0.15)');rect.setAttribute('stroke-width','1.5');g.appendChild(rect);
      var txt=document.createElementNS('http://www.w3.org/2000/svg','text');txt.setAttribute('x',s.x+SW/2);txt.setAttribute('y',s.y+SH/2+5);txt.setAttribute('text-anchor','middle');txt.setAttribute('fill','#e6edf3');txt.setAttribute('font-size','0.72rem');txt.setAttribute('font-weight','600');txt.textContent=s.label;g.appendChild(txt);fsmSvg.appendChild(g);
    });
    var title=document.createElementNS('http://www.w3.org/2000/svg','text');title.setAttribute('x',20);title.setAttribute('y',28);title.setAttribute('fill','#8b949e');title.setAttribute('font-size','0.75rem');title.setAttribute('font-weight','600');title.textContent='FSM 状态转换图 — '+(node.sub||node.label);fsmSvg.appendChild(title);
  }

  function renderFsmInfo(node){
    var tpl=node.template?TEMPLATES[node.template]:null;
    var pl={chain:'L3 业务链路层',guard:'L2/L4 资源解锁',batch:'L0–L2 编排调度层'};
    var pattern=tpl?pl[node.template]||'-':'-';
    fsmInfoPanel.innerHTML='<h4>节点信息</h4><div class="fsm-meta"><div>EntityKey: '+node.id+'</div><div>层级: Level '+(node.level||'Conn')+'</div><div>所属层: '+pattern+'</div><div>驱动组件: '+(tpl?tpl.name:'无')+'</div>'+(tpl?'<div>组件源文件: app/net/imadz/m25/component/'+tpl.file+'</div>':'')+(tpl?'<div>组件代码行数: '+tpl.lines+' 行</div>':'')+'</div><h4 style="margin-top:12px;">M2.5+ 关键区别</h4><div class="fsm-meta">'+(tpl?'<div>✓ 标准组件实例化，不生成 FSM 代码</div><div>✓ 修改组件一处 → 所有链路自动继承修复</div><div>✓ 参数由编译器验证完整性</div>':'<div>连接器节点 — 技术基础设施中间层</div>')+'</div>';
  }

  function showToast(msg){toast.textContent=msg;toast.classList.add('show');clearTimeout(toast._timeout);toast._timeout=setTimeout(function(){toast.classList.remove('show');},2000);}

  function init(){renderAll();setTimeout(function(){showToast('点击左侧层卡片查看详情与代码');},800);}
  var resizeTimeout;window.addEventListener('resize',function(){clearTimeout(resizeTimeout);resizeTimeout=setTimeout(function(){var was=activeTemplate,wasAnim=eventAnimActive;activeTemplate=null;renderAll();if(!wasAnim)stopEventAnimation();if(was)setTimeout(function(){selectTemplate(was);},50);},250);});
  init();
})();

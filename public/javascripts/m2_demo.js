/**
 * M2 DAG Execution Engine — 薪资存入完整 23 FSM 场景
 *
 * 23 Akka Persistent FSM + 出站网关。同层节点上下布局，边严格向下。
 * 5 阶段动画：故障注入、自愈闭环、动态批次、外部容错。
 */
(function () {
  'use strict';

  // ======================== DAG DATA ========================
  // 23 FSMs internally + 1 Outbound Gateway with 4 protocol entries

  var NODES = [
    // ===== Level 0: 触发层 (1) =====
    { id: 'cron', label: '定时调度', sub: 'Quartz Cron', level: 0, type: 'orchestrator', col: 0, row: 0 },

    // ===== Level 1: 编排层 (3, 2 sub-rows: JobActor above, PreBatch + ReBatch below) =====
    { id: 'job-actor', label: '作业编排器', sub: 'JobActor · 12 states', level: 1, type: 'orchestrator', col: 0, row: 0 },
    { id: 'pre-batch', label: '预批处理器', sub: 'PreBatchActor · 9 states', level: 1, type: 'orchestrator', col: 0, row: 1 },
    { id: 're-batch',  label: '补偿扫描器', sub: 'ReBatchActor · 死循环捞取', level: 1, type: 'compensator', col: 1, row: 1 },

    // ===== Level 2: 执行与资源层 (6, 3 sub-rows, BatchMaster+Worker center, QuotaReserve left) =====
    // Row 0: QuotaReserve (left), BatchMaster (center), BatchItemCreation (right)
    { id: 'batch-master',  label: '批次分发器', sub: 'BatchMaster', level: 2, type: 'orchestrator', col: 1, row: 0 },
    { id: 'quota-reserve', label: '用户额度预留', sub: 'UserQuotaReservation', level: 2, type: 'protector', col: 0, row: 0 },
    { id: 'batch-item',    label: '明细创建器', sub: 'BatchItemCreationActor', level: 2, type: 'orchestrator', col: 2, row: 0 },
    // Row 1: BatchWorker centered below BatchMaster
    { id: 'batch-worker',  label: '批次工人 ×N', sub: 'BatchWorker', level: 2, type: 'orchestrator', col: 1, row: 1 },
    // Row 2: quota cleanup
    { id: 'quota-release-u', label: '用户额度释放', sub: 'UserQuotaReleaseActor', level: 2, type: 'protector', col: 0, row: 2 },
    { id: 'quota-release-t', label: '总额度释放', sub: 'TotalQuotaReleaseActor', level: 2, type: 'protector', col: 2, row: 2 },

    // ===== Level 3: 业务链路层 (12, 2 columns × 6 rows, vertical flow) =====
    // Recharge chain — left column
    { id: 'recharge-req',     label: '充值请求', sub: 'RechargeRequestActor', level: 3, chain: 'recharge', row: 0 },
    { id: 'recharge-resp',    label: '充值响应', sub: 'RechargeResponseActor', level: 3, chain: 'recharge', row: 1 },
    { id: 'recharge-reconf',  label: '充值重确认', sub: 'RechargeReconfirmActor', level: 3, chain: 'recharge', row: 2 },
    { id: 'recharge-success', label: '充值成功', sub: 'RechargeSuccessActor', level: 3, chain: 'recharge', row: 3 },
    { id: 'recharge-failure', label: '充值失败', sub: 'RechargeFailureActor', level: 3, chain: 'recharge', row: 4 },
    { id: 'recharge-p2b',     label: '充值P2B通知', sub: 'RechargeP2BNotifyActor', level: 3, chain: 'recharge', row: 5 },
    // Purchase chain — right column
    { id: 'purchase-req',     label: '申购请求', sub: 'PurchaseRequestActor', level: 3, chain: 'purchase', row: 0 },
    { id: 'purchase-resp',    label: '申购响应', sub: 'PurchaseResponseActor', level: 3, chain: 'purchase', row: 1 },
    { id: 'purchase-reconf',  label: '申购重确认', sub: 'PurchaseReconfirmActor', level: 3, chain: 'purchase', row: 2 },
    { id: 'purchase-success', label: '申购成功', sub: 'PurchaseSuccessActor', level: 3, chain: 'purchase', row: 3 },
    { id: 'purchase-failure', label: '申购失败', sub: 'PurchaseFailureActor', level: 3, chain: 'purchase', row: 4 },
    { id: 'purchase-p2b',     label: '申购P2B通知', sub: 'PurchaseP2BNotifyActor', level: 3, chain: 'purchase', row: 5 },

    // ===== Level 4: 通知层 (2) =====
    { id: 'sms-service',  label: '短信服务', sub: 'SmsService · 合规窗口', level: 4, type: 'support', col: 0, row: 0 },
    { id: 'reminder-sms', label: '提醒短信', sub: 'ReminderSmsActor', level: 4, type: 'support', col: 1, row: 0 }
  ];

  // Outbound Gateway protocol entries (rendered inside gateway container)
  var GATEWAY_ENTRIES = [
    { id: 'gw-sftp',   label: '银行 SFTP', sub: '文件上传 / 轮询下载' },
    { id: 'gw-core',   label: '核心账户 API', sub: '余额 / 交易状态查证' },
    { id: 'gw-p2b',    label: 'P2B 理财平台', sub: '产品申购 / 回盘解析' },
    { id: 'gw-sms',    label: '短信通道', sub: '合规窗口下发' }
  ];

  var EDGES = [
    // ===== Level 0→1 =====
    { from: 'cron', to: 'job-actor' },
    // ===== Level 1 internal =====
    { from: 'job-actor', to: 'pre-batch' },
    { from: 'job-actor', to: 're-batch' },
    // ===== Level 1→2 =====
    { from: 'pre-batch', to: 'batch-master' },
    { from: 'pre-batch', to: 'batch-item' },
    { from: 'pre-batch', to: 'quota-reserve' },
    // ===== Level 2 internal (row 0→row 1) =====
    { from: 'batch-master', to: 'batch-worker' },
    { from: 'batch-item',   to: 'batch-worker' },
    { from: 'quota-reserve', to: 'batch-worker' },
    // ===== Level 2→3 (dispatch to chain heads) =====
    { from: 'batch-worker',  to: 'recharge-req' },
    { from: 'batch-worker',  to: 'purchase-req' },
    { from: 'quota-reserve', to: 'recharge-req' },
    { from: 'quota-reserve', to: 'purchase-req' },
    // ===== Level 3 vertical: Recharge chain (row 0→1→2, 1→3, 1→4, 2→3, 2→4, 3→5) =====
    { from: 'recharge-req',  to: 'recharge-resp' },
    { from: 'recharge-resp', to: 'recharge-reconf' },
    { from: 'recharge-resp', to: 'recharge-success' },
    { from: 'recharge-resp', to: 'recharge-failure' },
    { from: 'recharge-reconf', to: 'recharge-success' },
    { from: 'recharge-reconf', to: 'recharge-failure' },
    { from: 'recharge-success', to: 'recharge-p2b' },
    // ===== Level 3 vertical: Purchase chain =====
    { from: 'purchase-req',  to: 'purchase-resp' },
    { from: 'purchase-resp', to: 'purchase-reconf' },
    { from: 'purchase-resp', to: 'purchase-success' },
    { from: 'purchase-resp', to: 'purchase-failure' },
    { from: 'purchase-reconf', to: 'purchase-success' },
    { from: 'purchase-reconf', to: 'purchase-failure' },
    { from: 'purchase-success', to: 'purchase-p2b' },
    // ===== Level 3→4 (notification) =====
    { from: 'recharge-success', to: 'sms-service' },
    { from: 'purchase-success', to: 'sms-service' },
    { from: 'recharge-failure', to: 'reminder-sms' },
    { from: 'purchase-failure', to: 'reminder-sms' },
    // ===== Failure → quota release =====
    { from: 'recharge-failure', to: 'quota-release-u' },
    { from: 'purchase-failure', to: 'quota-release-u' },
    { from: 'quota-release-u',  to: 'quota-release-t' },
    // ===== Feedback: ReBatchActor ⇢ Worker (re-inject) =====
    { from: 're-batch', to: 'batch-worker', feedback: true },

    // ===== DAG → Outbound Gateway (external edges) =====
    { from: 'recharge-req',  to: 'gw-sftp', external: true },
    { from: 'recharge-resp', to: 'gw-sftp', external: true },
    { from: 'recharge-reconf', to: 'gw-core', external: true },
    { from: 'recharge-p2b', to: 'gw-p2b', external: true },
    { from: 'purchase-req',  to: 'gw-sftp', external: true },
    { from: 'purchase-resp', to: 'gw-sftp', external: true },
    { from: 'purchase-p2b', to: 'gw-p2b', external: true },
    { from: 'sms-service',  to: 'gw-sms', external: true },
    { from: 'reminder-sms', to: 'gw-sms', external: true }
  ];

  var PHASES = [
    { id: 1, key: 'trigger', color: '#38bdf8', pattern: 'orchestrator',
      title: '阶段 1：定时触发 → 作业创建',
      desc: 'Quartz 定时器触发 JobActor，创建作业、FTP 目录、发送提醒。',
      insight: '声明式入口：DAG 由事件激活，而非存储过程调用链。23 个 FSM 的协作由引擎调度——你只需声明触发条件。',
      contrast: '传统：存储过程 CALL 下一个，名字硬编码。改一环 = 全链路回归测试。',
      highlight: ['cron', 'job-actor', 'pre-batch', 're-batch'],
      paths: [['cron','job-actor'], ['job-actor','pre-batch'], ['job-actor','re-batch']],
      events: ['JobCreated', 'FtpDirectoryCreated', 'ReminderSent'] },
    { id: 2, key: 'preprocess', color: '#f97316', pattern: 'orchestrator',
      title: '阶段 2：数据预处理 → 额度预留',
      desc: 'PreBatchActor 读取薪资计划。BatchItemCreationActor 拆分微批次。三个额度守卫预留总额度与个人额度。',
      insight: '节点独立性：每个 DAG 节点独立版本化、独立测试、独立部署。6 个执行层节点各自独立——改 PreBatchActor 不影响 Worker，改预留不影响释放。爆炸半径限制在单节点内。',
      contrast: '对比西门子 1,580,000 行存储过程：每行共享事务作用域，改一行可能破坏全局。',
      highlight: ['pre-batch', 'batch-master', 'batch-item', 'quota-reserve', 'quota-release-u', 'quota-release-t'],
      paths: [['pre-batch','batch-master'], ['pre-batch','batch-item'], ['pre-batch','quota-reserve']],
      events: ['PlanAligned', 'BatchItemsCreated', 'QuotaReserved'] },
    { id: 3, key: 'guard', color: '#f59e0b', pattern: 'protector',
      title: '阶段 3：额度冻结 → 超时不变量守卫',
      desc: 'UserQuotaReservationActor 为每位员工月度额度加锁。超时不变量：Worker 崩溃或超时 → 锁自动释放。',
      insight: 'Protector 弹性模式：DAG 声明不变量——"锁 30 秒后释放"——引擎强制执行。清理逻辑从"人的记忆"变成"引擎的保证"。每个资源锁自带自毁开关。',
      contrast: '传统：try-catch-finally 散落各处。少写一个 = 死锁。DBA 手工解扣，冻结数小时。',
      highlight: ['quota-reserve', 'batch-worker'],
      paths: [['quota-reserve','batch-worker']],
      events: ['QuotaFrozen(per user)', 'TimeoutInvariantSet'] },
    { id: 4, key: 'execute', color: '#22c55e', pattern: 'communicator',
      title: '阶段 4：并发执行 → 流批一体 + 出站网关通信',
      desc: 'BatchMaster 扇出至 N 个 Worker。每个 Worker 分叉到充值链路（6 FSM 上下串联）和申购链路（同构）。通过出站网关与银行 SFTP、核心账户 API、P2B 平台通信。Communicator 模式：ReceiveTimeout 驱动主动轮询——不阻塞等待。',
      insight: '流批一体 + Communicator + 出站网关：DAG 与外部世界的所有通信通过统一的出站网关——SFTP/API/P2B/短信四种协议被封装在网关内部。Communicator 把外部不确定性封装在可重试的异步边界内。网关对外部系统的不可用进行缓冲——外部抖动不会拖垮 DAG 内部。',
      contrast: '传统：同步 HTTP + 固定超时。外部系统抖动 = 整个流程卡死。流/批两套代码。出站网关把"与外部系统通信"从遍布各处的集成代码变成了一个明确的架构边界。',
      highlight: ['batch-master', 'batch-worker', 'recharge-req', 'recharge-resp', 'recharge-reconf', 'recharge-success', 'purchase-req', 'purchase-resp', 'purchase-reconf', 'purchase-success'],
      paths: [['batch-master','batch-worker'], ['batch-worker','recharge-req'], ['batch-worker','purchase-req'],
              ['recharge-req','recharge-resp'], ['recharge-resp','recharge-success'],
              ['purchase-req','purchase-resp'], ['purchase-resp','purchase-success'],
              ['recharge-req','gw-sftp'], ['purchase-req','gw-sftp']],
      events: ['BatchDispatched(×N)', 'SFTPFileUploaded', 'BankPollingStarted', 'ReconfirmTriggered', 'ResponsePolled'] },
    { id: 5, key: 'compensate', color: '#ef4444', pattern: 'compensator',
      title: '阶段 5：通信层 → 成功/故障路由 + 补偿闭环',
      desc: '通信层演示两条路由：✅ 成功路径 — recharge/purchase-success → SmsService → 出站网关短信通道，通知用户。✕ 故障路径 — recharge/purchase-failure → QuotaRelease → 额度清理，ReminderSms 发送告警。♻ 补偿闭环 — ReBatchActor 扫描失败批次 → 重新注入 Worker Pool。',
      insight: '通信层是 DAG 与外部世界的边界。成功消息和故障消息在此层分流——成功走通知通道（绿），失败走补偿通道（红）。出站网关统一对外通信（SFTP/API/P2B/短信），隔离外部不确定性。Compensator 确保故障闭环——只要补偿器在运行，没有失败会被遗漏。',
      contrast: '传统：成功和失败的处理逻辑混在一起，缺少明确的通信边界。外部系统调用散落各处——换个短信通道要改 10 个文件。M2 的通信层把所有外部通信集中到出站网关，成功/失败路径在 DAG 中显式声明。',
      highlight: ['re-batch', 'batch-worker', 'quota-release-u', 'quota-release-t', 'sms-service', 'reminder-sms', 'recharge-failure', 'purchase-failure', 'recharge-success', 'purchase-success'],
      // Success paths (green), failure paths (red), recovery (blue)
      successPaths: [['recharge-success','sms-service'], ['purchase-success','sms-service'], ['sms-service','gw-sms']],
      failPaths: [['recharge-failure','quota-release-u'], ['purchase-failure','quota-release-u'], ['quota-release-u','quota-release-t'], ['recharge-failure','reminder-sms'], ['purchase-failure','reminder-sms'], ['reminder-sms','gw-sms']],
      recoverPaths: [['re-batch','batch-worker']],
      successEvents: ['RechargeOK→Notify', 'PurchaseOK→Notify', 'SMS Sent✓'],
      failEvents: ['RechargeFAIL→Release', 'PurchaseFAIL→Release', 'QuotaReleased', 'Alert SMS!'],
      recoverEvents: ['ReInject→Recovered'] }
  ];

  var PATTERN_LABELS = { orchestrator: '编排器', protector: '保护器', communicator: '通信器', compensator: '补偿器', support: '辅助' };
  var PATTERN_COLORS = { orchestrator: '#3b82f6', protector: '#f59e0b', communicator: '#a855f7', compensator: '#ef4444', support: '#14b8a6' };

  // ======================== DOM REFS ========================
  var canvas = document.getElementById('dagCanvas');
  var vizArea = document.getElementById('vizArea');
  var logContainer = document.getElementById('logContainer');
  var infoTitle = document.getElementById('infoTitle'), infoDesc = document.getElementById('infoDesc'), infoMeta = document.getElementById('infoMeta');
  var jCount = document.getElementById('jCount'), toast = document.getElementById('toast');
  var m2InsightBox = document.getElementById('m2InsightBox'), m2InsightText = document.getElementById('m2InsightText');
  var m2ContrastBox = document.getElementById('m2ContrastBox'), m2ContrastText = document.getElementById('m2ContrastText');
  var phaseBtns = []; for (var i = 1; i <= 5; i++) phaseBtns[i] = document.getElementById('phaseBtn' + i);

  // ======================== STATE ========================
  var currentPhase = 0, isAnimating = false, eventCount = 0;
  var completedPhases = {}, nodePositions = {}, svgEdges = {}, svgNodeGroups = {};
  var gwPositions = {}; // gateway entry positions

  // ======================== LAYOUT ========================
  var NODE_W = 118, NODE_H = 40;
  var GW_CONTAINER_W = 152, GW_ENTRY_W = 138, GW_ENTRY_H = 38;
  var GW_PAD_TOP = 48, GW_ENTRY_GAP = 6;

  function layoutNodes() {
    var w = vizArea.clientWidth, h = vizArea.clientHeight;
    var gwX = w - GW_CONTAINER_W - 12;
    var innerW = gwX - 20;
    var usableH = h - 24 - 8;

    // Weight-based height allocation: L0(5) L1(12) L2(18) L3(20) L4(8) = 63
    var weights = [5, 12, 18, 20, 8];
    var sumW = 63;
    var LEVEL_GAP = 14; // gap between levels
    var totalGap = LEVEL_GAP * 4; // 4 gaps between 5 levels
    var usableForLevels = usableH - totalGap;
    var lvlY = [24];
    for (var lv = 1; lv < 5; lv++) {
      lvlY.push(lvlY[lv - 1] + usableForLevels * weights[lv - 1] / sumW + LEVEL_GAP);
    }
    var l2Height = usableForLevels * weights[2] / sumW;
    var l3Height = usableForLevels * weights[3] / sumW;
    var l4Height = usableForLevels * weights[4] / sumW;

    // Level 0: 1 node centered
    placeRow(NODES.filter(function (n) { return n.level === 0; }), lvlY[0], 1, innerW);

    // Level 1: 2 sub-rows — JobActor above, PreBatch + ReBatch below
    var l1Height = usableForLevels * weights[1] / sumW;
    var l1r0 = NODES.filter(function (n) { return n.level === 1 && n.row === 0; }); // 1 node: JobActor
    var l1r1 = NODES.filter(function (n) { return n.level === 1 && n.row === 1; }); // 2 nodes: PreBatch, ReBatch
    placeRow(l1r0, lvlY[1], 1, innerW);
    placeRow(l1r1, lvlY[1] + l1Height - NODE_H - 4, 2, innerW);

    // Level 2: 3 sub-rows with doubled spacing
    var l2r0 = NODES.filter(function (n) { return n.level === 2 && n.row === 0; });
    var l2r1 = NODES.filter(function (n) { return n.level === 2 && n.row === 1; });
    var l2r2 = NODES.filter(function (n) { return n.level === 2 && n.row === 2; });
    var l2gap = Math.max((l2Height - 3 * NODE_H) / 4, 6); // 4 gaps for 3 rows
    var l2r0y = lvlY[2] + l2gap;
    var l2r1y = l2r0y + NODE_H + l2gap * 2;
    var l2r2y = l2r1y + NODE_H + l2gap * 2;
    placeRow(l2r0, l2r0y, 3, innerW);
    placeRow(l2r1, l2r1y, 3, innerW);
    placeRow(l2r2, l2r2y, 2, innerW);

    // Level 3: 2 vertical columns (recharge left, purchase right)
    placeColumn(NODES.filter(function (n) { return n.chain === 'recharge'; }), lvlY[3], l3Height, NODE_H, innerW * 0.33, innerW);
    placeColumn(NODES.filter(function (n) { return n.chain === 'purchase'; }), lvlY[3], l3Height, NODE_H, innerW * 0.67, innerW);

    // Level 4: 2 internal nodes + gateway on the right at same height
    placeRow(NODES.filter(function (n) { return n.level === 4; }), lvlY[4], 2, innerW);

    // ===== Gateway at Level 4 height (same vertical position) =====
    var gwTop = lvlY[4] - 8;
    var gwHeight = l4Height + 16;
    GATEWAY_ENTRIES.forEach(function (entry, idx) {
      var ey = gwTop + GW_PAD_TOP + idx * (GW_ENTRY_H + GW_ENTRY_GAP);
      var ex = gwX + (GW_CONTAINER_W - GW_ENTRY_W) / 2;
      gwPositions[entry.id] = { x: ex, y: ey, cx: ex + GW_ENTRY_W / 2, cy: ey + GW_ENTRY_H / 2, left: ex, right: ex + GW_ENTRY_W, bottom: ey + GW_ENTRY_H };
    });
    gwPositions._container = { x: gwX, y: gwTop, w: GW_CONTAINER_W, h: gwHeight };

    // Set SVG canvas height to fit all content
    var maxY = gwTop + gwHeight + 16;
    canvas.style.height = maxY + 'px';
    canvas.setAttribute('viewBox', '0 0 ' + w + ' ' + maxY);
  }

  function placeRow(nodes, y, cols, innerW) {
    var count = nodes.length;
    nodes.forEach(function (n, idx) {
      var col = n.col !== undefined ? n.col : idx;
      var spacing = innerW / (cols + 1);
      var x = spacing * (col + 1) - NODE_W / 2;
      nodePositions[n.id] = { x: x, y: y, cx: x + NODE_W / 2, cy: y + NODE_H / 2, right: x + NODE_W, bottom: y + NODE_H };
    });
  }

  function placeColumn(nodes, topY, totalH, nodeH, colCenter, innerW) {
    var count = nodes.length;
    var totalNodesH = count * nodeH + (count - 1) * 6;
    var startY = topY + (totalH - totalNodesH) / 2;
    nodes.forEach(function (n) {
      var y = startY + n.row * (nodeH + 6);
      var x = colCenter - NODE_W / 2;
      nodePositions[n.id] = { x: x, y: y, cx: x + NODE_W / 2, cy: y + NODE_H / 2, right: x + NODE_W, bottom: y + NODE_H, top: y };
    });
  }

  function getEdgePath(fromId, toId, isFeedback, isExternal) {
    var f = nodePositions[fromId];
    var t = isExternal ? gwPositions[toId] : nodePositions[toId];
    if (!f || !t) return '';

    if (isFeedback) {
      var bulge = vizArea.clientWidth - 20;
      return 'M ' + f.right + ' ' + (f.y + NODE_H / 2) + ' ' +
             'C ' + bulge + ' ' + (f.y + NODE_H / 2) + ', ' +
             bulge + ' ' + (t.y + NODE_H / 2) + ', ' +
             t.right + ' ' + (t.y + NODE_H / 2);
    }

    if (isExternal) {
      // From DAG node right edge → gateway entry left edge
      var midX = (f.right + t.left) / 2;
      return 'M ' + f.right + ' ' + (f.y + NODE_H / 2) + ' ' +
             'C ' + midX + ' ' + (f.y + NODE_H / 2) + ', ' +
             midX + ' ' + (t.y + GW_ENTRY_H / 2) + ', ' +
             t.left + ' ' + (t.y + GW_ENTRY_H / 2);
    }

    // Get target node dimensions
    var tH = NODE_H;
    var fromBot = f.bottom, toTop = t.y;

    // Normal downward: source bottom → target top
    var dx = t.cx - f.cx;
    var curve = Math.max(Math.abs(dx) * 0.4, 30);
    return 'M ' + f.cx + ' ' + fromBot + ' ' +
           'C ' + f.cx + ' ' + (fromBot + curve) + ', ' +
           t.cx + ' ' + (toTop - curve) + ', ' +
           t.cx + ' ' + toTop;
  }

  // ======================== RENDER ========================
  function renderAll() {
    layoutNodes();
    while (canvas.firstChild) canvas.removeChild(canvas.firstChild);
    svgEdges = {}; svgNodeGroups = {};
    renderLevelLabels();
    renderGatewayContainer();
    renderEdges();
    renderInternalNodes();
    renderGatewayEntries();
    updateAllEdgePaths();
  }

  function renderLevelLabels() {
    var labels = ['触发层', '编排层', '执行与资源层', '业务链路层 (2 链 × 6 FSM)', '通信层'];
    var h = vizArea.clientHeight;
    var usableH = h - 24 - 8;
    var slotH = usableH / 6;
    var yPos = [24, 24 + slotH, 24 + 2 * slotH, 24 + 3 * slotH, 24 + 4 * slotH];
    for (var lv = 0; lv <= 4; lv++) {
      var el = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      el.setAttribute('class', 'level-label');
      el.setAttribute('x', 6); el.setAttribute('y', yPos[lv] + 8);
      el.textContent = 'L' + lv + ' ' + labels[lv];
      canvas.appendChild(el);
    }
  }

  function renderGatewayContainer() {
    var c = gwPositions._container;
    var g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    // Container rect
    var rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', c.x); rect.setAttribute('y', c.y);
    rect.setAttribute('width', c.w); rect.setAttribute('height', c.h);
    rect.setAttribute('rx', 8); rect.setAttribute('ry', 8);
    rect.setAttribute('fill', 'rgba(240,136,62,0.04)');
    rect.setAttribute('stroke', 'rgba(240,136,62,0.3)');
    rect.setAttribute('stroke-width', 1.5);
    g.appendChild(rect);
    // Title
    var title = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    title.setAttribute('x', c.x + c.w / 2); title.setAttribute('y', c.y + 22);
    title.setAttribute('text-anchor', 'middle');
    title.setAttribute('fill', '#f0883e');
    title.setAttribute('font-size', '0.68rem'); title.setAttribute('font-weight', '700');
    title.setAttribute('font-family', '-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif');
    title.textContent = '出站网关';
    g.appendChild(title);
    // Subtitle
    var sub = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    sub.setAttribute('x', c.x + c.w / 2); sub.setAttribute('y', c.y + 38);
    sub.setAttribute('text-anchor', 'middle');
    sub.setAttribute('fill', 'rgba(240,136,62,0.6)');
    sub.setAttribute('font-size', '0.55rem');
    sub.setAttribute('font-family', '-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif');
    sub.textContent = 'Outbound Gateway';
    g.appendChild(sub);
    canvas.appendChild(g);
  }

  function renderGatewayEntries() {
    Object.keys(gwPositions).forEach(function (key) {
      if (key === '_container') return;
      var pos = gwPositions[key];
      var entry = GATEWAY_ENTRIES.find(function (e) { return e.id === key; });
      if (!entry) return;
      var g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
      g.setAttribute('class', 'dag-node ext-node');
      g.setAttribute('id', 'node-' + key);

      var rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      rect.setAttribute('class', 'node-rect');
      rect.setAttribute('width', GW_ENTRY_W); rect.setAttribute('height', GW_ENTRY_H);
      rect.setAttribute('rx', 4); rect.setAttribute('ry', 4);
      g.appendChild(rect);

      var label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      label.setAttribute('class', 'node-label');
      label.setAttribute('x', GW_ENTRY_W / 2); label.setAttribute('y', 16);
      label.setAttribute('text-anchor', 'middle');
      label.setAttribute('font-size', '0.62rem');
      label.textContent = entry.label;
      g.appendChild(label);

      var sub = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      sub.setAttribute('class', 'node-sub');
      sub.setAttribute('x', GW_ENTRY_W / 2); sub.setAttribute('y', 30);
      sub.setAttribute('text-anchor', 'middle');
      sub.setAttribute('font-size', '0.5rem');
      sub.textContent = entry.sub;
      g.appendChild(sub);

      g.setAttribute('transform', 'translate(' + pos.x + ',' + pos.y + ')');
      g.classList.add('dimmed');
      canvas.appendChild(g);
      svgNodeGroups[key] = { g: g, rect: rect, label: label, sub: sub };
    });
  }

  function renderEdges() {
    EDGES.forEach(function (e) {
      var key = e.from + '->' + e.to;
      if (svgEdges[key]) return;
      var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      var cls = 'dag-edge';
      if (e.feedback) cls += ' feedback';
      if (e.external) cls += ' external';
      path.setAttribute('class', cls);
      path.setAttribute('fill', 'none');
      canvas.appendChild(path);
      svgEdges[key] = { el: path, feedback: !!e.feedback, external: !!e.external };
    });
  }

  function updateAllEdgePaths() {
    Object.keys(svgEdges).forEach(function (key) {
      var parts = key.split('->');
      var e = svgEdges[key];
      e.el.setAttribute('d', getEdgePath(parts[0], parts[1], e.feedback, e.external));
    });
  }

  function renderInternalNodes() {
    NODES.forEach(function (n) {
      var pos = nodePositions[n.id];
      if (!pos) return;
      var g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
      g.setAttribute('class', 'dag-node type-' + n.type);
      g.setAttribute('id', 'node-' + n.id);

      var rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      rect.setAttribute('class', 'node-rect');
      rect.setAttribute('width', NODE_W); rect.setAttribute('height', NODE_H);
      rect.setAttribute('rx', 6); rect.setAttribute('ry', 6);
      g.appendChild(rect);

      var badge = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      badge.setAttribute('class', 'node-type-badge');
      badge.setAttribute('text-anchor', 'end');
      badge.setAttribute('fill', PATTERN_COLORS[n.type] || '#8b949e');
      g.appendChild(badge);

      var label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      label.setAttribute('class', 'node-label');
      label.setAttribute('text-anchor', 'middle');
      g.appendChild(label);

      var sub = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      sub.setAttribute('class', 'node-sub');
      sub.setAttribute('text-anchor', 'middle');
      g.appendChild(sub);

      canvas.appendChild(g);
      badge.setAttribute('x', NODE_W - 6); badge.setAttribute('y', 12);
      badge.textContent = PATTERN_LABELS[n.type];
      label.setAttribute('x', NODE_W / 2); label.setAttribute('y', 17);
      label.setAttribute('font-size', '0.63rem');
      label.textContent = n.label;
      sub.setAttribute('x', NODE_W / 2); sub.setAttribute('y', 32);
      sub.setAttribute('font-size', '0.53rem');
      sub.textContent = n.sub;
      g.setAttribute('transform', 'translate(' + pos.x + ',' + pos.y + ')');
      g.classList.add('dimmed');
      svgNodeGroups[n.id] = { g: g, rect: rect, badge: badge, label: label, sub: sub };
    });
  }

  // ======================== ANIMATION ========================
  function animateParticle(fromId, toId, label, color) {
    var f = nodePositions[fromId] || gwPositions[fromId];
    var t = nodePositions[toId] || gwPositions[toId];
    if (!f || !t) return;

    var isFb = false, isExt = false;
    EDGES.forEach(function (e) { if (e.from === fromId && e.to === toId) { isFb = e.feedback; isExt = e.external; } });

    var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', getEdgePath(fromId, toId, isFb, isExt));
    path.style.display = 'none'; canvas.appendChild(path);
    var len = path.getTotalLength();
    if (len === 0) { path.remove(); return; }

    var particle = document.createElement('div');
    particle.className = 'event-particle';
    if (isExt) { particle.style.borderLeftColor = '#f0883e'; color = '#f0883e'; }
    else { particle.style.borderLeftColor = color; }
    particle.innerHTML = '<span class="p-dot" style="background:' + color + '"></span>' + label;
    vizArea.appendChild(particle);

    var startTime = null, duration = isExt ? 800 : 1100;
    function step(ts) {
      if (!startTime) startTime = ts;
      var progress = Math.min((ts - startTime) / duration, 1);
      var eased = progress < 0.5 ? 2 * progress * progress : -1 + (4 - 2 * progress) * progress;
      var pt = path.getPointAtLength(eased * len);
      particle.style.left = pt.x + 'px'; particle.style.top = pt.y + 'px';
      if (progress < 1) { requestAnimationFrame(step); }
      else { particle.style.opacity = '0'; particle.style.transition = 'opacity 0.3s'; setTimeout(function () { if (particle.parentNode) particle.remove(); path.remove(); }, 300); }
    }
    requestAnimationFrame(step);
  }

  // ======================== PHASE ENGINE ========================
  function runPhase(phaseId) {
    if (isAnimating) return;
    if (phaseId > 1 && !completedPhases[phaseId - 1]) return;
    if (completedPhases[phaseId]) return;

    isAnimating = true; currentPhase = phaseId;
    var phase = PHASES[phaseId - 1];
    updatePhaseButtonStates();

    infoTitle.textContent = phase.title;
    infoDesc.textContent = phase.desc;
    var pathCount = phase.paths ? phase.paths.length : 0;
    if (phase.successPaths) pathCount = phase.successPaths.length + phase.failPaths.length + phase.recoverPaths.length;
    infoMeta.innerHTML = '<span><span class="dot" style="background:' + phase.color + '"></span>' + PATTERN_LABELS[phase.pattern] + '</span><span>' + phase.highlight.length + ' 节点</span><span>' + pathCount + ' 路径</span>';
    m2InsightText.textContent = phase.insight || ''; m2InsightBox.style.display = phase.insight ? 'block' : 'none';
    m2ContrastText.textContent = phase.contrast || ''; m2ContrastBox.style.display = phase.contrast ? 'block' : 'none';

    // Dim all
    Object.keys(svgNodeGroups).forEach(function (id) { svgNodeGroups[id].g.classList.add('dimmed'); svgNodeGroups[id].g.classList.remove('active', 'completed', 'failed', 'recovered'); });
    Object.keys(svgEdges).forEach(function (key) { svgEdges[key].el.classList.remove('highlight'); svgEdges[key].el.style.stroke = ''; });

    setTimeout(function () {
      phase.highlight.forEach(function (id) {
        var g = svgNodeGroups[id];
        if (g) { g.g.classList.remove('dimmed'); g.g.classList.add('active'); }
      });

      var pathDelay = 0;
      var paths, events, edgeColor;

      if (phaseId === 5 && phase.successPaths) {
        // Phase 5: animate success (green), then failure (red), then recovery (blue)
        var groups = [
          { paths: phase.successPaths, events: phase.successEvents, color: '#22c55e', logType: 'info' },
          { paths: phase.failPaths,    events: phase.failEvents,    color: '#f85149', logType: 'comp' },
          { paths: phase.recoverPaths, events: phase.recoverEvents, color: '#3b82f6', logType: 'orch' }
        ];
        groups.forEach(function (grp) {
          grp.paths.forEach(function (pair, idx) {
            pathDelay += 350;
            setTimeout(function () {
              var edgeKey = pair[0] + '->' + pair[1];
              var edgeObj = svgEdges[edgeKey];
              if (edgeObj) { edgeObj.el.classList.add('highlight'); edgeObj.el.style.stroke = grp.color; }
              addLog(grp.logType, (pair[0] + ' → ' + pair[1]));
              animateParticle(pair[0], pair[1], grp.events[idx] || 'Event', grp.color);
            }, pathDelay);
          });
          pathDelay += 600; // gap between groups
        });
      } else {
        paths = phase.paths || [];
        events = phase.events || [];
        edgeColor = phase.color;
        paths.forEach(function (pair, idx) {
          pathDelay += 350;
          setTimeout(function () {
            var edgeKey = pair[0] + '->' + pair[1];
            var edgeObj = svgEdges[edgeKey];
            if (edgeObj) { edgeObj.el.classList.add('highlight'); edgeObj.el.style.stroke = edgeColor; }
            addLog(phase.pattern, (pair[0] + ' → ' + pair[1]));
            animateParticle(pair[0], pair[1], events[idx] || 'Event', edgeColor);
          }, pathDelay);
        });
      }

      // Phase 4 extra: dynamic batch + failure injection
      if (phaseId === 4) {
        setTimeout(function () {
          var bw = svgNodeGroups['batch-worker'];
          if (bw) { bw.sub.textContent = 'BatchWorker · ×3→×5'; bw.g.classList.add('active'); setTimeout(function () { bw.g.classList.add('completed'); }, 600); }
          addLog('info', '⚡ 动态批次: Worker Pool 根据负载自动从 ×3 扩容到 ×5');
        }, pathDelay + 500);

        var failTime = pathDelay + 1600;
        setTimeout(function () {
          var rcf = svgNodeGroups['recharge-failure'];
          var gws = svgNodeGroups['gw-sftp'];
          if (rcf) { rcf.g.classList.remove('active', 'completed'); rcf.g.classList.add('failed'); }
          if (gws) { gws.g.classList.remove('dimmed'); gws.g.classList.add('active'); }
          addLog('comp', '✕ 充值链路 SFTP 超时! 出站网关无响应 → 批次 ABORTED');
          animateParticle('recharge-resp', 'recharge-failure', 'SFTP Timeout!', '#f85149');
          eventCount++; jCount.textContent = eventCount;
        }, failTime);
        pathDelay = failTime + 1400;
      }

      var totalTime = pathDelay + 800;
      setTimeout(function () {
        phase.highlight.forEach(function (id) {
          var g = svgNodeGroups[id];
          if (g) { if (phaseId === 4 && g.g.classList.contains('failed')) return; g.g.classList.add('completed'); }
        });
        completedPhases[phaseId] = true;
        var evCount = phase.events ? phase.events.length : 0;
        if (phase.successEvents) evCount = phase.successEvents.length + phase.failEvents.length + phase.recoverEvents.length;
        eventCount += evCount; jCount.textContent = eventCount;
        updatePhaseButtonStates(); isAnimating = false;

        if (phaseId === 5) {
          setTimeout(function () {
            var rcf = svgNodeGroups['recharge-failure'];
            var rb = svgNodeGroups['re-batch'];
            if (rcf) { rcf.g.classList.remove('failed'); rcf.g.classList.add('recovered'); }
            if (rb) rb.g.classList.add('completed');
            addLog('info', '✔ ReBatchActor 扫描到 ABORTED → 重新注入 Worker Pool → 充值链路恢复');
            animateParticle('re-batch', 'batch-worker', 'ReInject → Recovered', '#3fb950');
            eventCount++; jCount.textContent = eventCount;
            showToast('自愈完成：失败批次已重新注入 Worker Pool');
          }, 1200);
        }

        if (Object.keys(completedPhases).length === 5) {
          setTimeout(function () {
            showToast('全部 5 阶段完成——23 FSM + 出站网关，自愈闭环验证通过');
            addLog('info', '=== DAG 完整执行: 5 阶段, 23 FSM, ' + eventCount + ' 事件已持久化 ===');
            var rcf = svgNodeGroups['recharge-failure'];
            if (rcf) { rcf.g.classList.add('completed'); rcf.g.classList.remove('recovered'); }
          }, 2500);
        }
      }, totalTime);
    }, 350);
  }

  function updatePhaseButtonStates() {
    for (var i = 1; i <= 5; i++) {
      var btn = phaseBtns[i]; btn.classList.remove('active', 'completed', 'locked');
      if (completedPhases[i]) btn.classList.add('completed');
      else if (i === currentPhase && isAnimating) btn.classList.add('active');
      else if (i > 1 && !completedPhases[i - 1]) btn.classList.add('locked');
    }
  }

  function addLog(type, msg) {
    var now = new Date();
    var ts = ('0' + now.getHours()).slice(-2) + ':' + ('0' + now.getMinutes()).slice(-2) + ':' + ('0' + now.getSeconds()).slice(-2);
    var entry = document.createElement('div'); entry.className = 'log-entry ' + type;
    entry.innerHTML = '<span class="ts">' + ts + '</span>' + msg;
    logContainer.appendChild(entry); logContainer.scrollTop = logContainer.scrollHeight;
  }

  function showToast(msg) { toast.textContent = msg; toast.classList.add('show'); setTimeout(function () { toast.classList.remove('show'); }, 3000); }

  function autoPlay() {
    if (isAnimating) return;
    resetAll(); var i = 1;
    function next() { if (i > 5) return; runPhase(i); var check = setInterval(function () { if (!isAnimating) { clearInterval(check); i++; setTimeout(next, 600); } }, 200); }
    next();
  }

  function resetAll() {
    if (isAnimating) return;
    currentPhase = 0; completedPhases = {}; eventCount = 0; jCount.textContent = '0';
    Object.keys(svgNodeGroups).forEach(function (id) {
      svgNodeGroups[id].g.classList.add('dimmed');
      svgNodeGroups[id].g.classList.remove('active', 'completed', 'failed', 'recovered');
      if (id === 'batch-worker') svgNodeGroups[id].sub.textContent = 'BatchWorker';
    });
    Object.keys(svgEdges).forEach(function (key) { svgEdges[key].el.classList.remove('highlight'); svgEdges[key].el.style.stroke = ''; });
    infoTitle.textContent = '点击阶段按钮开始';
    infoDesc.textContent = '23 Akka Persistent FSM + 出站网关，跨 4 层级协作。点击按钮逐步推进。';
    infoMeta.innerHTML = ''; m2InsightBox.style.display = 'none'; m2ContrastBox.style.display = 'none';
    logContainer.innerHTML = '<div class="log-entry info"><span class="ts">00:00</span> 已重置。23 FSM + 出站网关就绪。</div>';
    for (var i = 1; i <= 5; i++) phaseBtns[i].classList.remove('active', 'completed', 'locked');
    phaseBtns[2].classList.add('locked'); phaseBtns[3].classList.add('locked'); phaseBtns[4].classList.add('locked'); phaseBtns[5].classList.add('locked');
  }

  // ======================== EVENTS & INIT ========================
  for (var p = 1; p <= 5; p++) {
    (function (pid) { phaseBtns[pid].addEventListener('click', function () { if (isAnimating) return; if (pid > 1 && !completedPhases[pid - 1]) return; if (completedPhases[pid]) return; runPhase(pid); }); })(p);
  }
  document.getElementById('autoPlayBtn').addEventListener('click', autoPlay);
  document.getElementById('resetBtn').addEventListener('click', resetAll);
  window.addEventListener('resize', function () { renderAll(); });

  canvas.setAttribute('preserveAspectRatio', 'xMidYMid meet');
  renderAll();
  phaseBtns[2].classList.add('locked'); phaseBtns[3].classList.add('locked'); phaseBtns[4].classList.add('locked'); phaseBtns[5].classList.add('locked');
  addLog('info', 'M2 DAG 就绪: 23 FSM + 出站网关(4协议), ' + EDGES.length + ' 条边, 4 层级, 5 阶段.');

})();

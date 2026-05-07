/**
 * M2 DAG Execution Engine — 薪资存入完整 23 FSM 场景
 *
 * 23 Akka Persistent FSM + 外部系统。同层节点上下布局，边严格向下。
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
    { id: 'quota-reserve', label: '用户申购额度锁定', sub: 'UserQuotaReservation', level: 2, type: 'protector', col: 0, row: 0 },
    { id: 'batch-item',    label: '明细创建器', sub: 'BatchItemCreationActor', level: 2, type: 'orchestrator', col: 2, row: 0 },
    // Row 1: BatchWorker centered below BatchMaster
    { id: 'batch-worker',  label: '批次工人 ×N', sub: 'BatchWorker', level: 2, type: 'orchestrator', col: 1, row: 1 },
    // Row 2: quota cleanup
    { id: 'quota-release-u', label: '用户申购额度解锁', sub: 'UserQuotaReleaseActor', level: 5, type: 'protector', col: 0, row: 0 },
    { id: 'quota-release-t', label: '总额度释放', sub: 'TotalQuotaReleaseActor', level: 5, type: 'protector', col: 0, row: 1 },

    // ===== Level 3: 业务链路层 (12, 2 chains × 5 rows, tree layout) =====
    // Each chain: req → resp → reconf → [success | failure] → p2b
    // Recharge chain — left half
    { id: 'recharge-req',     label: '充值请求', sub: 'RechargeRequestActor', level: 3, chain: 'recharge', row: 0 },
    { id: 'recharge-resp',    label: '充值响应', sub: 'RechargeResponseActor', level: 3, chain: 'recharge', row: 1 },
    { id: 'recharge-reconf',  label: '充值重确认', sub: 'RechargeReconfirmActor', level: 3, chain: 'recharge', row: 2 },
    { id: 'recharge-success', label: '充值成功', sub: 'RechargeSuccessActor', level: 3, chain: 'recharge', row: 3, col: 0 },
    { id: 'recharge-failure', label: '充值失败', sub: 'RechargeFailureActor', level: 3, chain: 'recharge', row: 3, col: 1 },
    { id: 'recharge-p2b',     label: '通知理财平台充值成功', sub: 'RechargeP2BNotifyActor', level: 3, chain: 'recharge', row: 4 },
    // Purchase chain — right half
    { id: 'purchase-req',     label: '申购请求', sub: 'PurchaseRequestActor', level: 3, chain: 'purchase', row: 0 },
    { id: 'purchase-resp',    label: '申购响应', sub: 'PurchaseResponseActor', level: 3, chain: 'purchase', row: 1 },
    { id: 'purchase-reconf',  label: '申购重确认', sub: 'PurchaseReconfirmActor', level: 3, chain: 'purchase', row: 2 },
    { id: 'purchase-success', label: '申购成功', sub: 'PurchaseSuccessActor', level: 3, chain: 'purchase', row: 3, col: 0 },
    { id: 'purchase-failure', label: '申购失败', sub: 'PurchaseFailureActor', level: 3, chain: 'purchase', row: 3, col: 1 },
    { id: 'purchase-p2b',     label: '通知理财平台申购成功', sub: 'PurchaseP2BNotifyActor', level: 3, chain: 'purchase', row: 4 },

    // ===== Level 4: 通知层 (2) =====
    { id: 'sms-service',  label: '短信服务', sub: 'SmsService · 合规窗口', level: 4, type: 'support', col: 0, row: 0 },
    { id: 'reminder-sms', label: '提醒短信', sub: 'ReminderSmsActor', level: 4, type: 'support', col: 1, row: 0 }
  ];

  // Outbound Gateway protocol entries (rendered inside gateway container)
  var GATEWAY_ENTRIES = [
    { id: 'gw-sftp',   label: '银行 SFTP', sub: '文件上传 / 轮询下载' },
    { id: 'gw-core',   label: '核心账户 API', sub: '余额 / 交易状态查证' },
    { id: 'gw-p2b',    label: '基金申购平台', sub: '产品申购 / 回盘解析' },
    { id: 'gw-sms',    label: '短信通道', sub: '合规窗口下发' }
  ];

  // Outbound connector style by gateway protocol
  var CONNECTOR_BY_GW = {
    'gw-sftp': { label: 'SFTP出站连接器', short: 'SFTP', color: '#f0883e' },
    'gw-core': { label: 'HTTP出站连接器 · XML', short: 'HTTP/XML', color: '#a855f7' },
    'gw-p2b':  { label: 'HTTP出站连接器 · JSON', short: 'HTTP/JSON', color: '#22c55e' },
    'gw-sms':  { label: 'HTTP出站连接器 · JSON', short: 'HTTP/JSON', color: '#22c55e' }
  };

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
    { from: 'recharge-req',  to: 'gw-core', external: true },
    { from: 'recharge-resp', to: 'gw-sftp', external: true },
    { from: 'recharge-reconf', to: 'gw-core', external: true },
    { from: 'recharge-p2b', to: 'gw-p2b', external: true },
    { from: 'purchase-req',  to: 'gw-sftp', external: true },
    { from: 'purchase-req',  to: 'gw-core', external: true },
    { from: 'purchase-resp', to: 'gw-sftp', external: true },
    { from: 'purchase-reconf', to: 'gw-core', external: true },
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
      title: '阶段 2：数据预处理 → 额度锁定',
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
      title: '阶段 4：并发执行 → 流批一体 + 外部系统通信',
      desc: 'BatchMaster 扇出至 N 个 Worker。每个 Worker 分叉到充值链路（6 FSM 上下串联）和申购链路（同构）。通过外部系统与银行 SFTP、核心账户 API、P2B 平台通信。Communicator 模式：ReceiveTimeout 驱动主动轮询——不阻塞等待。',
      insight: '流批一体 + Communicator + 外部系统：DAG 与外部世界的所有通信通过统一的外部系统——SFTP/API/P2B/短信四种协议被封装在网关内部。Communicator 把外部不确定性封装在可重试的异步边界内。网关对外部系统的不可用进行缓冲——外部抖动不会拖垮 DAG 内部。',
      contrast: '传统：同步 HTTP + 固定超时。外部系统抖动 = 整个流程卡死。流/批两套代码。外部系统把"与外部系统通信"从遍布各处的集成代码变成了一个明确的架构边界。',
      highlight: ['batch-master', 'batch-worker', 'recharge-req', 'recharge-resp', 'recharge-reconf', 'recharge-success', 'purchase-req', 'purchase-resp', 'purchase-reconf', 'purchase-success'],
      paths: [['batch-master','batch-worker'], ['batch-worker','recharge-req'], ['batch-worker','purchase-req'],
              ['recharge-req','recharge-resp'], ['recharge-resp','recharge-success'],
              ['purchase-req','purchase-resp'], ['purchase-resp','purchase-success'],
              ['recharge-req','gw-sftp'], ['purchase-req','gw-sftp'], ['purchase-reconf','gw-core']],
      events: ['BatchDispatched(×N)', 'SFTPFileUploaded', 'BankPollingStarted', 'ReconfirmTriggered', 'ResponsePolled', 'PurchaseReconfirmVerify'] },
    { id: 5, key: 'compensate', color: '#ef4444', pattern: 'compensator',
      title: '阶段 5：通信层 → 成功/故障路由 + 补偿闭环',
      desc: '通信层演示两条路由：✅ 成功路径 — recharge/purchase-success → SmsService → 外部系统短信通道，通知用户。✕ 故障路径 — recharge/purchase-failure → QuotaRelease → 额度清理，ReminderSms 发送告警。♻ 补偿闭环 — ReBatchActor 扫描失败批次 → 重新注入 Worker Pool。',
      insight: '通信层是 DAG 与外部世界的边界。成功消息和故障消息在此层分流——成功走通知通道（绿），失败走补偿通道（红）。外部系统统一对外通信（SFTP/API/P2B/短信），隔离外部不确定性。Compensator 确保故障闭环——只要补偿器在运行，没有失败会被遗漏。',
      contrast: '传统：成功和失败的处理逻辑混在一起，缺少明确的通信边界。外部系统调用散落各处——换个短信通道要改 10 个文件。M2 的通信层把所有外部通信集中到外部系统，成功/失败路径在 DAG 中显式声明。',
      highlight: ['re-batch', 'batch-worker', 'quota-release-u', 'quota-release-t', 'sms-service', 'reminder-sms', 'recharge-failure', 'purchase-failure', 'recharge-success', 'purchase-success', 'recharge-req', 'recharge-resp', 'recharge-reconf', 'recharge-p2b', 'purchase-req', 'purchase-resp', 'purchase-reconf', 'purchase-p2b'],
      // Success paths (green), failure paths (red), recovery (blue)
      successPaths: [['recharge-success','sms-service'], ['purchase-success','sms-service'], ['sms-service','gw-sms']],
      failPaths: [['recharge-failure','quota-release-u'], ['purchase-failure','quota-release-u'], ['quota-release-u','quota-release-t'], ['recharge-failure','reminder-sms'], ['purchase-failure','reminder-sms'], ['reminder-sms','gw-sms']],
      recoverPaths: [['re-batch','batch-worker']],
      replayPaths: [
        ['batch-worker','recharge-req'], ['recharge-req','recharge-resp'], ['recharge-req','gw-sftp'], ['recharge-req','gw-core'],
        ['recharge-resp','recharge-reconf'], ['recharge-resp','gw-sftp'],
        ['recharge-reconf','recharge-success'], ['recharge-reconf','gw-core'],
        ['recharge-success','recharge-p2b'], ['recharge-p2b','gw-p2b'],
        ['batch-worker','purchase-req'], ['purchase-req','purchase-resp'], ['purchase-req','gw-sftp'], ['purchase-req','gw-core'],
        ['purchase-resp','purchase-reconf'], ['purchase-resp','gw-sftp'],
        ['purchase-reconf','purchase-success'], ['purchase-reconf','gw-core'],
        ['purchase-success','purchase-p2b'], ['purchase-p2b','gw-p2b'],
        ['recharge-success','sms-service'], ['purchase-success','sms-service'], ['sms-service','gw-sms']
      ],
      successEvents: ['RechargeOK→Notify', 'PurchaseOK→Notify', 'SMS Sent✓'],
      failEvents: ['RechargeFAIL→Release', 'PurchaseFAIL→Release', 'QuotaReleased', 'Alert SMS!'],
      recoverEvents: ['ReInject→Recovered'],
      replayEvents: [
        'ReDispatch', 'RechargeReq', 'Upload', 'Reserve',
        'RechargeResp', 'PollResp',
        'Reconfirm', 'Verify',
        'RechargeOK', 'NotifyP2B',
        'ReDispatch', 'PurchaseReq', 'Upload', 'Reserve',
        'PurchaseResp', 'PollResp',
        'Reconfirm', 'Verify',
        'PurchaseOK', 'NotifyP2B',
        'NotifySMS', 'NotifySMS', 'SMS Sent'
      ] }
  ];

  var PATTERN_LABELS = { orchestrator: '编排器', protector: '保护器', communicator: '通信器', compensator: '补偿器', support: '辅助' };
  var PATTERN_COLORS = { orchestrator: '#3b82f6', protector: '#f59e0b', communicator: '#a855f7', compensator: '#ef4444', support: '#14b8a6' };

  // ======================== DOM REFS ========================
  var canvas = document.getElementById('dagCanvas');
  var vizArea = document.getElementById('vizArea');
  var logContainer = document.getElementById('logContainer');
  var infoTitle = document.getElementById('infoTitle'), infoDesc = document.getElementById('infoDesc'), infoMeta = document.getElementById('infoMeta');
  var toast = document.getElementById('toast');
  var m2InsightBox = document.getElementById('m2InsightBox'), m2InsightText = document.getElementById('m2InsightText');
  var m2ContrastBox = document.getElementById('m2ContrastBox'), m2ContrastText = document.getElementById('m2ContrastText');
  var phaseBtns = []; for (var i = 1; i <= 5; i++) phaseBtns[i] = document.getElementById('phaseBtn' + i);

  // ======================== STATE ========================
  var currentPhase = 0, isAnimating = false, eventCount = 0;
  var completedPhases = {}, nodePositions = {}, svgEdges = {}, svgNodeGroups = {};
  var gwPositions = {}; // gateway entry positions

  // ======================== LAYOUT ========================
  var NODE_W = 118, NODE_H = 40;
  var GW_CONTAINER_W = 156, GW_ENTRY_W = 142, GW_ENTRY_H = 38;
  var GW_PAD_TOP = 48, GW_ENTRY_GAP = 40;

  function layoutNodes() {
    var w = vizArea.clientWidth, h = vizArea.clientHeight;
    var innerW = w - 40;
    var GAP = Math.round(NODE_H * 1.5); // 60 — uniform 1.5x card-height spacing
    var LEVEL_GAP = GAP;
    var TOP_MARGIN = 24;

    // Content-aware heights
    var l0MinH = NODE_H;                     // 1 node = 40
    var l1MinH = 2 * NODE_H + GAP;           // 2 rows with GAP = 140
    var l2MinH = 2 * NODE_H + GAP;           // 2 rows with GAP = 140
    var l3MinH = 5 * NODE_H + 4 * GAP;       // 5 rows per chain = 440
    var l4MinH = NODE_H;                      // 1 row = 40
    var l5MinH = 2 * NODE_H + GAP;           // 2 nodes with GAP = 140

    // Stack levels sequentially from top
    var lvlY = [TOP_MARGIN];
    var heights = [l0MinH, l1MinH, l2MinH, l3MinH, l4MinH, l5MinH];
    for (var lv = 1; lv <= 5; lv++) {
      lvlY.push(lvlY[lv - 1] + heights[lv - 1] + LEVEL_GAP);
    }

    // Level 0: 1 node centered
    placeRow(NODES.filter(function (n) { return n.level === 0; }), lvlY[0], 1, innerW);

    // Level 1: 2 sub-rows centered vertically — JobActor above, PreBatch+ReBatch below
    var l1Total = 2 * NODE_H + GAP;
    var l1start = lvlY[1] + (l1MinH - l1Total) / 2;
    var l1r0 = NODES.filter(function (n) { return n.level === 1 && n.row === 0; });
    var l1r1 = NODES.filter(function (n) { return n.level === 1 && n.row === 1; });
    placeRow(l1r0, l1start, 1, innerW);
    placeRow(l1r1, l1start + NODE_H + GAP, 2, innerW);

    // Level 2: 2 sub-rows centered vertically
    var l2Total = 2 * NODE_H + GAP;
    var l2start = lvlY[2] + (l2MinH - l2Total) / 2;
    var l2r0 = NODES.filter(function (n) { return n.level === 2 && n.row === 0; });
    var l2r1 = NODES.filter(function (n) { return n.level === 2 && n.row === 1; });
    placeRow(l2r0, l2start, 3, innerW);
    placeRow(l2r1, l2start + NODE_H + GAP, 3, innerW);

    // Level 3: 2 chains side by side, tree layout per chain
    // Each chain: req → resp → [reconf | success | failure] → p2b
    var l3LeftCx = innerW * 0.25;   // center of left half
    var l3RightCx = innerW * 0.75;  // center of right half
    var l3ChainW = innerW * 0.42;   // available width per chain for 3-node row
    layoutChainL3(NODES.filter(function (n) { return n.chain === 'recharge'; }), lvlY[3], l3MinH, l3LeftCx, l3ChainW);
    layoutChainL3(NODES.filter(function (n) { return n.chain === 'purchase'; }), lvlY[3], l3MinH, l3RightCx, l3ChainW);

    // ===== Outbound Gateway Anti-Corruption Layer =====
    // Recharge chain nodes (rows 0-3): req, resp, reconf, success, failure
    var rcNodes = ['recharge-req','recharge-resp','recharge-reconf','recharge-success','recharge-failure'];
    var rcMinX = Infinity, rcMaxX = -Infinity, rcMinY = Infinity, rcMaxY = -Infinity;
    rcNodes.forEach(function(id) {
      var p = nodePositions[id]; if (!p) return;
      rcMinX = Math.min(rcMinX, p.x); rcMaxX = Math.max(rcMaxX, p.x + NODE_W);
      rcMinY = Math.min(rcMinY, p.y); rcMaxY = Math.max(rcMaxY, p.bottom);
    });
    var RCGW_PAD = 16;
    gwPositions._rechargeGw = { x: rcMinX - RCGW_PAD, y: rcMinY - RCGW_PAD - 20, w: rcMaxX - rcMinX + 2 * RCGW_PAD, h: rcMaxY - rcMinY + 2 * RCGW_PAD + 20 };

    // Purchase chain nodes (rows 0-3): req, resp, reconf, success, failure
    var pcNodes = ['purchase-req','purchase-resp','purchase-reconf','purchase-success','purchase-failure'];
    var pcMinX = Infinity, pcMaxX = -Infinity, pcMinY = Infinity, pcMaxY = -Infinity;
    pcNodes.forEach(function(id) {
      var p = nodePositions[id]; if (!p) return;
      pcMinX = Math.min(pcMinX, p.x); pcMaxX = Math.max(pcMaxX, p.x + NODE_W);
      pcMinY = Math.min(pcMinY, p.y); pcMaxY = Math.max(pcMaxY, p.bottom);
    });
    gwPositions._purchaseGw = { x: pcMinX - RCGW_PAD, y: pcMinY - RCGW_PAD - 20, w: pcMaxX - pcMinX + 2 * RCGW_PAD, h: pcMaxY - pcMinY + 2 * RCGW_PAD + 20 };

    // ===== 单节点出站网关防腐层 =====
    var SINGLE_GW_PAD_X = 16, SINGLE_GW_PAD_TOP = 36, SINGLE_GW_PAD_BOT = 16;
    function wrapSingleNode(nodeId) {
      var p = nodePositions[nodeId]; if (!p) return null;
      return {
        x: p.x - SINGLE_GW_PAD_X,
        y: p.y - SINGLE_GW_PAD_TOP,
        w: NODE_W + 2 * SINGLE_GW_PAD_X,
        h: NODE_H + SINGLE_GW_PAD_TOP + SINGLE_GW_PAD_BOT
      };
    }

    // Level 5 (moved up to old L4 level): 2 nodes stacked vertically centered
    var l5start = lvlY[4] - (NODE_H + GAP);
    var l5nodes = NODES.filter(function (n) { return n.level === 5; });
    var qru = l5nodes.find(function (n) { return n.id === 'quota-release-u'; });
    if (qru) { placeNode(qru, l5start, innerW * 0.5); }
    var qrt = l5nodes.find(function (n) { return n.id === 'quota-release-t'; });
    if (qrt) { placeNode(qrt, l5start + NODE_H + GAP, innerW * 0.5); }

    // ===== External Systems =====
    // Bank system: center column, aligned with L3 resp/reconf rows
    var bankCenterX = w / 2;
    var bankX = bankCenterX - GW_CONTAINER_W / 2;
    var respCenterY = lvlY[3] + NODE_H + GAP + NODE_H / 2;
    var reconfCenterY = lvlY[3] + 2 * (NODE_H + GAP) + NODE_H / 2;
    var bankEntryGap = reconfCenterY - respCenterY - GW_ENTRY_H;
    var bankStartY = respCenterY - GW_ENTRY_H / 2;
    ['gw-sftp', 'gw-core'].forEach(function (id, idx) {
      var ey = bankStartY + idx * (GW_ENTRY_H + bankEntryGap);
      var ex = bankX + (GW_CONTAINER_W - GW_ENTRY_W) / 2;
      gwPositions[id] = { x: ex, y: ey, cx: ex + GW_ENTRY_W / 2, cy: ey + GW_ENTRY_H / 2, left: ex, right: ex + GW_ENTRY_W, bottom: ey + GW_ENTRY_H };
    });
    var bankTop = bankStartY - GW_PAD_TOP;
    var bankH = GW_PAD_TOP + 2 * GW_ENTRY_H + bankEntryGap + 12;
    gwPositions._leftCol = { x: bankX, y: bankTop, w: GW_CONTAINER_W, h: bankH };

    // 理财平台: center column, below L5
    var l5Bottom = lvlY[4] + 2 * NODE_H + GAP;
    var p2bCenterX = w / 2;
    var p2bColX = p2bCenterX - GW_CONTAINER_W / 2;
    var p2bColTop = l5Bottom + 24;
    var p2bEntryY = p2bColTop + GW_PAD_TOP;
    var p2bEx = p2bColX + (GW_CONTAINER_W - GW_ENTRY_W) / 2;
    gwPositions['gw-p2b'] = { x: p2bEx, y: p2bEntryY, cx: p2bEx + GW_ENTRY_W / 2, cy: p2bEntryY + GW_ENTRY_H / 2, left: p2bEx, right: p2bEx + GW_ENTRY_W, bottom: p2bEntryY + GW_ENTRY_H };
    var p2bColH = GW_PAD_TOP + GW_ENTRY_H + 12;
    gwPositions._p2bCol = { x: p2bColX, y: p2bColTop, w: GW_CONTAINER_W, h: p2bColH };

    // Level 4 (moved below 理财平台): 2 nodes side by side on a single row
    var l4Y = p2bColTop + p2bColH + LEVEL_GAP;
    var l4nodes = NODES.filter(function (n) { return n.level === 4; });
    var smsSvc = l4nodes.find(function (n) { return n.id === 'sms-service'; });
    if (smsSvc) { placeNode(smsSvc, l4Y, innerW * 0.35); }
    var reminder = l4nodes.find(function (n) { return n.id === 'reminder-sms'; });
    if (reminder) { placeNode(reminder, l4Y, innerW * 0.65); }

    // ===== 单节点出站网关防腐层（所有节点位置已就绪）=====
    gwPositions._rechargeP2bGw = wrapSingleNode('recharge-p2b');
    gwPositions._purchaseP2bGw = wrapSingleNode('purchase-p2b');
    gwPositions._smsServiceGw = wrapSingleNode('sms-service');
    gwPositions._reminderSmsGw = wrapSingleNode('reminder-sms');

    // gw-sms: centered below L4, styled container
    var smsCenterX = w / 2;
    var smsColX = smsCenterX - GW_CONTAINER_W / 2;
    var smsColTop = l4Y + NODE_H + 24;
    var smsEntryY = smsColTop + GW_PAD_TOP;
    var smsEx = smsColX + (GW_CONTAINER_W - GW_ENTRY_W) / 2;
    gwPositions['gw-sms'] = { x: smsEx, y: smsEntryY, cx: smsEx + GW_ENTRY_W / 2, cy: smsEntryY + GW_ENTRY_H / 2, left: smsEx, right: smsEx + GW_ENTRY_W, bottom: smsEntryY + GW_ENTRY_H };
    var smsColH = GW_PAD_TOP + GW_ENTRY_H + 12;
    gwPositions._smsCol = { x: smsColX, y: smsColTop, w: GW_CONTAINER_W, h: smsColH };

    // Set SVG canvas height to fit all content
    var maxY = smsColTop + smsColH + 24;
    canvas.style.height = maxY + 'px';
    canvas.setAttribute('viewBox', '0 0 ' + w + ' ' + maxY);
  }

  function placeNode(n, y, x) {
    nodePositions[n.id] = { x: x - NODE_W / 2, y: y, cx: x, cy: y + NODE_H / 2, right: x + NODE_W / 2, bottom: y + NODE_H };
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
    var colGap = Math.round(nodeH * 1.5);
    var totalNodesH = count * nodeH + (count - 1) * colGap;
    var startY = topY + (totalH - totalNodesH) / 2;
    nodes.forEach(function (n) {
      var y = startY + n.row * (nodeH + colGap);
      var x = colCenter - NODE_W / 2;
      nodePositions[n.id] = { x: x, y: y, cx: x + NODE_W / 2, cy: y + NODE_H / 2, right: x + NODE_W, bottom: y + NODE_H, top: y };
    });
  }

  function layoutChainL3(nodes, topY, totalH, centerX, chainW) {
    // 5 rows: req(0), resp(1), reconf(2), [success | failure](3), p2b(4)
    var GAP = Math.round(NODE_H * 1.5);
    var totalNodesH = 5 * NODE_H + 4 * GAP;
    var startY = topY + (totalH - totalNodesH) / 2;

    var req = nodes.find(function (n) { return n.row === 0; });
    var resp = nodes.find(function (n) { return n.row === 1; });
    var reconf = nodes.find(function (n) { return n.row === 2; });
    var outcomes = nodes.filter(function (n) { return n.row === 3; }).sort(function (a, b) { return (a.col || 0) - (b.col || 0); });
    var p2b = nodes.find(function (n) { return n.row === 4; });

    if (req) placeNode(req, startY, centerX);
    if (resp) placeNode(resp, startY + NODE_H + GAP, centerX);
    if (reconf) placeNode(reconf, startY + 2 * (NODE_H + GAP), centerX);

    // Outcome row: 2 nodes (success | failure) side by side, centered on centerX
    var outcomeY = startY + 3 * (NODE_H + GAP);
    var outcomeTotalW = 2 * NODE_W + GAP;
    var outcomeStartX = centerX - outcomeTotalW / 2 + NODE_W / 2;
    outcomes.forEach(function (n, idx) {
      var x = outcomeStartX + idx * (NODE_W + GAP);
      nodePositions[n.id] = { x: x - NODE_W / 2, y: outcomeY, cx: x, cy: outcomeY + NODE_H / 2, right: x + NODE_W / 2, bottom: outcomeY + NODE_H };
    });

    // p2b aligned under success (col:0), not centered
    var p2bCx = outcomeStartX; // same X center as success node
    if (p2b) placeNode(p2b, startY + 4 * (NODE_H + GAP), p2bCx);
  }

  function getEdgePath(fromId, toId, isFeedback, isExternal) {
    var f = nodePositions[fromId];
    var t = isExternal ? gwPositions[toId] : nodePositions[toId];
    if (!f || !t) return '';

    // Smooth S-curve helper
    function sCurve(x1, y1, x2, y2, tension) {
      var t = tension || 0.45;
      var dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
      var cp = Math.max(dy * t, Math.min(dx * 0.5, 40));
      return 'M ' + x1 + ' ' + y1 + ' ' +
             'C ' + x1 + ' ' + (y1 + cp) + ', ' +
             x2 + ' ' + (y2 - cp) + ', ' +
             x2 + ' ' + y2;
    }

    if (isFeedback) {
      var bulge = vizArea.clientWidth - 30;
      var fy = f.y + NODE_H / 2, ty = t.y + NODE_H / 2;
      return 'M ' + f.right + ' ' + fy + ' ' +
             'C ' + bulge + ' ' + fy + ', ' +
             bulge + ' ' + ty + ', ' +
             t.right + ' ' + ty;
    }

    if (isExternal) {
      var fsy = f.y + NODE_H / 2, tty = t.y + GW_ENTRY_H / 2;
      if (f.cx < t.cx) {
        // DAG node left of gateway: right edge → gateway left edge
        var mx = (f.right + t.left) / 2;
        return 'M ' + f.right + ' ' + fsy + ' ' +
               'C ' + mx + ' ' + fsy + ', ' +
               mx + ' ' + tty + ', ' +
               t.left + ' ' + tty;
      } else {
        // DAG node right of gateway: left edge → gateway right edge
        var mx = (f.x + t.right) / 2;
        return 'M ' + f.x + ' ' + fsy + ' ' +
               'C ' + mx + ' ' + fsy + ', ' +
               mx + ' ' + tty + ', ' +
               t.right + ' ' + tty;
      }
    }

    // Normal downward: source bottom-center → target top-center
    var x1 = f.cx, y1 = f.bottom;
    var x2 = t.cx, y2 = t.y;
    var dx = x2 - x1, dy = y2 - y1;

    if (Math.abs(dx) < 8) {
      // Same column: straight down with minimal curve
      var cp = Math.max(dy * 0.35, 24);
      return sCurve(x1, y1, x2, y2, 0.35);
    } else {
      // Different columns: smooth S-curve
      var cp = Math.max(Math.abs(dx) * 0.35, 18);
      var midY = (y1 + y2) / 2;
      return 'M ' + x1 + ' ' + y1 + ' ' +
             'C ' + x1 + ' ' + (y1 + cp) + ', ' +
             x2 + ' ' + (y2 - cp) + ', ' +
             x2 + ' ' + y2;
    }
  }

  // ======================== RENDER ========================
  function renderAll() {
    layoutNodes();
    while (canvas.firstChild) canvas.removeChild(canvas.firstChild);

    // Thin arrow markers for edges
    var defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
    var arrows = [
      { id: 'arrow-normal',  fill: 'rgba(255,255,255,0.12)' },
      { id: 'arrow-feedback', fill: 'rgba(239,68,68,0.5)' },
      { id: 'arrow-external', fill: 'rgba(240,136,62,0.5)' },
      { id: 'arrow-highlight', fill: 'rgba(88,166,255,0.7)' }
    ];
    arrows.forEach(function(a) {
      var m = document.createElementNS('http://www.w3.org/2000/svg', 'marker');
      m.setAttribute('id', a.id);
      m.setAttribute('markerWidth', '5'); m.setAttribute('markerHeight', '4');
      m.setAttribute('refX', '5'); m.setAttribute('refY', '2');
      m.setAttribute('orient', 'auto');
      m.setAttribute('markerUnits', 'userSpaceOnUse');
      var p = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
      p.setAttribute('points', '0,0 5,2 0,4');
      p.setAttribute('fill', a.fill);
      m.appendChild(p);
      defs.appendChild(m);
    });
    canvas.appendChild(defs);
    svgEdges = {}; svgNodeGroups = {};
    renderLevelLabels();
    renderGatewayContainer();
    renderEdges();
    renderInternalNodes();
    renderGatewayEntries();
    updateAllEdgePaths();
    // Bring connector dots/labels to front so they're not covered by node rects
    Object.keys(svgEdges).forEach(function (key) {
      var e = svgEdges[key];
      if (e.connector) {
        canvas.appendChild(e.connector.el);
        if (e.connector.labelEl) canvas.appendChild(e.connector.labelEl);
      }
    });
  }

  function renderLevelLabels() {
    var labels = ['触发层', '编排层', '执行与资源层', '业务链路层 (2 链 × 5 行)', '通信层', '补偿释放层'];
    var h = vizArea.clientHeight;
    var usableH = h - 24 - 8;
    var slotH = usableH / 7;
    var yPos = [24, 24 + slotH, 24 + 2 * slotH, 24 + 3 * slotH, 24 + 4 * slotH, 24 + 5 * slotH];
    for (var lv = 0; lv <= 5; lv++) {
      var el = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      el.setAttribute('class', 'level-label');
      el.setAttribute('x', 6); el.setAttribute('y', yPos[lv] + 8);
      el.textContent = 'L' + lv + ' ' + labels[lv];
      canvas.appendChild(el);
    }
  }

  function renderGatewayContainer() {
    // Outbound Gateway Anti-Corruption Layer
    _renderOutboundGw(gwPositions._rechargeGw, '充值子流程动态批次防腐层', '多协议 · 多接口 · 各异BatchSize', '#3b82f6');
    _renderOutboundGw(gwPositions._purchaseGw, '申购子流程动态批次防腐层', '多协议 · 多接口 · 各异BatchSize', '#10b981');
    // 单节点出站网关防腐层
    _renderOutboundGw(gwPositions._rechargeP2bGw, '充值通知出站防腐层', 'P2B · 通知理财平台', '#6366f1');
    _renderOutboundGw(gwPositions._purchaseP2bGw, '申购通知出站防腐层', 'P2B · 通知理财平台', '#6366f1');
    _renderOutboundGw(gwPositions._smsServiceGw,  '短信服务出站防腐层', '合规窗口 · 消息队列', '#6366f1');
    _renderOutboundGw(gwPositions._reminderSmsGw, '提醒短信出站防腐层', '告警通知 · 用户触达', '#6366f1');
    // Center: Bank system (SFTP + Core API)
    _renderGatewayColumn(gwPositions._leftCol, '银行系统', 'SFTP / Core API');
    // Center: 理财平台 (P2B)
    _renderGatewayColumn(gwPositions._p2bCol, '理财平台', '基金申购 / 回盘');
    // Center: 短信通道 (SMS)
    _renderGatewayColumn(gwPositions._smsCol, '短信通道', '合规窗口下发');
  }

  function _renderOutboundGw(c, title, sub, color) {
    // Parse hex color for rgba variants
    var r = parseInt(color.slice(1, 3), 16);
    var g_ = parseInt(color.slice(3, 5), 16);
    var b = parseInt(color.slice(5, 7), 16);
    var g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    var rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', c.x); rect.setAttribute('y', c.y);
    rect.setAttribute('width', c.w); rect.setAttribute('height', c.h);
    rect.setAttribute('rx', 8); rect.setAttribute('ry', 8);
    rect.setAttribute('fill', 'rgba(' + r + ',' + g_ + ',' + b + ',0.04)');
    rect.setAttribute('stroke', 'rgba(' + r + ',' + g_ + ',' + b + ',0.28)');
    rect.setAttribute('stroke-width', 1.5);
    rect.setAttribute('stroke-dasharray', '6,3');
    g.appendChild(rect);
    var t = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    t.setAttribute('x', c.x + c.w / 2); t.setAttribute('y', c.y + 17);
    t.setAttribute('text-anchor', 'middle');
    t.setAttribute('fill', color);
    t.setAttribute('font-size', '0.6rem'); t.setAttribute('font-weight', '700');
    t.setAttribute('font-family', '-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif');
    t.textContent = title;
    g.appendChild(t);
    var s = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    s.setAttribute('x', c.x + c.w / 2); s.setAttribute('y', c.y + 33);
    s.setAttribute('text-anchor', 'middle');
    s.setAttribute('fill', 'rgba(' + r + ',' + g_ + ',' + b + ',0.5)');
    s.setAttribute('font-size', '0.5rem');
    s.setAttribute('font-family', '-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif');
    s.textContent = sub;
    g.appendChild(s);
    canvas.appendChild(g);
  }

  function _renderGatewayColumn(c, title, sub) {
    var g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    var rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', c.x); rect.setAttribute('y', c.y);
    rect.setAttribute('width', c.w); rect.setAttribute('height', c.h);
    rect.setAttribute('rx', 8); rect.setAttribute('ry', 8);
    rect.setAttribute('fill', 'rgba(240,136,62,0.04)');
    rect.setAttribute('stroke', 'rgba(240,136,62,0.3)');
    rect.setAttribute('stroke-width', 1.5);
    g.appendChild(rect);
    var t = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    t.setAttribute('x', c.x + c.w / 2); t.setAttribute('y', c.y + 18);
    t.setAttribute('text-anchor', 'middle');
    t.setAttribute('fill', '#f0883e');
    t.setAttribute('font-size', '0.62rem'); t.setAttribute('font-weight', '700');
    t.setAttribute('font-family', '-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif');
    t.textContent = title;
    g.appendChild(t);
    var s = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    s.setAttribute('x', c.x + c.w / 2); s.setAttribute('y', c.y + 34);
    s.setAttribute('text-anchor', 'middle');
    s.setAttribute('fill', 'rgba(240,136,62,0.5)');
    s.setAttribute('font-size', '0.52rem');
    s.setAttribute('font-family', '-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif');
    s.textContent = sub;
    g.appendChild(s);
    canvas.appendChild(g);
  }

  function renderCenterDivider() {
    // Find the Y range of L3 nodes
    var l3nodes = NODES.filter(function (n) { return n.level === 3; });
    var minY = Infinity, maxY = -Infinity;
    l3nodes.forEach(function (n) {
      var pos = nodePositions[n.id];
      if (pos) { minY = Math.min(minY, pos.y); maxY = Math.max(maxY, pos.bottom); }
    });
    if (minY === Infinity) return;
    var cx = vizArea.clientWidth / 2;
    var line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
    line.setAttribute('x1', cx); line.setAttribute('y1', minY - 8);
    line.setAttribute('x2', cx); line.setAttribute('y2', maxY + 8);
    line.setAttribute('stroke', 'rgba(255,255,255,0.12)');
    line.setAttribute('stroke-width', 1);
    line.setAttribute('stroke-dasharray', '6,4');
    canvas.appendChild(line);
  }

  function renderGatewayEntries() {
    Object.keys(gwPositions).forEach(function (key) {
      if (key.charAt(0) === '_') return;
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
    var connectorCountByNode = {}; // track dots per source node for offset
    EDGES.forEach(function (e) {
      var key = e.from + '->' + e.to;
      if (svgEdges[key]) return;
      var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      var cls = 'dag-edge';
      if (e.feedback) cls += ' feedback';
      if (e.external) cls += ' external';
      path.setAttribute('class', cls);
      path.setAttribute('fill', 'none');
      var marker = e.feedback ? 'url(#arrow-feedback)' : e.external ? 'url(#arrow-external)' : 'url(#arrow-normal)';
      path.setAttribute('marker-end', marker);
      canvas.appendChild(path);
      var edgeObj = { el: path, feedback: !!e.feedback, external: !!e.external };
      if (e.external) {
        var conn = CONNECTOR_BY_GW[e.to];
        if (conn) {
          var idx = connectorCountByNode[e.from] || 0;
          connectorCountByNode[e.from] = idx + 1;
          // Stack dots vertically when multiple connectors share same start point
          var yOff = (idx - (connectorCountByNode[e.from] - 1) / 2) * 10;
          var dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
          dot.setAttribute('r', '6');
          dot.setAttribute('fill', 'none');
          dot.setAttribute('stroke', conn.color);
          dot.setAttribute('stroke-width', '1.5');
          dot.setAttribute('class', 'connector-dot');
          var tip = document.createElementNS('http://www.w3.org/2000/svg', 'title');
          tip.textContent = conn.label;
          dot.appendChild(tip);
          canvas.appendChild(dot);
          var lbl = document.createElementNS('http://www.w3.org/2000/svg', 'text');
          lbl.setAttribute('fill', conn.color);
          lbl.setAttribute('pointer-events', 'none');
          lbl.setAttribute('font-size', '0.5rem');
          lbl.setAttribute('font-family', '-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif');
          lbl.setAttribute('class', 'connector-label');
          lbl.textContent = conn.short;
          canvas.appendChild(lbl);
          edgeObj.connector = { el: dot, labelEl: lbl, toGw: e.to, yOff: yOff };
        }
      }
      svgEdges[key] = edgeObj;
    });
  }

  function getExternalEdgeStartPos(fromId, toId) {
    var f = nodePositions[fromId];
    var t = gwPositions[toId];
    if (!f || !t) return null;
    var fsy = f.y + NODE_H / 2;
    var startX = f.cx < t.cx ? f.right : f.x;
    return { x: startX, y: fsy };
  }

  function updateAllEdgePaths() {
    Object.keys(svgEdges).forEach(function (key) {
      var parts = key.split('->');
      var e = svgEdges[key];
      e.el.setAttribute('d', getEdgePath(parts[0], parts[1], e.feedback, e.external));
      if (e.connector) {
        var sp = getExternalEdgeStartPos(parts[0], e.connector.toGw);
        if (sp) {
          var cy = sp.y + (e.connector.yOff || 0);
          e.connector.el.setAttribute('cx', sp.x);
          e.connector.el.setAttribute('cy', cy);
          if (e.connector.labelEl) {
            e.connector.labelEl.setAttribute('x', sp.x + 10);
            e.connector.labelEl.setAttribute('y', cy + 4);
          }
        }
      }
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
      // 只有 FSM 节点可以下钻（排除 cron 触发器）
      if (n.id !== 'cron') {
        g.setAttribute('data-fsm-id', n.id);
        g.addEventListener('dblclick', function(e) {
          e.stopPropagation();
          FSMViewer.open(n.id);
        });
      }
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
    Object.keys(svgEdges).forEach(function (key) {
      var e = svgEdges[key];
      e.el.classList.remove('highlight'); e.el.style.stroke = '';
      var m = e.feedback ? 'url(#arrow-feedback)' : e.external ? 'url(#arrow-external)' : 'url(#arrow-normal)';
      e.el.setAttribute('marker-end', m);
    });

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
              if (edgeObj) { edgeObj.el.classList.add('highlight'); edgeObj.el.style.stroke = grp.color; edgeObj.el.setAttribute('marker-end', 'url(#arrow-highlight)'); }
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
            if (edgeObj) { edgeObj.el.classList.add('highlight'); edgeObj.el.style.stroke = edgeColor; edgeObj.el.setAttribute('marker-end', 'url(#arrow-highlight)'); }
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
          addLog('comp', '✕ 充值链路 SFTP 超时! 外部系统无响应 → 批次 ABORTED');
          animateParticle('recharge-resp', 'recharge-failure', 'SFTP Timeout!', '#f85149');
          eventCount++;
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
        eventCount += evCount;
        updatePhaseButtonStates(); isAnimating = false;

        if (phaseId === 5) {
          setTimeout(function () {
            var rcf = svgNodeGroups['recharge-failure'];
            var rb = svgNodeGroups['re-batch'];
            if (rcf) { rcf.g.classList.remove('failed'); rcf.g.classList.add('recovered'); }
            if (rb) rb.g.classList.add('completed');
            addLog('info', '✔ ReBatchActor 扫描到 ABORTED → 重新注入 Worker Pool → 补偿重放 Happy Path');
            animateParticle('re-batch', 'batch-worker', 'ReInject → Recover', '#3fb950');
            eventCount++;
            showToast('自愈完成：失败批次已重新注入 Worker Pool，重走 Happy Path');

            // Replay happy path after recovery
            if (phase.replayPaths) {
              var replayDelay = 600;
              phase.replayPaths.forEach(function (pair, idx) {
                replayDelay += 320;
                setTimeout(function () {
                  var edgeKey = pair[0] + '->' + pair[1];
                  var edgeObj = svgEdges[edgeKey];
                  if (edgeObj) { edgeObj.el.classList.add('highlight'); edgeObj.el.style.stroke = '#3fb950'; edgeObj.el.setAttribute('marker-end', 'url(#arrow-highlight)'); }
                  addLog('info', '♻ ' + pair[0] + ' → ' + pair[1]);
                  animateParticle(pair[0], pair[1], phase.replayEvents[idx] || 'Event', '#3fb950');
                  eventCount++;
                }, replayDelay);
              });

              // Final all-done toast after replay completes
              setTimeout(function () {
                if (Object.keys(completedPhases).length === 5) {
                  showToast('全部 5 阶段完成——自愈闭环 + Happy Path 重放，23 FSM + 外部系统验证通过');
                  addLog('info', '=== DAG 完整执行: 5 阶段, 23 FSM, ' + eventCount + ' 事件已持久化 ===');
                  var rcf2 = svgNodeGroups['recharge-failure'];
                  if (rcf2) { rcf2.g.classList.add('completed'); rcf2.g.classList.remove('recovered'); }
                }
              }, replayDelay + 800);
            }
          }, 1200);
        } else if (Object.keys(completedPhases).length === 5) {
          setTimeout(function () {
            showToast('全部 5 阶段完成——23 FSM + 外部系统，自愈闭环验证通过');
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
    currentPhase = 0; completedPhases = {}; eventCount = 0;
    Object.keys(svgNodeGroups).forEach(function (id) {
      svgNodeGroups[id].g.classList.add('dimmed');
      svgNodeGroups[id].g.classList.remove('active', 'completed', 'failed', 'recovered');
      if (id === 'batch-worker') svgNodeGroups[id].sub.textContent = 'BatchWorker';
    });
    Object.keys(svgEdges).forEach(function (key) {
      var e = svgEdges[key];
      e.el.classList.remove('highlight'); e.el.style.stroke = '';
      var m = e.feedback ? 'url(#arrow-feedback)' : e.external ? 'url(#arrow-external)' : 'url(#arrow-normal)';
      e.el.setAttribute('marker-end', m);
    });
    infoTitle.textContent = '点击阶段按钮开始';
    infoDesc.textContent = '23 Akka Persistent FSM + 外部系统，跨 4 层级协作。点击按钮逐步推进。';
    infoMeta.innerHTML = ''; m2InsightBox.style.display = 'none'; m2ContrastBox.style.display = 'none';
    logContainer.innerHTML = '<div class="log-entry info"><span class="ts">00:00</span> 已重置。23 FSM + 外部系统就绪。</div>';
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
  addLog('info', 'M2 DAG 就绪: 23 FSM + 外部系统(4协议), ' + EDGES.length + ' 条边, 4 层级, 5 阶段.');

})();

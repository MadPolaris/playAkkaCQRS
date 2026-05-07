/**
 * FSM Viewer — 状态机交互式状态图查看器
 *
 * 用法:
 *   FSMViewer.open('job-actor')         // 双击 DAG 节点时调用
 *   FSMViewer.close()                   // 关闭模态框
 *
 * 数据源: /assets/data/fsm/{id}.json  (23 个 FSM 定义文件)
 * 索引:   /assets/data/fsm/index.json
 */

var FSMViewer = (function() {
  'use strict';

  // ---- 布局常量 ----
  var COL_GAP = 180;
  var ROW_GAP = 100;
  var PAD_LEFT = 80;
  var PAD_TOP = 60;
  var NODE_W = 130;
  var NODE_H = 44;
  var NODE_RX = 8;

  // ---- 模式标签 ----
  var PATTERN_LABELS = { orchestrator: '编排器', protector: '保护器', communicator: '通信器', compensator: '补偿器', support: '辅助' };

  // ---- 颜色映射 (复用 M2 主题) ----
  var COLORS = {
    initial:   { stroke: '#3fb950', fill: 'rgba(63,185,80,0.08)' },
    normal:    { stroke: '#30363d', fill: 'rgba(22,27,34,0.92)' },
    terminal:  { stroke: '#3fb950', fill: 'rgba(63,185,80,0.06)' },
    error:     { stroke: '#f85149', fill: 'rgba(248,81,73,0.08)' },
    recovery:  { stroke: '#f59e0b', fill: 'rgba(245,158,11,0.08)' },
    active:    { stroke: '#58a6ff', fill: 'rgba(88,166,255,0.12)', glow: '0 0 14px rgba(88,166,255,0.6)' },
    traversed: { stroke: '#3fb950', fill: 'rgba(63,185,80,0.12)', glow: '0 0 8px rgba(63,185,80,0.4)' },
    available: { stroke: '#f59e0b', fill: 'rgba(245,158,11,0.1)',  glow: '0 0 6px rgba(245,158,11,0.4)' }
  };

  var EDGE_COLORS = {
    command:  '#58a6ff',
    timer:    '#f59e0b',
    failure:  '#f85149',
    recovery: '#3fb950',
    child:    '#a855f7'
  };

  // ---- 内部状态 ----
  var _fsmData = null;
  var _currentState = null;
  var _stateHistory = [];
  var _eventLog = [];
  var _logCounter = 0;
  var _autoPlayTimer = null;
  var _svgEl = null;
  var _stateElems = {};   // stateId → SVG group
  var _transElems = {};   // transId → SVG group
  var _modalVisible = false;

  // ---- 初始化 (页面加载后调用一次) ----
  function init() {
    // 模态框 HTML 已在模板中
    document.getElementById('fsmModalClose').addEventListener('click', close);
    document.getElementById('fsmModalOverlay').addEventListener('click', function(e) {
      if (e.target === this) close();
    });
    document.getElementById('fsmBtnAutoPlay').addEventListener('click', autoPlay);
    document.getElementById('fsmBtnStep').addEventListener('click', stepForward);
    document.getElementById('fsmBtnReset').addEventListener('click', reset);
    document.getElementById('fsmBtnFault').addEventListener('click', injectFault);
    document.addEventListener('keydown', function(e) {
      if (!_modalVisible) return;
      if (e.key === 'Escape') close();
      if (e.key === 'ArrowRight') stepForward();
    });
  }

  // ---- 加载 FSM 数据 ----
  function loadFSM(fsmId, callback) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', '/assets/data/fsm/' + fsmId + '.json', true);
    xhr.onload = function() {
      if (xhr.status === 200) {
        callback(JSON.parse(xhr.responseText));
      } else {
        console.error('Failed to load FSM:', fsmId, xhr.status);
      }
    };
    xhr.onerror = function() {
      console.error('XHR error loading FSM:', fsmId);
    };
    xhr.send();
  }

  // ---- 打开模态框 ----
  function open(fsmId) {
    loadFSM(fsmId, function(data) {
      _fsmData = data;
      _currentState = data.initialState;
      _stateHistory = [data.initialState];
      _eventLog = [];
      _logCounter = 0;
      _stateElems = {};
      _transElems = {};
      if (_autoPlayTimer) { clearTimeout(_autoPlayTimer); _autoPlayTimer = null; }

      // 更新标题
      document.getElementById('fsmTitle').textContent = data.label + ' 状态机';
      document.getElementById('fsmSubtitle').textContent =
        (PATTERN_LABELS && PATTERN_LABELS[data.resiliencePattern] || data.resiliencePattern) +
        ' · ' + data.states.length + ' states · Level ' + data.level;

      // 更新信息面板
      document.getElementById('fsmDesc').textContent = data.description;
      document.getElementById('fsmInsight').textContent = data.m2Insight || '';
      document.getElementById('fsmHappyPath').textContent = (data.happyPath || []).join(' → ');
      document.getElementById('fsmFailurePath').textContent = (data.failurePath || []).join(' → ') || '(无)';
      document.getElementById('fsmTimeoutPath').textContent = (data.timeoutPath || []).join(' → ') || '(无)';

      // 渲染状态图
      _svgEl = document.getElementById('fsmSvg');
      renderDiagram();

      // 更新日志
      updateLogPanel();
      updateControlButtons();

      // 显示模态框
      document.getElementById('fsmModalOverlay').style.display = 'flex';
      _modalVisible = true;
    });
  }

  function close() {
    document.getElementById('fsmModalOverlay').style.display = 'none';
    _modalVisible = false;
    if (_autoPlayTimer) { clearTimeout(_autoPlayTimer); _autoPlayTimer = null; }
  }

  // ---- SVG 渲染 ----
  function renderDiagram() {
    var d = _fsmData;
    var states = d.states;
    var trans = d.transitions;

    // 计算网格范围
    var minX = 0, maxX = 0, minY = 0, maxY = 0;
    states.forEach(function(s) {
      if (s.x < minX) minX = s.x;
      if (s.x > maxX) maxX = s.x;
      if (s.y < minY) minY = s.y;
      if (s.y > maxY) maxY = s.y;
    });

    var svgW = (maxX - minX + 2) * COL_GAP + PAD_LEFT * 2;
    var svgH = (maxY - minY + 2) * ROW_GAP + PAD_TOP * 2;

    _svgEl.setAttribute('viewBox', '0 0 ' + svgW + ' ' + svgH);
    _svgEl.innerHTML = '';

    // 添加 defs (箭头标记 + 滤镜)
    var defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
    defs.innerHTML =
      '<marker id="fsmArrowCmd" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">' +
        '<path d="M0,0 L8,3 L0,6 Z" fill="#58a6ff"/></marker>' +
      '<marker id="fsmArrowTimer" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">' +
        '<path d="M0,0 L8,3 L0,6 Z" fill="#f59e0b"/></marker>' +
      '<marker id="fsmArrowFail" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">' +
        '<path d="M0,0 L8,3 L0,6 Z" fill="#f85149"/></marker>' +
      '<marker id="fsmArrowRecovery" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">' +
        '<path d="M0,0 L8,3 L0,6 Z" fill="#3fb950"/></marker>' +
      '<marker id="fsmArrowChild" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">' +
        '<path d="M0,0 L8,3 L0,6 Z" fill="#a855f7"/></marker>' +
      '<filter id="fsmGlowActive"><feGaussianBlur stdDeviation="3" result="blur"/>' +
        '<feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge></filter>' +
      '<filter id="fsmGlowTraversed"><feGaussianBlur stdDeviation="2" result="blur"/>' +
        '<feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge></filter>' +
      '<filter id="fsmGlowAvailable"><feGaussianBlur stdDeviation="2" result="blur"/>' +
        '<feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge></filter>';
    _svgEl.appendChild(defs);

    // 主图层
    var g = document.createElementNS('http://www.w3.org/2000/svg', 'g');

    // 1. 先画转换 (箭头在节点下方)
    trans.forEach(function(t) {
      var fromS = findState(t.from);
      var toS = findState(t.to);
      if (!fromS || !toS) return;

      var fx = gridX(fromS.x), fy = gridY(fromS.y);
      var tx = gridX(toS.x), ty = gridY(toS.y);

      var edgeColor = EDGE_COLORS[t.type] || '#8b949e';
      var markerId = 'url(#fsmArrow' + (t.type === 'command' ? 'Cmd' :
                       t.type === 'timer' ? 'Timer' :
                       t.type === 'failure' ? 'Fail' :
                       t.type === 'recovery' ? 'Recovery' : 'Child') + ')';

      var pathD = computeEdgePath(fx, fy, tx, ty, fromS, toS);
      var isTraversed = isTransitionTraversed(t);
      var isAvailable = isTransitionAvailable(t);

      var strokeDash = (t.type === 'timer') ? '6,3' : (t.type === 'recovery') ? '4,2' : 'none';
      var strokeW = isTraversed ? 3 : (isAvailable ? 2.5 : 1.5);
      var strokeCol = isTraversed ? '#3fb950' : (isAvailable ? '#f59e0b' : edgeColor);
      var opacity = isTraversed || isAvailable ? 1 : 0.35;

      var edgeGroup = document.createElementNS('http://www.w3.org/2000/svg', 'g');
      edgeGroup.setAttribute('class', 'fsm-edge');
      edgeGroup.setAttribute('data-trans-id', t.id);
      edgeGroup.style.cursor = isAvailable ? 'pointer' : 'default';
      edgeGroup.style.opacity = opacity;

      var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', pathD);
      path.setAttribute('fill', 'none');
      path.setAttribute('stroke', strokeCol);
      path.setAttribute('stroke-width', strokeW);
      path.setAttribute('stroke-dasharray', strokeDash);
      path.setAttribute('marker-end', markerId);
      edgeGroup.appendChild(path);

      // 事件标签 (在路径中点)
      var midPt = getPathMidpoint(fx, fy, tx, ty, fromS, toS);
      var label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      label.setAttribute('x', midPt.x);
      label.setAttribute('y', midPt.y - 6);
      label.setAttribute('text-anchor', 'middle');
      label.setAttribute('fill', isTraversed ? '#3fb950' : (isAvailable ? '#f59e0b' : '#8b949e'));
      label.setAttribute('font-size', '9');
      label.setAttribute('font-family', 'var(--mono, monospace)');
      label.textContent = t.label || t.event;
      edgeGroup.appendChild(label);

      // 点击可用转换
      if (isAvailable) {
        edgeGroup.addEventListener('click', function() { executeTransition(t); });
      }

      g.appendChild(edgeGroup);
      _transElems[t.id] = edgeGroup;
    });

    // 2. 再画节点
    states.forEach(function(s) {
      var sx = gridX(s.x) - NODE_W / 2;
      var sy = gridY(s.y) - NODE_H / 2;
      var isCurrent = s.id === _currentState;
      var isTraversed = _stateHistory.indexOf(s.id) >= 0;
      var isAvailable = isCurrent && !isTerminal(s);

      var style = COLORS[s.type || 'normal'];
      if (isCurrent) style = COLORS.active;
      else if (isTraversed) style = COLORS.traversed;
      if (isAvailable) style = COLORS.available;

      var nodeGroup = document.createElementNS('http://www.w3.org/2000/svg', 'g');
      nodeGroup.setAttribute('class', 'fsm-node');
      nodeGroup.setAttribute('data-state-id', s.id);
      nodeGroup.style.cursor = isAvailable ? 'pointer' : 'default';

      // 发光效果
      var filter = '';
      if (isCurrent) filter = 'url(#fsmGlowActive)';
      else if (isTraversed) filter = 'url(#fsmGlowTraversed)';
      else if (isAvailable) filter = 'url(#fsmGlowAvailable)';

      // 矩形
      var rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      rect.setAttribute('x', sx);
      rect.setAttribute('y', sy);
      rect.setAttribute('width', NODE_W);
      rect.setAttribute('height', NODE_H);
      rect.setAttribute('rx', NODE_RX);
      rect.setAttribute('ry', NODE_RX);
      rect.setAttribute('fill', style.fill);
      rect.setAttribute('stroke', style.stroke);
      rect.setAttribute('stroke-width', s.type === 'terminal' ? '3' : (isCurrent ? '2.5' : '1.5'));
      if (filter) rect.setAttribute('filter', filter);
      nodeGroup.appendChild(rect);

      // 如果是 terminal 类型，画双线边框
      if (s.type === 'terminal') {
        var innerRect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
        innerRect.setAttribute('x', sx + 3);
        innerRect.setAttribute('y', sy + 3);
        innerRect.setAttribute('width', NODE_W - 6);
        innerRect.setAttribute('height', NODE_H - 6);
        innerRect.setAttribute('rx', NODE_RX - 2);
        innerRect.setAttribute('ry', NODE_RX - 2);
        innerRect.setAttribute('fill', 'none');
        innerRect.setAttribute('stroke', style.stroke);
        innerRect.setAttribute('stroke-width', '1');
        nodeGroup.appendChild(innerRect);
      }

      // 如果是 initial 类型，画起点圆
      if (s.type === 'initial') {
        var circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
        circle.setAttribute('cx', sx - 12);
        circle.setAttribute('cy', sy + NODE_H / 2);
        circle.setAttribute('r', '5');
        circle.setAttribute('fill', '#3fb950');
        nodeGroup.appendChild(circle);
      }

      // 标签
      var label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      label.setAttribute('x', sx + NODE_W / 2);
      label.setAttribute('y', sy + NODE_H / 2 + 4);
      label.setAttribute('text-anchor', 'middle');
      label.setAttribute('fill', isCurrent ? '#58a6ff' : '#e6edf3');
      label.setAttribute('font-size', '11');
      label.setAttribute('font-weight', isCurrent ? '700' : '500');
      label.setAttribute('font-family', 'var(--font, sans-serif)');
      label.textContent = s.label;
      nodeGroup.appendChild(label);

      // 点击当前状态 → 显示可用的转换
      if (isAvailable) {
        nodeGroup.addEventListener('click', function() { highlightAvailableTransitions(s.id); });
      }

      g.appendChild(nodeGroup);
      _stateElems[s.id] = nodeGroup;
    });

    _svgEl.appendChild(g);
  }

  // ---- 坐标计算 ----
  function gridX(col) { return PAD_LEFT + (col + 1) * COL_GAP; }
  function gridY(row) { return PAD_TOP + (row + 1) * ROW_GAP; }

  function findState(id) {
    return _fsmData.states.find(function(s) { return s.id === id; });
  }

  function isTerminal(s) {
    return s.type === 'terminal';
  }

  function isTransitionTraversed(t) {
    // 如果 from 和 to 都在历史中，且是相邻的转换
    var idxFrom = _stateHistory.indexOf(t.from);
    var idxTo = _stateHistory.indexOf(t.to);
    return idxFrom >= 0 && idxTo === idxFrom + 1;
  }

  function isTransitionAvailable(t) {
    return t.from === _currentState;
  }

  // ---- 边路径计算 ----
  function computeEdgePath(x1, y1, x2, y2, fromS, toS) {
    // 自循环 (回环到自身)
    if (fromS.id === toS.id) {
      return ['M', x1, y1 - NODE_H/2,
              'C', x1 - 40, y1 - NODE_H/2 - 30, x1 - 40, y1 - NODE_H/2, x1, y1 - NODE_H/2 + 6].join(' ');
    }

    // 垂直向下或向上
    if (fromS.x === toS.x) {
      var yStart = y1 + NODE_H / 2;
      var yEnd = y2 - NODE_H / 2;
      if (y2 < y1) {
        // 向上: 从节点左侧绕出
        var xLeft = x1 - NODE_W / 2 - 10;
        return ['M', x1 - NODE_W/2, y1,
                'C', xLeft, y1, xLeft, y2, x2 - NODE_W/2, y2].join(' ');
      }
      return ['M', x1, yStart, 'C', x1, (yStart + yEnd) / 2, x2, (yStart + yEnd) / 2, x2, yEnd].join(' ');
    }

    // 水平方向
    var xStart = fromS.x < toS.x ? x1 + NODE_W / 2 : x1 - NODE_W / 2;
    var xEnd   = fromS.x < toS.x ? x2 - NODE_W / 2 : x2 + NODE_W / 2;

    // 同时有水平和垂直偏移
    if (fromS.y !== toS.y) {
      var midX = (x1 + x2) / 2;
      return ['M', xStart, y1,
              'C', midX, y1, midX, y2, xEnd, y2].join(' ');
    }

    return ['M', xStart, y1, 'C', (xStart + xEnd) / 2, y1 - 20, (xStart + xEnd) / 2, y2 - 20, xEnd, y2].join(' ');
  }

  function getPathMidpoint(x1, y1, x2, y2, fromS, toS) {
    var mx = (x1 + x2) / 2;
    var my = (y1 + y2) / 2;
    if (fromS.id === toS.id) {
      return { x: x1 - 40, y: y1 - NODE_H/2 - 34 };
    }
    // 垂直方向，标签偏移到侧边
    if (fromS.x === toS.x && fromS.y !== toS.y) {
      mx += 55;
    }
    if (fromS.x !== toS.x && fromS.y !== toS.y) {
      my -= 10;
    }
    return { x: mx, y: my };
  }

  // ---- 交互逻辑 ----
  function highlightAvailableTransitions(stateId) {
    // 重新渲染以显示可用转换
    renderDiagram();
    // 高亮可用转换已经通过 isAvailable 实现
  }

  function executeTransition(t) {
    if (!isTransitionAvailable(t)) return;

    var fromState = findState(t.from);
    var toState = findState(t.to);

    _currentState = t.to;
    _stateHistory.push(t.to);

    _logCounter++;
    var ts = ('0' + Math.floor(_logCounter / 60)).slice(-2) + ':' +
              ('0' + (_logCounter % 60)).slice(-2);
    _eventLog.push({
      ts: ts,
      event: t.event,
      from: t.from,
      fromLabel: fromState ? fromState.label : t.from,
      to: t.to,
      toLabel: toState ? toState.label : t.to,
      type: t.type
    });

    updateLogPanel();
    updateControlButtons();
    renderDiagram();

    // 到达终态时闪烁提示
    if (isTerminal(toState)) {
      showToast('已到达终态: ' + toState.label);
    }
  }

  function stepForward() {
    if (!_fsmData) return;
    var available = _fsmData.transitions.filter(function(t) {
      return t.from === _currentState;
    });
    if (available.length === 0) {
      showToast('已到达终态，无可用转换');
      return;
    }
    // 优先走 happy path 中的下一状态
    var hpIdx = _fsmData.happyPath.indexOf(_currentState);
    var chosen = null;
    if (hpIdx >= 0 && hpIdx + 1 < _fsmData.happyPath.length) {
      var nextHp = _fsmData.happyPath[hpIdx + 1];
      chosen = available.find(function(t) { return t.to === nextHp; });
    }
    if (!chosen) chosen = available[0];
    executeTransition(chosen);
  }

  function autoPlay() {
    if (_autoPlayTimer) {
      clearTimeout(_autoPlayTimer);
      _autoPlayTimer = null;
      document.getElementById('fsmBtnAutoPlay').textContent = '▶ 自动播放';
      return;
    }
    document.getElementById('fsmBtnAutoPlay').textContent = '⏸ 暂停';
    _autoStep();
  }

  function _autoStep() {
    if (!_fsmData) return;
    var available = _fsmData.transitions.filter(function(t) {
      return t.from === _currentState;
    });
    if (available.length === 0 || isTerminal(findState(_currentState))) {
      document.getElementById('fsmBtnAutoPlay').textContent = '▶ 自动播放';
      _autoPlayTimer = null;
      showToast('自动播放完成');
      return;
    }
    // 默认走 happy path
    var hpIdx = _fsmData.happyPath.indexOf(_currentState);
    var chosen = null;
    if (hpIdx >= 0 && hpIdx + 1 < _fsmData.happyPath.length) {
      var nextHp = _fsmData.happyPath[hpIdx + 1];
      chosen = available.find(function(t) { return t.to === nextHp; });
    }
    if (!chosen) chosen = available[0];
    executeTransition(chosen);
    _autoPlayTimer = setTimeout(_autoStep, 1200);
  }

  function injectFault() {
    if (!_fsmData) return;
    // 找到从当前状态出发的 failure 类型转换
    var failTrans = _fsmData.transitions.filter(function(t) {
      return t.from === _currentState && t.type === 'failure';
    });
    if (failTrans.length === 0) {
      // 尝试走 timeout path
      failTrans = _fsmData.transitions.filter(function(t) {
        return t.from === _currentState && t.type === 'timer';
      });
    }
    if (failTrans.length === 0) {
      showToast('当前状态无可注入的故障转换');
      return;
    }
    // 停止自动播放，注入故障
    if (_autoPlayTimer) { clearTimeout(_autoPlayTimer); _autoPlayTimer = null; }
    document.getElementById('fsmBtnAutoPlay').textContent = '▶ 自动播放';
    executeTransition(failTrans[0]);
  }

  function reset() {
    if (!_fsmData) return;
    if (_autoPlayTimer) { clearTimeout(_autoPlayTimer); _autoPlayTimer = null; }
    document.getElementById('fsmBtnAutoPlay').textContent = '▶ 自动播放';
    _currentState = _fsmData.initialState;
    _stateHistory = [_fsmData.initialState];
    _eventLog = [];
    _logCounter = 0;
    updateLogPanel();
    updateControlButtons();
    renderDiagram();
    showToast('已重置');
  }

  // ---- UI 更新 ----
  function updateLogPanel() {
    var logEl = document.getElementById('fsmEventLog');
    if (!logEl) return;
    if (_eventLog.length === 0) {
      logEl.innerHTML = '<div class="fsm-log-entry info">等待状态转换...</div>';
      return;
    }
    var html = '';
    _eventLog.forEach(function(e) {
      html += '<div class="fsm-log-entry ' + e.type + '">' +
        '<span class="ts">' + e.ts + '</span>' +
        '<span class="evt">' + e.event + '</span>: ' +
        '<span class="st">' + e.fromLabel + '</span> → ' +
        '<span class="st">' + e.toLabel + '</span></div>';
    });
    logEl.innerHTML = html;
    logEl.scrollTop = logEl.scrollHeight;
  }

  function updateControlButtons() {
    if (!_fsmData) return;
    var cs = findState(_currentState);
    var isEnd = cs && isTerminal(cs);
    document.getElementById('fsmBtnStep').disabled = isEnd;

    var hasFault = _fsmData.transitions.some(function(t) {
      return t.from === _currentState && (t.type === 'failure' || t.type === 'timer');
    });
    document.getElementById('fsmBtnFault').disabled = !hasFault;
  }

  function showToast(msg) {
    var toast = document.getElementById('fsmToast');
    if (!toast) return;
    toast.textContent = msg;
    toast.classList.add('show');
    setTimeout(function() { toast.classList.remove('show'); }, 2000);
  }

  // ---- 公开 API ----
  return {
    init: init,
    open: open,
    close: close,
    stepForward: stepForward,
    autoPlay: autoPlay,
    injectFault: injectFault,
    reset: reset
  };
})();

// 页面加载后初始化
document.addEventListener('DOMContentLoaded', function() {
  FSMViewer.init();
});

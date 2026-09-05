/**
 * ===== M3.5 Cinema Director =====
 * 写实镜头导演系统：
 *   1. 运输阶段 —— 镜头切换到天车（OHT），跟随 FOUP 沿 AMHS 轨道环移动（推近 2.2x）
 *   2. 加工阶段 —— 镜头聚焦到目标设备区，弹出写实风格腔室特写（CAM 监视器画中画），
 *      设备内部 FSM（装载 → 对准 → 工艺 → 卸载）与真实管线事件同步演出
 *   3. 其余时间 —— 全景俯瞰
 *
 * 事件源：fab_m35_observable.js 的 _m35Streams（与写侧管线事件同源，天然协同）。
 * 全部为增量渲染：主 SVG 只动 viewBox（摄像机），不破坏既有图元。
 */
(function () {
  'use strict';

  var MAX_WAIT = 20000;

  // ============================================================
  // Camera
  // ============================================================
  var BASE = { x: 0, y: 0, w: 1000, h: 440 };
  var ASPECT = 1000 / 440;
  var cam = { x: 0, y: 0, w: 1000, h: 440 };
  var camT = { x: 0, y: 0, w: 1000, h: 440 };
  var mode = 'overview';           // overview | follow | focus
  var followUntil = 0;
  var focusHoldUntil = 0;
  var autoDirector = true;
  var foupEl = null, svgEl = null;

  function ensureEls() {
    if (!svgEl) svgEl = document.getElementById('factorySvg');
    if (!foupEl) foupEl = document.getElementById('foupIcon');
    return svgEl && foupEl;
  }

  function clampCam(v) {
    v.w = Math.min(Math.max(v.w, 220), 1000);
    v.h = v.w / ASPECT;
    v.x = Math.min(Math.max(v.x, 0), 1000 - v.w);
    v.y = Math.min(Math.max(v.y, 0), 440 - v.h);
    return v;
  }

  function camToCenter(cx, cy, w) {
    var t = { x: cx - w / 2, y: cy - w / ASPECT / 2, w: w, h: w / ASPECT };
    camT = clampCam(t);
  }

  function overview() {
    if (!autoDirector) return;
    mode = 'overview';
    camT = { x: 0, y: 0, w: 1000, h: 440 };
  }

  function followFoup(etaMs) {
    if (!autoDirector) return;
    mode = 'follow';
    followUntil = Date.now() + (etaMs || 1500) + 1800;
  }

  function focusEquipment(equipmentId, chamber) {
    if (!autoDirector) return;
    mode = 'focus';
    focusHoldUntil = Date.now() + (chamber.durationMs || 3000) + 900;
    var nodeId = (equipmentId || '').replace('-01', '').toLowerCase();
    var node = document.getElementById('eq-' + nodeId);
    if (!node) return;
    var r = node.querySelector('rect');
    if (!r) return;
    var cx = parseFloat(r.getAttribute('x')) + parseFloat(r.getAttribute('width')) / 2;
    var cy = parseFloat(r.getAttribute('y')) + 20;
    camToCenter(cx, cy, 360);
  }

  function cameraFrame() {
    if (!ensureEls()) { requestAnimationFrame(cameraFrame); return; }
    if (autoDirector) {
      var now = Date.now();
      if (mode === 'follow') {
        if (now > followUntil) overview();
        else {
          var fx = parseFloat(foupEl.getAttribute('x')) + 8;
          var fy = parseFloat(foupEl.getAttribute('y')) + 5;
          camToCenter(fx, fy, 430);
        }
      } else if (mode === 'focus' && now > focusHoldUntil) {
        overview();
      }
    } else if (mode !== 'overview') {
      overview();
    }
    // smooth approach
    var k = 0.10;
    cam.x += (camT.x - cam.x) * k;
    cam.y += (camT.y - cam.y) * k;
    cam.w += (camT.w - cam.w) * k;
    cam.h += (camT.h - cam.h) * k;
    svgEl.setAttribute('viewBox', cam.x + ' ' + cam.y + ' ' + cam.w + ' ' + cam.h);
    requestAnimationFrame(cameraFrame);
  }

  // ============================================================
  // Equipment chamber cinema (CAM 监视器画中画 + 内部 FSM)
  // ============================================================
  var overlay = null;
  var cinemaSvg = null;
  var cinemaScene = null;      // { type, elements..., fsm }
  var cinemaTimer = null;
  var FSM_PHASES = [
    { name: '装载', upTo: 0.15 },
    { name: '对准', upTo: 0.30 },
    { name: '工艺', upTo: 0.85 },
    { name: '卸载', upTo: 1.00 }
  ];

  var svgNS = 'http://www.w3.org/2000/svg';

  function el(tag, attrs, parent) {
    var n = document.createElementNS(svgNS, tag);
    for (var k in attrs) n.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(n);
    return n;
  }

  function ensureOverlay() {
    if (overlay) return overlay;
    overlay = document.createElement('div');
    overlay.id = 'eqCinema';
    overlay.style.cssText = 'position:fixed;right:18px;top:96px;width:430px;z-index:60;' +
      'background:#05070d;border:1px solid #2b3a55;border-radius:8px;overflow:hidden;' +
      'box-shadow:0 8px 40px rgba(0,0,0,.65);display:none;font-family:monospace';
    overlay.innerHTML =
      '<div style="display:flex;justify-content:space-between;align-items:center;padding:6px 10px;' +
        'background:#0a1120;border-bottom:1px solid #2b3a55">' +
        '<span style="color:#58a6ff;font-size:12px">CAM · <span id="cinemaEquip">--</span></span>' +
        '<span style="color:#f85149;font-size:11px" id="cinemaRec">● REC</span>' +
      '</div>' +
      '<svg id="cinemaSvg" viewBox="0 0 400 240" style="display:block;width:100%;background:#070b12"></svg>' +
      '<div id="cinemaFsm" style="display:flex;gap:6px;padding:6px 10px;background:#0a1120"></div>';
    document.body.appendChild(overlay);
    return overlay;
  }

  function fsmChips(progress) {
    var box = document.getElementById('cinemaFsm');
    if (!box) return;
    var html = '';
    FSM_PHASES.forEach(function (ph, i) {
      var active = progress >= (i === 0 ? 0 : FSM_PHASES[i - 1].upTo) && progress < ph.upTo;
      var done = progress >= ph.upTo;
      var color = active ? '#f59e0b' : (done ? '#2ea043' : '#30363d');
      html += '<span style="flex:1;text-align:center;font-size:11px;padding:3px 0;border:1px solid ' +
        color + ';color:' + (active || done ? color : '#4a5568') + ';border-radius:3px">' + ph.name + '</span>';
    });
    box.innerHTML = html;
  }

  function chamberAreaType(equipmentId) {
    var s = (equipmentId || '').toUpperCase();
    if (s.indexOf('LITHO') >= 0) return 'litho';
    if (s.indexOf('CDSEM') >= 0 || s.indexOf('MET') >= 0) return 'met';
    return 'generic';
  }

  /** 写实腔室场景：按设备类型绘制不同逼真内构 */
  function buildScene(type, equipmentId) {
    cinemaSvg = document.getElementById('cinemaSvg');
    while (cinemaSvg.firstChild) cinemaSvg.removeChild(cinemaSvg.firstChild);

    var defs = el('defs', {}, cinemaSvg);
    var glow = el('radialGradient', { id: 'procGlow', cx: '50%', cy: '50%', r: '50%' }, defs);
    el('stop', { offset: '0%', 'stop-color': '#f59e0b', 'stop-opacity': '0.9' }, glow);
    el('stop', { offset: '100%', 'stop-color': '#f59e0b', 'stop-opacity': '0' }, glow);
    var chamberGrad = el('linearGradient', { id: 'chamberGrad', x1: '0', y1: '0', x2: '0', y2: '1' }, defs);
    el('stop', { offset: '0%', 'stop-color': '#141b2b' }, chamberGrad);
    el('stop', { offset: '100%', 'stop-color': '#0a0f1a' }, chamberGrad);

    // 腔室外壳
    el('rect', { x: 8, y: 8, width: 384, height: 224, rx: 10, fill: 'url(#chamberGrad)', stroke: '#3b4a66', 'stroke-width': 1.5 }, cinemaSvg);
    // 观察窗
    el('rect', { x: 300, y: 16, width: 80, height: 40, rx: 4, fill: '#0d1420', stroke: '#3b4a66' }, cinemaSvg);
    el('text', { x: 340, y: 40, 'text-anchor': 'middle', 'font-size': 10, fill: '#4a5568' }, cinemaSvg)
      .textContent = 'VIEW PORT';

    var scene = { type: type, els: {} };

    // 公共：晶圆（片心）
    scene.els.wafer = el('circle', { cx: 200, cy: 150, r: 26, fill: '#1f2a3d', stroke: '#8b9dc3', 'stroke-width': 2 }, cinemaSvg);
    scene.els.waferGlow = el('circle', { cx: 200, cy: 150, r: 34, fill: 'url(#procGlow)', opacity: 0 }, cinemaSvg);
    // 装载臂（两段），绕右铰点旋转
    scene.els.arm = el('g', {}, cinemaSvg);
    el('rect', { x: 330, y: 146, width: 52, height: 8, rx: 3, fill: '#4a5568' }, scene.els.arm);
    el('circle', { cx: 336, cy: 150, r: 6, fill: '#6e7681' }, scene.els.arm);
    scene.els.arm.style.transition = 'transform 0.5s ease';
    scene.els.arm.style.transformOrigin = '336px 150px';

    if (type === 'litho') {
      // 光刻：掩模版 + 曝光光束 + 工件台扫描
      scene.els.reticle = el('rect', { x: 160, y: 40, width: 80, height: 56, rx: 3, fill: '#101828', stroke: '#58a6ff', 'stroke-width': 1.5 }, cinemaSvg);
      el('line', { x1: 176, y1: 56, x2: 224, y2: 56, stroke: '#58a6ff', 'stroke-width': 1 }, scene.els.reticle);
      el('line', { x1: 176, y1: 68, x2: 224, y2: 68, stroke: '#58a6ff', 'stroke-width': 1 }, scene.els.reticle);
      el('line', { x1: 176, y1: 80, x2: 224, y2: 80, stroke: '#58a6ff', 'stroke-width': 1 }, scene.els.reticle);
      scene.els.beam = el('polygon', { points: '170,96 230,96 226,140 174,140', fill: '#58a6ff', opacity: 0 }, cinemaSvg);
      scene.els.stage = el('rect', { x: 150, y: 178, width: 100, height: 14, rx: 3, fill: '#101828', stroke: '#3b4a66' }, cinemaSvg);
    } else if (type === 'met') {
      // 量测（CDSEM）：电子枪 + 扫描束 + 能谱条纹
      el('rect', { x: 184, y: 24, width: 32, height: 52, rx: 6, fill: '#101828', stroke: '#a855f7', 'stroke-width': 1.5 }, cinemaSvg);
      el('text', { x: 200, y: 55, 'text-anchor': 'middle', 'font-size': 9, fill: '#a855f7' }, cinemaSvg).textContent = 'e-gun';
      scene.els.beam = el('line', { x1: 200, y1: 78, x2: 200, y2: 122, stroke: '#a855f7', 'stroke-width': 2, opacity: 0 }, cinemaSvg);
      scene.els.scan = el('line', { x1: 180, y1: 150, x2: 180, y2: 130, stroke: '#a855f7', 'stroke-width': 1, opacity: 0 }, cinemaSvg);
      scene.els.spectrum = el('polyline', { points: '', fill: 'none', stroke: '#2ea043', 'stroke-width': 1.2, opacity: 0.85 }, cinemaSvg);
    } else {
      // 通用工艺腔：工艺辉光
      scene.els.glowPulse = el('circle', { cx: 200, cy: 150, r: 40, fill: 'url(#procGlow)', opacity: 0 }, cinemaSvg);
    }
    return scene;
  }

  /** FSM 演出：progress ∈ [0,1] 由真实 estimatedMs 驱动 */
  function renderCinemaFrame() {
    if (!cinemaScene) { requestAnimationFrame(renderCinemaFrame); return; }
    var now = performance.now();
    var progress = Math.min((now - cinemaScene.startedAt) / cinemaScene.durationMs, 1);
    var E = cinemaScene.els;
    fsmChips(progress);

    var phaseIdx = 0;
    FSM_PHASES.forEach(function (ph, i) { if (progress >= (i === 0 ? 0 : FSM_PHASES[i - 1].upTo)) phaseIdx = i; });
    var localT = (progress - (phaseIdx === 0 ? 0 : FSM_PHASES[phaseIdx - 1].upTo)) /
                 (FSM_PHASES[phaseIdx].upTo - (phaseIdx === 0 ? 0 : FSM_PHASES[phaseIdx - 1].upTo) || 1);

    if (phaseIdx === 0) { // 装载：机械臂旋入 + 晶圆滑入
      cinemaScene.els.arm.style.transform = 'rotate(' + (-18 + localT * 18) + 'deg)';
      E.wafer.setAttribute('cx', 200 - (1 - localT) * 120);
    } else if (phaseIdx === 1) { // 对准：晶圆旋入位
      E.wafer.setAttribute('cx', 200);
      E.wafer.setAttribute('stroke', '#f59e0b');
    } else {
      E.wafer.setAttribute('cx', 200);
      E.wafer.setAttribute('stroke', '#8b9dc3');
    }

    if (phaseIdx === 2) { // 工艺
      if (cinemaScene.type === 'litho') {
        E.beam.setAttribute('opacity', 0.25 + 0.35 * Math.abs(Math.sin(now / 90)));
        E.waferGlow.setAttribute('opacity', 0.8);
        E.wafer.setAttribute('cx', 200 + Math.sin(now / 120) * 24); // 工件台扫描
      } else if (cinemaScene.type === 'met') {
        E.beam.setAttribute('opacity', 0.9);
        E.scan.setAttribute('x1', 180 + (now / 6) % 40);
        E.scan.setAttribute('x2', 180 + (now / 6) % 40);
        E.scan.setAttribute('opacity', 0.9);
        E.waferGlow.setAttribute('opacity', 0.5);
        var pts = [];
        for (var x = 0; x <= 120; x += 6) {
          pts.push((140 + x) + ',' + (210 - 18 * Math.abs(Math.sin((x + now / 60) / 9))));
        }
        E.spectrum.setAttribute('points', pts.join(' '));
        E.spectrum.setAttribute('opacity', 0.85);
      } else {
        E.glowPulse.setAttribute('opacity', 0.5 + 0.4 * Math.sin(now / 100));
      }
    } else {
      ['beam', 'scan'].forEach(function (k) { if (E[k]) E[k].setAttribute('opacity', 0); });
      if (E.waferGlow) E.waferGlow.setAttribute('opacity', 0);
      if (E.glowPulse) E.glowPulse.setAttribute('opacity', 0);
      if (E.spectrum) E.spectrum.setAttribute('opacity', 0);
      if (phaseIdx === 3) { // 卸载：机械臂旋出 + 晶圆滑出
        cinemaScene.els.arm.style.transform = 'rotate(' + (-localT * 18) + 'deg)';
        E.wafer.setAttribute('cx', 200 + localT * 120);
      }
    }

    if (progress < 1) requestAnimationFrame(renderCinemaFrame);
  }

  function openChamber(data) {
    ensureOverlay();
    overlay.style.display = 'block';
    document.getElementById('cinemaEquip').textContent = data.equipmentId + ' · ' + (data.recipeId || '');
    cinemaScene = {
      type: chamberAreaType(data.equipmentId),
      durationMs: Math.max(500, (data.estimatedMs || 2000) / 1),
      startedAt: performance.now(),
      els: {}
    };
    buildScene(cinemaScene.type, data.equipmentId);
    fsmChips(0);
    requestAnimationFrame(renderCinemaFrame);
  }

  function closeChamber() {
    cinemaScene = null;
    if (overlay) overlay.style.display = 'none';
  }

  // ============================================================
  // Wire-up
  // ============================================================
  function start() {
    if (!window._m35Streams) {
      if ((MAX_WAIT -= 300) <= 0) return;
      return setTimeout(start, 300);
    }
    var S = window._m35Streams;
    var sub = window._m35Subscribe;

    // —— 镜头导演 ——
    sub(S.foupInTransit$, function (data) {
      followFoup(data.etaMs);
      openChamber({ equipmentId: 'OHT ' + (data.fromArea || '') + '→' + (data.toArea || ''), estimatedMs: data.etaMs, recipeId: 'TRANSPORT' });
    });
    sub(S.processingStart$, function (data) {
      focusEquipment(data.equipmentId, { durationMs: data.estimatedMs });
      openChamber(data);
    });
    sub(S.processingDone$, function (data) {
      setTimeout(closeChamber, 700);
    });
    sub(S.recoveryEvent$, function () {
      closeChamber();
      overview();
    });

    // —— 镜头开关 ——
    var btn = document.createElement('button');
    btn.textContent = '🎥 自动镜头: 开';
    btn.style.cssText = 'position:fixed;left:14px;bottom:14px;z-index:70;background:#161b22;color:#58a6ff;' +
      'border:1px solid #3b4a66;border-radius:5px;padding:5px 12px;cursor:pointer;font-size:12px';
    btn.onclick = function () {
      autoDirector = !autoDirector;
      btn.textContent = autoDirector ? '🎥 自动镜头: 开' : '🎥 自动镜头: 关';
      if (!autoDirector) { camT = { x: 0, y: 0, w: 1000, h: 440 }; closeChamber(); }
    };
    document.body.appendChild(btn);

    setCamLoop();
    function setCamLoop() { ensureEls(); requestAnimationFrame(cameraFrame); }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();

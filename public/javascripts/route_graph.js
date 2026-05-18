/**
 * Route Graph Visualizer — renders a configurable Fab route as an SVG flowchart.
 *
 * Node types: equipment, transport, decision, saga, classify, hold
 * Edge types: material (solid), exception (dashed), ocap (dotted)
 *
 * Color scheme matches the architecture plan:
 *   equipment: #4A90D9 (blue), decision: #F5A623 (orange),
 *   saga: #7ED321 (green) / #50E3C2 (teal merge),
 *   classify: #F8E71C (yellow), hold: #D0021B (red),
 *   transport: lightblue, subprocess: #B8E986
 */
(function (global) {
  'use strict';

  var SVG_NS = 'http://www.w3.org/2000/svg';

  var COLORS = {
    equipment:  { fill: '#1a3a5c', stroke: '#4A90D9', text: '#4A90D9' },
    transport:  { fill: '#1a2e3c', stroke: '#6ab0de', text: '#6ab0de' },
    decision:   { fill: '#3d2a0a', stroke: '#F5A623', text: '#F5A623' },
    saga:       { fill: '#1a3a1a', stroke: '#7ED321', text: '#7ED321' },
    merge:      { fill: '#1a3a2a', stroke: '#50E3C2', text: '#50E3C2' },
    classify:   { fill: '#3d380a', stroke: '#F8E71C', text: '#F8E71C' },
    hold:       { fill: '#3a1a1a', stroke: '#D0021B', text: '#D0021B' },
    subprocess: { fill: '#1a2a1a', stroke: '#B8E986', text: '#B8E986' }
  };

  function routeColor(nodeType, sagaType) {
    if (nodeType === 'saga') return sagaType === 'Merge' ? COLORS.merge : COLORS.saga;
    return COLORS[nodeType] || COLORS.equipment;
  }

  /**
   * Highlight OCAP-affected nodes with purple glow effect.
   * @param nodeIds array of node IDs to highlight with OCAP styling
   */
  global.highlightOcapNodes = function (nodeIds) {
    // First clear previous OCAP highlights
    document.querySelectorAll('#route-graph-svg .rg-node.rg-ocap').forEach(function (el) {
      el.classList.remove('rg-ocap');
    });
    nodeIds.forEach(function (nid) {
      var el = document.getElementById('rg-' + nid);
      if (el) el.classList.add('rg-ocap');
    });
  };

  /**
   * Show an OCAP-triggered path with animated purple glow on an edge.
   * Creates a temporary overlay path on the SVG that pulses.
   * @param fromId source node ID
   * @param toId   target node ID
   * @param label  optional label text for the path
   */
  global.highlightOcapPath = function (fromId, toId, label) {
    var svg = document.getElementById('route-graph-svg');
    if (!svg) return;
    var nodeMap = svg._nodeMap;
    if (!nodeMap) return;
    var from = nodeMap[fromId], to = nodeMap[toId];
    if (!from || !to) return;

    var x1 = from.x + from.w / 2, y1 = from.y + from.h;
    var x2 = to.x + to.w / 2, y2 = to.y;

    // Same routing logic as renderGraph
    if (Math.abs(from.y - to.y) < 30 && from.x < to.x) {
      x1 = from.x + from.w; y1 = from.y + from.h / 2;
      x2 = to.x; y2 = to.y + to.h / 2;
    }
    if (from.y < to.y && Math.abs(from.x - to.x) < 30) {
      x1 = from.x + from.w / 2; y1 = from.y + from.h;
      x2 = to.x + to.w / 2; y2 = to.y;
    }

    var cpY = (y1 + y2) / 2;
    var d = 'M' + x1 + ',' + y1 + ' C' + x1 + ',' + cpY + ' ' + x2 + ',' + cpY + ' ' + x2 + ',' + y2;

    // Remove previous OCAP paths
    svg.querySelectorAll('.ocap-dynamic-path').forEach(function (el) { el.remove(); });

    var path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('class', 'ocap-dynamic-path ocap-path-glow');
    path.setAttribute('d', d);
    path.setAttribute('stroke', '#a855f7');
    path.setAttribute('stroke-width', '2.5');
    path.setAttribute('fill', 'none');
    path.setAttribute('stroke-dasharray', '6 4');
    path.setAttribute('opacity', '0.9');
    path.setAttribute('marker-end', 'url(#rg-arrow-ocap)');
    svg.insertBefore(path, svg.querySelector('style'));

    if (label) {
      var labelEl = document.createElementNS(SVG_NS, 'text');
      labelEl.setAttribute('class', 'ocap-dynamic-path');
      labelEl.setAttribute('x', (x1 + x2) / 2 - 20);
      labelEl.setAttribute('y', cpY - 6);
      labelEl.setAttribute('fill', '#a855f7');
      labelEl.setAttribute('font-size', '10');
      labelEl.setAttribute('font-family', 'SF Mono, Fira Code, monospace');
      labelEl.setAttribute('font-weight', '700');
      labelEl.textContent = label || 'OCAP';
      svg.insertBefore(labelEl, svg.querySelector('style'));
    }
  };

  /**
   * Show a dynamic stage (dashed purple node) injected into the route graph.
   * @param stageType the type of stage injected (e.g. "ReworkLoop", "HoldRelease")
   * @param parentNodeId the node this stage branches from
   * @param stageIndex index for positioning
   */
  global.showDynamicStage = function (stageType, parentNodeId, stageIndex) {
    var svg = document.getElementById('route-graph-svg');
    if (!svg) return;
    // Remove previous dynamic stage nodes
    svg.querySelectorAll('.rg-dynamic-stage').forEach(function (el) { el.remove(); });

    var parentEl = document.getElementById('rg-' + parentNodeId);
    if (!parentEl) return;
    var parentNode = svg._nodeMap[parentNodeId];
    if (!parentNode) return;

    var ox = parentNode.x + parentNode.w + 20;
    var oy = parentNode.y + (stageIndex || 0) * 50;

    var g = document.createElementNS(SVG_NS, 'g');
    g.setAttribute('class', 'rg-node rg-dynamic-stage');
    g.setAttribute('transform', 'translate(' + ox + ',' + oy + ')');

    var rect = document.createElementNS(SVG_NS, 'rect');
    rect.setAttribute('width', 80);
    rect.setAttribute('height', 30);
    rect.setAttribute('rx', 6);
    rect.setAttribute('ry', 6);
    rect.setAttribute('fill', '#2a1a3a');
    rect.setAttribute('stroke', '#a855f7');
    rect.setAttribute('stroke-width', '2');
    rect.setAttribute('stroke-dasharray', '5 3');
    g.appendChild(rect);

    var label = document.createElementNS(SVG_NS, 'text');
    label.setAttribute('x', 40);
    label.setAttribute('y', 15);
    label.setAttribute('fill', '#a855f7');
    label.setAttribute('font-size', '9');
    label.setAttribute('font-family', 'SF Mono, Fira Code, monospace');
    label.setAttribute('text-anchor', 'middle');
    label.setAttribute('dominant-baseline', 'middle');
    label.setAttribute('font-weight', '700');
    label.textContent = stageType || 'OCAP';
    g.appendChild(label);

    svg.insertBefore(g, svg.querySelector('style'));
  };

  /**
   * Clear all OCAP highlights and dynamic stages.
   */
  global.clearOcapHighlights = function () {
    document.querySelectorAll('#route-graph-svg .rg-node.rg-ocap').forEach(function (el) {
      el.classList.remove('rg-ocap');
    });
    document.querySelectorAll('#route-graph-svg .ocap-dynamic-path').forEach(function (el) {
      el.remove();
    });
    document.querySelectorAll('#route-graph-svg .rg-dynamic-stage').forEach(function (el) {
      el.remove();
    });
  };

  // ---- Public API ----

  /**
   * Load route graph JSON from the API and render it.
   * @param scenarioId e.g. "send-ahead-pilot"
   * @param container DOM element to render the SVG into
   */
  global.loadRouteGraph = function (scenarioId, container) {
    fetch('/api/fab-demo/scenario/' + encodeURIComponent(scenarioId) + '/route-graph')
      .then(function (r) { return r.json(); })
      .then(function (graph) {
        renderGraph(graph, container);
      })
      .catch(function (err) {
        container.innerHTML = '<div style="color:#f85149;padding:12px">Route graph: ' + err.message + '</div>';
      });
  };

  /**
   * Highlight a specific node by ID.
   * @param nodeId the node to highlight, or null to clear
   */
  global.highlightRouteNode = function (nodeId) {
    document.querySelectorAll('#route-graph-svg .rg-node').forEach(function (el) {
      el.classList.remove('rg-active');
    });
    if (nodeId) {
      var el = document.getElementById('rg-' + nodeId);
      if (el) el.classList.add('rg-active');
    }
  };

  // ---- Render engine ----

  function renderGraph(graph, container) {
    var nodes = graph.nodes;
    var edges = graph.edges;
    var nodeMap = {};
    nodes.forEach(function (n) { nodeMap[n.id] = n; });

    // Calculate SVG viewBox
    var maxX = 0, maxY = 0;
    nodes.forEach(function (n) {
      var r = n.x + n.w + 20;
      var b = n.y + n.h + 20;
      if (r > maxX) maxX = r;
      if (b > maxY) maxY = b;
    });
    maxX = Math.max(maxX, 800);
    maxY = Math.max(maxY, 300);

    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('id', 'route-graph-svg');
    svg.setAttribute('viewBox', '0 0 ' + maxX + ' ' + maxY);
    svg.setAttribute('width', '100%');
    svg.setAttribute('height', '100%');
    svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    svg.style.overflow = 'visible';

    // Defs: arrow markers + glow filter
    var defs = document.createElementNS(SVG_NS, 'defs');
    defs.innerHTML =
      '<marker id="rg-arrow-material" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">' +
        '<polygon points="0 0, 8 3, 0 6" fill="#58a6ff"/>' +
      '</marker>' +
      '<marker id="rg-arrow-exception" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">' +
        '<polygon points="0 0, 8 3, 0 6" fill="#F5A623"/>' +
      '</marker>' +
      '<marker id="rg-arrow-ocap" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">' +
        '<polygon points="0 0, 8 3, 0 6" fill="#D0021B"/>' +
      '</marker>' +
      '<filter id="rg-glow"><feGaussianBlur stdDeviation="2" result="blur"/>' +
        '<feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge></filter>';
    svg.appendChild(defs);

    // Title bar
    var titleBg = document.createElementNS(SVG_NS, 'rect');
    titleBg.setAttribute('x', 0); titleBg.setAttribute('y', 0);
    titleBg.setAttribute('width', maxX); titleBg.setAttribute('height', 32);
    titleBg.setAttribute('fill', '#161b22'); titleBg.setAttribute('rx', 4);
    svg.appendChild(titleBg);

    var titleText = document.createElementNS(SVG_NS, 'text');
    titleText.setAttribute('x', 8); titleText.setAttribute('y', 21);
    titleText.setAttribute('fill', '#8b949e'); titleText.setAttribute('font-size', '12');
    titleText.setAttribute('font-family', 'SF Mono, Fira Code, monospace');
    titleText.textContent = graph.name + ' — ' + graph.description;
    svg.appendChild(titleText);

    // Edges
    edges.forEach(function (e) {
      var from = nodeMap[e.from], to = nodeMap[e.to];
      if (!from || !to) return;

      var x1 = from.x + from.w / 2, y1 = from.y + from.h;
      var x2 = to.x + to.w / 2, y2 = to.y;

      // For horizontal edges (same row), route from right to left
      if (Math.abs(from.y - to.y) < 30 && from.x < to.x) {
        x1 = from.x + from.w; y1 = from.y + from.h / 2;
        x2 = to.x; y2 = to.y + to.h / 2;
      }
      // For vertical edges (different rows)
      if (from.y < to.y && Math.abs(from.x - to.x) < 30) {
        x1 = from.x + from.w / 2; y1 = from.y + from.h;
        x2 = to.x + to.w / 2; y2 = to.y;
      }

      var isException = e.type === 'exception' || e.type === 'ocap';
      var markerEnd = isException ? 'url(#rg-arrow-exception)' : 'url(#rg-arrow-material)';
      var strokeColor = isException ? '#F5A623' : '#58a6ff';
      var dashArray = isException ? '5,4' : 'none';

      var path = document.createElementNS(SVG_NS, 'path');
      // Cubic bezier for smoother edges
      var cpY = (y1 + y2) / 2;
      var d = 'M' + x1 + ',' + y1 + ' C' + x1 + ',' + cpY + ' ' + x2 + ',' + cpY + ' ' + x2 + ',' + y2;
      path.setAttribute('d', d);
      path.setAttribute('stroke', strokeColor);
      path.setAttribute('stroke-width', isException ? '1.5' : '2');
      path.setAttribute('fill', 'none');
      path.setAttribute('stroke-dasharray', dashArray);
      path.setAttribute('marker-end', markerEnd);
      path.setAttribute('opacity', '0.6');
      svg.appendChild(path);

      // Edge label
      if (e.label) {
        var elText = document.createElementNS(SVG_NS, 'text');
        elText.setAttribute('x', (x1 + x2) / 2 - 15);
        elText.setAttribute('y', cpY - 4);
        elText.setAttribute('fill', isException ? '#F5A623' : '#8b949e');
        elText.setAttribute('font-size', '9');
        elText.setAttribute('font-family', 'SF Mono, Fira Code, monospace');
        elText.textContent = e.label;
        svg.appendChild(elText);
      }
    });

    // Nodes
    nodes.forEach(function (n) {
      var g = document.createElementNS(SVG_NS, 'g');
      g.setAttribute('id', 'rg-' + n.id);
      g.setAttribute('class', 'rg-node');
      g.setAttribute('transform', 'translate(' + n.x + ',' + n.y + ')');

      var color = routeColor(n.type, n.sagaType);
      var rx = n.type === 'decision' ? n.w / 2 : 6; // diamond → circle-ish via rx

      // Background rect
      var rect = document.createElementNS(SVG_NS, 'rect');
      rect.setAttribute('width', n.w);
      rect.setAttribute('height', n.h);
      rect.setAttribute('rx', rx);
      rect.setAttribute('ry', rx);
      rect.setAttribute('fill', color.fill);
      rect.setAttribute('stroke', color.stroke);
      rect.setAttribute('stroke-width', '1.5');
      g.appendChild(rect);

      // Label text
      var lines = n.label.split('\n');
      lines.forEach(function (line, i) {
        var t = document.createElementNS(SVG_NS, 'text');
        t.setAttribute('x', n.w / 2);
        t.setAttribute('y', n.h / 2 - (lines.length - 1) * 7 + i * 13);
        t.setAttribute('fill', color.text);
        t.setAttribute('font-size', i === 0 && lines.length > 1 ? '10' : '9');
        t.setAttribute('font-family', 'SF Mono, Fira Code, monospace');
        t.setAttribute('text-anchor', 'middle');
        t.setAttribute('dominant-baseline', 'middle');
        t.textContent = line;
        g.appendChild(t);
      });

      svg.appendChild(g);
    });

    // CSS animation for active node
    var style = document.createElementNS(SVG_NS, 'style');
    style.textContent =
      '.rg-node { transition: all 0.3s ease; }' +
      '.rg-active rect { stroke: #fff !important; stroke-width: 2.5 !important; filter: url(#rg-glow); }' +
      '.rg-active text { fill: #fff !important; font-weight: bold; }' +
      '.rg-ocap rect { stroke: #a855f7 !important; stroke-width: 2.5 !important; filter: url(#rg-glow); }' +
      '.rg-ocap text { fill: #a855f7 !important; }' +
      '.ocap-dynamic-path { animation: ocap-path-pulse 1.5s ease-in-out infinite; }' +
      '@keyframes ocap-path-pulse { 0%,100% { stroke-opacity:0.4; } 50% { stroke-opacity:1; } }' +
      '.rg-dynamic-stage { animation: dynamic-stage-fadein 0.5s ease-out; }' +
      '@keyframes dynamic-stage-fadein { from { opacity:0; transform:translateY(10px); } to { opacity:1; transform:translateY(0); } }';
    svg.appendChild(style);

    container.innerHTML = '';
    container.appendChild(svg);

    // Store node map for highlighting
    svg._nodeMap = nodeMap;
    svg._edges = edges;
  }

})(window);

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
      '.rg-active text { fill: #fff !important; font-weight: bold; }';
    svg.appendChild(style);

    container.innerHTML = '';
    container.appendChild(svg);

    // Store node map for highlighting
    svg._nodeMap = nodeMap;
    svg._edges = edges;
  }

})(window);

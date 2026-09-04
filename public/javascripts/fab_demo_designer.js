/**
 * Fab Demo — Route Designer Page JS
 *
 * Route Browser CRUD + Route Graph rendering.
 * Start button navigates to /fab-demo?route=<id> to run the simulation.
 */
(function() {
  'use strict';

  // ===================================================================
  // Route Graph Panel
  // ===================================================================

  window.toggleRouteGraphPanel = function() {
    var panel = document.getElementById('routeGraphPanel');
    var btn = panel.querySelector('.panel-header button');
    if (panel.classList.contains('collapsed')) {
      panel.classList.remove('collapsed');
      btn.textContent = '▲ Collapse';
    } else {
      panel.classList.add('collapsed');
      btn.textContent = '▼ Expand';
    }
  };

  /** Load route graph for a scenario/route ID */
  window.showRouteGraph = function(scenarioId, workOrderId) {
    document.getElementById('routeGraphScenario').textContent = scenarioId;
    var container = document.getElementById('routeGraphContent');
    if (typeof loadRouteGraph === 'function') {
      loadRouteGraph(scenarioId, container);
    }
  };

  // ===================================================================
  // Route Browser
  // ===================================================================

  window.toggleRouteBrowserPanel = function () {
    var panel = document.getElementById('routeBrowserPanel');
    if (!panel) return;
    panel.classList.toggle('collapsed');
  };

  /** Seed default routes into RoutingRepository (idempotent) */
  window.seedRoutes = function () {
    var listEl = document.getElementById('routeList');
    listEl.innerHTML = '<span style="color:var(--amber)">Seeding routes...</span>';
    fetch('/api/fab-demo/routes/seed', { method: 'POST' })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        fetchRouteList();
      })
      .catch(function (err) {
        listEl.innerHTML = '<span style="color:var(--red)">Error: ' + err.message + '</span>';
      });
  };

  /** Fetch and display the route list from RoutingRepository */
  window.fetchRouteList = function () {
    var listEl = document.getElementById('routeList');
    fetch('/api/fab-demo/routes')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        var routes = data.routes || [];
        if (routes.length === 0) {
          listEl.innerHTML = '<p style="color:var(--fg-muted)">No routes in repository. Click "Seed Default Routes" first.</p>';
          return;
        }
        var html = '<table style="width:100%;border-collapse:collapse;font-size:11px">' +
          '<thead><tr style="color:var(--fg-muted);border-bottom:1px solid var(--border)">' +
          '<th style="text-align:left;padding:4px">Route ID</th>' +
          '<th style="text-align:left;padding:4px">Name</th>' +
          '<th style="text-align:right;padding:4px">Ver</th>' +
          '<th style="text-align:right;padding:4px">Nodes</th>' +
          '<th style="text-align:right;padding:4px">Edges</th>' +
          '<th style="padding:4px">Actions</th></tr></thead><tbody>';
        routes.forEach(function (r) {
          html += '<tr style="border-bottom:1px solid var(--border)">' +
            '<td style="padding:4px;color:var(--accent)">' + r.routeId + '</td>' +
            '<td style="padding:4px">' + r.name + '</td>' +
            '<td style="padding:4px;text-align:right">v' + r.version + '</td>' +
            '<td style="padding:4px;text-align:right">' + r.nodeCount + '</td>' +
            '<td style="padding:4px;text-align:right">' + r.edgeCount + '</td>' +
            '<td style="padding:4px">' +
            '<button onclick="compileRoute(\'' + r.routeId + '\')" style="background:var(--bg-alt);color:var(--accent);border:1px solid var(--border);padding:2px 8px;border-radius:3px;cursor:pointer;font-size:10px">Compile</button>' +
            '<button onclick="showRouteGraphFor(\'' + r.routeId + '\')" style="background:var(--bg-alt);color:var(--green);border:1px solid var(--border);padding:2px 8px;border-radius:3px;cursor:pointer;font-size:10px;margin-left:4px">Graph</button>' +
            '<button onclick="startRoute(\'' + r.routeId + '\')" style="background:var(--accent);color:#fff;border:1px solid var(--accent);padding:2px 8px;border-radius:3px;cursor:pointer;font-size:10px;margin-left:4px">Start</button>' +
            '</td></tr>';
        });
        html += '</tbody></table>';
        listEl.innerHTML = html;
      })
      .catch(function (err) {
        listEl.innerHTML = '<span style="color:var(--red)">' + err.message + '</span>';
      });
  };

  /** Compile a route and show the step sequence preview */
  window.compileRoute = function (routeId) {
    var previewEl = document.getElementById('routeStepsPreview');
    previewEl.style.display = 'block';
    previewEl.innerHTML = '<span style="color:var(--amber)">Compiling ' + routeId + '...</span>';
    fetch('/api/fab-demo/routes/' + encodeURIComponent(routeId) + '/compile')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        var steps = data.steps || [];
        var html = '<div style="color:var(--green);margin-bottom:4px">' + routeId + ' v' + data.version + ' — ' + data.stepCount + ' steps</div>';
        html += '<div style="display:flex;flex-wrap:wrap;gap:3px">';
        steps.forEach(function (s, i) {
          var isTrackIn = s.indexOf('TrackIn') === 0;
          var isTrackOut = s.indexOf('TrackOut') === 0;
          var color = isTrackIn ? 'var(--green)' : (isTrackOut ? 'var(--amber)' : 'var(--accent)');
          html += '<span style="padding:1px 6px;border:1px solid ' + color + ';border-radius:3px;color:' + color + ';font-family:monospace;font-size:10px" title="Step ' + (i + 1) + '">' + s + '</span>';
        });
        html += '</div>';
        previewEl.innerHTML = html;
      })
      .catch(function (err) {
        previewEl.innerHTML = '<span style="color:var(--red)">' + err.message + '</span>';
      });
  };

  /** Show route graph for a route ID */
  window.showRouteGraphFor = function (routeId) {
    var panel = document.getElementById('routeGraphPanel');
    var content = document.getElementById('routeGraphContent');
    var label = document.getElementById('routeGraphScenario');
    if (label) label.textContent = routeId;
    // Try route-graph API first, fall back to compile endpoint
    fetch('/api/fab-demo/scenario/' + encodeURIComponent(routeId) + '/route-graph')
      .then(function (r) {
        if (!r.ok) throw new Error('No graph available for ' + routeId);
        return r.json();
      })
      .then(function (graph) {
        window.loadRouteGraph(routeId, content);
      })
      .catch(function (err) {
        fetch('/api/fab-demo/routes/' + encodeURIComponent(routeId) + '/compile')
          .then(function (r) { return r.json(); })
          .then(function (data) {
            var steps = data.steps || [];
            var html = '<div style="padding:12px;font-family:monospace;font-size:10px;line-height:2">';
            steps.forEach(function (s, i) {
              html += '<span style="color:var(--accent)">' + (i + 1) + '.</span> ' + s + '<br>';
            });
            html += '</div>';
            content.innerHTML = html;
          })
          .catch(function () {
            content.innerHTML = '<p style="color:var(--red);padding:12px">Cannot render graph for ' + routeId + '</p>';
          });
      });
  };

  /** Start a route — navigate to simulation page */
  window.startRoute = function (routeId) {
    window.location.href = '/fab-demo?route=' + encodeURIComponent(routeId);
  };

  // ===================================================================
  // Init
  // ===================================================================

  document.addEventListener('DOMContentLoaded', function() {
    // Auto-load route list on page load
    fetchRouteList();
  });

})();

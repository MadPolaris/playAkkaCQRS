/**
 * GraphEngine — unified animation engine for graph-based showcase visualizations.
 *
 * Supports two animation modes:
 *   'css' — CSS transition floating dots (simple, works without SVG)
 *   'svg' — SVG path + requestAnimationFrame particles (precise edge animation)
 *
 * API:
 *   registerNode(def)        — register a node with { id, initialState, reducer, render, onRendered }
 *   updateNodeState(id, ev)  — manually update a node's state via its reducer
 *   pushEvent(event)         — queue an event for animated dispatch
 *   dispatchEvent(event)     — immediately animate an event through the graph
 *   onEventProcessed(cb)     — register external listener for every processed event
 *   clearDynamicNodes(pref)  — remove nodes whose id starts with prefix
 *   redraw()                 — re-layout static SVG edges (after resize)
 */
(function () {
  'use strict';

  class GraphEngine {
    constructor(containerId, config) {
      this.container = document.getElementById(containerId);
      this.config = config || {};
      this.settings = Object.assign(
        { animationSpeed: 1.0, eventTravelTime: 800, mode: 'css', eventGap: 600 },
        this.config.settings
      );

      // Node state
      this.nodes = new Map();     // id -> { def, state }
      this.domNodes = new Map();  // id -> DOM element

      // Event queue
      this.eventQueue = [];
      this.isAnimating = false;
      this.eventListeners = [];

      // SVG support
      this.svg = null;
      this.svgViz = null;
      this.staticPaths = [];

      // Dispatch queue (prevents concurrent event animations)
      this._dispatchQueue = [];
      this._isDispatching = false;
      this._eventGap = this.settings.eventGap || 600;

      if (this.settings.mode === 'svg') {
        this.svg = this.config.svgCanvas
          ? (typeof this.config.svgCanvas === 'string'
              ? document.getElementById(this.config.svgCanvas)
              : this.config.svgCanvas)
          : null;
        this.svgViz = this.config.svgViz
          ? (typeof this.config.svgViz === 'string'
              ? document.getElementById(this.config.svgViz)
              : this.config.svgViz)
          : this.container;
      }

      this._init();
    }

    // ---- Lifecycle ----

    _init() {
      if (this.config.nodes) {
        this.config.nodes.forEach(function (n) { this.registerNode(n); }.bind(this));
      }
      if (this.settings.mode === 'svg') {
        this._initEdges();
      }
    }

    // ---- Node Registration ----

    registerNode(nodeDef, dynamicState) {
      var id = nodeDef.id;
      var state = dynamicState || Object.assign({}, nodeDef.initialState);
      this.nodes.set(id, { def: nodeDef, state: state });

      var wrapper = this.domNodes.get(id) || document.getElementById(nodeDef.domId || id);
      if (!wrapper && nodeDef.containerSelector) {
        var container = document.querySelector(nodeDef.containerSelector);
        if (container) {
          wrapper = document.createElement('div');
          wrapper.id = id;
          container.appendChild(wrapper);
        }
      }
      if (wrapper) {
        this.domNodes.set(id, wrapper);
        if (nodeDef.render) {
          wrapper.innerHTML = nodeDef.render(state);
        }
        if (nodeDef.onRendered) {
          nodeDef.onRendered(wrapper, state);
        }
      }
    }

    updateNodeState(id, event) {
      var node = this.nodes.get(id);
      if (!node || !node.def.reducer) return;
      var newState = node.def.reducer(node.state, event);
      if (newState !== node.state) {
        node.state = newState;
        var wrapper = this.domNodes.get(id);
        if (wrapper && node.def.render) {
          wrapper.innerHTML = node.def.render(newState);
          if (node.def.onRendered) {
            node.def.onRendered(wrapper, newState);
          }
        }
      }
    }

    clearDynamicNodes(prefix) {
      var self = this;
      this.nodes.forEach(function (_, id) {
        if (id.indexOf(prefix) === 0) {
          self.nodes.delete(id);
          self.domNodes.delete(id);
        }
      });
    }

    // ---- Event Processing ----

    pushEvent(event) {
      this.eventQueue.push(event);
      this._processNext();
    }

    onEventProcessed(callback) {
      this.eventListeners.push(callback);
    }

    _processNext() {
      if (this.isAnimating || this.eventQueue.length === 0) return;
      this.isAnimating = true;
      var event = this.eventQueue.shift();
      this._handleEvent(event).then(function () {
        this.isAnimating = false;
        this._processNext();
      }.bind(this));
    }

    async _handleEvent(event) {
      // Animate travel for matched route
      var route = null;
      if (this.config.eventRoutes) {
        route = this.config.eventRoutes.find(function (r) { return r.type === event.type; });
      }
      if (route) {
        var sourceId = typeof route.source === 'function' ? route.source(event) : route.source;
        var targetId = typeof route.target === 'function' ? route.target(event) : route.target;
        if (sourceId && targetId) {
          if (this.settings.mode === 'svg') {
            await this._animateRouteSvg(route, event, sourceId, targetId);
          } else {
            await this._animateTravelCss(sourceId, targetId, event, route);
          }
        }
      }

      // Dispatch to reducers
      var targetIds;
      if (this.config.eventDispatch) {
        targetIds = this.config.eventDispatch(event) || [];
      } else {
        targetIds = Array.from(this.nodes.keys());
      }
      var self = this;
      targetIds.forEach(function (id) { self.updateNodeState(id, event); });

      // Fire external listeners
      this.eventListeners.forEach(function (cb) { cb(event); });
    }

    // ---- CSS Transition Animation ----

    _animateTravelCss(sourceId, targetId, event, route) {
      var self = this;
      return new Promise(function (resolve) {
        var sourceEl = self.domNodes.get(sourceId);
        var targetEl = self.domNodes.get(targetId);
        if (!sourceEl || !targetEl) { resolve(); return; }

        var sRect = sourceEl.getBoundingClientRect();
        var tRect = targetEl.getBoundingClientRect();
        var scrollX = window.scrollX || window.pageXOffset;
        var scrollY = window.scrollY || window.pageYOffset;

        var startX = sRect.left + scrollX + sRect.width / 2;
        var startY = sRect.top + scrollY + sRect.height / 2;
        var endX = tRect.left + scrollX + tRect.width / 2;
        var endY = tRect.top + scrollY + tRect.height / 2;

        var dot = document.createElement('div');
        dot.className = 'event-dot type-' + (event.type || '');
        dot.style.cssText =
          'position:absolute;width:16px;height:16px;border-radius:50%;' +
          'background-color:' + (route.color || '#00d4ff') + ';' +
          'box-shadow:0 0 12px ' + (route.color || '#00d4ff') + ';' +
          'z-index:9999;left:' + (startX - 8) + 'px;top:' + (startY - 8) + 'px;' +
          'transition:all ' + self.settings.eventTravelTime + 'ms ease-in-out;';

        var label = document.createElement('div');
        label.innerText = event.type || '';
        label.style.cssText =
          'position:absolute;top:-20px;left:50%;transform:translateX(-50%);' +
          'white-space:nowrap;font-size:0.7rem;color:#fff;font-weight:bold;text-shadow:0 0 4px #000;';
        dot.appendChild(label);
        document.body.appendChild(dot);

        // Force reflow, then animate
        void dot.offsetWidth;
        dot.style.left = (endX - 8) + 'px';
        dot.style.top = (endY - 8) + 'px';

        setTimeout(function () {
          if (dot.parentNode) dot.parentNode.removeChild(dot);
          resolve();
        }, self.settings.eventTravelTime);
      });
    }

    // ---- SVG Path Animation ----

    dispatchEvent(event) {
      console.log('[engine] dispatchEvent:', event.type, 'userId:', event.userId);
      this._dispatchQueue.push(event);
      this._processDispatchQueue();
    }

    async _processDispatchQueue() {
      if (this._isDispatching || this._dispatchQueue.length === 0) return;
      this._isDispatching = true;
      try {
        var self = this;
        while (this._dispatchQueue.length > 0) {
          var event = this._dispatchQueue.shift();
          var routes = this.config.eventRouting ? this.config.eventRouting[event.type] : null;
          console.log('[engine] Processing event from queue:', event.type, 'routes:', routes ? routes.length : 0);
          if (routes) {
            for (var i = 0; i < routes.length; i++) {
              var route = routes[i];
              var toId = route.to;
              if (toId.indexOf('{userId}') !== -1) {
                toId = toId.replace('{userId}', event.userId || '');
              }
              await self._animateRouteSvg(route, event, route.from, toId);
            }
          }
          if (this._dispatchQueue.length > 0) {
            await new Promise(function (r) { setTimeout(r, self._eventGap); });
          }
        }
      } finally {
        this._isDispatching = false;
      }
    }

    _animateRouteSvg(route, event, fromId, toId) {
      var self = this;
      return new Promise(function (resolve) {
        var fromEl = self._svgGetNode(fromId);
        var toEl = self._svgGetNode(toId);
        if (!fromEl || !toEl) { resolve(); return; }

        var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('class', 'conn-path active');
        self._updatePathCoords(path, fromEl, toEl);
        if (self.svg) self.svg.appendChild(path);

        var particle = self._createParticle(event);
        if (self.svgViz) self.svgViz.appendChild(particle);

        var len = path.getTotalLength();
        var start = null;
        var travelTime = Math.round(1200 / (parseFloat(
          (document.getElementById('speedSlider') || {}).value
        ) || 1));

        function step(ts) {
          if (!start) start = ts;
          var progress = (ts - start) / travelTime;
          if (progress < 1) {
            var pt = path.getPointAtLength(progress * len);
            particle.style.left = pt.x + 'px';
            particle.style.top = pt.y + 'px';
            particle.style.display = 'flex';
            requestAnimationFrame(step);
          } else {
            particle.remove();
            path.remove();
            if (toEl) {
              toEl.classList.add('highlight-proj');
              setTimeout(function () { toEl.classList.remove('highlight-proj'); }, 800);
            }
            if (route.updateCard && toId.indexOf('card-') === 0) {
              var updater = self.config.cardStateUpdaters
                ? self.config.cardStateUpdaters['default']
                : null;
              if (updater) updater(event);
            }
            resolve();
          }
        }
        requestAnimationFrame(step);
      });
    }

    _createParticle(event) {
      var div = document.createElement('div');
      div.className = 'event-particle';
      var displayAmt = event.amount ? '¥' + event.amount : '';
      div.innerHTML =
        '<div class="event-icon">⚡</div>' +
        '<div style="display:flex;flex-direction:column;">' +
        '<div style="font-weight:800;color:#fff;">' + (event.type || '') + '</div>' +
        (displayAmt ? '<div class="event-data-label">' + displayAmt + '</div>' : '') +
        '<div class="stage-indicator">Traveling...</div></div>';
      return div;
    }

    // ---- SVG Edge Management ----

    _svgGetNode(id) {
      if (this.domNodes.has(id)) return this.domNodes.get(id);
      if (id.indexOf('card-') === 0) {
        var userId = id.replace('card-', '');
        var card = document.querySelector('[data-user-id="' + userId + '"]');
        if (card) {
          this.domNodes.set(id, card);
          return card;
        }
      }
      var el = document.getElementById(id);
      if (el) this.domNodes.set(id, el);
      return el;
    }

    _initEdges() {
      var self = this;
      this.staticPaths.forEach(function (p) { p.remove(); });
      this.staticPaths = [];
      if (!this.svg || !this.config.edges) return;
      this.config.edges.forEach(function (edge) {
        if (edge.from.indexOf('{') !== -1 || edge.to.indexOf('{') !== -1) return;
        var fromEl = self._svgGetNode(edge.from);
        var toEl = self._svgGetNode(edge.to);
        if (fromEl && toEl) {
          var path = self._createStaticPath(fromEl, toEl);
          self.staticPaths.push(path);
        }
      });
      this._drawDynamicEdges();
    }

    _drawDynamicEdges() {
      var self = this;
      var templates = (this.config.edges || []).filter(function (e) {
        return e.from.indexOf('{') !== -1 || e.to.indexOf('{') !== -1;
      });
      if (templates.length === 0) return;
      this.domNodes.forEach(function (_el, nodeId) {
        if (nodeId.indexOf('card-') !== 0) return;
        var userId = nodeId.replace('card-', '');
        templates.forEach(function (tmpl) {
          var fromId = tmpl.from.replace('{userId}', userId);
          var toId = tmpl.to.replace('{userId}', userId);
          var fromEl = self._svgGetNode(fromId);
          var toEl = self._svgGetNode(toId);
          if (fromEl && toEl) {
            var path = self._createStaticPath(fromEl, toEl);
            self.staticPaths.push(path);
          }
        });
      });
    }

    _createStaticPath(fromEl, toEl) {
      var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('class', 'conn-path');
      this._updatePathCoords(path, fromEl, toEl);
      this.svg.appendChild(path);
      return path;
    }

    _updatePathCoords(path, fromEl, toEl) {
      var fromBox = fromEl.getBoundingClientRect();
      var toBox = toEl.getBoundingClientRect();
      var vizBox = this.svgViz.getBoundingClientRect();
      var fromAlign = fromBox.left < toBox.left ? 'right' : 'left';
      var toAlign = fromBox.left < toBox.left ? 'left' : 'right';
      var p1 = this._getPoint(fromEl, fromAlign, vizBox);
      var p2 = this._getPoint(toEl, toAlign, vizBox);
      var curve = Math.max(Math.abs(p2.x - p1.x) * 0.4, 60);
      var d =
        'M ' + p1.x + ' ' + p1.y + ' ' +
        'C ' + (p1.x + (fromAlign === 'right' ? curve : -curve)) + ' ' + p1.y + ', ' +
        (p2.x - (toAlign === 'left' ? curve : -curve)) + ' ' + p2.y + ', ' +
        p2.x + ' ' + p2.y;
      path.setAttribute('d', d);
    }

    _getPoint(el, align, vizBox) {
      var box = el.getBoundingClientRect();
      var x;
      if (align === 'right') {
        x = box.right - vizBox.left;
      } else if (align === 'left') {
        x = box.left - vizBox.left;
      } else {
        x = box.left + box.width / 2 - vizBox.left;
      }
      var y = box.top + box.height / 2 - vizBox.top;
      return { x: x, y: y };
    }

    redraw() {
      if (this.settings.mode === 'svg') {
        this._initEdges();
      }
    }

    // ---- Dynamic SVG Nodes ----

    createDynamicSvgNode(userId) {
      var cardId = 'card-' + userId;
      if (this.domNodes.has(cardId)) return;
      var dynamicDef = (this.config.nodes || []).find(function (n) { return n.id === 'card-{userId}'; });
      if (!dynamicDef || !dynamicDef.ui) return;
      var el = dynamicDef.ui(userId);
      var container = document.getElementById('readModelList');
      if (container) container.appendChild(el);
      this.domNodes.set(cardId, el);
      this._initEdges();
    }
  }

  // Exports
  window.GraphEngine = GraphEngine;
  window.AnimationEngine = GraphEngine; // backward compat
})();

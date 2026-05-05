class AnimationEngine {
    constructor(containerId, config) {
        this.container = document.getElementById(containerId);
        this.config = config;
        this.settings = Object.assign({ animationSpeed: 1.0, eventTravelTime: 800 }, config.settings);
        
        // internal state
        this.nodes = new Map(); // id -> node definition + state
        this.domNodes = new Map(); // id -> DOM Element
        this.eventQueue = [];
        this.isAnimating = false;
        this.eventListeners = []; // external hooks
        
        this.init();
    }

    init() {
        if (this.config.nodes) {
            this.config.nodes.forEach(n => this.registerNode(n));
        }
    }
    
    onEventProcessed(callback) {
        this.eventListeners.push(callback);
    }
    
    registerNode(nodeDef, dynamicState = null) {
        const id = nodeDef.id;
        const state = dynamicState || Object.assign({}, nodeDef.initialState);
        this.nodes.set(id, { def: nodeDef, state: state });
        
        // Try to bind DOM
        let wrapper = this.domNodes.get(id) || document.getElementById(nodeDef.domId || id);
        if (!wrapper && nodeDef.containerSelector) {
            const container = document.querySelector(nodeDef.containerSelector);
            if(container) {
                wrapper = document.createElement('div');
                wrapper.id = id;
                container.appendChild(wrapper);
            }
        }
        if(wrapper) {
            this.domNodes.set(id, wrapper);
            wrapper.innerHTML = nodeDef.render(state);
            if (nodeDef.onRendered) {
                nodeDef.onRendered(wrapper, state);
            }
        }
    }

    updateNodeState(id, event) {
        const node = this.nodes.get(id);
        if (!node) return;
        
        const newState = node.def.reducer(node.state, event);
        if (newState !== node.state) {
            node.state = newState;
            const wrapper = this.domNodes.get(id);
            if (wrapper) {
                wrapper.innerHTML = node.def.render(newState);
                if (node.def.onRendered) {
                    node.def.onRendered(wrapper, newState);
                }
            }
        }
    }

    pushEvent(event) {
        this.eventQueue.push(event);
        this._processNext();
    }

    async _processNext() {
        if (this.isAnimating || this.eventQueue.length === 0) return;
        
        this.isAnimating = true;
        const event = this.eventQueue.shift();
        
        // Find matching route for animation
        let route = null;
        if (this.config.eventRoutes) {
            route = this.config.eventRoutes.find(r => r.type === event.type);
        }
        
        if (route) {
            const sourceId = typeof route.source === 'function' ? route.source(event) : route.source;
            const targetId = typeof route.target === 'function' ? route.target(event) : route.target;
            
            if (sourceId && targetId && this.domNodes.has(sourceId) && this.domNodes.has(targetId)) {
                await this._animateTravel(sourceId, targetId, event, route);
            }
        }
        
        // Dispatch to reducers
        if (this.config.eventDispatch) {
             const targetIds = this.config.eventDispatch(event) || [];
             targetIds.forEach(id => this.updateNodeState(id, event));
        } else {
             // Default: update all nodes
             for (const id of this.nodes.keys()) {
                 this.updateNodeState(id, event);
             }
        }
        
        // Fire external listeners
        this.eventListeners.forEach(cb => cb(event));
        
        this.isAnimating = false;
        this._processNext();
    }

    _animateTravel(sourceId, targetId, event, route) {
        return new Promise(resolve => {
            const sourceEl = this.domNodes.get(sourceId);
            const targetEl = this.domNodes.get(targetId);
            
            if (!sourceEl || !targetEl) {
                resolve();
                return;
            }
            
            const sRect = sourceEl.getBoundingClientRect();
            const tRect = targetEl.getBoundingClientRect();
            
            // Scroll offset correction
            const scrollX = window.scrollX || window.pageXOffset;
            const scrollY = window.scrollY || window.pageYOffset;
            
            const startX = sRect.left + scrollX + sRect.width / 2;
            const startY = sRect.top + scrollY + sRect.height / 2;
            const endX = tRect.left + scrollX + tRect.width / 2;
            const endY = tRect.top + scrollY + tRect.height / 2;
            
            const dot = document.createElement('div');
            dot.className = 'event-dot type-' + event.type;
            dot.style.position = 'absolute';
            dot.style.width = '16px';
            dot.style.height = '16px';
            dot.style.borderRadius = '50%';
            dot.style.backgroundColor = route.color || '#00d4ff';
            dot.style.boxShadow = '0 0 12px ' + (route.color || '#00d4ff');
            dot.style.zIndex = '9999';
            dot.style.left = (startX - 8) + 'px';
            dot.style.top = (startY - 8) + 'px';
            dot.style.transition = `all ${this.settings.eventTravelTime}ms ease-in-out`;
            
            // Add label for ubiquitous language
            const label = document.createElement('div');
            label.innerText = event.type;
            label.style.position = 'absolute';
            label.style.top = '-20px';
            label.style.left = '50%';
            label.style.transform = 'translateX(-50%)';
            label.style.whiteSpace = 'nowrap';
            label.style.fontSize = '0.7rem';
            label.style.color = '#fff';
            label.style.fontWeight = 'bold';
            label.style.textShadow = '0 0 4px #000';
            dot.appendChild(label);

            document.body.appendChild(dot);
            
            // Force reflow
            void dot.offsetWidth;
            
            // Move
            dot.style.left = (endX - 8) + 'px';
            dot.style.top = (endY - 8) + 'px';
            
            setTimeout(() => {
                if(dot.parentNode) dot.parentNode.removeChild(dot);
                resolve();
            }, this.settings.eventTravelTime);
        });
    }

    clearDynamicNodes(prefix) {
        for (const id of this.nodes.keys()) {
            if (id.startsWith(prefix)) {
                this.nodes.delete(id);
                this.domNodes.delete(id); // Keep DOM elements intact in HTML, just remove reference from Engine
            }
        }
    }
}
window.AnimationEngine = AnimationEngine;
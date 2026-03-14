/**
 * Grunt Web UI - API Client
 */
const API = {
    base: '',

    async get(path) {
        const res = await fetch(this.base + path);
        return res.json();
    },

    async post(path, body) {
        const res = await fetch(this.base + path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        return res.json();
    },

    async uploadJar(file) {
        const formData = new FormData();
        formData.append('file', file);
        const res = await fetch(this.base + '/api/upload', {
            method: 'POST',
            body: formData
        });
        return res.json();
    },

    getTransformers() {
        return this.get('/api/transformers');
    },

    getConfig() {
        return this.get('/api/config');
    },

    updateConfig(config) {
        return this.post('/api/config', config);
    },

    saveConfig(path) {
        return this.post('/api/config/save', { path: path || 'config.json' });
    },

    loadConfig(path) {
        return this.post('/api/config/load', { path: path || 'config.json' });
    },

    pickAndLoadConfig() {
        return this.post('/api/config/pick-load', {});
    },

    resetConfig() {
        return this.post('/api/config/reset', {});
    },

    startObfuscation() {
        return this.post('/api/obfuscate', {});
    },

    getStatus() {
        return this.get('/api/status');
    },

    getStructure() {
        return this.get('/api/structure');
    },

    getDownloadUrl() {
        return this.base + '/api/download';
    },

    getLogs() {
        return this.get('/api/logs');
    },

    getProjectMeta(scope) {
        const s = encodeURIComponent(scope || 'input');
        return this.get('/api/project/meta?scope=' + s);
    },

    getProjectTree(scope) {
        const s = encodeURIComponent(scope || 'input');
        return this.get('/api/project/tree?scope=' + s);
    },

    getProjectSource(scope, className) {
        const s = encodeURIComponent(scope || 'input');
        const c = encodeURIComponent(className || '');
        return this.get('/api/project/source?scope=' + s + '&class=' + c);
    },

    // WebSocket connections
    connectConsole(onMessage) {
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const ws = new WebSocket(protocol + '//' + location.host + '/ws/console');
        ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                onMessage(data);
            } catch (e) {
                onMessage({ type: 'log', message: event.data });
            }
        };
        ws.onerror = (e) => console.error('Console WS error:', e);
        ws.onclose = () => {
            setTimeout(() => {
                onMessage({ type: 'clear' });
                this.connectConsole(onMessage);
            }, 2000);
        };
        return ws;
    },

    connectProgress(onProgress) {
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        const ws = new WebSocket(protocol + '//' + location.host + '/ws/progress');
        ws.onmessage = (event) => {
            try {
                const data = JSON.parse(event.data);
                onProgress(data);
            } catch (e) {
                console.error('Progress parse error:', e);
            }
        };
        ws.onerror = (e) => console.error('Progress WS error:', e);
        ws.onclose = () => {
            setTimeout(() => this.connectProgress(onProgress), 2000);
        };
        return ws;
    }
};

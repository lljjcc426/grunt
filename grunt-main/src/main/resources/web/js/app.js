/* Grunt Web UI - IDE layout app */
(function () {
    'use strict';

    const K = {
        theme: 'grunt.web.theme',
        left: 'grunt.web.split.leftPct',
        bottom: 'grunt.web.split.bottomPct'
    };
    const CFG = {
        theme: 'dark',
        left: 30,
        bottom: 28,
        minLeft: 280,
        minRight: 420,
        minTop: 260,
        minBottom: 160,
        compact: 1100
    };

    let transformers = [];
    let isRunning = false;
    let isCompact = false;
    let activePane = 'config';
    let leftPct = CFG.left;
    let bottomPct = CFG.bottom;
    let scope = 'input';

    const project = {
        tree: { input: [], output: [] },
        treeLoaded: { input: false, output: false },
        treeError: { input: '', output: '' },
        meta: {
            input: { available: false, classCount: 0, loaded: false },
            output: { available: false, classCount: 0, loaded: false }
        },
        tabs: { input: [], output: [] },
        active: { input: null, output: null },
        selected: { input: null, output: null }
    };

    const el = {
        workspace: document.getElementById('workspace'),
        navBtns: Array.from(document.querySelectorAll('.workspace-nav-btn')),
        panelConfig: document.getElementById('panel-config'),
        panelProject: document.getElementById('panel-project'),
        panelConsole: document.getElementById('panel-console'),
        splitV: document.getElementById('splitter-vertical'),
        splitH: document.getElementById('splitter-horizontal'),
        fileInput: document.getElementById('file-input'),
        btnObf: document.getElementById('btn-obfuscate'),
        btnDl: document.getElementById('btn-download'),
        statusDot: document.querySelector('.status-dot'),
        statusText: document.getElementById('status-text'),
        progressWrap: document.getElementById('progress-container'),
        progressBar: document.getElementById('progress-bar'),
        progressText: document.getElementById('progress-text'),
        console: document.getElementById('console-output'),
        tfWrap: document.getElementById('transformers-content'),
        btnTheme: document.getElementById('btn-theme-toggle'),
        themeIcon: document.getElementById('theme-icon'),
        themeText: document.getElementById('theme-text'),
        tree: document.getElementById('project-tree'),
        tabs: document.getElementById('editor-tabs'),
        code: document.getElementById('code-viewer'),
        meta: document.getElementById('project-meta'),
        btnScopeIn: document.getElementById('btn-scope-input'),
        btnScopeOut: document.getElementById('btn-scope-output')
    };

    function esc(v) {
        if (v == null) return '';
        return String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }
    function renderHighlightedLine(line) {
        const raw = line == null ? '' : String(line);
        const safeFallback = esc(raw || ' ');
        try {
            const hljs = window.hljs;
            if (!hljs || typeof hljs.highlight !== 'function') return safeFallback;
            const result = hljs.highlight(raw || ' ', { language: 'java', ignoreIllegals: true });
            if (!result || typeof result.value !== 'string' || !result.value) return safeFallback;
            return result.value;
        } catch (_) {
            return safeFallback;
        }
    }
    function jsEsc(v) {
        if (v == null) return '';
        return String(v).replace(/\\/g, '\\\\').replace(/'/g, "\\'");
    }
    function clamp(v, min, max) { return Math.min(max, Math.max(min, v)); }
    function setStatus(s, t) { el.statusDot.className = 'status-dot ' + s; el.statusText.textContent = t; }
    function applyAccessTier() {
        const tier = new URLSearchParams(window.location.search).get('tier');
        const isBasic = (tier || '').toLowerCase() === 'basic';
        const proBadge = document.querySelector('.pro-badge');
        if (!proBadge) return;
        proBadge.style.display = isBasic ? 'none' : 'inline-flex';
    }
    function log(msg, lv) {
        const line = document.createElement('div');
        line.className = 'console-line ' + (lv || 'info');
        line.textContent = msg;
        el.console.appendChild(line);
        el.console.scrollTop = el.console.scrollHeight;
    }

    function restoreSplit() {
        const l = parseFloat(localStorage.getItem(K.left) || '');
        const b = parseFloat(localStorage.getItem(K.bottom) || '');
        if (Number.isFinite(l)) leftPct = l;
        if (Number.isFinite(b)) bottomPct = b;
    }
    function saveSplit() {
        localStorage.setItem(K.left, String(leftPct));
        localStorage.setItem(K.bottom, String(bottomPct));
    }
    function applySplit() {
        document.documentElement.style.setProperty('--left-split', leftPct.toFixed(2) + '%');
        document.documentElement.style.setProperty('--bottom-split', bottomPct.toFixed(2) + '%');
    }
    function clampLeft(pct) {
        const w = Math.max(1, el.workspace.clientWidth);
        return clamp(pct, CFG.minLeft / w * 100, 100 - CFG.minRight / w * 100);
    }
    function clampBottom(pct) {
        const h = Math.max(1, el.workspace.clientHeight);
        return clamp(pct, CFG.minBottom / h * 100, 100 - CFG.minTop / h * 100);
    }

    function syncCompact() {
        isCompact = window.innerWidth < CFG.compact;
        document.body.classList.toggle('compact', isCompact);
        if (!isCompact) {
            [el.panelConfig, el.panelProject, el.panelConsole].forEach((p) => p.classList.remove('active'));
            return;
        }
        setActivePane(activePane);
    }
    function setActivePane(p) {
        activePane = p;
        if (!isCompact) return;
        const map = { config: el.panelConfig, project: el.panelProject, console: el.panelConsole };
        Object.keys(map).forEach((k) => map[k].classList.toggle('active', k === p));
        el.navBtns.forEach((b) => b.classList.toggle('active', b.getAttribute('data-pane') === p));
    }

    function setupSplitters() {
        el.splitV.addEventListener('mousedown', (e) => {
            if (isCompact) return;
            const startX = e.clientX, w = el.workspace.clientWidth, start = w * leftPct / 100;
            document.body.classList.add('is-resizing-vertical');
            const onMove = (m) => { leftPct = clampLeft((start + (m.clientX - startX)) / w * 100); applySplit(); };
            const onUp = () => {
                document.body.classList.remove('is-resizing-vertical');
                saveSplit();
                document.removeEventListener('mousemove', onMove);
                document.removeEventListener('mouseup', onUp);
            };
            document.addEventListener('mousemove', onMove);
            document.addEventListener('mouseup', onUp);
        });
        el.splitH.addEventListener('mousedown', (e) => {
            if (isCompact) return;
            const startY = e.clientY, h = el.workspace.clientHeight, start = h * bottomPct / 100;
            document.body.classList.add('is-resizing-horizontal');
            const onMove = (m) => { bottomPct = clampBottom((start + (startY - m.clientY)) / h * 100); applySplit(); };
            const onUp = () => {
                document.body.classList.remove('is-resizing-horizontal');
                saveSplit();
                document.removeEventListener('mousemove', onMove);
                document.removeEventListener('mouseup', onUp);
            };
            document.addEventListener('mousemove', onMove);
            document.addEventListener('mouseup', onUp);
        });
    }

    function initTheme() {
        let theme = CFG.theme;
        const saved = localStorage.getItem(K.theme);
        if (saved === 'light' || saved === 'dark') theme = saved;
        applyTheme(theme);
    }
    function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        el.themeIcon.className = theme === 'dark' ? 'ui-icon fas fa-moon' : 'ui-icon fas fa-sun';
        el.themeText.textContent = theme === 'dark' ? 'Dark' : 'Light';
        applyThemeIcons(theme);
    }
    function applyThemeIcons(theme) {
        document.querySelectorAll('.ui-icon[data-icon-dark][data-icon-light]').forEach((icon) => {
            const cls = theme === 'dark' ? icon.getAttribute('data-icon-dark') : icon.getAttribute('data-icon-light');
            if (!cls) return;
            icon.className = 'ui-icon ' + cls;
        });
    }
    function toggleTheme() {
        const cur = document.documentElement.getAttribute('data-theme') || CFG.theme;
        const nxt = cur === 'light' ? 'dark' : 'light';
        applyTheme(nxt);
        localStorage.setItem(K.theme, nxt);
    }

    async function loadTransformers() {
        try {
            transformers = await API.getTransformers();
            renderTransformers(transformers);
        } catch (_) {
            el.tfWrap.innerHTML = '<div class="loading">Failed to load transformers</div>';
        }
    }
    function renderSettingInput(tf, s) {
        const id = 'tf-' + tf + '-' + s.name;
        let h = '<div class="config-item">';
        if (s.type === 'boolean') {
            h += '<label><input type="checkbox" id="' + esc(id) + '" ' + (s.value ? 'checked' : '') +
                ' data-transformer="' + esc(tf) + '" data-setting="' + esc(s.name) + '" data-type="boolean"> ' + esc(s.name) + '</label>';
        } else if (s.type === 'int') {
            h += '<label>' + esc(s.name) + '</label><input type="number" class="config-input" id="' + esc(id) + '" value="' + esc(s.value) +
                '" data-transformer="' + esc(tf) + '" data-setting="' + esc(s.name) + '" data-type="int">';
        } else if (s.type === 'float') {
            h += '<label>' + esc(s.name) + '</label><input type="number" step="0.1" class="config-input" id="' + esc(id) + '" value="' + esc(s.value) +
                '" data-transformer="' + esc(tf) + '" data-setting="' + esc(s.name) + '" data-type="float">';
        } else if (s.type === 'string') {
            h += '<label>' + esc(s.name) + '</label><input type="text" class="config-input" id="' + esc(id) + '" value="' + esc(s.value) +
                '" data-transformer="' + esc(tf) + '" data-setting="' + esc(s.name) + '" data-type="string">';
        } else if (s.type === 'list') {
            h += '<label>' + esc(s.name) + '</label><input type="text" class="config-input" id="' + esc(id) + '" value="' +
                esc(Array.isArray(s.value) ? s.value.join(', ') : '') +
                '" data-transformer="' + esc(tf) + '" data-setting="' + esc(s.name) + '" data-type="list" placeholder="comma separated">';
        }
        return h + '</div>';
    }
    function renderTransformers(list) {
        const groups = {};
        list.forEach((t) => { if (!groups[t.category]) groups[t.category] = []; groups[t.category].push(t); });
        const order = ['Optimization', 'Miscellaneous', 'Controlflow', 'Encryption', 'Redirect', 'Renaming', 'Minecraft'];
        let html = '';
        order.forEach((cat) => {
            const items = groups[cat];
            if (!items || !items.length) return;
            html += '<div class="category-group"><div class="category-group-header"><span class="category-badge category-' + cat + '">' + cat + '</span></div>';
            items.forEach((t) => {
                const en = t.settings.find((s) => s.name === 'Enabled');
                const on = en ? en.value : false;
                const rest = t.settings.filter((s) => s.name !== 'Enabled');
                html += '<div class="transformer-card" data-transformer="' + esc(t.name) + '">';
                html += '<div class="transformer-header" onclick="App.toggleTransformerSettings(\'' + jsEsc(t.name) + '\')">';
                html += '<div class="transformer-name">' + esc(t.name) + '</div>';
                html += '<div class="transformer-toggle ' + (on ? 'active' : '') + '" data-transformer-toggle="' + esc(t.name) + '" onclick="event.stopPropagation(); App.toggleTransformer(\'' + jsEsc(t.name) + '\', this)"></div>';
                html += '</div>';
                if (rest.length) {
                    html += '<div class="transformer-settings" id="settings-' + esc(t.name) + '">';
                    rest.forEach((s) => { html += renderSettingInput(t.name, s); });
                    html += '</div>';
                }
                html += '</div>';
            });
            html += '</div>';
        });
        el.tfWrap.innerHTML = html;
    }

    async function loadConfig() {
        try {
            const c = await API.getConfig();
            if (!c.settings) return;
            document.getElementById('cfg-output').value = c.settings.output || 'output.jar';
            document.getElementById('cfg-parallel').checked = !!c.settings.parallel;
            document.getElementById('cfg-remap').checked = c.settings.generateRemap !== false;
            document.getElementById('cfg-corrupt').checked = !!c.settings.corruptOutput;
        } catch (_) { log('Failed to load config', 'error'); }
    }
    async function saveCurrentConfig() {
        const cfg = {
            output: document.getElementById('cfg-output').value,
            parallel: document.getElementById('cfg-parallel').checked,
            generateRemap: document.getElementById('cfg-remap').checked,
            corruptOutput: document.getElementById('cfg-corrupt').checked,
            transformers: {}
        };
        document.querySelectorAll('[data-transformer-toggle]').forEach((n) => {
            const name = n.getAttribute('data-transformer-toggle'); if (!name) return;
            if (!cfg.transformers[name]) cfg.transformers[name] = {};
            cfg.transformers[name].Enabled = n.classList.contains('active');
        });
        document.querySelectorAll('[data-transformer][data-setting]').forEach((n) => {
            const name = n.getAttribute('data-transformer');
            const set = n.getAttribute('data-setting');
            const type = n.getAttribute('data-type');
            if (!name || !set || !type) return;
            if (!cfg.transformers[name]) cfg.transformers[name] = {};
            if (type === 'boolean') cfg.transformers[name][set] = !!n.checked;
            else if (type === 'int') cfg.transformers[name][set] = parseInt(n.value, 10) || 0;
            else if (type === 'float') cfg.transformers[name][set] = parseFloat(n.value) || 0;
            else if (type === 'string') cfg.transformers[name][set] = n.value;
            else if (type === 'list') cfg.transformers[name][set] = n.value.split(',').map((s) => s.trim()).filter(Boolean);
        });
        try { await API.updateConfig(cfg); } catch (e) { log('Failed to save config: ' + (e.message || e), 'error'); }
    }

    function resetProjectState() {
        project.tree = { input: [], output: [] };
        project.treeLoaded = { input: false, output: false };
        project.treeError = { input: '', output: '' };
        project.meta.output = { available: false, classCount: 0, loaded: false };
        project.tabs = { input: [], output: [] };
        project.active = { input: null, output: null };
        project.selected = { input: null, output: null };
    }
    async function refreshMeta(s, force) {
        if (!force && project.meta[s].loaded) return;
        try {
            const r = await API.getProjectMeta(s);
            if (r.status === 'ok') project.meta[s] = { available: !!r.available, classCount: r.classCount || 0, loaded: true };
            else project.meta[s] = { available: false, classCount: 0, loaded: true };
        } catch (_) { project.meta[s] = { available: false, classCount: 0, loaded: true }; }
    }
    async function refreshTree(s, force) {
        if (!force && project.treeLoaded[s]) return;
        project.treeError[s] = '';
        try {
            const r = await API.getProjectTree(s);
            if (r.status === 'ok' && Array.isArray(r.classes)) project.tree[s] = r.classes.slice().sort();
            else { project.tree[s] = []; project.treeError[s] = r.message || 'No data available'; }
        } catch (_) { project.tree[s] = []; project.treeError[s] = 'Failed to load tree'; }
        project.treeLoaded[s] = true;
    }
    function updateMetaLabel() {
        const m = project.meta[scope], name = scope === 'input' ? 'Input' : 'Output';
        el.meta.textContent = m.available ? (name + ': ' + m.classCount + ' classes') : (name + ': No data');
    }

    let treeId = 0;
    function buildTree(classes) {
        const root = {};
        classes.forEach((c) => {
            let cur = root; const parts = c.split('/');
            parts.forEach((p, i) => {
                if (i === parts.length - 1) {
                    if (!cur.__files) cur.__files = [];
                    cur.__files.push(c);
                } else {
                    if (!cur[p]) cur[p] = {};
                    cur = cur[p];
                }
            });
        });
        return root;
    }
    function renderTreeNode(node, depth) {
        let h = '';
        const indent = '<span class="tree-indent"></span>'.repeat(depth);
        Object.keys(node).filter((k) => k !== '__files').sort().forEach((folder) => {
            const id = 'ptree-' + (treeId++);
            h += '<div class="tree-node tree-folder" data-folder-children="' + id + '">' + indent + '<span class="icon">&#9662;</span><span class="label">' + esc(folder) + '</span></div>';
            h += '<div id="' + id + '">' + renderTreeNode(node[folder], depth + 1) + '</div>';
        });
        (node.__files || []).sort().forEach((full) => {
            const name = full.split('/').pop() || full;
            const active = project.selected[scope] === full ? ' active' : '';
            h += '<div class="tree-node tree-class' + active + '" data-class-enc="' + encodeURIComponent(full) + '">' +
                indent + '<span class="tree-indent"></span><span class="icon">&#9679;</span><span class="label">' + esc(name) + '</span></div>';
        });
        return h;
    }
    function renderProjectTree() {
        const cls = project.tree[scope], err = project.treeError[scope];
        if (err) { el.tree.innerHTML = '<div class="empty-state">' + esc(err) + '</div>'; return; }
        if (!cls.length) { el.tree.innerHTML = '<div class="empty-state">No classes available</div>'; return; }
        treeId = 0;
        el.tree.innerHTML = renderTreeNode(buildTree(cls), 0);
    }

    function renderTabs() {
        const tabs = project.tabs[scope], active = project.active[scope];
        if (!tabs.length) { el.tabs.innerHTML = ''; return; }
        el.tabs.innerHTML = tabs.map((t) => {
            const a = t.className === active ? ' active' : '';
            const enc = encodeURIComponent(t.className);
            return '<div class="editor-tab' + a + '" data-class-enc="' + enc + '"><span class="editor-tab-title">' + esc(t.title) + '</span><button class="editor-tab-close" data-close-enc="' + enc + '" title="Close">&times;</button></div>';
        }).join('');
    }
    function renderCode() {
        const cur = project.active[scope];
        if (!cur) { el.code.innerHTML = '<div class="empty-state">Select a class in the file tree</div>'; return; }
        const tab = project.tabs[scope].find((t) => t.className === cur);
        if (!tab) { el.code.innerHTML = '<div class="empty-state">Select a class in the file tree</div>'; return; }
        if (tab.loading) { el.code.innerHTML = '<div class="loading">Decompiling ' + esc(tab.title) + '...</div>'; return; }
        if (tab.error) { el.code.innerHTML = '<div class="empty-state">' + esc(tab.error) + '</div>'; return; }
        if (!tab.code) { el.code.innerHTML = '<div class="empty-state">No code available</div>'; return; }
        el.code.innerHTML = '';
        const frag = document.createDocumentFragment();
        tab.code.split('\n').forEach((line, i) => {
            const row = document.createElement('div'); row.className = 'code-line';
            const no = document.createElement('span'); no.className = 'line-no'; no.textContent = String(i + 1);
            const tx = document.createElement('span'); tx.className = 'line-text'; tx.innerHTML = renderHighlightedLine(line);
            row.appendChild(no); row.appendChild(tx); frag.appendChild(row);
        });
        el.code.appendChild(frag);
    }
    async function loadSource(s, cls) {
        const tab = project.tabs[s].find((t) => t.className === cls); if (!tab) return;
        tab.loading = true; tab.error = ''; renderCode();
        try {
            const r = await API.getProjectSource(s, cls);
            if (r.status === 'ok') tab.code = r.code || '';
            else tab.error = r.message || 'Failed to load source';
        } catch (_) { tab.error = 'Failed to load source'; }
        tab.loading = false;
        if (s === scope) renderCode();
    }
    function openClass(cls) {
        let tab = project.tabs[scope].find((t) => t.className === cls);
        if (!tab) {
            tab = { className: cls, title: cls.split('/').pop() || cls, loading: false, code: '', error: '' };
            project.tabs[scope].push(tab);
        }
        project.active[scope] = cls;
        renderTabs(); renderCode();
        if (!tab.loading && !tab.code && !tab.error) loadSource(scope, cls);
    }

    async function setScope(s, force) {
        if (s !== 'input' && s !== 'output') return;
        scope = s;
        el.btnScopeIn.classList.toggle('active', s === 'input');
        el.btnScopeOut.classList.toggle('active', s === 'output');
        await refreshMeta(s, !!force);
        await refreshTree(s, !!force);
        renderProjectTree(); renderTabs(); renderCode(); updateMetaLabel();
    }

    async function uploadFile(ev) {
        const file = ev.target.files && ev.target.files[0];
        if (!file) return;
        setStatus('uploading', 'Uploading...');
        log('Uploading ' + file.name + '...', 'info');
        try {
            const r = await API.uploadJar(file);
            if (r.status === 'ok') {
                log('Uploaded ' + r.fileName + ' (' + r.classCount + ' classes)', 'success');
                setStatus('ready', 'Ready');
                el.btnObf.disabled = false; el.btnDl.disabled = true;
                resetProjectState();
                await refreshMeta('input', true);
                await refreshMeta('output', true);
                await setScope('input', true);
            } else {
                log('Upload failed: ' + (r.message || 'Unknown error'), 'error');
                setStatus('error', 'Upload Failed');
            }
        } catch (e) {
            log('Upload error: ' + (e.message || e), 'error');
            setStatus('error', 'Error');
        }
        el.fileInput.value = '';
    }
    async function obfuscate() {
        if (isRunning) return;
        await saveCurrentConfig();
        setStatus('running', 'Running...');
        el.progressWrap.style.display = 'block';
        el.progressBar.style.width = '0%';
        el.progressText.textContent = '0%';
        el.btnObf.disabled = true; el.btnDl.disabled = true;
        isRunning = true;
        log('Starting obfuscation...', 'info');
        try {
            const r = await API.startObfuscation();
            if (r.status !== 'started') {
                log('Failed to start: ' + (r.message || 'Unknown error'), 'error');
                setStatus('error', 'Error');
                isRunning = false; el.btnObf.disabled = false;
            }
        } catch (e) {
            log('Error: ' + (e.message || e), 'error');
            setStatus('error', 'Error');
            isRunning = false; el.btnObf.disabled = false;
        }
    }
    async function onComplete() {
        isRunning = false;
        setStatus('completed', 'Completed');
        el.btnObf.disabled = false; el.btnDl.disabled = false;
        await refreshMeta('output', true);
        await refreshTree('output', true);
        if (scope === 'output') { renderProjectTree(); renderTabs(); renderCode(); }
        updateMetaLabel();
    }
    function onError(err) {
        isRunning = false;
        setStatus('error', 'Error');
        el.btnObf.disabled = false;
        log('Obfuscation failed: ' + err, 'error');
    }
    function connectWs() {
        API.connectConsole((d) => {
            if (d.type === 'clear') { el.console.innerHTML = ''; return; }
            if (d.type === 'log' && d.message) {
                const lv = d.message.includes('ERROR') ? 'error' : (d.message.includes('WARN') ? 'warn' : 'info');
                log(d.message, lv);
            }
        });
        API.connectProgress((d) => {
            if (d.progress !== undefined) { el.progressBar.style.width = d.progress + '%'; el.progressText.textContent = d.progress + '%'; }
            if (d.step) el.statusText.textContent = d.step;
            if (d.step === 'Completed') onComplete();
            else if (d.error) onError(d.error);
        });
    }

    function bindEvents() {
        el.fileInput.addEventListener('change', uploadFile);
        el.btnObf.addEventListener('click', obfuscate);
        el.btnDl.addEventListener('click', () => { window.location.href = API.getDownloadUrl(); });
        document.getElementById('btn-clear-console').addEventListener('click', () => { el.console.innerHTML = ''; });
        document.getElementById('btn-save-config').addEventListener('click', async () => { await saveCurrentConfig(); await API.saveConfig('config.json'); log('Config saved to config.json', 'success'); });
        document.getElementById('btn-load-config').addEventListener('click', async () => {
            try {
                const r = await API.pickAndLoadConfig();
                if (r.status === 'ok') {
                    await loadConfig();
                    await loadTransformers();
                    log('Imported external config from ' + (r.sourcePath || 'selected file') + ' and overwrote default config.json', 'success');
                    return;
                }
                if (r.status === 'cancelled') {
                    log('Load config cancelled', 'info');
                    return;
                }
                log('Failed to import config: ' + (r.message || 'Unknown error'), 'error');
            } catch (e) {
                log('Failed to import config: ' + (e.message || e), 'error');
            }
        });
        document.getElementById('btn-reset-config').addEventListener('click', async () => { await API.resetConfig(); await loadConfig(); await loadTransformers(); log('Config reset to defaults', 'info'); });
        document.querySelectorAll('.config-section-header').forEach((h) => h.addEventListener('click', () => {
            const id = h.getAttribute('data-toggle'); const t = id ? document.getElementById(id) : null; if (t) t.classList.toggle('collapsed');
        }));
        el.btnTheme.addEventListener('click', toggleTheme);
        el.btnScopeIn.addEventListener('click', () => setScope('input'));
        el.btnScopeOut.addEventListener('click', () => setScope('output'));
        el.navBtns.forEach((b) => b.addEventListener('click', () => { const p = b.getAttribute('data-pane'); if (p) setActivePane(p); }));
        el.tree.addEventListener('click', (e) => {
            const folder = e.target.closest('.tree-folder');
            if (folder) {
                const id = folder.getAttribute('data-folder-children'); const c = id ? document.getElementById(id) : null;
                if (!c) return; const hide = c.style.display !== 'none'; c.style.display = hide ? 'none' : 'block';
                const icon = folder.querySelector('.icon'); if (icon) icon.innerHTML = hide ? '&#9656;' : '&#9662;';
                return;
            }
            const cls = e.target.closest('.tree-class'); if (!cls) return;
            const enc = cls.getAttribute('data-class-enc'); if (!enc) return;
            const name = decodeURIComponent(enc);
            project.selected[scope] = name;
            renderProjectTree();
            openClass(name);
        });
        el.tabs.addEventListener('click', (e) => {
            const close = e.target.closest('[data-close-enc]');
            if (close) {
                const enc = close.getAttribute('data-close-enc'); if (!enc) return;
                const name = decodeURIComponent(enc), tabs = project.tabs[scope], idx = tabs.findIndex((t) => t.className === name);
                if (idx >= 0) tabs.splice(idx, 1);
                if (project.active[scope] === name) {
                    const next = tabs[idx] || tabs[idx - 1] || null;
                    project.active[scope] = next ? next.className : null;
                }
                renderTabs(); renderCode(); return;
            }
            const tab = e.target.closest('.editor-tab'); if (!tab) return;
            const enc = tab.getAttribute('data-class-enc'); if (!enc) return;
            project.active[scope] = decodeURIComponent(enc);
            renderTabs(); renderCode();
        });
        window.addEventListener('resize', () => {
            syncCompact();
            if (!isCompact) {
                leftPct = clampLeft(leftPct);
                bottomPct = clampBottom(bottomPct);
                applySplit();
            }
        });
        setupSplitters();
    }

    window.App = {
        toggleTransformer: function (_name, n) { n.classList.toggle('active'); },
        toggleTransformerSettings: function (name) {
            const s = document.getElementById('settings-' + name);
            if (s) s.classList.toggle('open');
        }
    };

    async function start() {
        applyAccessTier();
        initTheme();
        restoreSplit();
        applySplit();
        syncCompact();
        bindEvents();
        await loadTransformers();
        await loadConfig();
        connectWs();
        await refreshMeta('input', true);
        await refreshMeta('output', true);
        await setScope('input', true);
    }

    document.addEventListener('DOMContentLoaded', start);
})();

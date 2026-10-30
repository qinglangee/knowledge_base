/* Knowledge Base 前端逻辑（原生 JS，无依赖） */
'use strict';

/* ---------- 状态 ---------- */
const state = {
    libraries: [],
    settings: null,
    search: { q: '', lib: '', page: 0, size: 20 },
    dirPollTimer: null,
    expandedErrors: new Set(),
};

const IMAGE_EXTS = new Set(['png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp', 'svg', 'ico']);

const $ = id => document.getElementById(id);

/* ---------- 工具 ---------- */
function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g,
        c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/* snippet 含 <em> 高亮标签：先整体转义，再还原 em 标签 */
function renderSnippet(raw) {
    return esc(raw).replace(/&lt;em&gt;/g, '<em>').replace(/&lt;\/em&gt;/g, '</em>');
}

function fmtSize(bytes) {
    if (bytes == null) return '';
    const units = ['B', 'KB', 'MB', 'GB'];
    let v = bytes, i = 0;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return (i === 0 ? v : v.toFixed(1)) + ' ' + units[i];
}

function fmtTime(msOrIso) {
    if (!msOrIso) return '';
    const d = typeof msOrIso === 'number' ? new Date(msOrIso) : new Date(String(msOrIso).replace(' ', 'T'));
    if (isNaN(d.getTime())) return String(msOrIso);
    const p = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

let toastTimer = null;
function toast(msg, isErr = false) {
    const el = $('toast');
    el.textContent = msg;
    el.classList.toggle('err', isErr);
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, 3000);
}

/* 统一 API 调用：响应为 {code,msg,data}，code!=0 或 HTTP 错误时抛异常 */
async function api(path, options = {}) {
    const resp = await fetch(path, options);
    let body = null;
    try { body = await resp.json(); } catch (e) { /* 非 JSON 响应 */ }
    if (!resp.ok || (body && body.code !== 0)) {
        throw new Error((body && body.msg) || `请求失败（HTTP ${resp.status}）`);
    }
    return body ? body.data : null;
}

async function apiJson(path, method, payload) {
    return api(path, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
}

/* ---------- 视图切换 ---------- */
function switchView(name) {
    for (const v of ['search', 'libraries', 'settings']) {
        $('view-' + v).hidden = v !== name;
    }
    document.querySelectorAll('.nav-btn').forEach(b =>
        b.classList.toggle('active', b.dataset.view === name));
    stopDirPoll();
    if (name === 'libraries') refreshLibraries();
    if (name === 'settings') refreshSettings();
}

document.querySelectorAll('.nav-btn').forEach(btn =>
    btn.addEventListener('click', () => switchView(btn.dataset.view)));

/* ---------- 库下拉框 ---------- */
function renderLibSelect() {
    const opts = state.libraries.map(l =>
        `<option value="${l.id}">${esc(l.name)}</option>`).join('');
    $('search-lib').innerHTML = `<option value="">所有库</option>` + opts;
    const sel = $('search-lib');
    if (state.settings && state.settings.lastLibraryId != null &&
        state.libraries.some(l => l.id === state.settings.lastLibraryId)) {
        sel.value = String(state.settings.lastLibraryId);
    }
}

/* ---------- 搜索 ---------- */
$('search-form').addEventListener('submit', e => {
    e.preventDefault();
    doSearch(0);
});

async function doSearch(page) {
    const q = $('search-input').value.trim();
    if (!q) { toast('请输入搜索关键词'); return; }
    const lib = $('search-lib').value;
    const size = (state.settings && state.settings.pageSize) || 20;
    state.search = { q, lib, page, size };

    const statusEl = $('search-status');
    statusEl.hidden = false;
    statusEl.textContent = '搜索中…';
    $('search-results').innerHTML = '';
    $('pagination').hidden = true;

    try {
        const libParam = lib ? `&lib=${encodeURIComponent(lib)}` : '';
        const data = await api(
            `/api/search?q=${encodeURIComponent(q)}&page=${page}&size=${size}${libParam}`);
        renderResults(data);
    } catch (e) {
        statusEl.textContent = '搜索失败：' + e.message;
    }
}

function renderResults(data) {
    const statusEl = $('search-status');
    const container = $('search-results');
    const { total, page, size, tookMs, items } = data;

    if (!total) {
        statusEl.textContent = '没有找到匹配的结果。';
        container.innerHTML = '';
        $('pagination').hidden = true;
        return;
    }

    const pages = Math.ceil(total / size);
    statusEl.textContent = `共 ${total} 条结果，用时 ${tookMs} ms`;

    container.innerHTML = items.map(item => `
        <div class="result-item" data-path="${esc(item.path)}">
            <div class="result-name">${esc(item.name)}</div>
            <div class="result-path">
                ${item.libraryName ? `<span class="lib-tag">${esc(item.libraryName)}</span>` : ''}${esc(item.path)}
            </div>
            <div class="result-snippet">${renderSnippet(item.snippet)}</div>
            <div class="result-meta">
                ${esc(item.ext || '无扩展名')} · ${fmtSize(item.size)} · 修改于 ${fmtTime(item.mtime)}
            </div>
        </div>`).join('');

    container.querySelectorAll('.result-item').forEach(el =>
        el.addEventListener('click', () => {
            const item = items.find(i => i.path === el.dataset.path);
            if (item) openPreview(item);
        }));

    /* 分页 */
    const pag = $('pagination');
    pag.hidden = pages <= 1;
    $('page-info').textContent = `第 ${page + 1} / ${pages} 页`;
    $('page-prev').disabled = page <= 0;
    $('page-next').disabled = page >= pages - 1;
}

$('page-prev').addEventListener('click', () => doSearch(state.search.page - 1));
$('page-next').addEventListener('click', () => doSearch(state.search.page + 1));

/* ---------- 文件预览 ---------- */
function openPreview(item) {
    const overlay = $('preview-overlay');
    const body = $('preview-body');
    $('preview-title').textContent = item.name;
    body.innerHTML = '<div class="preview-note">加载预览中…</div>';
    overlay.hidden = false;

    const ext = (item.ext || '').toLowerCase();
    const rawUrl = '/api/file/raw?path=' + encodeURIComponent(item.path);

    if (ext === 'pdf') {
        body.innerHTML = '';
        const iframe = document.createElement('iframe');
        iframe.src = rawUrl;
        body.appendChild(iframe);
    } else if (IMAGE_EXTS.has(ext)) {
        body.innerHTML = '';
        const img = document.createElement('img');
        img.src = rawUrl;
        img.alt = item.name;
        body.appendChild(img);
    } else {
        api('/api/file/text?path=' + encodeURIComponent(item.path)).then(t => {
            body.innerHTML = '';
            if (t.truncated) {
                const note = document.createElement('div');
                note.className = 'preview-note';
                note.textContent = '内容过长，仅显示前一部分。';
                body.appendChild(note);
            }
            const pre = document.createElement('pre');
            pre.textContent = t.content || '（空文件或无法抽取内容）';
            body.appendChild(pre);
        }).catch(err => {
            body.innerHTML = `<div class="preview-note">预览失败：${esc(err.message)}</div>`;
        });
    }
}

$('preview-close').addEventListener('click', () => {
    $('preview-overlay').hidden = true;
    $('preview-body').innerHTML = '';
});

$('preview-overlay').addEventListener('click', e => {
    if (e.target === $('preview-overlay')) $('preview-close').click();
});

document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !$('preview-overlay').hidden) $('preview-close').click();
});

/* ---------- 库管理 ---------- */
$('lib-create-form').addEventListener('submit', async e => {
    e.preventDefault();
    const name = $('lib-create-name').value.trim();
    if (!name) return;
    try {
        await apiJson('/api/libraries', 'POST', { name });
        $('lib-create-name').value = '';
        toast('库已创建');
        await refreshLibraries();
    } catch (err) {
        toast(err.message, true);
    }
});

async function refreshLibraries() {
    try {
        state.libraries = await api('/api/libraries');
        renderLibSelect();
        const container = $('library-list');
        if (!state.libraries.length) {
            container.innerHTML = '<p class="muted">还没有库，先在上方新建一个吧。</p>';
            return;
        }
        const dirLists = await Promise.all(state.libraries.map(l =>
            api(`/api/libraries/${l.id}/dirs`).catch(() => [])));
        container.innerHTML = '';
        state.libraries.forEach((lib, i) =>
            container.appendChild(renderLibraryCard(lib, dirLists[i])));
        scheduleDirPoll();
    } catch (err) {
        toast(err.message, true);
    }
}

function renderLibraryCard(lib, dirs) {
    const card = document.createElement('div');
    card.className = 'library-card';
    card.innerHTML = `
        <div class="lib-head">
            <span class="lib-name">${esc(lib.name)}</span>
            <span class="lib-info">${lib.dirCount} 个目录 · 索引位置 ${esc(lib.indexRelativePath)}</span>
            <span class="lib-actions">
                <button data-act="rename">重命名</button>
                <button data-act="rescan">重扫全库</button>
                <button data-act="rebuild">重建索引</button>
                <button data-act="delete" class="danger">删除库</button>
            </span>
        </div>
        <ul class="dir-list">
            ${dirs.map(d => {
                const scanning = d.status === 'SCANNING' && d.progressTotal != null;
                const countText = scanning
                    ? `扫描中 ${d.progressDone ?? 0}/${d.progressTotal}`
                    : `${d.fileCount} 个文件 · ${fmtTime(d.scannedAt)}`;
                const expanded = state.expandedErrors.has(String(d.id));
                const errBtn = d.errorCount > 0 || expanded
                    ? `<button data-act="dir-errors" data-id="${d.id}" class="${expanded ? 'active' : ''}">错误(${d.errorCount || 0})</button>`
                    : '';
                return `
                <li>
                    <span class="dir-path">${esc(d.path)}</span>
                    <span class="status status-${esc(d.status)}">${esc(d.status)}</span>
                    <span class="muted">${countText}</span>
                    ${errBtn}
                    <button data-act="dir-rescan" data-id="${d.id}">重扫</button>
                    <button data-act="dir-delete" data-id="${d.id}" class="danger">移除</button>
                </li>`;
            }).join('') || '<li class="muted">尚未添加目录</li>'}
        </ul>
        <form class="dir-form">
            <input type="text" placeholder="添加要索引的目录绝对路径，如 D:\\docs" required>
            <button type="submit" class="primary">添加目录</button>
        </form>`;

    /* 库级操作 */
    card.querySelector('[data-act="rename"]').addEventListener('click', async () => {
        const name = prompt('新的库名称：', lib.name);
        if (!name || name.trim() === lib.name) return;
        try {
            await apiJson(`/api/libraries/${lib.id}`, 'PUT', { name: name.trim() });
            toast('已重命名');
            await refreshLibraries();
        } catch (err) { toast(err.message, true); }
    });

    card.querySelector('[data-act="rescan"]').addEventListener('click', async () => {
        try {
            await api(`/api/libraries/${lib.id}/rescan`, { method: 'POST' });
            toast('已开始重扫全库');
            await refreshLibraries();
        } catch (err) { toast(err.message, true); }
    });

    card.querySelector('[data-act="rebuild"]').addEventListener('click', async () => {
        if (!confirm(`确定重建库「${lib.name}」的索引？将删除现有索引并全量重扫所有目录。`)) return;
        try {
            await api(`/api/libraries/${lib.id}/rebuild`, { method: 'POST' });
            toast('已开始重建索引');
            await refreshLibraries();
        } catch (err) { toast(err.message, true); }
    });

    card.querySelector('[data-act="delete"]').addEventListener('click', async () => {
        if (!confirm(`确定删除库「${lib.name}」？其索引文件将被删除。`)) return;
        try {
            await api(`/api/libraries/${lib.id}`, { method: 'DELETE' });
            toast('库已删除');
            await refreshLibraries();
        } catch (err) { toast(err.message, true); }
    });

    /* 目录级操作 */
    card.querySelectorAll('[data-act="dir-rescan"]').forEach(btn =>
        btn.addEventListener('click', async () => {
            try {
                await api(`/api/dirs/${btn.dataset.id}/rescan`, { method: 'POST' });
                toast('已开始重扫该目录');
                await refreshLibraries();
            } catch (err) { toast(err.message, true); }
        }));

    card.querySelectorAll('[data-act="dir-delete"]').forEach(btn =>
        btn.addEventListener('click', async () => {
            if (!confirm('确定从库中移除该目录？其索引将被删除。')) return;
            try {
                await api(`/api/dirs/${btn.dataset.id}`, { method: 'DELETE' });
                toast('目录已移除');
                await refreshLibraries();
            } catch (err) { toast(err.message, true); }
        }));

    card.querySelectorAll('[data-act="dir-errors"]').forEach(btn =>
        btn.addEventListener('click', () => toggleErrors(btn)));

    /* 轮询刷新后恢复展开的错误面板 */
    dirs.forEach(d => {
        if (state.expandedErrors.has(String(d.id))) {
            const btn = card.querySelector(`button[data-act="dir-errors"][data-id="${d.id}"]`);
            if (btn) toggleErrors(btn);
        }
    });

    /* 添加目录 */
    card.querySelector('.dir-form').addEventListener('submit', async e => {
        e.preventDefault();
        const input = e.target.querySelector('input');
        const path = input.value.trim();
        if (!path) return;
        try {
            await apiJson(`/api/libraries/${lib.id}/dirs`, 'POST', { path });
            input.value = '';
            toast('目录已添加，开始扫描');
            await refreshLibraries();
        } catch (err) {
            toast(err.message, true);   // 重叠/不存在等错误提示
        }
    });

    return card;
}

/* 展开/收起某目录的扫描错误列表 */
async function toggleErrors(btn) {
    const dirId = btn.dataset.id;
    const li = btn.closest('li');
    if (state.expandedErrors.has(dirId)) {
        state.expandedErrors.delete(dirId);
        btn.classList.remove('active');
        const panel = li.nextElementSibling;
        if (panel && panel.classList.contains('err-list')) panel.remove();
        return;
    }
    state.expandedErrors.add(dirId);
    btn.classList.add('active');
    const panel = document.createElement('div');
    panel.className = 'err-list';
    panel.innerHTML = '<div class="muted">加载中…</div>';
    li.after(panel);
    try {
        const errors = await api(`/api/dirs/${dirId}/errors`);
        panel.innerHTML = errors.length
            ? errors.map(e => `
                <div class="err-item">
                    <div class="err-path">${esc(e.path)}</div>
                    <div class="err-msg">${esc(e.error || '未知错误')}</div>
                    <div class="err-time">${fmtTime(e.createdAt)}</div>
                </div>`).join('')
            : '<div class="muted">没有错误记录。</div>';
    } catch (err) {
        panel.innerHTML = `<div class="muted">加载失败：${esc(err.message)}</div>`;
    }
}

/* 扫描状态轮询：存在 PENDING/SCANNING 目录时每 2 秒刷新一次 */
function scheduleDirPoll() {
    const busy = document.querySelectorAll('#library-list .status-SCANNING, #library-list .status-PENDING').length > 0;
    if (busy && $('view-libraries').hidden === false) {
        state.dirPollTimer = setTimeout(refreshLibraries, 2000);
    }
}

function stopDirPoll() {
    clearTimeout(state.dirPollTimer);
    state.dirPollTimer = null;
}

/* ---------- 设置 ---------- */
async function refreshSettings() {
    try {
        const s = await api('/api/settings');
        state.settings = s;
        $('settings-index-root').value = s.indexRoot || '';
        $('settings-page-size').value = s.pageSize;
        $('settings-last-library').innerHTML =
            '<option value="">（不修改）</option>' +
            state.libraries.map(l => `<option value="${l.id}">${esc(l.name)}</option>`).join('');
        $('settings-last-library').value =
            s.lastLibraryId != null ? String(s.lastLibraryId) : '';
        setMsg('', '');
    } catch (err) {
        toast(err.message, true);
    }
}

function setMsg(text, cls) {
    const el = $('settings-msg');
    el.textContent = text;
    el.className = 'msg' + (cls ? ' ' + cls : '');
}

$('settings-form').addEventListener('submit', async e => {
    e.preventDefault();
    const pageSize = parseInt($('settings-page-size').value, 10);
    if (isNaN(pageSize) || pageSize < 1 || pageSize > 100) {
        setMsg('每页数量需在 1-100 之间', 'err');
        return;
    }
    const payload = {
        indexRoot: $('settings-index-root').value.trim() || null,
        pageSize,
        lastLibraryId: $('settings-last-library').value || null,
    };
    try {
        const s = await apiJson('/api/settings', 'PUT', payload);
        state.settings = s;
        setMsg('已保存', 'ok');
        toast('设置已保存');
    } catch (err) {
        setMsg(err.message, 'err');
    }
});

/* ---------- 初始化 ---------- */
async function init() {
    try {
        const [libs, settings] = await Promise.all([api('/api/libraries'), api('/api/settings')]);
        state.libraries = libs;
        state.settings = settings;
        renderLibSelect();
    } catch (err) {
        toast('初始化失败：' + err.message, true);
    }
}

init();

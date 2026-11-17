(() => {
    'use strict';

    const TOKEN_KEY = 'invoice_ocr_token';
    const STATUS = {
        PROCESSING: { label: '处理中', cls: 'processing' },
        COMPLETED: { label: '已识别', cls: 'completed' },
        NEEDS_REVIEW: { label: '待复核', cls: 'review' },
        CONFIRMED: { label: '已确认', cls: 'confirmed' },
        REJECTED: { label: '已拒绝', cls: 'rejected' },
        OCR_FAILED: { label: '识别失败', cls: 'failed' },
        DUPLICATE: { label: '重复上传', cls: 'duplicate' }
    };
    const TYPE_LABELS = {
        VAT_SPECIAL: '增值税专用发票',
        VAT_NORMAL: '增值税普通发票',
        VAT_E_NORMAL: '增值税电子普通发票',
        DIGITAL: '全电发票（数电票）',
        VAT_ROLL: '增值税卷票',
        OTHER: '其他发票'
    };
    const VERIFY_LABELS = {
        CONSISTENT: '一致',
        INCONSISTENT: '不一致',
        NOT_FOUND: '查无此票',
        INTERFACE_ERROR: '接口异常'
    };
    const CONCLUSION_LABELS = { PASS: '通过', REJECT: '拒绝', NEEDS_MORE_INFO: '需进一步核实' };
    const FIELD_LABELS = {
        invoiceType: '发票类型', invoiceCode: '发票代码', invoiceNumber: '发票号码',
        invoiceDate: '开票日期', machineNumber: '机器编号', buyerName: '购买方名称',
        buyerTaxId: '购买方税号', buyerAddressPhone: '购买方地址电话', buyerBankAccount: '购买方开户行及账号',
        sellerName: '销售方名称', sellerTaxId: '销售方税号', sellerAddressPhone: '销售方地址电话',
        sellerBankAccount: '销售方开户行及账号', totalAmount: '合计金额（不含税）', totalTax: '合计税额',
        grandTotal: '价税合计（小写）', grandTotalCn: '价税合计（大写）', taxRate: '税率',
        checkCode: '校验码', payee: '收款人', reviewerName: '复核人', drawer: '开票人',
        items: '商品明细', conclusion: '复核结论'
    };
    const EDITABLE_FIELDS = [
        'invoiceType', 'invoiceCode', 'invoiceNumber', 'invoiceDate', 'machineNumber',
        'buyerName', 'buyerTaxId', 'buyerAddressPhone', 'buyerBankAccount',
        'sellerName', 'sellerTaxId', 'sellerAddressPhone', 'sellerBankAccount',
        'totalAmount', 'totalTax', 'grandTotal', 'grandTotalCn', 'taxRate', 'checkCode',
        'payee', 'reviewerName', 'drawer'
    ];
    const state = {
        token: localStorage.getItem(TOKEN_KEY),
        user: null,
        view: 'upload',
        selectedFile: null,
        ledgerPage: 0,
        reviewPage: 0,
        auditPage: 0,
        detailPollId: null,
        detailPollTick: 0
    };

    const $ = (selector, root = document) => root.querySelector(selector);
    const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

    function esc(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function toast(message, type = 'info') {
        const node = document.createElement('div');
        node.className = 'toast ' + (type === 'error' ? 'error' : type === 'success' ? 'success' : '');
        node.textContent = message;
        $('#toast-root').appendChild(node);
        setTimeout(() => node.remove(), 4200);
    }

    async function api(path, options = {}) {
        const headers = Object.assign({}, options.headers || {});
        if (state.token) headers.Authorization = 'Bearer ' + state.token;
        let body = options.body;
        if (body && !(body instanceof FormData) && typeof body !== 'string') {
            headers['Content-Type'] = 'application/json';
            body = JSON.stringify(body);
        }
        const response = await fetch(path, Object.assign({}, options, { headers, body }));
        if (response.status === 401 && !path.startsWith('/api/auth/login')) {
            logout();
            throw new Error('登录已过期，请重新登录');
        }
        const contentType = response.headers.get('content-type') || '';
        if (!response.ok) {
            let message = '请求失败（' + response.status + '）';
            if (contentType.includes('application/json')) {
                try {
                    const error = await response.json();
                    message = error.message || error.error || message;
                } catch (ignored) { /* noop */ }
            }
            throw new Error(message);
        }
        if (contentType.includes('application/json')) return response.json();
        return response;
    }

    function statusBadge(status) {
        const meta = STATUS[status] || { label: status || '未知', cls: 'subtle' };
        return '<span class="badge ' + meta.cls + '">' + esc(meta.label) + '</span>';
    }

    function verifyBadge(d) {
        if (!d.verificationStatus) return '<span class="muted tiny">未验真</span>';
        const label = d.verificationStatusLabel || VERIFY_LABELS[d.verificationStatus] || d.verificationStatus;
        const cls = d.verificationStatus === 'CONSISTENT' ? 'completed' : 'review';
        return '<span class="badge ' + cls + '">' + esc(label) + '</span>';
    }

    function duplicateBadge(flag, type) {
        if (!flag) return '<span class="muted tiny">—</span>';
        const map = {
            EXACT: '精确重复', FINGERPRINT: '指纹重复', FIELD_HASH: '字段碰撞', FILE_HASH: '文件重复'
        };
        return '<span class="badge duplicate">' + esc(map[type] || '重复') + '</span>';
    }

    function fmtDateTime(value) {
        if (!value) return '—';
        return String(value).replace('T', ' ').slice(0, 16);
    }

    function fmtMoney(value) {
        if (value === null || value === undefined || value === '') return '—';
        let text = String(value);
        const negative = text.startsWith('-');
        if (negative) text = text.slice(1);
        const parts = text.split('.');
        parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
        if (parts.length === 1) parts.push('00');
        else parts[1] = (parts[1] + '00').slice(0, 2);
        return (negative ? '-' : '') + parts[0] + '.' + parts[1];
    }

    function confidenceBadge(value) {
        if (value === null || value === undefined) return '';
        const cls = value >= 80 ? 'conf-good' : value >= 60 ? 'conf-mid' : 'conf-low';
        return '<span class="conf-badge ' + cls + '">置信度 ' + esc(value) + '</span>';
    }

    function formToObject(form) {
        const result = {};
        new FormData(form).forEach((value, key) => {
            if (value !== null && String(value).trim() !== '') result[key] = String(value).trim();
        });
        return result;
    }

    function queryString(object) {
        const params = new URLSearchParams();
        Object.keys(object).forEach(key => {
            if (object[key] !== undefined && object[key] !== null && object[key] !== '') {
                params.set(key, object[key]);
            }
        });
        return params.toString();
    }

    /* ---------------- Authentication ---------------- */

    async function login(username, password) {
        const response = await api('/api/auth/login', {
            method: 'POST', body: { username, password }
        });
        state.token = response.token;
        state.user = response.user;
        localStorage.setItem(TOKEN_KEY, response.token);
        showApp();
    }

    function logout() {
        state.token = null;
        state.user = null;
        state.detailPollId = null;
        state.detailPollTick = 0;
        localStorage.removeItem(TOKEN_KEY);
        $('#modal-root').innerHTML = '';
        $('#app-view').classList.add('hidden');
        $('#login-view').classList.remove('hidden');
        $('#login-password').value = '';
    }

    function showApp() {
        $('#login-view').classList.add('hidden');
        $('#app-view').classList.remove('hidden');
        $('#user-name').textContent = state.user.displayName + ' / ' + state.user.username;
        $('#user-role').textContent = state.user.roleLabel;
        $('#user-avatar').textContent = (state.user.displayName || 'U').slice(0, 1);
        const role = state.user.role;
        const canUpload = ['SUBMITTER', 'REVIEWER', 'ADMIN'].includes(role);
        const canReview = ['REVIEWER', 'ADMIN'].includes(role);
        const canAudit = ['ADMIN', 'AUDITOR'].includes(role);
        const isAdmin = role === 'ADMIN';
        $$('#main-nav .nav-item').forEach(button => {
            const view = button.dataset.view;
            let visible = true;
            if (view === 'upload') visible = canUpload;
            if (view === 'review') visible = canReview;
            if (view === 'audit') visible = canAudit;
            if (view === 'config') visible = isAdmin;
            button.classList.toggle('hidden', !visible);
        });
        const defaultView = canUpload ? 'upload' : 'ledger';
        navigate(defaultView);
    }

    /* ---------------- Navigation ---------------- */

    const VIEW_META = {
        upload: ['上传识别', '支持 JPG / PNG / BMP / PDF / OFD，单文件不超过 10MB'],
        ledger: ['发票台账', '组合筛选、查看详情与导出'],
        review: ['复核队列', '低置信度、查重命中与验真异常'],
        audit: ['审计日志', '关键操作的只读记录'],
        config: ['系统配置', 'OCR 引擎、置信度阈值与验签渠道']
    };

    function navigate(view) {
        state.view = view;
        $$('#main-nav .nav-item').forEach(button => button.classList.toggle('active', button.dataset.view === view));
        $$('.view').forEach(section => section.classList.toggle('active', section.id === 'view-' + view));
        const meta = VIEW_META[view] || ['', ''];
        $('#view-title').textContent = meta[0];
        $('#view-subtitle').textContent = meta[1];
        if (view === 'ledger') loadLedger();
        if (view === 'review') loadReviewQueue();
        if (view === 'audit') loadAudit();
        if (view === 'config') loadConfig();
    }

    /* ---------------- Upload ---------------- */

    function bindUpload() {
        const dropZone = $('#drop-zone');
        const fileInput = $('#file-input');
        const uploadButton = $('#upload-btn');
        dropZone.addEventListener('click', () => fileInput.click());
        dropZone.addEventListener('dragover', event => {
            event.preventDefault();
            dropZone.classList.add('dragover');
        });
        dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
        dropZone.addEventListener('drop', event => {
            event.preventDefault();
            dropZone.classList.remove('dragover');
            if (event.dataTransfer.files.length) {
                selectFile(event.dataTransfer.files[0]);
            }
        });
        fileInput.addEventListener('change', () => {
            if (fileInput.files.length) selectFile(fileInput.files[0]);
        });

        function selectFile(file) {
            const allowed = ['jpg', 'jpeg', 'png', 'bmp', 'pdf', 'ofd'];
            const extension = (file.name.split('.').pop() || '').toLowerCase();
            if (!allowed.includes(extension)) {
                toast('仅支持 JPG、PNG、BMP、PDF、OFD 格式', 'error');
                return;
            }
            if (file.size > 10 * 1024 * 1024) {
                toast('文件超过 10MB 限制', 'error');
                return;
            }
            state.selectedFile = file;
            const nameBox = $('#file-name');
            nameBox.textContent = file.name + ' · ' + (file.size / 1024).toFixed(1) + ' KB';
            nameBox.classList.remove('hidden');
            uploadButton.disabled = false;
        }

        uploadButton.addEventListener('click', async () => {
            if (!state.selectedFile) return;
            uploadButton.disabled = true;
            const form = new FormData();
            form.append('file', state.selectedFile);
            const typeHint = $('#upload-type').value;
            if (typeHint) form.append('invoiceType', typeHint);
            $('#upload-result').classList.remove('hidden');
            $('#upload-result').innerHTML = '<span class="muted">正在上传并创建识别任务…</span>';
            try {
                const response = await api('/api/invoices/upload', { method: 'POST', body: form });
                if (response.existing) {
                    response.existing.duplicateFlag = response.duplicateFlag;
                    response.existing.duplicateType = response.duplicateType;
                    response.existing.message = response.message;
                    renderUploadResult(response.existing);
                    toast('该文件已识别过，已返回历史结果', 'success');
                } else {
                    pollUpload(response.invoiceId);
                }
            } catch (error) {
                $('#upload-result').innerHTML = '<span class="form-error">' + esc(error.message) + '</span>';
                toast(error.message, 'error');
            } finally {
                uploadButton.disabled = false;
            }
        });
    }

    function pollUpload(invoiceId) {
        let attempts = 0;
        const tick = async () => {
            try {
                const data = await api('/api/invoices/' + encodeURIComponent(invoiceId));
                renderUploadResult(data);
                if (data.status === 'PROCESSING' && attempts++ < 80) {
                    setTimeout(tick, 1500);
                }
            } catch (error) {
                $('#upload-result').innerHTML = '<span class="form-error">' + esc(error.message) + '</span>';
            }
        };
        tick();
    }

    function renderUploadResult(data) {
        const id = data.id || data.invoiceId;
        const status = data.status;
        const preview = data.previewUrl;
        let html = '<div class="result-head">' + statusBadge(status);
        html += '<strong>' + esc(data.invoiceNumber ? '发票号码 ' + data.invoiceNumber : '任务 ' + String(id).slice(0, 8)) + '</strong>';
        if (data.duplicateFlag) html += duplicateBadge(true, data.duplicateType);
        html += '</div>';
        if (data.status === 'PROCESSING') {
            html += '<p class="muted">后台正在识别，请稍候… ' + esc(data.message || '') + '</p>';
        } else if (status === 'OCR_FAILED') {
            html += '<p class="form-error">' + esc(data.errorMessage || data.message || '识别失败') + '</p>';
        } else if (status === 'DUPLICATE') {
            html += '<p class="muted">' + esc(data.message || '该发票已存在，未重复入库') + '</p>';
        } else {
            html += '<p class="muted">' + esc(data.message || '识别完成') + '</p>';
            const seller = data.seller ? data.seller.name : data.sellerName;
            const total = data.grandTotal;
            if (seller || (total !== undefined && total !== null)) {
                html += '<div class="kv" style="margin-top:8px">';
                if (seller) html += '<span>销售方</span><span>' + esc(seller) + '</span>';
                if (total !== undefined && total !== null) {
                    html += '<span>价税合计</span><span class="money">' + esc(fmtMoney(total)) + '</span>';
                }
                html += '</div>';
            }
        }
        html += '<div class="detail-links">';
        if (id) html += '<button class="btn primary small" data-open="' + esc(id) + '">查看详情</button>';
        if (data.duplicateOfInvoiceId) html += '<button class="btn ghost small" data-open="' + esc(data.duplicateOfInvoiceId) + '">查看已有发票</button>';
        if (preview) html += '<a class="btn ghost small" href="' + esc(preview) + '" target="_blank" rel="noopener">原始文件</a>';
        if ((status === 'OCR_FAILED' || status === 'NEEDS_REVIEW') && state.user && ['REVIEWER', 'ADMIN'].includes(state.user.role)) {
            html += '<button class="btn ghost small" data-action="reprocess" data-invoice="' + esc(id) + '">重新识别</button>';
        }
        html += '</div>';
        const box = $('#upload-result');
        box.innerHTML = html;
        box.classList.remove('hidden');
        bindDynamicButtons(box);
    }

    /* ---------------- Ledger ---------------- */

    async function loadLedger() {
        const form = $('#ledger-filter');
        const query = formToObject(form);
        query.page = state.ledgerPage;
        query.size = 20;
        try {
            const page = await api('/api/invoices?' + queryString(query));
            renderLedger(page);
        } catch (error) {
            toast(error.message, 'error');
        }
    }

    function renderLedger(page) {
        const table = $('#ledger-table');
        const items = page.content || [];
        $('#ledger-count').textContent = '共 ' + (page.totalElements || 0) + ' 张';
        let html = '<thead><tr><th>状态</th><th>发票号码</th><th>开票日期</th><th>销售方</th><th>价税合计</th><th>查重</th><th>验真</th><th>上传人</th><th>上传时间</th></tr></thead><tbody>';
        if (!items.length) {
            html += '<tr><td colspan="9" class="empty-row">暂无数据</td></tr>';
        } else {
            items.forEach(item => {
                html += '<tr data-open="' + esc(item.id) + '">' +
                    '<td>' + statusBadge(item.status) + '</td>' +
                    '<td class="mono">' + esc(item.invoiceNumber || '—') + '</td>' +
                    '<td>' + esc(item.invoiceDate || '—') + '</td>' +
                    '<td>' + esc(item.sellerName || '—') + '</td>' +
                    '<td class="money">' + esc(fmtMoney(item.grandTotal)) + '</td>' +
                    '<td>' + duplicateBadge(item.duplicateFlag, item.duplicateType) + '</td>' +
                    '<td>' + (item.verificationStatus ? verifyBadge(item) : '<span class="muted tiny">未验真</span>') + '</td>' +
                    '<td>' + esc(item.ownerUsername || '—') + '</td>' +
                    '<td class="muted">' + esc(fmtDateTime(item.createdAt)) + '</td>' +
                    '</tr>';
            });
        }
        html += '</tbody>';
        table.innerHTML = html;
        bindRowClicks(table);
        renderPager('#ledger-pager', page, state.ledgerPage, value => {
            state.ledgerPage = value;
            loadLedger();
        });
    }

    /* ---------------- Review queue ---------------- */

    async function loadReviewQueue() {
        const query = formToObject($('#review-filter'));
        query.page = state.reviewPage;
        query.size = 20;
        try {
            const page = await api('/api/review-tasks?' + queryString(query));
            renderReview(page);
        } catch (error) {
            toast(error.message, 'error');
        }
    }

    function renderReview(page) {
        const items = page.content || [];
        let html = '<thead><tr><th>状态</th><th>发票号码</th><th>销售方</th><th>价税合计</th><th>复核原因</th><th>上传时间</th></tr></thead><tbody>';
        if (!items.length) {
            html += '<tr><td colspan="6" class="empty-row">暂无待复核发票</td></tr>';
        } else {
            items.forEach(item => {
                const reasons = (item.reviewReasons || []).join('；');
                html += '<tr data-open="' + esc(item.id) + '">' +
                    '<td>' + statusBadge(item.status) + '</td>' +
                    '<td class="mono">' + esc(item.invoiceNumber || '—') + '</td>' +
                    '<td>' + esc(item.sellerName || '—') + '</td>' +
                    '<td class="money">' + esc(fmtMoney(item.grandTotal)) + '</td>' +
                    '<td class="reasons-cell">' + esc(reasons || '—') + '</td>' +
                    '<td class="muted">' + esc(fmtDateTime(item.createdAt)) + '</td>' +
                    '</tr>';
            });
        }
        html += '</tbody>';
        $('#review-table').innerHTML = html;
        bindRowClicks($('#review-table'));
        renderPager('#review-pager', page, state.reviewPage, value => {
            state.reviewPage = value;
            loadReviewQueue();
        });
    }

    /* ---------------- Audit ---------------- */

    async function loadAudit() {
        const query = formToObject($('#audit-filter'));
        query.page = state.auditPage;
        query.size = 20;
        try {
            const page = await api('/api/audit-logs?' + queryString(query));
            renderAudit(page);
        } catch (error) {
            toast(error.message, 'error');
        }
    }

    function renderAudit(page) {
        const items = page.content || [];
        let html = '<thead><tr><th>时间</th><th>操作人</th><th>角色</th><th>操作</th><th>对象</th><th>IP</th><th>详情</th></tr></thead><tbody>';
        if (!items.length) {
            html += '<tr><td colspan="7" class="empty-row">暂无审计记录</td></tr>';
        } else {
            items.forEach(item => {
                const detail = item.detail || '';
                html += '<tr style="cursor:default">' +
                    '<td class="muted">' + esc(fmtDateTime(item.createdAt)) + '</td>' +
                    '<td>' + esc(item.username || 'system') + '</td>' +
                    '<td class="muted">' + esc(item.role || '—') + '</td>' +
                    '<td><span class="badge subtle">' + esc(item.action) + '</span></td>' +
                    '<td class="mono">' + esc(item.objectType || '') + ' ' + esc(item.objectId || '') + '</td>' +
                    '<td class="mono">' + esc(item.ip || '—') + '</td>' +
                    '<td class="muted" title="' + esc(detail) + '">' + esc(detail.length > 80 ? detail.slice(0, 80) + '…' : detail) + '</td>' +
                    '</tr>';
            });
        }
        html += '</tbody>';
        $('#audit-table').innerHTML = html;
        renderPager('#audit-pager', page, state.auditPage, value => {
            state.auditPage = value;
            loadAudit();
        });
    }

    function renderPager(selector, page, current, onPage) {
        const root = $(selector);
        if (!page || !page.totalElements) {
            root.innerHTML = '';
            return;
        }
        root.innerHTML = '<span class="page-info">第 ' + (page.page + 1) + ' / ' + Math.max(page.totalPages, 1) + ' 页</span>' +
            '<button class="btn ghost small" data-page="' + (page.page - 1) + '" ' + (page.page <= 0 ? 'disabled' : '') + '>上一页</button>' +
            '<button class="btn ghost small" data-page="' + (page.page + 1) + '" ' + (page.page >= page.totalPages - 1 ? 'disabled' : '') + '>下一页</button>';
        $$('.btn[data-page]', root).forEach(button => {
            button.onclick = () => onPage(Number(button.dataset.page));
        });
    }

    function bindRowClicks(table) {
        $$('tbody tr[data-open]', table).forEach(row => {
            row.addEventListener('click', () => openDetail(row.dataset.open));
        });
    }

    /* ---------------- Detail modal ---------------- */

    async function openDetail(invoiceId) {
        if (!invoiceId) return;
        try {
            const data = await api('/api/invoices/' + encodeURIComponent(invoiceId));
            const modal = document.createElement('div');
            modal.className = 'modal-backdrop';
            modal.dataset.invoiceId = invoiceId;
            modal.innerHTML = '<div class="modal-panel">' + invoiceDetailHtml(data) + '</div>';
            $('#modal-root').innerHTML = '';
            $('#modal-root').appendChild(modal);
            modal.addEventListener('click', event => {
                if (event.target === modal) closeModal();
            });
            bindDynamicButtons(modal);
            if (data.status === 'PROCESSING') {
                if (state.detailPollId !== invoiceId) {
                    state.detailPollId = invoiceId;
                    state.detailPollTick = 0;
                }
                if (state.detailPollTick++ < 60) {
                    setTimeout(() => {
                        const current = $('.modal-backdrop');
                        if (current && current.dataset.invoiceId === invoiceId) openDetail(invoiceId);
                    }, 1800);
                }
            }
        } catch (error) {
            toast(error.message, 'error');
        }
    }

    function closeModal() {
        state.detailPollId = null;
        state.detailPollTick = 0;
        $('#modal-root').innerHTML = '';
    }

    function invoiceDetailHtml(d) {
        const isInvoice = d.buyer !== undefined && d.seller !== undefined;
        let html = '<div class="modal-head"><div><h2>' +
            esc(isInvoice ? (d.invoiceNumber || '发票详情') : '识别任务') + '</h2><span class="muted tiny">' +
            esc(d.id) + '</span></div><button class="close" data-close>×</button></div>';
        html += '<div class="modal-body">';
        if (!isInvoice) {
            html += taskBodyHtml(d);
            html += '</div>';
            return html;
        }
        html += '<div class="card"><div class="detail-top"><div><div class="detail-summary">' +
            statusBadge(d.status) +
            '<span class="badge subtle">' + esc(d.invoiceTypeLabel || TYPE_LABELS[d.invoiceType] || '未知类型') + '</span>' +
            (d.duplicateFlag ? duplicateBadge(true, d.duplicateType) : '') +
            verifyBadge(d) +
            '</div><h3 style="margin-top:10px">' + esc(d.seller ? d.seller.name || '—' : '—') + '</h3>' +
            '<span class="muted tiny">价税合计 <strong class="money">' + esc(fmtMoney(d.grandTotal)) + '</strong> · 开票日期 ' + esc(d.invoiceDate || '—') + '</span></div>' +
            '<div class="detail-actions">' +
            (d.previewUrl ? '<a class="btn ghost small" href="' + esc(d.previewUrl) + '" target="_blank" rel="noopener">原始文件</a>' : '') +
            (state.user && ['REVIEWER', 'ADMIN'].includes(state.user.role) ? '<button class="btn ghost small" data-action="verify" data-invoice="' + esc(d.id) + '">触发验真</button>' : '') +
            (state.user && ['REVIEWER', 'ADMIN'].includes(state.user.role) && ['NEEDS_REVIEW', 'OCR_FAILED', 'COMPLETED'].includes(d.status) ? '<button class="btn ghost small" data-action="reprocess" data-invoice="' + esc(d.id) + '">重新识别</button>' : '') +
            '</div></div>';
        if ((d.reviewReasons || []).length) {
            html += '<ul class="reason-list" style="margin-top:12px">' + d.reviewReasons.map(reason => '<li>' + esc(reason) + '</li>').join('') + '</ul>';
        }
        if (d.duplicateOfInvoiceId) {
            html += '<div class="notice" style="margin-top:12px">该发票与已有发票重复。<button class="btn ghost small" data-open="' + esc(d.duplicateOfInvoiceId) + '">查看重复来源</button></div>';
        }
        html += '</div>';

        html += '<div class="field-grid">' + basicCard(d) + partyCard('购买方', d.buyer) + partyCard('销售方', d.seller) + amountCard(d) + '</div>';

        html += '<div class="card"><div class="section-title">商品明细</div>' + itemsHtml(d.items) + '</div>';

        html += '<div class="card"><div class="section-title">原始文件预览</div>' + previewHtml(d) + '</div>';

        if (state.user && state.user.role === 'REVIEWER') {
            html += reviewFormHtml(d);
        }
        html += historyHtml(d);
        html += '</div>';
        return html;
    }

    function taskBodyHtml(d) {
        let html = '<div class="card"><div class="detail-summary">' + statusBadge(d.status);
        if (d.duplicateFlag) html += duplicateBadge(true, d.duplicateType);
        html += '</div>';
        if (d.message) html += '<p class="muted">' + esc(d.message) + '</p>';
        if (d.errorMessage) html += '<p class="form-error">' + esc(d.errorMessage) + '</p>';
        html += '<div class="kv" style="margin-top:12px">' +
            '<span>任务状态</span><span>' + esc(d.statusLabel || d.status) + '</span>' +
            '<span>OCR 引擎</span><span>' + esc(d.engine || '—') + '</span>' +
            '<span>创建时间</span><span>' + esc(fmtDateTime(d.createdAt)) + '</span>' +
            '<span>完成时间</span><span>' + esc(fmtDateTime(d.finishedAt)) + '</span>' +
            '</div>';
        html += '<div class="detail-links">';
        if (d.previewUrl) html += '<a class="btn ghost small" href="' + esc(d.previewUrl) + '" target="_blank" rel="noopener">原始文件</a>';
        if (d.duplicateOfInvoiceId) html += '<button class="btn primary small" data-open="' + esc(d.duplicateOfInvoiceId) + '">查看已有发票</button>';
        if (state.user && ['REVIEWER', 'ADMIN'].includes(state.user.role)) {
            html += '<button class="btn ghost small" data-action="reprocess" data-invoice="' + esc(d.id) + '">重新识别</button>';
        }
        html += '</div></div>';
        return html;
    }

    function fieldRow(label, value, key, confidence) {
        let html = '<tr><td>' + esc(label) + '</td><td><div class="field-value"><span>' + esc(value || '—') + '</span>';
        if (key && confidence && confidence[key] !== undefined) html += confidenceBadge(confidence[key]);
        html += '</div></td></tr>';
        return html;
    }

    function basicCard(d) {
        const c = d.fieldConfidence || {};
        return '<div class="card"><div class="section-title">基本信息</div><table class="field-table">' +
            fieldRow('发票类型', d.invoiceTypeLabel || TYPE_LABELS[d.invoiceType], 'invoiceType', c) +
            fieldRow('发票代码', d.invoiceCode, 'invoiceCode', c) +
            fieldRow('发票号码', d.invoiceNumber, 'invoiceNumber', c) +
            fieldRow('开票日期', d.invoiceDate, 'invoiceDate', c) +
            fieldRow('机器编号', d.machineNumber, 'machineNumber', c) +
            fieldRow('校验码', d.checkCode, 'checkCode', c) +
            '</table></div>';
    }

    function partyCard(title, party) {
        const p = party || {};
        return '<div class="card"><div class="section-title">' + esc(title) + '</div><table class="field-table">' +
            fieldRow('名称', p.name) +
            fieldRow('纳税人识别号', p.taxId) +
            fieldRow('地址、电话', p.addressPhone) +
            fieldRow('开户行及账号', p.bankAccount) +
            '</table></div>';
    }

    function amountCard(d) {
        const c = d.fieldConfidence || {};
        return '<div class="card"><div class="section-title">金额信息</div><table class="field-table">' +
            fieldRow('合计金额（不含税）', fmtMoney(d.totalAmount), 'totalAmount', c) +
            fieldRow('合计税额', fmtMoney(d.totalTax), 'totalTax', c) +
            fieldRow('价税合计（小写）', fmtMoney(d.grandTotal), 'grandTotal', c) +
            fieldRow('价税合计（大写）', d.grandTotalCn, 'grandTotalCn', c) +
            fieldRow('税率', d.taxRate, 'taxRate', c) +
            fieldRow('收款人 / 复核人 / 开票人', [d.payee, d.reviewerName, d.drawer].filter(Boolean).join(' / ') || '—') +
            '</table></div>';
    }

    function itemsHtml(items) {
        if (!items || !items.length) return '<div class="empty">未解析到商品明细</div>';
        let html = '<div class="table-wrap"><table class="items-table"><thead><tr><th>#</th><th>货物或服务名称</th><th>单位</th><th>数量</th><th>单价</th><th>金额</th><th>税率</th><th>税额</th></tr></thead><tbody>';
        items.forEach(item => {
            html += '<tr><td>' + esc(item.lineNo) + '</td><td>' + esc(item.itemName || '') + '</td><td>' + esc(item.unit || '') + '</td>' +
                '<td>' + esc(item.quantity || '') + '</td><td>' + esc(item.unitPrice || '') + '</td>' +
                '<td>' + esc(item.amount || '') + '</td><td>' + esc(item.taxRate || '') + '</td><td>' + esc(item.taxAmount || '') + '</td></tr>';
        });
        return html + '</tbody></table></div>';
    }

    function previewHtml(d) {
        if (!d.previewUrl) return '<div class="empty">无预览</div>';
        if (String(d.fileType).toLowerCase() === 'pdf') {
            return '<iframe class="preview-frame" src="' + esc(d.previewUrl) + '" title="发票预览"></iframe>';
        }
        if (['jpg', 'jpeg', 'png', 'bmp'].includes(String(d.fileType).toLowerCase())) {
            return '<img class="preview-image" src="' + esc(d.previewUrl) + '" alt="发票预览">';
        }
        return '<a class="btn ghost small" href="' + esc(d.previewUrl) + '" target="_blank" rel="noopener">下载原始文件</a>';
    }

    function reviewFormHtml(d) {
        const options = ['<option value="">（未知）</option>'].concat(Object.keys(TYPE_LABELS).map(key =>
            '<option value="' + key + '"' + (d.invoiceType === key ? ' selected' : '') + '>' + esc(TYPE_LABELS[key]) + '</option>')).join('');
        const values = {
            invoiceType: d.invoiceType || '', invoiceCode: d.invoiceCode || '', invoiceNumber: d.invoiceNumber || '',
            invoiceDate: d.invoiceDate || '', machineNumber: d.machineNumber || '',
            buyerName: d.buyer && d.buyer.name || '', buyerTaxId: d.buyer && d.buyer.taxId || '',
            buyerAddressPhone: d.buyer && d.buyer.addressPhone || '', buyerBankAccount: d.buyer && d.buyer.bankAccount || '',
            sellerName: d.seller && d.seller.name || '', sellerTaxId: d.seller && d.seller.taxId || '',
            sellerAddressPhone: d.seller && d.seller.addressPhone || '', sellerBankAccount: d.seller && d.seller.bankAccount || '',
            totalAmount: d.totalAmount || '', totalTax: d.totalTax || '', grandTotal: d.grandTotal || '',
            grandTotalCn: d.grandTotalCn || '', taxRate: d.taxRate || '', checkCode: d.checkCode || '',
            payee: d.payee || '', reviewerName: d.reviewerName || '', drawer: d.drawer || ''
        };
        let fields = '';
        EDITABLE_FIELDS.forEach(key => {
            if (key === 'invoiceType') {
                fields += '<label>' + esc(FIELD_LABELS[key]) + '<select data-field="' + key + '">' + options + '</select></label>';
            } else if (key === 'invoiceDate') {
                fields += '<label>' + esc(FIELD_LABELS[key]) + '<input type="date" data-field="' + key + '" value="' + esc(values[key]) + '"></label>';
            } else {
                fields += '<label>' + esc(FIELD_LABELS[key]) + '<input data-field="' + key + '" value="' + esc(values[key]) + '"></label>';
            }
        });
        return '<div class="card"><div class="section-title">人工复核</div>' +
            '<form id="review-form" class="review-form" data-invoice="' + esc(d.id) + '">' +
            '<div class="review-fields">' + fields + '</div>' +
            '<div class="review-actions"><label class="grow">复核结论<select id="review-conclusion" required>' +
            '<option value="PASS">通过（进入台账）</option><option value="NEEDS_MORE_INFO">需进一步核实</option><option value="REJECT">拒绝</option>' +
            '</select></label><label class="grow">备注<input id="review-comment" placeholder="可选，拒绝时建议填写原因"></label>' +
            '<button class="btn primary" type="submit">提交复核</button></div>' +
            '<p class="muted tiny">提交后将为每个变更字段写入 review_record（修改前值、修改后值、复核人、时间），并重新计算查重指纹。</p>' +
            '</form></div>';
    }

    function historyHtml(d) {
        let html = '<div class="grid" style="grid-template-columns:1fr 1fr">';
        html += '<div class="card"><div class="section-title">复核历史</div><div class="timeline">';
        if (!d.reviews || !d.reviews.length) {
            html += '<div class="empty">暂无复核记录</div>';
        } else {
            d.reviews.forEach(record => {
                html += '<div class="timeline-item"><strong>' + esc(record.reviewer) + '</strong> · ' +
                    esc(fmtDateTime(record.reviewedAt)) + ' · ' + esc(CONCLUSION_LABELS[record.conclusion] || record.conclusion) +
                    '<br>' + esc(FIELD_LABELS[record.fieldName] || record.fieldName) + '：' +
                    esc(record.oldValue || '—') + ' → ' + esc(record.newValue || '—') +
                    (record.comment ? '<br><span class="muted">' + esc(record.comment) + '</span>' : '') + '</div>';
            });
        }
        html += '</div></div>';
        html += '<div class="card"><div class="section-title">验真历史</div><div class="timeline">';
        if (!d.verifications || !d.verifications.length) {
            html += '<div class="empty">暂无验真记录</div>';
        } else {
            d.verifications.forEach(record => {
                html += '<div class="timeline-item"><strong>' + esc(record.channel) + '</strong> · ' +
                    esc(fmtDateTime(record.verifiedAt)) + ' · ' + esc(record.statusLabel || record.status) +
                    '<br><span class="muted">' + esc(record.summary || '') + '</span></div>';
            });
        }
        html += '</div></div></div>';
        html += '<div class="card"><div class="section-title">文件与任务</div><div class="kv">' +
            '<span>原始文件名</span><span>' + esc(d.originalFilename || '—') + '</span>' +
            '<span>文件类型 / 大小</span><span>' + esc(d.fileType || '—') + ' / ' + esc(((d.fileSize || 0) / 1024).toFixed(1)) + ' KB</span>' +
            '<span>文件 SHA-256</span><span class="mono">' + esc(d.fileHash || '—') + '</span>' +
            '<span>上传人</span><span>' + esc(d.ownerUsername || '—') + '</span>' +
            '<span>识别引擎</span><span>' + esc(d.task && d.task.engine || '—') + '</span>' +
            '<span>任务状态</span><span>' + esc(d.task && d.task.statusLabel || '—') + '</span>' +
            '<span>创建 / 更新时间</span><span>' + esc(fmtDateTime(d.createdAt)) + ' / ' + esc(fmtDateTime(d.updatedAt)) + '</span>' +
            '</div></div>';
        return html;
    }

    function bindDynamicButtons(root) {
        $$('[data-close]', root).forEach(button => button.onclick = closeModal);
        $$('[data-open]', root).forEach(button => button.onclick = () => openDetail(button.dataset.open));
        $$('[data-action="verify"]', root).forEach(button => {
            button.onclick = async () => {
                button.disabled = true;
                button.textContent = '验真中…';
                try {
                    const result = await api('/api/invoices/' + encodeURIComponent(button.dataset.invoice) + '/verify', { method: 'POST' });
                    toast('验真完成：' + (result.statusLabel || result.status), 'success');
                    openDetail(button.dataset.invoice);
                } catch (error) {
                    toast(error.message, 'error');
                    button.disabled = false;
                    button.textContent = '触发验真';
                }
            };
        });
        $$('[data-action="reprocess"]', root).forEach(button => {
            button.onclick = async () => {
                button.disabled = true;
                button.textContent = '提交中…';
                try {
                    await api('/api/invoices/' + encodeURIComponent(button.dataset.invoice) + '/reprocess', { method: 'POST' });
                    toast('已重新提交识别', 'success');
                    state.detailPollId = null;
                    state.detailPollTick = 0;
                    openDetail(button.dataset.invoice);
                } catch (error) {
                    toast(error.message, 'error');
                    button.disabled = false;
                    button.textContent = '重新识别';
                }
            };
        });
        const form = $('#review-form', root);
        if (form) {
            form.addEventListener('submit', async event => {
                event.preventDefault();
                const fields = {};
                $$('[data-field]', form).forEach(input => { fields[input.dataset.field] = input.value; });
                const conclusion = $('#review-conclusion', form).value;
                const comment = $('#review-comment', form).value;
                const submit = $('button[type="submit"]', form);
                submit.disabled = true;
                try {
                    const result = await api('/api/invoices/' + encodeURIComponent(form.dataset.invoice) + '/review', {
                        method: 'POST', body: { fields, conclusion, comment }
                    });
                    const resultStatus = (STATUS[result.status] || {}).label || result.status;
                    toast('复核已保存，状态：' + resultStatus, 'success');
                    openDetail(form.dataset.invoice);
                    if (state.view === 'ledger') loadLedger();
                    if (state.view === 'review') loadReviewQueue();
                } catch (error) {
                    toast(error.message, 'error');
                    submit.disabled = false;
                }
            });
        }
    }

    /* ---------------- Config ---------------- */

    async function loadConfig() {
        try {
            const data = await api('/api/admin/config');
            const list = $('#config-list');
            list.innerHTML = data.items.map(item =>
                '<div class="config-item"><div><div class="key">' + esc(item.key) + '</div>' +
                '<div class="desc">' + esc(item.description || '') + '</div></div>' +
                '<input data-config-key="' + esc(item.key) + '" value="' + esc(item.value == null ? '' : item.value) + '" ' +
                (item.masked ? 'placeholder="已加密，修改请输入新值"' : '') + '></div>'
            ).join('') || '<div class="empty">无配置项</div>';
            $('#engine-list').innerHTML = (data.engines || []).map(engine =>
                '<div class="engine-item"><div><strong>' + esc(engine.name) + ' · ' + esc(engine.description) + '</strong>' +
                '<small><span class="dot ' + (engine.available ? 'ok' : 'off') + '"></span>' + esc(engine.detail || (engine.available ? '可用' : '未配置')) + '</small></div>' +
                (data.currentEngine === engine.name ? '<span class="badge completed">当前</span>' : '') + '</div>'
            ).join('') + '<div class="muted tiny" style="margin-top:8px">当前验真渠道：' + esc(data.currentVerifyChannel) + '</div>';
        } catch (error) {
            toast(error.message, 'error');
        }
    }

    async function saveConfig() {
        const values = {};
        $$('[data-config-key]').forEach(input => {
            if (input.value !== '******') values[input.dataset.configKey] = input.value;
        });
        try {
            await api('/api/admin/config', { method: 'PUT', body: { values } });
            toast('配置已保存', 'success');
            loadConfig();
        } catch (error) {
            toast(error.message, 'error');
        }
    }

    /* ---------------- Bindings ---------------- */

    function bindNavigation() {
        $$('#main-nav .nav-item').forEach(button => {
            button.addEventListener('click', () => navigate(button.dataset.view));
        });
        $('#logout-btn').addEventListener('click', logout);
    }

    function bindLogin() {
        $('#login-form').addEventListener('submit', async event => {
            event.preventDefault();
            $('#login-error').textContent = '';
            try {
                await login($('#login-username').value.trim(), $('#login-password').value);
            } catch (error) {
                $('#login-error').textContent = error.message;
            }
        });
        const accounts = {
            admin: ['admin', 'Admin@123'],
            reviewer: ['reviewer', 'Review@123'],
            submitter: ['submitter', 'Submit@123'],
            auditor: ['auditor', 'Audit@123']
        };
        $$('.demo-accounts button').forEach(button => {
            button.addEventListener('click', () => {
                const account = accounts[button.dataset.account];
                $('#login-username').value = account[0];
                $('#login-password').value = account[1];
                $('#login-error').textContent = '';
            });
        });
    }

    function bindFilters() {
        $('#ledger-filter').addEventListener('submit', event => {
            event.preventDefault();
            state.ledgerPage = 0;
            loadLedger();
        });
        $('#ledger-reset').addEventListener('click', () => {
            $('#ledger-filter').reset();
            state.ledgerPage = 0;
            loadLedger();
        });
        $('#ledger-export').addEventListener('click', exportLedger);
        $('#review-filter').addEventListener('submit', event => {
            event.preventDefault();
            state.reviewPage = 0;
            loadReviewQueue();
        });
        $('#audit-filter').addEventListener('submit', event => {
            event.preventDefault();
            state.auditPage = 0;
            loadAudit();
        });
        $('#config-save').addEventListener('click', saveConfig);
        document.addEventListener('keydown', event => {
            if (event.key === 'Escape') closeModal();
        });
    }

    async function exportLedger() {
        const query = formToObject($('#ledger-filter'));
        query.format = 'csv';
        try {
            const response = await fetch('/api/invoices/export?' + queryString(query), {
                headers: { Authorization: 'Bearer ' + state.token }
            });
            if (!response.ok) {
                const error = await response.json().catch(() => ({}));
                throw new Error(error.message || '导出失败');
            }
            const blob = await response.blob();
            const disposition = response.headers.get('Content-Disposition') || '';
            const match = disposition.match(/filename="([^"]+)"/);
            const filename = match ? match[1] : 'invoices.csv';
            const link = document.createElement('a');
            link.href = URL.createObjectURL(blob);
            link.download = filename;
            link.click();
            URL.revokeObjectURL(link.href);
            toast('导出成功', 'success');
        } catch (error) {
            toast(error.message, 'error');
        }
    }

    async function bootstrap() {
        bindLogin();
        bindNavigation();
        bindUpload();
        bindFilters();
        if (!state.token) return;
        try {
            state.user = await api('/api/auth/me');
            showApp();
        } catch (error) {
            logout();
        }
    }

    bootstrap();
})();

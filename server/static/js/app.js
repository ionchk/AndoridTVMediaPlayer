/**
 * Smart TV Media Player — Admin Dashboard JavaScript
 */

// ══════════════════════════════════════════════════════════════════════
//  State & Config
// ══════════════════════════════════════════════════════════════════════

const API = {
    headers() {
        const token = localStorage.getItem('auth_token');
        return {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json'
        };
    },
    headerAuth() {
        const token = localStorage.getItem('auth_token');
        return { 'Authorization': `Bearer ${token}` };
    }
};

let mediaFiles = [];
let selectedIds = new Set();

// ══════════════════════════════════════════════════════════════════════
//  Navigation
// ══════════════════════════════════════════════════════════════════════

document.querySelectorAll('.nav-item').forEach(item => {
    item.addEventListener('click', (e) => {
        e.preventDefault();
        const section = item.dataset.section;
        switchSection(section);
    });
});

function switchSection(section) {
    // Update nav
    document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
    document.querySelector(`[data-section="${section}"]`).classList.add('active');

    // Update sections
    document.querySelectorAll('.section').forEach(s => s.classList.remove('active'));
    document.getElementById(`section-${section}`).classList.add('active');

    // Load section data
    if (section === 'dashboard') loadStats();
    if (section === 'media') loadMedia();
    if (section === 'playlist') loadPlaylistPreview();
    if (section === 'settings') loadSettings();
}

// ══════════════════════════════════════════════════════════════════════
//  Dashboard / Stats
// ══════════════════════════════════════════════════════════════════════

async function loadStats() {
    try {
        const res = await fetch('/api/stats', { headers: API.headers() });
        if (res.status === 401) return logout();
        const data = await res.json();

        document.getElementById('statTotalFiles').textContent = data.total_files;
        document.getElementById('statActiveFiles').textContent = data.active_files;
        document.getElementById('statTotalSize').textContent = formatSize(data.total_size);

        const types = Object.entries(data.type_counts || {})
            .map(([k, v]) => `${k.toUpperCase()}: ${v}`)
            .join(', ') || '—';
        document.getElementById('statTypes').textContent = types;
    } catch (err) {
        console.error('Failed to load stats:', err);
    }
}

// ══════════════════════════════════════════════════════════════════════
//  Media Management
// ══════════════════════════════════════════════════════════════════════

async function loadMedia() {
    try {
        const res = await fetch('/api/media', { headers: API.headers() });
        if (res.status === 401) return logout();
        mediaFiles = await res.json();
        renderMediaGrid();
    } catch (err) {
        console.error('Failed to load media:', err);
    }
}

function renderMediaGrid() {
    const grid = document.getElementById('mediaGrid');
    const emptyState = document.getElementById('emptyState');

    if (mediaFiles.length === 0) {
        grid.innerHTML = '';
        grid.appendChild(createEmptyState());
        return;
    }

    grid.innerHTML = mediaFiles.map(media => createMediaCard(media)).join('');

    // Attach event listeners
    grid.querySelectorAll('.media-card').forEach(card => {
        const id = parseInt(card.dataset.id);
        const media = mediaFiles.find(m => m.id === id);
        if (!media) return;

        // Duration input
        const durationInput = card.querySelector('.duration-input');
        if (durationInput) {
            let debounce;
            durationInput.addEventListener('input', () => {
                clearTimeout(debounce);
                debounce = setTimeout(() => {
                    updateMedia(id, { display_duration: parseInt(durationInput.value) || 10 });
                }, 600);
            });
        }

        // Priority slider
        const prioritySlider = card.querySelector('.priority-slider');
        const priorityVal = card.querySelector('.priority-value');
        if (prioritySlider) {
            prioritySlider.addEventListener('input', () => {
                priorityVal.textContent = prioritySlider.value;
            });
            let debounce;
            prioritySlider.addEventListener('change', () => {
                clearTimeout(debounce);
                debounce = setTimeout(() => {
                    updateMedia(id, { priority: parseInt(prioritySlider.value) });
                }, 300);
            });
        }

        // Active toggle
        const toggle = card.querySelector('.active-toggle');
        if (toggle) {
            toggle.addEventListener('change', () => {
                updateMedia(id, { is_active: toggle.checked });
                card.classList.toggle('inactive', !toggle.checked);
            });
        }

        // Delete button
        const deleteBtn = card.querySelector('.delete-btn');
        if (deleteBtn) {
            deleteBtn.addEventListener('click', () => {
                showConfirm(
                    'Удалить файл?',
                    `Вы уверены, что хотите удалить "${media.original_name}"?`,
                    () => deleteMedia(id)
                );
            });
        }

        // Checkbox
        const checkbox = card.querySelector('.media-card-checkbox');
        if (checkbox) {
            checkbox.addEventListener('change', () => {
                if (checkbox.checked) {
                    selectedIds.add(id);
                } else {
                    selectedIds.delete(id);
                }
                updateBulkActions();
            });
        }
    });
}

function createMediaCard(media) {
    const thumbSrc = media.thumbnail
        ? `/thumbnails/${media.thumbnail}`
        : null;

    const typeIcons = { mp4: '🎬', jpg: '🖼️', jpeg: '🖼️', xlsx: '📊' };
    const icon = typeIcons[media.file_type] || '📄';

    return `
        <div class="media-card ${media.is_active ? '' : 'inactive'}" data-id="${media.id}">
            <input type="checkbox" class="media-card-checkbox" ${selectedIds.has(media.id) ? 'checked' : ''}>
            <div class="media-thumb">
                ${thumbSrc
                    ? `<img src="${thumbSrc}" alt="${media.original_name}" loading="lazy">`
                    : `<span class="media-thumb-placeholder">${icon}</span>`
                }
                <span class="media-type-badge">${media.file_type}</span>
            </div>
            <div class="media-card-body">
                <div class="media-card-title" title="${media.original_name}">${media.original_name}</div>
                <div class="media-card-meta">${formatSize(media.file_size)} • ${formatDate(media.created_at)}</div>

                <div class="media-controls">
                    <div class="media-control-row">
                        <label>⏱ Время:</label>
                        <input type="number" class="duration-input" value="${media.display_duration}" min="1" max="3600">
                        <span style="font-size:12px;color:var(--text-secondary);">сек</span>
                    </div>
                    <div class="media-control-row">
                        <label>🎯 Приоритет:</label>
                        <input type="range" class="priority-slider" value="${media.priority}" min="1" max="10" step="1">
                        <span class="priority-value">${media.priority}</span>
                    </div>
                    <div class="media-control-row">
                        <label>Активен:</label>
                        <div class="toggle-switch">
                            <input type="checkbox" class="active-toggle" ${media.is_active ? 'checked' : ''}>
                            <span class="toggle-slider"></span>
                        </div>
                    </div>
                </div>

                <div class="media-card-actions">
                    <button class="btn btn-danger btn-sm delete-btn">🗑️ Удалить</button>
                </div>
            </div>
        </div>
    `;
}

function createEmptyState() {
    const div = document.createElement('div');
    div.className = 'empty-state';
    div.id = 'emptyState';
    div.innerHTML = `
        <span class="empty-icon">📭</span>
        <h3>Нет медиа файлов</h3>
        <p>Загрузите файлы для начала работы</p>
    `;
    return div;
}

async function updateMedia(id, data) {
    try {
        const res = await fetch(`/api/media/${id}`, {
            method: 'PUT',
            headers: API.headers(),
            body: JSON.stringify(data)
        });
        if (res.ok) {
            const updated = await res.json();
            const idx = mediaFiles.findIndex(m => m.id === id);
            if (idx >= 0) mediaFiles[idx] = updated;
            showToast('✅ Обновлено', 'success');
        }
    } catch (err) {
        showToast('Ошибка обновления', 'error');
    }
}

async function deleteMedia(id) {
    try {
        const res = await fetch(`/api/media/${id}`, {
            method: 'DELETE',
            headers: API.headers()
        });
        if (res.ok) {
            mediaFiles = mediaFiles.filter(m => m.id !== id);
            selectedIds.delete(id);
            renderMediaGrid();
            updateBulkActions();
            showToast('🗑️ Файл удалён', 'success');
        }
    } catch (err) {
        showToast('Ошибка удаления', 'error');
    }
}

function updateBulkActions() {
    const btn = document.getElementById('bulkDeleteBtn');
    btn.style.display = selectedIds.size > 0 ? 'inline-flex' : 'none';
    if (selectedIds.size > 0) {
        btn.textContent = `🗑️ Удалить выбранные (${selectedIds.size})`;
    }
}

// ══════════════════════════════════════════════════════════════════════
//  File Upload
// ══════════════════════════════════════════════════════════════════════

function setupDropZone(dropZone, fileInput) {
    dropZone.addEventListener('click', () => fileInput.click());

    dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropZone.classList.add('dragover');
    });

    dropZone.addEventListener('dragleave', () => {
        dropZone.classList.remove('dragover');
    });

    dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropZone.classList.remove('dragover');
        if (e.dataTransfer.files.length) {
            uploadFiles(e.dataTransfer.files);
        }
    });

    fileInput.addEventListener('change', () => {
        if (fileInput.files.length) {
            uploadFiles(fileInput.files);
            fileInput.value = '';
        }
    });
}

async function uploadFiles(files) {
    const formData = new FormData();
    let valid = 0;

    for (const file of files) {
        const ext = file.name.split('.').pop().toLowerCase();
        if (['mp4', 'jpg', 'jpeg', 'xlsx', 'avi', 'mov'].includes(ext)) {
            formData.append('files', file);
            valid++;
        }
    }

    if (valid === 0) {
        showToast('⚠️ Нет файлов поддерживаемого формата', 'error');
        return;
    }

    // Show progress
    const progress = document.getElementById('uploadProgress');
    const progressFill = document.getElementById('progressFill');
    const progressText = document.getElementById('progressText');

    if (progress) {
        progress.style.display = 'block';
        progressFill.style.width = '0%';
        progressText.textContent = `Загрузка ${valid} файлов...`;
    }

    try {
        // Simulated progress
        let pct = 0;
        const progressInterval = setInterval(() => {
            pct = Math.min(pct + Math.random() * 15, 90);
            if (progressFill) progressFill.style.width = pct + '%';
        }, 200);

        const res = await fetch('/api/media/upload', {
            method: 'POST',
            headers: API.headerAuth(),
            body: formData
        });

        clearInterval(progressInterval);
        if (progressFill) progressFill.style.width = '100%';

        const data = await res.json();

        if (data.uploaded && data.uploaded.length > 0) {
            showToast(`✅ ${data.message}`, 'success');
            loadMedia();
            loadStats();
        }

        if (data.errors && data.errors.length > 0) {
            data.errors.forEach(err => showToast(`⚠️ ${err}`, 'error'));
        }

        setTimeout(() => {
            if (progress) progress.style.display = 'none';
        }, 1000);
    } catch (err) {
        showToast('❌ Ошибка загрузки', 'error');
        if (progress) progress.style.display = 'none';
    }
}

// ══════════════════════════════════════════════════════════════════════
//  Playlist Preview
// ══════════════════════════════════════════════════════════════════════

async function loadPlaylistPreview() {
    try {
        const res = await fetch('/api/media', { headers: API.headers() });
        if (res.status === 401) return logout();
        const allMedia = await res.json();
        const activeMedia = allMedia.filter(m => m.is_active);

        if (activeMedia.length === 0) {
            document.getElementById('playlistTimeline').innerHTML = `
                <div class="empty-state" style="padding:40px;">
                    <span class="empty-icon">📋</span>
                    <h3>Плейлист пуст</h3>
                    <p>Активируйте медиа файлы для создания плейлиста</p>
                </div>
            `;
            document.getElementById('playlistInfo').textContent = '';
            return;
        }

        // Simulate playlist generation client-side
        const playlist = generateClientPlaylist(activeMedia);

        const totalDuration = playlist.reduce((s, item) => s + item.display_duration, 0);

        document.getElementById('playlistInfo').textContent =
            `${playlist.length} элементов • ${formatDuration(totalDuration)} общее время цикла`;

        const typeIcons = { mp4: '🎬', jpg: '🖼️', jpeg: '🖼️', xlsx: '📊' };

        document.getElementById('playlistTimeline').innerHTML = playlist.map((item, idx) => `
            <div class="playlist-item">
                <div class="playlist-item-index">${idx + 1}</div>
                <span class="playlist-item-type">${typeIcons[item.file_type] || '📄'}</span>
                <span class="playlist-item-name" title="${item.original_name}">${item.original_name}</span>
                <span class="playlist-item-duration">${item.display_duration}с  P${item.priority}</span>
            </div>
        `).join('');
    } catch (err) {
        console.error('Failed to load playlist:', err);
    }
}

function generateClientPlaylist(mediaFiles) {
    // Bresenham-like weighted distribution (matches server logic)
    const sorted = [...mediaFiles].sort((a, b) => a.sort_order - b.sort_order);
    const totalSlots = sorted.reduce((s, m) => s + m.priority, 0);

    if (totalSlots === 0) return [];

    const entries = sorted.map(m => ({
        media: m,
        weight: m.priority,
        accumulator: 0
    }));

    const playlist = [];
    for (let i = 0; i < totalSlots; i++) {
        entries.forEach(e => e.accumulator += e.weight);
        let best = entries[0];
        entries.forEach(e => { if (e.accumulator > best.accumulator) best = e; });
        playlist.push({ ...best.media });
        best.accumulator -= totalSlots;
    }

    return playlist;
}

// ══════════════════════════════════════════════════════════════════════
//  Settings
// ══════════════════════════════════════════════════════════════════════

async function loadSettings() {
    try {
        const res = await fetch('/api/settings', { headers: API.headers() });
        if (res.status === 401) return logout();
        const data = await res.json();

        document.getElementById('apiKeyDisplay').textContent = data.api_key;
        document.getElementById('defaultDuration').value = data.default_duration;

        // Show server IP
        document.getElementById('serverIp').textContent = window.location.hostname + ':' + window.location.port;
    } catch (err) {
        console.error('Failed to load settings:', err);
    }
}

// Copy API key
document.getElementById('copyApiKey').addEventListener('click', () => {
    const key = document.getElementById('apiKeyDisplay').textContent;
    navigator.clipboard.writeText(key).then(() => {
        showToast('📋 API-ключ скопирован', 'success');
    }).catch(() => {
        showToast('Ошибка копирования', 'error');
    });
});

// Regenerate API key
document.getElementById('regenerateApiKey').addEventListener('click', () => {
    showConfirm(
        'Обновить API-ключ?',
        'Все подключённые TV устройства потеряют доступ и потребуется ввести новый ключ.',
        async () => {
            try {
                const res = await fetch('/api/settings', {
                    method: 'PUT',
                    headers: API.headers(),
                    body: JSON.stringify({ regenerate_api_key: true })
                });
                const data = await res.json();
                document.getElementById('apiKeyDisplay').textContent = data.api_key;
                showToast('🔑 API-ключ обновлён', 'success');
            } catch (err) {
                showToast('Ошибка', 'error');
            }
        }
    );
});

// Save default duration
document.getElementById('saveDuration').addEventListener('click', async () => {
    const duration = parseInt(document.getElementById('defaultDuration').value) || 10;
    try {
        await fetch('/api/settings', {
            method: 'PUT',
            headers: API.headers(),
            body: JSON.stringify({ default_duration: duration })
        });
        showToast('💾 Время по умолчанию сохранено', 'success');
    } catch (err) {
        showToast('Ошибка', 'error');
    }
});

// Change password
document.getElementById('changePasswordBtn').addEventListener('click', async () => {
    const oldPwd = document.getElementById('oldPassword').value;
    const newPwd = document.getElementById('newPassword').value;

    if (!oldPwd || !newPwd) {
        showToast('⚠️ Заполните оба поля', 'error');
        return;
    }

    try {
        const res = await fetch('/api/auth/change-password', {
            method: 'POST',
            headers: API.headers(),
            body: JSON.stringify({ old_password: oldPwd, new_password: newPwd })
        });
        const data = await res.json();

        if (res.ok) {
            showToast('🔐 Пароль изменён', 'success');
            document.getElementById('oldPassword').value = '';
            document.getElementById('newPassword').value = '';
        } else {
            showToast(`⚠️ ${data.error}`, 'error');
        }
    } catch (err) {
        showToast('Ошибка', 'error');
    }
});

// ══════════════════════════════════════════════════════════════════════
//  Upload Button & Drop Zones
// ══════════════════════════════════════════════════════════════════════

document.getElementById('uploadBtn').addEventListener('click', () => {
    const area = document.getElementById('uploadArea');
    area.style.display = area.style.display === 'none' ? 'block' : 'none';
});

// Main drop zone
setupDropZone(
    document.getElementById('mainDropZone'),
    document.getElementById('mainFileInput')
);

// Quick drop zone (dashboard)
setupDropZone(
    document.getElementById('quickDropZone'),
    document.getElementById('quickFileInput')
);

// Bulk delete
document.getElementById('bulkDeleteBtn').addEventListener('click', () => {
    showConfirm(
        'Удалить выбранные файлы?',
        `Будет удалено файлов: ${selectedIds.size}. Это действие нельзя отменить.`,
        async () => {
            try {
                const res = await fetch('/api/media/bulk-delete', {
                    method: 'POST',
                    headers: API.headers(),
                    body: JSON.stringify({ ids: [...selectedIds] })
                });
                if (res.ok) {
                    selectedIds.clear();
                    updateBulkActions();
                    loadMedia();
                    showToast('🗑️ Файлы удалены', 'success');
                }
            } catch (err) {
                showToast('Ошибка', 'error');
            }
        }
    );
});

// Playlist refresh
document.getElementById('refreshPlaylist').addEventListener('click', loadPlaylistPreview);

// ══════════════════════════════════════════════════════════════════════
//  Auth & Logout
// ══════════════════════════════════════════════════════════════════════

document.getElementById('logoutBtn').addEventListener('click', async () => {
    try {
        await fetch('/api/auth/logout', { method: 'POST' });
    } catch(e) {}
    logout();
});

function logout() {
    localStorage.removeItem('auth_token');
    window.location.href = '/login';
}

// ══════════════════════════════════════════════════════════════════════
//  Toast Notifications
// ══════════════════════════════════════════════════════════════════════

function showToast(message, type = 'info') {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.animation = 'toastOut 0.3s ease forwards';
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

// ══════════════════════════════════════════════════════════════════════
//  Confirm Modal
// ══════════════════════════════════════════════════════════════════════

let confirmCallback = null;

function showConfirm(title, text, callback) {
    document.getElementById('confirmTitle').textContent = title;
    document.getElementById('confirmText').textContent = text;
    document.getElementById('confirmModal').classList.add('visible');
    confirmCallback = callback;
}

document.getElementById('confirmOk').addEventListener('click', () => {
    document.getElementById('confirmModal').classList.remove('visible');
    if (confirmCallback) confirmCallback();
    confirmCallback = null;
});

document.getElementById('confirmCancel').addEventListener('click', () => {
    document.getElementById('confirmModal').classList.remove('visible');
    confirmCallback = null;
});

// ══════════════════════════════════════════════════════════════════════
//  Utilities
// ══════════════════════════════════════════════════════════════════════

function formatSize(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
}

function formatDate(isoStr) {
    if (!isoStr) return '';
    const d = new Date(isoStr);
    return d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' });
}

function formatDuration(seconds) {
    if (seconds < 60) return `${seconds} сек`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)} мин ${seconds % 60} сек`;
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    return `${h} ч ${m} мин`;
}

// ══════════════════════════════════════════════════════════════════════
//  Initialization
// ══════════════════════════════════════════════════════════════════════

loadStats();

// ── TV Pairing ────────────────────────────────────────────────────────
const submitPairingBtn = document.getElementById('submitPairingBtn');
if (submitPairingBtn) {
    submitPairingBtn.addEventListener('click', async () => {
        const codeInput = document.getElementById('pairingCodeInput');
        const messageDiv = document.getElementById('pairingMessage');
        const code = codeInput.value.trim();
        
        if (!code || code.length !== 6) {
            messageDiv.style.color = '#ff6b8a';
            messageDiv.textContent = '⚠️ Введите 6-значный код';
            return;
        }
        
        try {
            messageDiv.style.color = '#8a87a0';
            messageDiv.textContent = 'Отправка...';
            
            const res = await fetch('/api/tv/pair/confirm', {
                method: 'POST',
                headers: API.headers(),
                body: JSON.stringify({ code: code })
            });
            const data = await res.json();
            
            if (res.ok) {
                messageDiv.style.color = '#5cfc7c';
                messageDiv.textContent = '✅ ТВ успешно подключен!';
                codeInput.value = '';
            } else {
                messageDiv.style.color = '#ff6b8a';
                messageDiv.textContent = `❌ ${data.error || 'Ошибка сопряжения'}`;
            }
        } catch (err) {
            messageDiv.style.color = '#ff6b8a';
            messageDiv.textContent = '❌ Сетевая ошибка';
        }
    });
}

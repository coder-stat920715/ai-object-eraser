/* Object Eraser - canvas masking editor, SSE progress and before/after viewer. No build step. */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const els = {
    engine: $('engine'), drop: $('drop'), file: $('file'), editor: $('editor'),
    base: $('base'), overlay: $('overlay'), ring: $('ring'),
    compare: $('compare'), afterImg: $('afterImg'), beforeImg: $('beforeImg'), handle: $('handle'),
    tools: $('tools'), brush: $('brush'), brushOut: $('brushOut'), grow: $('grow'), growOut: $('growOut'),
    undo: $('undo'), redo: $('redo'), clear: $('clear'), showMask: $('showMask'),
    stages: $('stages'), bar: $('bar'), barFill: $('barFill'),
    results: $('results'), dlPng: $('dlPng'), dlWebp: $('dlWebp'), again: $('again'), editMask: $('editMask'),
    status: $('status'), actions: $('actions'), erase: $('erase'), swap: $('swap'), stage: $('stage'),
  };
  const baseCtx = els.base.getContext('2d');
  const overlayCtx = els.overlay.getContext('2d');
  const MASK_COLOR = '#e5233b';

  const state = {
    mode: 'empty',          // empty | edit | result
    busy: false,
    strokes: [],            // [{ size, pts: [[x, y], ...] }] in image pixels
    redo: [],
    drawing: null,
    info: null,
    name: 'photo',
    beforeUrl: null,
    resultBlob: null,
    resultUrl: null,
  };

  /* ------------------------------------------------------------------ helpers */

  function say(text, isError = false) {
    els.status.textContent = text;
    els.status.classList.toggle('error', isError);
  }

  function canvasToBlob(canvas, type = 'image/png', quality) {
    return new Promise((resolve, reject) =>
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not export the image.'))), type, quality));
  }

  async function problemMessage(res) {
    try {
      const p = await res.json();
      return p.detail || p.title || `Request failed (${res.status}).`;
    } catch {
      return `Request failed (${res.status}).`;
    }
  }

  function showMode(mode) {
    state.mode = mode;
    els.drop.hidden = mode !== 'empty';
    els.editor.hidden = mode !== 'edit';
    els.compare.hidden = mode !== 'result';
    els.tools.hidden = mode !== 'edit';
    els.results.hidden = mode !== 'result';
    els.actions.hidden = mode === 'empty';
    els.erase.hidden = mode !== 'edit';
    if (mode !== 'edit') els.ring.hidden = true;
    updateButtons();
  }

  function updateButtons() {
    const editing = state.mode === 'edit';
    els.undo.disabled = !editing || state.busy || state.strokes.length === 0;
    els.redo.disabled = !editing || state.busy || state.redo.length === 0;
    els.clear.disabled = !editing || state.busy || state.strokes.length === 0;
    els.erase.disabled = !editing || state.busy || state.strokes.length === 0;
    els.swap.disabled = state.busy;
    els.brush.disabled = els.grow.disabled = state.busy;
    els.editor.classList.toggle('busy', state.busy);
  }

  /* ------------------------------------------------------------------ loading photos */

  async function loadFile(file) {
    if (!file) return;
    if (!/^image\/(png|jpe?g|webp)$/i.test(file.type)) {
      say('That file is not a PNG, JPG or WEBP image.', true);
      return;
    }
    const url = URL.createObjectURL(file);
    try {
      const img = new Image();
      img.src = url;
      await img.decode();
      setImage(img, img.naturalWidth, img.naturalHeight, file.name.replace(/\.[^.]+$/, '') || 'photo');
    } catch {
      say('That image could not be opened. Try another file.', true);
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  /** Draws the photo onto the base canvas (downscaled if it exceeds the server's pixel limit). */
  function setImage(source, w, h, name) {
    let scale = 1;
    if (state.info && w * h > state.info.maxPixels) scale = Math.sqrt(state.info.maxPixels / (w * h)) * 0.98;
    const cw = Math.max(1, Math.round(w * scale));
    const ch = Math.max(1, Math.round(h * scale));
    els.base.width = els.overlay.width = cw;
    els.base.height = els.overlay.height = ch;
    baseCtx.drawImage(source, 0, 0, cw, ch);
    state.strokes = [];
    state.redo = [];
    state.name = name;
    redrawOverlay();
    resetProgress();
    showMode('edit');
    say(scale < 1
      ? 'Photo scaled down to fit the size limit. Paint over what you want removed.'
      : 'Paint over what you want removed, then press Erase.');
  }

  /* ------------------------------------------------------------------ brush */

  const mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];

  function style(ctx, size, color) {
    ctx.lineWidth = size;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
  }

  function dot(ctx, p, size, color) {
    style(ctx, size, color);
    ctx.beginPath();
    ctx.arc(p[0], p[1], size / 2, 0, Math.PI * 2);
    ctx.fill();
  }

  function curve(ctx, from, ctrl, to, size, color) {
    style(ctx, size, color);
    ctx.beginPath();
    ctx.moveTo(from[0], from[1]);
    ctx.quadraticCurveTo(ctrl[0], ctrl[1], to[0], to[1]);
    ctx.stroke();
  }

  /** Replays a stroke with midpoint-smoothed quadratic curves (same maths as the live preview). */
  function replay(ctx, stroke, color) {
    const pts = stroke.pts;
    if (pts.length === 1) return dot(ctx, pts[0], stroke.size, color);
    let last = pts[0];
    for (let i = 1; i < pts.length; i++) {
      const m = mid(pts[i - 1], pts[i]);
      curve(ctx, last, pts[i - 1], m, stroke.size, color);
      last = m;
    }
    curve(ctx, last, pts[pts.length - 1], pts[pts.length - 1], stroke.size, color);
  }

  function redrawOverlay() {
    overlayCtx.clearRect(0, 0, els.overlay.width, els.overlay.height);
    for (const s of state.strokes) replay(overlayCtx, s, MASK_COLOR);
  }

  function imagePoint(ev) {
    const r = els.overlay.getBoundingClientRect();
    return [(ev.clientX - r.left) * els.overlay.width / r.width, (ev.clientY - r.top) * els.overlay.height / r.height];
  }

  function moveRing(ev) {
    const wrap = els.editor.getBoundingClientRect();
    const size = Number(els.brush.value);
    els.ring.style.width = els.ring.style.height = `${size}px`;
    els.ring.style.left = `${ev.clientX - wrap.left}px`;
    els.ring.style.top = `${ev.clientY - wrap.top}px`;
    els.ring.hidden = false;
  }

  els.overlay.addEventListener('pointerdown', (ev) => {
    if (state.busy || state.mode !== 'edit' || (ev.pointerType === 'mouse' && ev.button !== 0)) return;
    ev.preventDefault();
    els.overlay.setPointerCapture(ev.pointerId);
    const rect = els.overlay.getBoundingClientRect();
    const size = Number(els.brush.value) * els.overlay.width / rect.width;   // slider is in screen px
    const p = imagePoint(ev);
    state.drawing = { size, pts: [p], last: p };
    state.redo = [];
    dot(overlayCtx, p, size, MASK_COLOR);
    moveRing(ev);
    updateButtons();
  });

  els.overlay.addEventListener('pointermove', (ev) => {
    moveRing(ev);
    const d = state.drawing;
    if (!d) return;
    const events = ev.getCoalescedEvents ? ev.getCoalescedEvents() : [ev];
    for (const e of (events.length ? events : [ev])) {
      const p = imagePoint(e);
      const prev = d.pts[d.pts.length - 1];
      d.pts.push(p);
      const m = mid(prev, p);
      curve(overlayCtx, d.last, prev, m, d.size, MASK_COLOR);
      d.last = m;
    }
  });

  function endStroke() {
    const d = state.drawing;
    if (!d) return;
    const end = d.pts[d.pts.length - 1];
    curve(overlayCtx, d.last, end, end, d.size, MASK_COLOR);
    state.strokes.push({ size: d.size, pts: d.pts });
    state.drawing = null;
    updateButtons();
  }
  els.overlay.addEventListener('pointerup', endStroke);
  els.overlay.addEventListener('pointercancel', endStroke);
  els.overlay.addEventListener('pointerleave', () => { if (!state.drawing) els.ring.hidden = true; });

  function undo() {
    if (state.busy || !state.strokes.length) return;
    state.redo.push(state.strokes.pop());
    redrawOverlay();
    updateButtons();
  }
  function redo() {
    if (state.busy || !state.redo.length) return;
    state.strokes.push(state.redo.pop());
    redrawOverlay();
    updateButtons();
  }
  function clearMask() {
    if (state.busy || !state.strokes.length) return;
    state.strokes = [];
    state.redo = [];
    redrawOverlay();
    updateButtons();
    say('Mask cleared.');
  }

  els.undo.addEventListener('click', undo);
  els.redo.addEventListener('click', redo);
  els.clear.addEventListener('click', clearMask);
  els.showMask.addEventListener('change', () => els.overlay.classList.toggle('hide', !els.showMask.checked));
  els.brush.addEventListener('input', () => { els.brushOut.textContent = els.brush.value; });
  els.grow.addEventListener('input', () => { els.growOut.textContent = els.grow.value; });

  document.addEventListener('keydown', (ev) => {
    if (state.mode !== 'edit' || ev.target.matches('input[type="range"]') && ev.key.startsWith('Arrow')) return;
    const mod = ev.ctrlKey || ev.metaKey;
    if (mod && ev.key.toLowerCase() === 'z') { ev.preventDefault(); ev.shiftKey ? redo() : undo(); }
    else if (mod && ev.key.toLowerCase() === 'y') { ev.preventDefault(); redo(); }
    else if (ev.key === '[' || ev.key === ']') {
      const step = ev.key === ']' ? 6 : -6;
      els.brush.value = Math.min(Number(els.brush.max), Math.max(Number(els.brush.min), Number(els.brush.value) + step));
      els.brushOut.textContent = els.brush.value;
    }
  });

  /* ------------------------------------------------------------------ mask export (binary PNG, 0 keep / 255 erase) */

  async function exportMask() {
    const c = document.createElement('canvas');
    c.width = els.base.width;
    c.height = els.base.height;
    const ctx = c.getContext('2d', { willReadFrequently: true });
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, c.width, c.height);
    for (const s of state.strokes) replay(ctx, s, '#fff');
    const img = ctx.getImageData(0, 0, c.width, c.height);
    const px = img.data;
    for (let i = 0; i < px.length; i += 4) {
      const v = px[i] > 127 ? 255 : 0;       // threshold away anti-aliasing: strictly black or white
      px[i] = px[i + 1] = px[i + 2] = v;
      px[i + 3] = 255;
    }
    ctx.putImageData(img, 0, 0);
    return canvasToBlob(c, 'image/png');
  }

  /* ------------------------------------------------------------------ erase + SSE progress */

  const stageItems = [...els.stages.querySelectorAll('li')];

  function resetProgress() {
    stageItems.forEach((li) => li.classList.remove('active', 'done', 'failed'));
    els.stages.hidden = true;
    els.bar.hidden = true;
    els.barFill.style.width = '0';
  }

  function applyProgress(ev) {
    els.stages.hidden = false;
    els.bar.hidden = false;
    stageItems.forEach((li, i) => {
      li.classList.toggle('done', i < ev.index);
      li.classList.toggle('active', i === ev.index);
      li.classList.remove('failed');
    });
    els.barFill.style.width = `${ev.percent}%`;
    say(ev.message || ev.label);
  }

  function markFailed() {
    const active = stageItems.find((li) => li.classList.contains('active')) || stageItems[0];
    active.classList.remove('active');
    active.classList.add('failed');
  }

  /** Opens the SSE stream; resolves with the finished image blob. */
  function follow(job) {
    return new Promise((resolve, reject) => {
      const es = new EventSource(job.streamUrl);
      let settled = false;
      const finish = (fn, value) => { if (!settled) { settled = true; es.close(); fn(value); } };

      es.addEventListener('progress', (e) => applyProgress(JSON.parse(e.data)));
      es.addEventListener('complete', async (e) => {
        const done = JSON.parse(e.data);
        try {
          const res = await fetch(done.resultUrl);
          if (!res.ok) throw new Error(await problemMessage(res));
          finish(resolve, { blob: await res.blob(), done });
        } catch (err) {
          finish(reject, err);
        }
      });
      es.addEventListener('failure', (e) => {
        let msg = 'The erase job failed.';
        try { const p = JSON.parse(e.data); msg = p.detail || p.title || msg; } catch { /* keep default */ }
        finish(reject, new Error(msg));
      });
      es.onerror = () => finish(reject, new Error('Lost the connection to the server. Please try again.'));
    });
  }

  async function runErase() {
    if (state.busy || !state.strokes.length) return;
    state.busy = true;
    updateButtons();
    resetProgress();
    applyProgress({ index: 0, percent: 4, label: 'Uploading', message: 'Uploading the photo and mask…' });
    try {
      const [photo, mask] = await Promise.all([canvasToBlob(els.base), exportMask()]);
      const form = new FormData();
      form.append('image', photo, 'photo.png');
      form.append('mask', mask, 'mask.png');
      form.append('growMask', els.grow.value);
      const res = await fetch('/api/v1/erase/async', { method: 'POST', body: form });
      if (!res.ok) throw new Error(await problemMessage(res));
      const job = await res.json();

      const { blob, done } = await follow(job);
      stageItems.forEach((li) => { li.classList.remove('active'); li.classList.add('done'); });
      els.barFill.style.width = '100%';
      showResult(photo, blob);
      say(`Done in ${(done.durationMs / 1000).toFixed(1)} s with the ${done.provider} engine. Drag the handle to compare.`);
    } catch (err) {
      markFailed();
      say(err.message || 'Something went wrong. Please try again.', true);
    } finally {
      state.busy = false;
      updateButtons();
    }
  }

  /* ------------------------------------------------------------------ before / after viewer */

  function showResult(beforeBlob, afterBlob) {
    if (state.beforeUrl) URL.revokeObjectURL(state.beforeUrl);
    if (state.resultUrl) URL.revokeObjectURL(state.resultUrl);
    state.beforeUrl = URL.createObjectURL(beforeBlob);
    state.resultUrl = URL.createObjectURL(afterBlob);
    state.resultBlob = afterBlob;
    els.beforeImg.src = state.beforeUrl;
    els.afterImg.src = state.resultUrl;
    setSplit(50);
    showMode('result');
  }

  function setSplit(pct) {
    const v = Math.min(100, Math.max(0, pct));
    els.compare.style.setProperty('--pos', `${v}%`);
    els.handle.setAttribute('aria-valuenow', String(Math.round(v)));
  }

  function splitFromEvent(ev) {
    const r = els.compare.getBoundingClientRect();
    setSplit((ev.clientX - r.left) / r.width * 100);
  }

  els.compare.addEventListener('pointerdown', (ev) => {
    els.compare.setPointerCapture(ev.pointerId);
    els.compare.dataset.drag = '1';
    splitFromEvent(ev);
  });
  els.compare.addEventListener('pointermove', (ev) => { if (els.compare.dataset.drag) splitFromEvent(ev); });
  const stopDrag = () => { delete els.compare.dataset.drag; };
  els.compare.addEventListener('pointerup', stopDrag);
  els.compare.addEventListener('pointercancel', stopDrag);
  els.handle.addEventListener('keydown', (ev) => {
    const now = Number(els.handle.getAttribute('aria-valuenow'));
    const keys = { ArrowLeft: now - 3, ArrowDown: now - 3, ArrowRight: now + 3, ArrowUp: now + 3, Home: 0, End: 100 };
    if (ev.key in keys) { ev.preventDefault(); setSplit(keys[ev.key]); }
  });

  /* ------------------------------------------------------------------ downloads and follow-up actions */

  function save(blob, ext) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${state.name}-erased.${ext}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 4000);
  }

  els.dlPng.addEventListener('click', async () => {
    if (!state.resultBlob) return;
    if (state.resultBlob.type === 'image/png') return save(state.resultBlob, 'png');
    const c = document.createElement('canvas');
    c.width = els.afterImg.naturalWidth;
    c.height = els.afterImg.naturalHeight;
    c.getContext('2d').drawImage(els.afterImg, 0, 0);
    save(await canvasToBlob(c, 'image/png'), 'png');
  });

  els.dlWebp.addEventListener('click', async () => {
    if (!state.resultBlob) return;
    const c = document.createElement('canvas');
    c.width = els.afterImg.naturalWidth;
    c.height = els.afterImg.naturalHeight;
    c.getContext('2d').drawImage(els.afterImg, 0, 0);
    const blob = await canvasToBlob(c, 'image/webp', 0.95);
    if (blob.type === 'image/webp') {
      save(blob, 'webp');
    } else {
      save(blob, 'png');
      say('This browser cannot encode WEBP, so a PNG was saved instead.');
    }
  });

  els.again.addEventListener('click', () => {
    if (!state.resultBlob) return;
    setImage(els.afterImg, els.afterImg.naturalWidth, els.afterImg.naturalHeight, state.name);
  });

  els.editMask.addEventListener('click', () => {
    showMode('edit');
    resetProgress();
    say('Adjust the mask, then press Erase again.');
  });

  els.erase.addEventListener('click', runErase);
  els.swap.addEventListener('click', () => els.file.click());

  /* ------------------------------------------------------------------ input sources */

  els.file.addEventListener('change', () => {
    loadFile(els.file.files[0]);
    els.file.value = '';
  });

  ['dragenter', 'dragover'].forEach((t) => els.stage.addEventListener(t, (ev) => {
    ev.preventDefault();
    if (!state.busy) els.drop.classList.add('over');
  }));
  ['dragleave', 'drop'].forEach((t) => els.stage.addEventListener(t, (ev) => {
    ev.preventDefault();
    els.drop.classList.remove('over');
  }));
  els.stage.addEventListener('drop', (ev) => {
    if (!state.busy) loadFile(ev.dataTransfer.files[0]);
  });

  document.addEventListener('paste', (ev) => {
    if (state.busy) return;
    const item = [...(ev.clipboardData?.items || [])].find((i) => i.type.startsWith('image/'));
    if (item) loadFile(item.getAsFile());
  });

  /* ------------------------------------------------------------------ boot */

  fetch('/api/v1/info')
    .then((r) => r.json())
    .then((info) => {
      state.info = info;
      els.engine.textContent = `Engine: ${info.provider}`;
      els.grow.value = Math.min(Number(els.grow.max), info.defaultGrowMask);
      els.growOut.textContent = els.grow.value;
    })
    .catch(() => { els.engine.textContent = 'Engine: unavailable'; });

  showMode('empty');
})();

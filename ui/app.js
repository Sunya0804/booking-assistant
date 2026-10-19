// 예매 도우미 PC 화면. 예매 엔진은 파이썬(gui.py의 Api)이 돌리고, 이 화면은
// window.pywebview.api 로 부르고 window.app.push(evt) 로 소식을 받는다.
// 시계와 카운트다운은 측정한 서버 오프셋으로 여기서 직접 그린다.

const THEMES = [
  ['light', '라이트', '#F2F4F6', '#2B5CE6'],
  ['dark', '다크', '#0E1116', '#4C7DFF'],
  ['sand', '샌드', '#F4EFE6', '#C2410C'],
  ['midnight', '미드나잇', '#0B1020', '#6F5EF0'],
  ['mono', '모노', '#FAFAFA', '#18181B'],
];
const STATUS_TEXT = { idle: '대기', busy: '진행 중', done: '클릭 완료', fail: '확인 필요' };

const $ = (id) => document.getElementById(id);
const state = {
  theme: 'light', offset: null, openAt: null, phase: 'idle', finished: true,
  logs: [], logOpen: false, early: false, result: null,
};
let api = null;
let saveTimer = null;

// ---------- events from Python ----------
window.app = {
  push(evt) {
    switch (evt.type) {
      case 'offset': return onOffset(evt);
      case 'log': return addLog(evt.time, evt.text);
      case 'status': return setStatus(evt.key, evt.detail);
      case 'phase': return setPhase(evt.phase);
      case 'result': state.result = evt; return setPhase('done');
      case 'finished': return onFinished(evt);
      case 'update': return onUpdate(evt);
    }
  },
};

window.addEventListener('pywebviewready', async () => {
  api = window.pywebview.api;
  const init = await api.init();
  setTheme(init.theme, false);
  fill(init.settings);
  $('ver').textContent = `v${init.version}` + (init.frozen ? '' : ' · 소스 실행은 자동 업데이트하지 않음');
  await preview();
  for (const evt of init.events) window.app.push(evt);
});

// ---------- theme ----------
function setTheme(name, save = true) {
  state.theme = THEMES.some((t) => t[0] === name) ? name : 'light';
  document.documentElement.dataset.theme = state.theme;
  renderThemeMenu();
  if (save && api) api.set_theme(state.theme);
}

function renderThemeMenu() {
  const menu = $('themeMenu');
  menu.replaceChildren(...THEMES.map(([key, name, bg, accent]) => {
    const b = document.createElement('button');
    b.setAttribute('role', 'menuitemradio');
    b.setAttribute('aria-checked', String(key === state.theme));
    const sw = document.createElement('span');
    sw.className = 'swatch';
    sw.style.background = `linear-gradient(135deg, ${bg} 50%, ${accent} 50%)`;
    const label = document.createElement('span');
    label.textContent = name;
    b.append(sw, label);
    if (key === state.theme) {
      const c = document.createElement('span');
      c.className = 'check';
      c.textContent = '✓';
      b.append(c);
    }
    b.addEventListener('click', () => { setTheme(key); toggleMenu(false); });
    return b;
  }));
}

function toggleMenu(open) {
  $('themeMenu').hidden = !open;
  $('themeBtn').setAttribute('aria-expanded', String(open));
}
$('themeBtn').addEventListener('click', (e) => { e.stopPropagation(); toggleMenu($('themeMenu').hidden); });
document.addEventListener('click', (e) => { if (!e.target.closest('.menu')) toggleMenu(false); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') toggleMenu(false); });

// ---------- form ----------
const FIELDS = ['url', 'date', 'time', 'keywords', 'labels', 'reload'];

function fill(s) {
  $('url').value = s.url || '';
  $('date').value = s.date || '';
  $('time').value = s.time || '20:00:00';
  $('keywords').value = s.keywords || '';
  $('labels').value = s.labels || '예매하기, 예매 하기';
  $('reload').value = String(s.reload_after ?? 2.0);
  setEarly(!!s.early_press);
  updateSite();
}

function collect() {
  return {
    url: $('url').value.trim(), date: $('date').value.trim(), time: $('time').value.trim(),
    keywords: $('keywords').value, labels: $('labels').value, reload_after: $('reload').value.trim(),
    early_press: state.early,
  };
}

function scheduleSave() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => api && api.save(collect()), 400);
}

function setEarly(on) {
  state.early = on;
  $('early').setAttribute('aria-checked', String(on));
}

function isMelon(url) {
  try { return new URL(url).hostname.endsWith('melon.com'); } catch { return false; }
}

function updateSite() {
  const melon = isMelon($('url').value.trim());
  $('site').textContent = melon ? '멜론티켓' : 'NOL';
  $('keywordHelp').textContent = melon
    ? '멜론은 날짜·시각 (예: 10월 24일 18시) · 회차가 하나면 비워 두세요'
    : '버튼이 여러 개일 때 카드의 글자 (예: 3차전)';
}

async function preview() {
  if (!api) return;
  const p = await api.preview($('date').value, $('time').value);
  if (p.date) $('date').value = p.date;
  if (p.time) $('time').value = p.time;
  state.openAt = p.open_at;
  $('target').textContent = p.label || '오픈 날짜와 시각을 확인하세요 (예: 20261024 · 200000)';
}

for (const id of FIELDS) $(id).addEventListener('input', scheduleSave);
$('url').addEventListener('input', updateSite);
for (const id of ['date', 'time']) {
  $(id).addEventListener('blur', () => { preview(); scheduleSave(); });
  $(id).addEventListener('keydown', (e) => { if (e.key === 'Enter') e.target.blur(); });
}
$('early').addEventListener('click', () => { setEarly(!state.early); scheduleSave(); });
$('advBtn').addEventListener('click', () => {
  const open = $('adv').hidden;
  $('adv').hidden = !open;
  $('advBtn').setAttribute('aria-expanded', String(open));
});

// ---------- actions ----------
$('startBtn').addEventListener('click', async () => {
  await preview();
  const r = await api.start(collect());
  showError(r.ok ? '' : r.error);
});
$('stopBtn').addEventListener('click', () => api.stop());
$('resetBtn').addEventListener('click', () => { state.result = null; setStatus('idle'); setPhase('idle'); });
$('remeasure').addEventListener('click', () => api.measure());
$('logsBtn').addEventListener('click', () => api.open_logs());
$('updateBtn').addEventListener('click', () => api.check_update());
$('updLater').addEventListener('click', () => { $('updateDialog').hidden = true; api.decline_update(); });
$('updGo').addEventListener('click', () => { $('updGo').disabled = true; api.install_update(); });

function showError(text) {
  $('error').hidden = !text;
  $('error').textContent = text || '';
}

// ---------- phases: idle → running → done ----------
function setPhase(phase) {
  state.phase = phase;
  const idle = phase === 'idle', running = phase === 'running', done = phase === 'done';
  $('formCard').hidden = !idle;
  $('summaryCard').hidden = idle;
  $('startBtn').hidden = !idle;
  $('stopBtn').hidden = !running;
  $('resetBtn').hidden = !done;
  $('clockBlock').hidden = done;
  $('resultBlock').hidden = !done;
  $('eyebrow').hidden = done;
  if (running) {
    state.finished = false;
    const s = collect();
    $('sumUrl').textContent = s.url.replace(/^https?:\/\//, '');
    $('sumKeywords').textContent = s.keywords.trim() || '지정 안 함';
    $('sumOptions').textContent = state.early ? '미리 누르기 켬' : '정각에 누르기';
    setLog(true);
    showError('');
  }
  if (done) renderResult();
  $('resetBtn').disabled = !state.finished;
  $('hint').textContent = running ? '열린 Chrome 창에서 로그인하고 날짜/회차를 골라 두세요. 버튼이 열리면 바로 누릅니다.'
    : done ? (state.finished ? '' : 'Chrome 창을 닫으면 처음으로 돌아갈 수 있어요.')
      : '오픈 5분 전에 시작하고, 열린 Chrome에서 로그인과 날짜/회차를 골라 두세요.';
  $('hint').hidden = !$('hint').textContent;
}

function onFinished(evt) {
  state.finished = true;
  if (state.phase === 'done') { $('resetBtn').disabled = false; $('hint').hidden = true; return; }
  setPhase('idle');
  if (evt.message) showError(evt.message);
}

function renderResult() {
  const r = state.result || { outcome: 'fail' };
  const ok = r.outcome === 'clicked';
  $('resultTitle').textContent = ok ? '예매창을 열었어요' : '확인이 필요해요';
  $('resultSub').textContent = ok ? '이제 Chrome에서 대기열 · 좌석 · 결제를 직접 진행하세요.'
    : (r.message || '기록을 확인하고 Chrome에서 직접 진행하세요.');
  $('rLate').textContent = r.late_ms == null ? '—' : `${r.late_ms >= 0 ? '+' : ''}${r.late_ms} ms`;
  $('rTries').textContent = r.tries ? `${r.tries}회` : '—';
  $('rError').textContent = state.offset ? `±${Math.round(state.offset.error * 1000)} ms` : '—';
}

function setStatus(key, detail) {
  const chip = $('chip');
  chip.className = 'chip' + (key === 'idle' ? '' : ' ' + key);
  $('chipText').textContent = detail || STATUS_TEXT[key] || key;
}

// ---------- server clock ----------
function onOffset(o) {
  if (!o.ok) {
    state.offset = null;
    $('drift').textContent = '측정 실패 — 인터넷 연결을 확인하세요';
    $('rtt').textContent = '—';
    $('host').textContent = o.message || '—';
    $('speedDot').className = 'speed';
    return;
  }
  state.offset = o;
  const ms = Math.abs(o.seconds * 1000);
  $('drift').textContent = ms < 50 ? '내 PC와 거의 같음' : `내 PC보다 ${(ms / 1000).toFixed(2)}초 ${o.seconds < 0 ? '느림' : '빠름'}`;
  $('rtt').textContent = `왕복 ${Math.round(o.rtt * 1000)} ms`;
  $('host').textContent = `오차 ±${Math.round(o.error * 1000)} ms · ${o.host}`;
  $('speedDot').className = 'speed ' + ({ '빠름': 'fast', '보통': 'ok', '느림': 'slow' }[o.speed] || '');
  $('serverLabel').textContent = `${isMelon($('url').value) ? '멜론' : 'NOL'} 서버 시각`;
}

const pad = (n, w = 2) => String(n).padStart(w, '0');
let shown = { clock: '', count: '', cls: '' };

function tick() {
  const now = Date.now() / 1000 + (state.offset ? state.offset.seconds : 0);
  if (state.offset) {
    const d = new Date(now * 1000);
    const clock = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
    if (clock !== shown.clock) { $('serverClock').textContent = clock; shown.clock = clock; }
  }
  let html, cls = 'countdown num';
  if (state.openAt == null) {
    html = '날짜와 시각을 확인하세요'; cls += ' quiet';
  } else if (state.openAt <= now) {
    html = '오픈 시각이 지났습니다'; cls += ' quiet';
  } else {
    const left = state.openAt - now;
    const whole = Math.floor(left);
    const days = Math.floor(whole / 86400), h = Math.floor(whole % 86400 / 3600), m = Math.floor(whole % 3600 / 60), s = whole % 60;
    const main = days ? `${days}일 ${pad(h)}:${pad(m)}:${pad(s)}` : h ? `${pad(h)}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
    html = whole < 60 * 60 && !days ? `${main}<span class="frac">.${Math.floor((left - whole) * 10)}</span>` : main;
    if (left < 60) cls += ' urgent';
  }
  if (html !== shown.count) { $('countdown').innerHTML = html; shown.count = html; }
  if (cls !== shown.cls) { $('countdown').className = cls; shown.cls = cls; }
  requestAnimationFrame(tick);
}
requestAnimationFrame(tick);

// ---------- log ----------
function logClass(text) {
  if (/예매창을 열었습니다|로그인 확인:/.test(text)) return 'ok';
  if (/⚠|알림:|오류|누르지 못했|멈췄습니다/.test(text)) return 'bad';
  if (/클릭 \d+회째|회차 선택|감시/.test(text)) return 'hi';
  return '';
}

function addLog(time, text) {
  state.logs.push([time, text]);
  if (state.logs.length > 400) state.logs.shift();
  const body = $('logBody');
  const e = document.createElement('div');
  e.className = 'e ' + logClass(text);
  const t = document.createElement('time');
  t.textContent = time;
  const s = document.createElement('span');
  s.textContent = text;
  e.append(t, s);
  body.append(e);
  while (body.children.length > 400) body.firstChild.remove();
  $('logCount').textContent = String(state.logs.length);
  if (state.logOpen) body.scrollTop = body.scrollHeight;
}

function setLog(open) {
  state.logOpen = open;
  $('logBody').hidden = !open;
  $('logBtn').setAttribute('aria-expanded', String(open));
  if (open) $('logBody').scrollTop = $('logBody').scrollHeight;
}
$('logBtn').addEventListener('click', () => setLog(!state.logOpen));

// ---------- updates ----------
function onUpdate(evt) {
  if (evt.text) $('ver').textContent = evt.text;
  if (evt.offer) {
    $('updText').textContent = `새 버전 ${evt.offer.version}을 내려받았어요. 설치하면 프로그램이 닫혔다가 다시 열립니다.`;
    $('updNotes').hidden = !evt.offer.notes;
    $('updNotes').textContent = evt.offer.notes || '';
    $('updGo').disabled = false;
    $('updateDialog').hidden = false;
  }
}

/* PartyTime – web client (served by the game master's phone) */
(function () {
  'use strict';

  // ------------------------------------------------------------ utilities
  var LS = {
    get: function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: function (k, v) { try { localStorage.setItem(k, v); } catch (e) {} },
    del: function (k) { try { localStorage.removeItem(k); } catch (e) {} }
  };
  var NativeApp = window.PartyApp || null;
  var $ = function (id) { return document.getElementById(id); };
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function setHTML(el, html) {
    if (!el) return;
    if (el._h !== html) { el.innerHTML = html; el._h = html; }
  }

  var TEAM = { blue: 'Kékek', red: 'Pirosak' };
  var CATS = [['person', '🌟 Híres ember'], ['movie', '🎬 Film / sorozat'], ['animal', '🐾 Állat'], ['other', '💡 Tárgy / fogalom']];
  var CAT = {}; CATS.forEach(function (c) { CAT[c[0]] = c[1]; });
  var METHOD = { mime: '🙌 Mutogatás', draw: '✏️ Rajzolás', talk: '🗣️ Körülírás' };
  var METHOD_HINT = {
    mime: 'Csak mozdulatokkal, hang nélkül!',
    draw: 'Rajzolj! Betűt, számot nem írhatsz.',
    talk: 'Magyarázd el, de a szót (és részeit) nem mondhatod ki!'
  };

  // ------------------------------------------------------------ sound / vibration
  var actx = null;
  function audio() {
    try {
      if (!actx) actx = new (window.AudioContext || window.webkitAudioContext)();
      if (actx.state === 'suspended') actx.resume();
    } catch (e) { actx = null; }
    return actx;
  }
  function beep(freq, dur, vol, type) {
    var a = audio(); if (!a) return;
    try {
      var o = a.createOscillator(), g = a.createGain();
      o.type = type || 'sine'; o.frequency.value = freq || 880;
      g.gain.setValueAtTime(vol || 0.25, a.currentTime);
      g.gain.exponentialRampToValueAtTime(0.0001, a.currentTime + (dur || 0.15));
      o.connect(g); g.connect(a.destination);
      o.start(); o.stop(a.currentTime + (dur || 0.15) + 0.05);
    } catch (e) {}
  }
  function alarm() {
    beep(660, 0.25, 0.35, 'square');
    setTimeout(function () { beep(520, 0.25, 0.35, 'square'); }, 300);
    setTimeout(function () { beep(400, 0.6, 0.35, 'square'); }, 600);
  }
  function vibrate(ms) {
    try {
      if (NativeApp && NativeApp.vibrate) NativeApp.vibrate(ms);
      else if (navigator.vibrate) navigator.vibrate(ms);
    } catch (e) {}
  }
  document.addEventListener('touchstart', audio, { passive: true });
  document.addEventListener('click', audio);

  // ------------------------------------------------------------ toasts & modals
  function toast(msg, kind, ms) {
    var t = document.createElement('div');
    t.className = 'toast' + (kind ? ' ' + kind : '');
    t.textContent = msg;
    t.onclick = function () { t.remove(); };
    $('toasts').appendChild(t);
    setTimeout(function () { t.remove(); }, ms || 3500);
  }
  var modalOnClose = null;
  function modal(html) {
    $('modalRoot').innerHTML = '<div class="modal-bg" id="modalBg"><div class="modal">' + html + '</div></div>';
    $('modalBg').addEventListener('click', function (e) { if (e.target.id === 'modalBg') closeModal(false); });
  }
  function closeModal(val) {
    $('modalRoot').innerHTML = '';
    var cb = modalOnClose; modalOnClose = null;
    if (cb) cb(val);
  }
  function askConfirm(text, okLabel, danger) {
    return new Promise(function (resolve) {
      modal('<h2>' + esc(text) + '</h2><div class="btns"><button class="btn ghost" data-act="modalNo">Mégse</button>' +
        '<button class="btn ' + (danger ? 'bad' : 'primary') + '" data-act="modalYes">' + esc(okLabel || 'Igen') + '</button></div>');
      modalOnClose = resolve;
    });
  }
  function askText(title, fields) {
    return new Promise(function (resolve) {
      var h = '<h2>' + esc(title) + '</h2><div class="stack">';
      fields.forEach(function (f, i) {
        h += f.area
          ? '<textarea id="askF' + i + '" maxlength="' + (f.max || 200) + '" placeholder="' + esc(f.ph || '') + '"></textarea>'
          : '<input type="text" id="askF' + i + '" maxlength="' + (f.max || 100) + '" placeholder="' + esc(f.ph || '') + '" value="' + esc(f.value || '') + '">';
      });
      h += '<div class="btns"><button class="btn ghost" data-act="modalNo">Mégse</button><button class="btn primary" data-act="askOk">OK</button></div></div>';
      modal(h);
      modalOnClose = resolve;
      var first = $('askF0'); if (first) first.focus();
    });
  }

  // ------------------------------------------------------------ network
  var secret = null;
  var S = null;               // last state
  var tab = 'mission';
  var failCount = 0, inflight = false, pollAgain = false;
  var autoName = null;      // name passed by the native app – used to re-join automatically

  function api(path, body) {
    return fetch(path, {
      method: body ? 'POST' : 'GET',
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
      cache: 'no-store'
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (d) { d._status = r.status; return d; });
    });
  }

  function act(type, data) {
    var body = Object.assign({ s: secret, type: type }, data || {});
    return api('/api/action', body).then(function (d) {
      if (d._status !== 200) { toast(d.error || 'Hiba történt', 'err'); return false; }
      if (d.message) toast(d.message);
      poll();
      return true;
    }).catch(function () { toast('Nincs kapcsolat a játékmesterrel', 'err'); return false; });
  }

  function poll() {
    if (!secret) return;
    if (inflight) { pollAgain = true; return; }
    inflight = true;
    api('/api/state?s=' + encodeURIComponent(secret)).then(function (d) {
      if (d._status === 401) {
        secret = null; LS.del('pt_secret');
        if (autoName) { var n = autoName; autoName = null; doJoin(n); return; }
        showJoin('A játékmester kiléptetett, vagy új játék indult. Csatlakozz újra!');
        return;
      }
      if (d._status !== 200) throw new Error('bad');
      failCount = 0;
      $('banner').classList.add('hidden');
      var prev = S; S = d;
      onState(prev);
    }).catch(function () {
      failCount++;
      if (failCount >= 3) $('banner').classList.remove('hidden');
    }).then(function () {
      inflight = false;
      if (pollAgain) { pollAgain = false; poll(); }
    });
  }
  setInterval(poll, 1000);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) poll(); });

  // ------------------------------------------------------------ join
  function showJoin(err) {
    $('gameView').classList.add('hidden');
    $('joinView').classList.remove('hidden');
    $('joinName').value = LS.get('pt_name') || '';
    if (err) toast(err, 'err', 5000);
    api('/api/ping').then(function (d) {
      if (d.host) $('joinHostInfo').innerHTML = 'Játékmester: <b>' + esc(d.host) + '</b>. Add meg a neved, amivel a többiek látni fognak.';
    }).catch(function () {});
  }
  function showGame() {
    $('joinView').classList.add('hidden');
    $('gameView').classList.remove('hidden');
  }
  function doJoin(name) {
    name = (name || '').trim();
    if (!name) { toast('Add meg a neved!', 'err'); return; }
    $('joinBtn').disabled = true;
    api('/api/join', { name: name }).then(function (d) {
      $('joinBtn').disabled = false;
      if (d._status !== 200) { showJoin(d.error || 'Nem sikerült csatlakozni'); return; }
      secret = d.secret;
      LS.set('pt_secret', secret);
      LS.set('pt_name', name);
      showGame();
      if (d.rejoined) toast('Üdv újra, ' + name + '!');
      poll();
    }).catch(function () {
      $('joinBtn').disabled = false;
      toast('Nem érem el a játékmestert', 'err');
    });
  }
  $('joinBtn').onclick = function () { doJoin($('joinName').value); };
  $('joinName').addEventListener('keydown', function (e) { if (e.key === 'Enter') doJoin($('joinName').value); });

  // ------------------------------------------------------------ tabs
  function setTab(t) {
    tab = t;
    document.querySelectorAll('nav.tabs button').forEach(function (b) { b.classList.toggle('active', b.dataset.tab === t); });
    document.querySelectorAll('.tab').forEach(function (el) { el.classList.toggle('hidden', el.id !== 'tab-' + t); });
    var badge = $('badge-' + t); if (badge) badge.classList.add('hidden');
    LS.set('pt_tab', t);
    window.scrollTo(0, 0);
  }
  document.querySelectorAll('nav.tabs button').forEach(function (b) {
    b.addEventListener('click', function () { setTab(b.dataset.tab); });
  });
  function badge(t) { if (tab !== t) { var b = $('badge-' + t); if (b) b.classList.remove('hidden'); } }

  // ------------------------------------------------------------ local UI state
  var missionOpen = false, missionTimer = null;
  var numOpen = false;
  var showSpoiler = false;
  var riddleCat = 'person';
  var timer = { seq: -1, endAt: 0, phase: '', alarmed: -1, lastTick: -1, duration: 60000 };

  // ------------------------------------------------------------ state handling
  function onState(prev) {
    var me = S.me;
    // notifications
    (S.inbox || []).forEach(function (m) { toast(m, 'big', 9000); vibrate(300); });
    if (prev) {
      if (S.top10.round !== prev.top10.round && S.top10.round > 0) {
        numOpen = false;
        if (S.top10.number) { toast('🔟 Új számot kaptál!', 'big'); vibrate(200); }
        badge('top10');
      }
      var r = S.activity.round, pr = prev.activity.round;
      if (r.seq !== pr.seq && r.phase === 'ready') badge('activity');
      if (r.phase === 'running' && pr.phase === 'ready') { beep(880, 0.2, 0.3); vibrate(150); badge('activity'); }
      var pm = prev.mission, m = S.mission;
      if (m.my && (!pm.my || pm.my.title !== m.my.title)) { missionOpen = false; badge('mission'); }
    }
    syncTimer();
    render();
  }

  function syncTimer() {
    var r = S.activity.round;
    var now = performance.now();
    timer.duration = S.activity.duration * 1000;
    if (r.seq !== timer.seq) { timer.seq = r.seq; timer.lastTick = -1; }
    timer.phase = r.phase;
    if (r.phase === 'running') {
      var end = now + r.remainingMs;
      if (Math.abs(end - timer.endAt) > 600) timer.endAt = end;
    } else if (r.phase === 'timeup') {
      timer.endAt = Math.min(timer.endAt, now);
    }
  }

  function tick() {
    if (!S) return;
    var el = $('actTimer'), bar = $('actBar');
    var ph = timer.phase;
    var rem;
    if (ph === 'running' || ph === 'timeup') rem = Math.max(0, timer.endAt - performance.now());
    else rem = timer.duration;
    var sec = Math.ceil(rem / 1000);
    if ((ph === 'running' || ph === 'timeup') && rem <= 0 && timer.alarmed !== timer.seq) {
      timer.alarmed = timer.seq;
      alarm(); vibrate(800);
    }
    if (ph === 'running' && sec <= 5 && sec > 0 && timer.lastTick !== sec) { timer.lastTick = sec; beep(1000, 0.08, 0.2); }
    if (el) {
      if ((ph === 'running' || ph === 'timeup') && rem <= 0) {
        el.textContent = '⏰ Lejárt az idő!'; el.className = 'timer done';
      } else {
        el.textContent = sec;
        el.className = 'timer' + (ph === 'running' ? (sec <= 10 ? ' crit' : sec <= 20 ? ' warn' : '') : '');
      }
    }
    if (bar) bar.style.width = (100 * rem / timer.duration) + '%';
  }
  setInterval(tick, 200);

  // ------------------------------------------------------------ render
  function render() {
    var me = S.me;
    setHTML($('meChip'), '<span class="dot ' + (me.team || 'none') + '"></span>' + (me.host ? '👑 ' : '') + esc(me.name));
    renderMission();
    renderActivity();
    renderTop10();
    renderPlayers();
    tick();
  }

  function playerName(pid) {
    for (var i = 0; i < S.players.length; i++) if (S.players[i].pid === pid) return S.players[i].name;
    return '?';
  }

  // ---------------- mission
  function renderMission() {
    var m = S.mission, me = S.me, h = '';
    if (!m.active) {
      h = '<div class="card hero center stack"><div style="font-size:52px">🕵️</div><h2>Titkos küldetés</h2>' +
        '<p class="muted">Amint a játékmester elindítja, mindenki kap egy titkos feladatot, amit az este folyamán kell teljesítenie. Senkinek ne áruld el!</p></div>';
    } else if (m.my) {
      h = '<div class="card hero stack"><h3>A titkos küldetésed</h3>' +
        '<div class="secret' + (missionOpen ? ' open' : '') + '" data-act="toggleSecret">' +
        '<div class="cover"><span class="ic">🔒</span>Koppints a felfedéshez<span class="small">(nézz körül, ki lát rá!)</span></div>' +
        '<div class="mission-title">' + esc(m.my.title) + '</div><div class="mission-text">' + esc(m.my.text) + '</div></div>' +
        '<button class="btn ok block big" data-act="missionDone">✅ Küldetés teljesítve!</button>' +
        '<button class="btn ghost block sm" data-act="missionSkip">🔄 Ez nem megy, másikat kérek</button></div>';
    } else {
      h = '<div class="card hero center stack"><div style="font-size:52px">🎯</div><h2>' +
        (m.mine.length ? 'Szép munka! Jöhet a következő?' : 'Még nincs küldetésed') + '</h2>' +
        '<p class="muted">' + (m.left > 0 ? 'Kérj magadnak egy új titkos küldetést!' : 'Elfogytak a küldetések – a játékmester tud újakat hozzáadni.') + '</p>' +
        '<button class="btn primary block big" data-act="missionRequest"' + (m.left > 0 ? '' : ' disabled') + '>🎲 Kérek egy küldetést</button></div>';
    }
    if (m.mine.length) {
      h += '<div class="card"><h3>Teljesített küldetéseim (' + m.mine.length + ')</h3><ul class="list">' +
        m.mine.map(function (c) {
          return '<li><div class="name"><b>' + esc(c.title) + '</b><div class="small muted">Segített: ' + esc(c.targets.join(', ')) + (c.note ? ' · ' + esc(c.note) : '') + '</div></div><span class="pill ok">+1</span></li>';
        }).join('') + '</ul></div>';
    }
    setHTML($('missionMain'), h);

    // game master
    var g = '';
    if (me.host) {
      g = '<div class="card gm stack"><h3>Játékmester</h3>' +
        '<p class="small muted" style="margin:0">Szabad küldetések: <b>' + m.left + '</b> / ' + m.total + '</p>' +
        '<button class="btn gold block big" data-act="gm" data-type="missionStart">' +
        (m.active ? '📨 Kiosztás az új / küldetés nélküli játékosoknak' : '🚀 Titkos küldetés indítása') + '</button>' +
        '<div class="btns">' +
        '<button class="btn sm" data-act="missionAdd">➕ Saját küldetés</button>' +
        '<button class="btn sm" data-act="gm" data-type="missionReveal" data-on="' + (!m.revealed) + '">' + (m.revealed ? '🙈 Eredmények elrejtése' : '📢 Eredmények felfedése') + '</button>' +
        '</div>' +
        '<button class="btn sm ghost block" data-act="spoiler">' + (showSpoiler ? 'Kiosztott küldetések elrejtése' : '👀 Ki milyen küldetést kapott? (spoiler!)') + '</button>' +
        (showSpoiler ? '<ul class="list">' + (m.assigned.length ? m.assigned.map(function (a) {
          return '<li><span class="name">' + esc(a.name) + '</span><span class="pill">' + esc(a.title) + '</span></li>';
        }).join('') : '<li class="muted">Senkinek nincs aktív küldetése.</li>') + '</ul>' : '') +
        '<button class="btn sm ghost block" data-act="gmConfirm" data-type="missionReset" data-q="Biztosan újraindítod a titkos küldetést? Minden pont és küldetés törlődik.">♻️ Küldetések nullázása</button>' +
        '</div>';
    }
    setHTML($('missionGm'), g);

    // leaderboard
    var ranked = S.players.slice().sort(function (a, b) { return b.missions - a.missions || a.name.localeCompare(b.name); });
    var b = '<div class="card"><h3>🏆 Ranglista</h3><ul class="list">' + ranked.map(function (p, i) {
      var medal = p.missions > 0 ? (['🥇', '🥈', '🥉'][i] || '') : '';
      return '<li><span class="dot ' + (p.online ? 'on' : 'off') + '"></span><span class="name">' + medal + ' ' + esc(p.name) + (p.pid === me.pid ? ' <span class="muted">(te)</span>' : '') + '</span><span class="pill gold">' + p.missions + ' ✔</span></li>';
    }).join('') + '</ul></div>';
    if (m.log.length) {
      b += '<div class="card"><h3>📜 Teljesített küldetések' + (m.revealed ? '' : ' (csak a játékmester látja)') + '</h3><ul class="list">' + m.log.map(function (c) {
        return '<li><div class="name"><b>' + esc(c.name) + '</b> – ' + esc(c.title) + '<div class="small muted">' + esc(c.text) + '</div><div class="small">🤝 ' + esc(c.targets.join(', ')) + (c.note ? ' · <i>' + esc(c.note) + '</i>' : '') + '</div></div></li>';
      }).join('') + '</ul></div>';
    }
    setHTML($('missionBoard'), b);
  }

  // ---------------- activity
  function renderActivity() {
    var a = S.activity, me = S.me, r = a.round;
    // score
    var s = '<div class="card"><div class="score">' +
      '<div class="t blue' + (me.team === 'blue' ? ' mine' : '') + '"><div class="n">' + a.scores.blue + '</div>Kékek</div>' +
      '<div class="vs">:</div>' +
      '<div class="t red' + (me.team === 'red' ? ' mine' : '') + '"><div class="n">' + a.scores.red + '</div>Pirosak</div></div>';
    if (!me.team) {
      s += '<p class="center" style="margin:12px 0 8px">Válassz csapatot (vagy várd meg, amíg a játékmester kisorsolja):</p>' +
        '<div class="btns"><button class="btn blue" data-act="pickTeam" data-team="blue">Kékek</button><button class="btn red" data-act="pickTeam" data-team="red">Pirosak</button></div>';
    } else {
      s += '<p class="center small muted" style="margin:10px 0 0">Te a <b>' + TEAM[me.team] + '</b> csapatában vagy · <a href="#" data-act="pickTeam" data-team="' + (me.team === 'blue' ? 'red' : 'blue') + '" style="color:inherit">csapatváltás</a></p>';
    }
    s += '</div>';
    setHTML($('actScore'), s);

    // round
    var h = '';
    var canControl = me.host || r.iAmPerformer;
    if (r.phase === 'idle') {
      h = '<div class="card hero center stack"><div style="font-size:52px">🎭</div><h2>Activity</h2>' +
        '<p class="muted">Várakozás a következő körre… Addig is küldj be feladványokat a másik csapatnak!</p>' +
        '<p class="small muted">Előadható feladványok – Kékek: <b>' + a.pool.blue + '</b> · Pirosak: <b>' + a.pool.red + '</b></p></div>';
    } else {
      var teamCls = r.team === 'blue' ? 'blue' : 'red';
      h = '<div class="card hero stack">' +
        '<div class="row"><span class="pill ' + teamCls + '">' + TEAM[r.team] + '</span><span class="pill">' + (CAT[r.cat] || '') + '</span><span class="pill gold">' + (METHOD[r.method] || '') + '</span></div>' +
        '<div class="performer">' + (r.iAmPerformer ? '🎭 Te adod elő!' : '🎭 Előadó: ' + esc(r.performerName)) + '</div>';
      if (r.iAmPerformer && r.phase !== 'done') h += '<div class="small muted">' + METHOD_HINT[r.method] + '</div>';
      if (r.text != null) {
        h += '<div class="riddle">' + esc(r.text) + '</div>';
      } else {
        h += '<div class="riddle">❓ Találjátok ki!</div>';
      }
      if (r.phase === 'done') {
        h += '<div class="result ' + (r.result === 'guessed' ? 'ok">✅ Kitalálták! +1 pont: ' + TEAM[r.team] : 'bad">❌ Nem sikerült kitalálni') + '</div>';
      } else {
        h += '<div class="timer" id="actTimer"></div><div class="bar"><div id="actBar"></div></div>';
      }
      if (canControl && r.phase === 'ready') {
        h += '<button class="btn primary block big" data-act="gm" data-type="actStart">▶ Start – ' + a.duration + ' mp</button>' +
          '<div class="btns"><button class="btn sm" data-act="gm" data-type="actSkipRiddle">🔄 Másik feladvány</button>' +
          (me.host ? '<button class="btn sm" data-act="gm" data-type="actOtherPerformer">👤 Másik előadó</button>' : '') + '</div>';
      }
      if (canControl && (r.phase === 'running' || r.phase === 'timeup')) {
        h += '<div class="btns"><button class="btn ok big" data-act="gm" data-type="actResult" data-guessed="true">✅ Kitalálták</button>' +
          '<button class="btn bad big" data-act="gm" data-type="actResult" data-guessed="false">❌ Nem sikerült</button></div>';
      }
      if (!canControl && r.phase === 'ready') h += '<p class="center muted small">Mindjárt indul a visszaszámlálás…</p>';
      if (me.host && (r.phase === 'ready' || r.phase === 'running' || r.phase === 'timeup')) {
        h += '<button class="btn sm ghost block" data-act="gm" data-type="actCancel">Kör megszakítása</button>';
      }
      h += '</div>';
    }
    setHTML($('actRound'), h);

    // my riddles
    var mr = a.myRiddles;
    setHTML($('myRiddles'), mr.length ? '<h3 style="margin-top:14px">Beküldött feladványaim</h3><ul class="list">' + mr.map(function (x) {
      return '<li><span class="name">' + esc(x.text) + ' <span class="small muted">' + (CAT[x.cat] || '') + '</span></span>' +
        (x.used ? '<span class="pill">elhasználva</span>' : '<button class="btn sm ghost" data-act="riddleDel" data-id="' + x.id + '">✖</button>') + '</li>';
    }).join('') + '</ul>' : '');
    setHTML($('riddleCat'), CATS.map(function (c) {
      return '<button class="' + (riddleCat === c[0] ? 'on' : '') + '" data-act="riddleCat" data-cat="' + c[0] + '">' + c[1] + '</button>';
    }).join(''));

    // game master
    var g = '';
    if (me.host) {
      var other = a.nextTeam === 'blue' ? 'red' : 'blue';
      var busy = r.phase === 'ready' || r.phase === 'running' || r.phase === 'timeup';
      g = '<div class="card gm stack"><h3>Játékmester</h3>' +
        '<p class="small muted" style="margin:0">Előadható feladványok – Kékek: <b>' + a.pool.blue + '</b> · Pirosak: <b>' + a.pool.red + '</b></p>' +
        '<button class="btn gold block big" data-act="gm" data-type="actNext" data-team="' + a.nextTeam + '"' + (busy ? ' disabled' : '') + '>⏭ Következő kör: ' + TEAM[a.nextTeam] + '</button>' +
        '<button class="btn sm block" data-act="gm" data-type="actNext" data-team="' + other + '"' + (busy ? ' disabled' : '') + '>vagy inkább a(z) ' + TEAM[other] + ' jönnek</button>' +
        '<div><div class="small muted" style="margin-bottom:6px">Idő</div><div class="seg">' + [30, 45, 60, 90, 120].map(function (d) {
          return '<button class="' + (a.duration === d ? 'on' : '') + '" data-act="actDur" data-d="' + d + '">' + d + ' mp</button>';
        }).join('') + '</div></div>' +
        '<div><div class="small muted" style="margin-bottom:6px">Előadás módja</div><div class="seg">' +
        '<button class="' + (a.methods ? 'on' : '') + '" data-act="actMethods" data-on="true">Vegyes (mutogatás / rajz / körülírás)</button>' +
        '<button class="' + (!a.methods ? 'on' : '') + '" data-act="actMethods" data-on="false">Csak mutogatás</button></div></div>' +
        '<div class="btns"><button class="btn sm" data-act="gm" data-type="shuffleTeams">🔀 Csapatok sorsolása</button>' +
        '<button class="btn sm" data-act="gmConfirm" data-type="actReset" data-q="Nullázod az Activity-t? A pontok és a feladványok törlődnek.">♻️ Nullázás</button></div>' +
        '</div>';
    }
    setHTML($('actGm'), g);

    // history
    setHTML($('actHistory'), a.history.length ? '<div class="card"><h3>Előző körök</h3><ul class="list">' + a.history.map(function (x) {
      return '<li><span class="dot ' + x.team + '"></span><span class="name"><b>' + esc(x.text) + '</b><div class="small muted">' + esc(x.performer) + ' · ' + (CAT[x.cat] || '') + '</div></span>' +
        (x.result === 'guessed' ? '<span class="pill ok">✔ +1</span>' : '<span class="pill bad">✖</span>') + '</li>';
    }).join('') + '</ul></div>' : '');
  }

  // ---------------- top10
  function renderTop10() {
    var t = S.top10, me = S.me, h = '';
    if (t.round === 0) {
      h = '<div class="card hero center stack"><div style="font-size:52px">🔟</div><h2>TOP10</h2>' +
        '<p class="muted">A játékmester gombnyomására mindenki kap egy titkos számot 1 és 10 között – mindenki különbözőt. ' +
        'A témához a számod erősségének megfelelő példát kell mondanod (1 = legkevésbé, 10 = legjobban), a többiek pedig megpróbálják sorba rendezni a játékosokat.</p>' +
        '<p class="small muted">Várakozás a játékmesterre…</p></div>';
    } else {
      h = '<div class="card hero stack"><h3>Téma · ' + t.round + '. kör</h3><div class="topic">' + esc(t.topic) + '</div>' +
        '<p class="small muted center" style="margin:0">1 = legkevésbé · 10 = legjobban</p></div>';
      if (t.number) {
        h += '<div class="card stack"><h3>A titkos számod</h3><div class="secret numcard' + (numOpen ? ' open' : '') + '" data-act="toggleNum">' +
          '<div class="cover"><span class="ic">🔒</span>Koppints a számod megnézéséhez</div><div class="bignum">' + t.number + '</div></div>' +
          '<p class="small muted center" style="margin:0">' + t.count + ' játékos kapott számot ebben a körben.</p></div>';
      } else if (t.sittingOut) {
        h += '<div class="card center"><h2>🙅 Ebben a körben nem kaptál számot</h2><p class="muted">' +
          (me.host && t.hostOut ? 'Több mint 10-en vagytok, ezért a játékmester most nem játszik – te vezeted a kört.' :
            'Több mint 10 játékos van jelen, ezért valakinek ki kell maradnia, vagy a kör kiosztása után érkeztél. Következő körben újra sorsolunk!') + '</p></div>';
      }
      if (t.revealed && t.all) {
        h += '<div class="card"><h3>👁 Felfedés</h3><ul class="list">' + t.all.map(function (x) {
          return '<li><span class="pill gold" style="font-size:16px;min-width:40px;text-align:center">' + x.number + '</span><span class="name">' + esc(x.name) + '</span></li>';
        }).join('') + '</ul></div>';
      }
    }
    setHTML($('topMain'), h);

    var g = '';
    if (me.host) {
      g = '<div class="card gm stack"><h3>Játékmester</h3>' +
        '<button class="btn gold block big" data-act="gm" data-type="top10Deal">🎲 ' + (t.round ? 'Új számok mindenkinek' : 'Számok kiosztása') + '</button>' +
        '<p class="small muted" style="margin:0">Ha 10-nél többen vagytok, a játékmester nem kap számot.</p>' +
        (t.round ? '<div class="btns"><button class="btn sm" data-act="gm" data-type="top10Topic">🔄 Másik téma</button>' +
          '<button class="btn sm" data-act="top10Custom">✏️ Saját téma</button>' +
          '<button class="btn sm" data-act="gm" data-type="top10Reveal"' + (t.revealed ? ' disabled' : '') + '>👁 Számok felfedése</button></div>' : '') +
        '</div>';
    }
    setHTML($('topGm'), g);
  }

  // ---------------- players
  function renderPlayers() {
    var me = S.me, h = '';
    if (me.host) {
      h = '<div class="card stack center"><h3>Csatlakozás</h3>' +
        '<p class="small muted" style="margin:0">A PartyTime appban a játék automatikusan megjelenik az azonos wifin lévőknek. App nélkül (pl. iPhone-ról) olvassák be a QR-kódot, vagy írják be a böngészőbe:</p>' +
        '<img class="qr" src="/api/qr.svg?u=' + encodeURIComponent(S.joinUrl || '') + '" alt="QR-kód">' +
        '<div class="url">' + esc(S.joinUrl || '') + '</div></div>';
    }
    setHTML($('joinInfo'), h);

    var online = S.players.filter(function (p) { return p.online; }).length;
    var l = '<div class="card"><h3>Játékosok (' + online + ' jelen / ' + S.players.length + ')</h3><ul class="list">' + S.players.map(function (p) {
      var ctl = '';
      if (me.host) {
        ctl = '<button class="btn sm" data-act="cycleTeam" data-pid="' + p.pid + '" data-team="' + p.team + '"><span class="dot ' + (p.team || 'none') + '"></span>&nbsp;' + (TEAM[p.team] || 'nincs') + '</button>' +
          (p.host ? '' : '<button class="btn sm ghost" data-act="kick" data-pid="' + p.pid + '">✖</button>');
      } else if (p.team) {
        ctl = '<span class="pill ' + p.team + '">' + TEAM[p.team] + '</span>';
      }
      return '<li><span class="dot ' + (p.online ? 'on' : 'off') + '" title="' + (p.online ? 'jelen' : 'nem elérhető') + '"></span><span class="name">' + (p.host ? '👑 ' : '') + esc(p.name) + (p.pid === me.pid ? ' <span class="muted">(te)</span>' : '') + '</span>' + ctl + '</li>';
    }).join('') + '</ul>' + (me.host ? '<p class="small muted">Koppints a csapat gombra a csapat váltásához. A zöld pont jelzi, ki van jelen.</p>' : '') + '</div>';
    setHTML($('playerList'), l);
    setHTML($('myInfo'), '<p style="margin:0"><b>' + esc(me.name) + '</b>' + (me.host ? ' – játékmester 👑' : '') + (me.team ? ' · ' + TEAM[me.team] : '') + '</p>');
    $('leaveBtn').classList.toggle('hidden', !!me.host);
  }

  // ------------------------------------------------------------ click handling
  document.addEventListener('click', function (e) {
    var el = e.target.closest('[data-act]');
    if (!el) return;
    var d = el.dataset;
    switch (d.act) {
      case 'modalYes': closeModal(true); break;
      case 'modalNo': closeModal(false); break;
      case 'askOk': {
        var vals = [], i = 0, f;
        while ((f = $('askF' + i))) { vals.push(f.value); i++; }
        closeModal(vals);
        break;
      }
      case 'toggleSecret':
        missionOpen = !missionOpen;
        clearTimeout(missionTimer);
        if (missionOpen) missionTimer = setTimeout(function () { missionOpen = false; if (S) renderMission(); }, 12000);
        renderMission();
        break;
      case 'toggleNum': numOpen = !numOpen; renderTop10(); break;
      case 'spoiler': showSpoiler = !showSpoiler; renderMission(); break;
      case 'missionRequest': act('missionRequest').then(function (ok) { if (ok) { missionOpen = false; toast('🕵️ Megvan az új küldetésed!'); } }); break;
      case 'missionSkip':
        askConfirm('Biztosan másikat kérsz? Ez a küldetés elveszik.', 'Másikat kérek').then(function (ok) { if (ok) act('missionSkip'); });
        break;
      case 'missionDone': openMissionDone(); break;
      case 'missionDoneSend': {
        var targets = [];
        document.querySelectorAll('#mdTargets input:checked').forEach(function (c) { targets.push(c.value); });
        if (!targets.length) { toast('Jelöld meg, kit sikerült rávenned!', 'err'); return; }
        var note = $('mdNote').value;
        closeModal(null);
        act('missionComplete', { targets: targets, note: note }).then(function (ok) { if (ok) { vibrate(200); beep(1200, 0.12, 0.25); } });
        break;
      }
      case 'missionAdd':
        askText('Saját küldetés hozzáadása', [{ ph: 'Rövid név (pl. A zenész)', max: 40 }, { ph: 'Leírás (pl. Érd el, hogy valaki dúdoljon egy dalt.)', area: true, max: 200 }])
          .then(function (v) { if (v) act('missionAdd', { title: v[0], text: v[1] }); });
        break;
      case 'top10Custom':
        askText('Saját TOP10 téma', [{ ph: 'pl. Mennyire lenne jó ez a vacsora egy randin?', area: true, max: 150 }])
          .then(function (v) { if (v && v[0].trim()) act('top10Topic', { text: v[0] }); });
        break;
      case 'gm': {
        var data = {};
        if (d.team) data.team = d.team;
        if (d.on) data.on = d.on === 'true';
        if (d.guessed) data.guessed = d.guessed === 'true';
        el.disabled = true;
        act(d.type, data).then(function () { el.disabled = false; });
        break;
      }
      case 'gmConfirm':
        askConfirm(d.q, 'Igen', true).then(function (ok) { if (ok) act(d.type); });
        break;
      case 'pickTeam': e.preventDefault(); act('setTeam', { team: d.team }); break;
      case 'cycleTeam': {
        var next = d.team === 'blue' ? 'red' : d.team === 'red' ? '' : 'blue';
        act('setTeam', { pid: d.pid, team: next });
        break;
      }
      case 'kick':
        askConfirm('Kidobod ' + playerName(d.pid) + ' játékost?', 'Kidobás', true).then(function (ok) { if (ok) act('kick', { pid: d.pid }); });
        break;
      case 'riddleCat': riddleCat = d.cat; renderActivity(); break;
      case 'riddleDel': act('riddleDelete', { id: +d.id }); break;
      case 'actDur': act('actSettings', { duration: +d.d, methods: S.activity.methods }); break;
      case 'actMethods': act('actSettings', { duration: S.activity.duration, methods: d.on === 'true' }); break;
    }
  });

  function openMissionDone() {
    var others = S.players.filter(function (p) { return p.pid !== S.me.pid; });
    others.sort(function (a, b) { return (b.online - a.online) || a.name.localeCompare(b.name); });
    modal('<h2>✅ Küldetés teljesítve</h2><p class="muted">Kit sikerült rávenned? Ki mondta ki, ki segített? (Több is jelölhető.)</p>' +
      '<div id="mdTargets">' + (others.length ? others.map(function (p) {
        return '<label class="chk"><input type="checkbox" value="' + p.pid + '"><span class="dot ' + (p.team || 'none') + '"></span>' + esc(p.name) + '</label>';
      }).join('') : '<p class="muted">Még nincs más játékos.</p>') + '</div>' +
      '<textarea id="mdNote" maxlength="200" placeholder="Megjegyzés (nem kötelező) – pl. mi volt a titkos szó, hogyan sikerült"></textarea>' +
      '<div class="btns" style="margin-top:12px"><button class="btn ghost" data-act="modalNo">Mégse</button><button class="btn ok" data-act="missionDoneSend">Beküldöm</button></div>');
  }

  // riddle form
  function sendRiddle() {
    var text = $('riddleText').value.trim();
    if (!text) { toast('Írd be a feladványt!', 'err'); return; }
    act('riddleAdd', { text: text, cat: riddleCat }).then(function (ok) { if (ok) $('riddleText').value = ''; });
  }
  $('riddleSend').onclick = sendRiddle;
  $('riddleText').addEventListener('keydown', function (e) { if (e.key === 'Enter') sendRiddle(); });

  $('leaveBtn').onclick = function () {
    askConfirm('Kilépsz? Ugyanezzel a névvel később visszaléphetsz.', 'Kilépés', true).then(function (ok) {
      if (!ok) return;
      secret = null; S = null; LS.del('pt_secret');
      showJoin();
    });
  };

  if (NativeApp && NativeApp.goHome) {
    $('homeBtn').classList.remove('hidden');
    $('homeBtn').onclick = function () { NativeApp.goHome(); };
  }

  // ------------------------------------------------------------ boot
  (function boot() {
    var qs = new URLSearchParams(location.search);
    var hostSecret = qs.get('host');
    var nameParam = qs.get('name');
    if (hostSecret) {
      secret = hostSecret;
      LS.set('pt_secret', secret);
    } else {
      secret = LS.get('pt_secret');
    }
    if (nameParam) { LS.set('pt_name', nameParam); autoName = nameParam; }
    if (hostSecret || nameParam) history.replaceState(null, '', location.pathname);
    var savedTab = LS.get('pt_tab');
    if (savedTab && $('tab-' + savedTab)) setTab(savedTab);
    if (secret) { showGame(); poll(); }
    else if (nameParam) { showGame(); doJoin(nameParam); }
    else showJoin();
  })();
})();

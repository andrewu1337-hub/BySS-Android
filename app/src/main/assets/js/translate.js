/* Лев: перевод страницы прямо на месте.
   Собирает текст по абзацам, отдаёт браузеру (переводит ML Kit на телефоне)
   и подставляет перевод. Следит за новым текстом (подгрузка при прокрутке).
   Работает даже на сайтах, которые запрещают перевод. */
(function () {
  if (window.__byssTr) return 'ready';

  var SKIP = {SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, CODE: 1, PRE: 1, TEXTAREA: 1, INPUT: 1, SELECT: 1,
    OPTION: 1, SVG: 1, MATH: 1, CANVAS: 1, KBD: 1, SAMP: 1, VAR: 1, TEMPLATE: 1, IFRAME: 1, OBJECT: 1};
  var INLINE = {A: 1, ABBR: 1, B: 1, BDI: 1, BDO: 1, CITE: 1, DFN: 1, EM: 1, FONT: 1, I: 1, LABEL: 1,
    MARK: 1, Q: 1, S: 1, SMALL: 1, SPAN: 1, STRONG: 1, SUB: 1, SUP: 1, TIME: 1, U: 1, BUTTON: 1, DATA: 1};
  var INTERACTIVE = {A: 1, BUTTON: 1, LABEL: 1};
  var LETTER = /[A-Za-zÀ-ɏͰ-ϿЀ-ӿԱ-֏א-׿؀-ۿऀ-ॿ一-鿿぀-ヿ가-힯]/;

  var done = [];          // переведённые текстовые узлы
  var jobs = {};
  var seq = 0;
  var observer = null;
  var timer = null;
  var on = false;

  function skipped(el) {
    for (var e = el; e && e.nodeType === 1; e = e.parentElement) {
      if (SKIP[e.tagName]) return true;
      if (e.isContentEditable) return true;
      if (e !== document.documentElement && e !== document.body && e.getAttribute('translate') === 'no') return true;
    }
    return false;
  }

  function blockOf(node) {
    var b = node.parentElement;
    while (b && INLINE[b.tagName] && b.parentElement && b.parentElement !== document.documentElement) b = b.parentElement;
    return b;
  }

  function collect(root) {
    var groups = [];
    var byEl = new Map();
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = walker.nextNode())) {
      var v = n.nodeValue;
      if (!v || v.length < 2 || !LETTER.test(v)) continue;
      if (n.__byssTx !== undefined && n.nodeValue === n.__byssTx) continue;  // уже переведено
      if (n.__byssTx !== undefined) { n.__byssOrig = n.nodeValue; }           // сайт поменял текст
      if (n.__byssBusy) continue;
      if (!n.parentElement || skipped(n.parentElement)) continue;
      var b = blockOf(n);
      if (!b) continue;
      var g = byEl.get(b);
      if (!g) { g = {el: b, nodes: [], inter: false}; byEl.set(b, g); groups.push(g); }
      g.nodes.push(n);
      if (!g.inter) {
        for (var p = n.parentElement; p && p !== b; p = p.parentElement) {
          if (INTERACTIVE[p.tagName]) { g.inter = true; break; }
        }
      }
    }
    // Сначала то, что на экране, потом ниже, потом выше.
    var h = window.innerHeight || 800;
    groups.forEach(function (g) {
      var r = g.el.getBoundingClientRect();
      g.rank = (r.bottom >= 0 && r.top <= h) ? 0 : (r.top > h ? 1 + r.top / 100000 : 3);
    });
    groups.sort(function (a, b) { return a.rank - b.rank; });
    return groups;
  }

  function send(groups) {
    var batch = [], items = [], chars = 0;
    function flush() {
      if (!items.length) return;
      var id = ++seq;
      jobs[id] = batch;
      try { window.ByssNative.trBatch(id, JSON.stringify(items)); } catch (e) { /* ignore */ }
      batch = []; items = []; chars = 0;
    }
    groups.forEach(function (g) {
      g.nodes.forEach(function (n) { n.__byssBusy = true; });
      var whole = !g.inter && g.nodes.length > 1;
      if (whole) {
        var t = g.nodes.map(function (n) { return n.nodeValue; }).join('');
        items.push({w: 1, t: t.trim()});
        chars += t.length;
      } else {
        var parts = g.nodes.map(function (n) { return n.nodeValue.trim(); });
        items.push({w: 0, t: parts});
        chars += parts.join('').length;
      }
      batch.push({g: g, whole: whole});
      if (items.length >= 25 || chars > 2500) flush();
    });
    flush();
  }

  function keepSpace(orig, tx) {
    var lead = orig.match(/^\s*/)[0], trail = orig.match(/\s*$/)[0];
    return lead + tx + trail;
  }

  function setNode(n, value) {
    if (n.__byssOrig === undefined) n.__byssOrig = n.nodeValue;
    n.__byssTx = value;
    n.nodeValue = value;
    n.__byssBusy = false;
    done.push(n);
  }

  function apply(id, results) {
    var batch = jobs[id];
    delete jobs[id];
    if (!batch || !on) return;
    for (var i = 0; i < batch.length; i++) {
      var b = batch[i], r = results[i], nodes = b.g.nodes;
      if (r === null || r === undefined) {
        nodes.forEach(function (n) { n.__byssBusy = false; });
        continue;
      }
      if (b.whole) {
        var first = true;
        nodes.forEach(function (n) {
          var orig = n.__byssOrig !== undefined ? n.__byssOrig : n.nodeValue;
          if (first) { setNode(n, keepSpace(orig, r)); first = false; } else { setNode(n, orig.match(/^\s*/)[0] ? ' ' : ''); }
        });
      } else {
        nodes.forEach(function (n, k) {
          var orig = n.__byssOrig !== undefined ? n.__byssOrig : n.nodeValue;
          var t = r[k];
          if (t === null || t === undefined || t === '') { n.__byssBusy = false; return; }
          setNode(n, keepSpace(orig, t));
        });
      }
    }
  }

  function fail(id) {
    var batch = jobs[id];
    delete jobs[id];
    if (!batch) return;
    batch.forEach(function (b) { b.g.nodes.forEach(function (n) { n.__byssBusy = false; }); });
  }

  function watch() {
    if (observer) return;
    observer = new MutationObserver(function () {
      if (!on) return;
      clearTimeout(timer);
      timer = setTimeout(function () { if (on) send(collect(document.body)); }, 600);
    });
    observer.observe(document.body, {childList: true, subtree: true, characterData: true});
  }

  window.__byssTr = {
    sample: function () {
      var text = (document.body && document.body.innerText || '').slice(0, 2000);
      return JSON.stringify({lang: document.documentElement.lang || '', text: text});
    },
    start: function () {
      if (!document.body) return 0;
      on = true;
      var g = collect(document.body);
      send(g);
      watch();
      return g.length;
    },
    apply: apply,
    fail: fail,
    restore: function () {
      on = false;
      if (observer) { observer.disconnect(); observer = null; }
      done.forEach(function (n) {
        if (n.__byssOrig !== undefined && n.nodeValue === n.__byssTx) n.nodeValue = n.__byssOrig;
        delete n.__byssOrig; delete n.__byssTx; n.__byssBusy = false;
      });
      done = [];
      jobs = {};
    },
    active: function () { return on; }
  };
  return 'ready';
})();

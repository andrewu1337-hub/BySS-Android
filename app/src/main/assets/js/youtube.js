/* Лев: только для YouTube. Запускается до загрузки страницы.
   bg  — видео не останавливается, когда браузер свёрнут или экран выключен;
   ads — убирает рекламу из ответов плеера, прячет рекламные блоки, пропускает ролики. */
(function () {
  if (window.__levYt) return;
  window.__levYt = true;
  var cfg = {};
  try { cfg = JSON.parse(window.LevNative.cfg()); } catch (e) { cfg = {bg: true, ads: true}; }

  if (cfg.bg) {
    try {
      Object.defineProperty(Document.prototype, 'hidden', {configurable: true, get: function () { return false; }});
      Object.defineProperty(Document.prototype, 'visibilityState', {configurable: true, get: function () { return 'visible'; }});
      Object.defineProperty(Document.prototype, 'webkitHidden', {configurable: true, get: function () { return false; }});
      Object.defineProperty(Document.prototype, 'webkitVisibilityState', {configurable: true, get: function () { return 'visible'; }});
    } catch (e) { /* ignore */ }
    var stop = function (e) { e.stopImmediatePropagation(); };
    ['visibilitychange', 'webkitvisibilitychange'].forEach(function (t) {
      window.addEventListener(t, stop, true);
      document.addEventListener(t, stop, true);
    });
  }

  if (!cfg.ads) return;

  var KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  function prune(o, depth) {
    if (!o || typeof o !== 'object' || depth > 5) return o;
    for (var i = 0; i < KEYS.length; i++) {
      if (Object.prototype.hasOwnProperty.call(o, KEYS[i])) {
        try { delete o[KEYS[i]]; } catch (e) { /* ignore */ }
      }
    }
    if (o.playerResponse) prune(o.playerResponse, depth + 1);
    if (o.response) prune(o.response, depth + 1);
    if (Array.isArray(o)) {
      for (var j = 0; j < o.length && j < 12; j++) prune(o[j], depth + 1);
    }
    return o;
  }

  var origParse = JSON.parse;
  JSON.parse = function () {
    var r = origParse.apply(this, arguments);
    try {
      if (r && typeof r === 'object' && (r.adPlacements || r.playerAds || r.adSlots || r.playerResponse || Array.isArray(r))) prune(r, 0);
    } catch (e) { /* ignore */ }
    return r;
  };
  try {
    var origJson = Response.prototype.json;
    Response.prototype.json = function () {
      return origJson.apply(this, arguments).then(function (r) {
        try { prune(r, 0); } catch (e) { /* ignore */ }
        return r;
      });
    };
  } catch (e) { /* ignore */ }
  try {
    var initial;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      configurable: true,
      get: function () { return initial; },
      set: function (v) { initial = prune(v, 0); }
    });
  } catch (e) { /* ignore */ }

  var css = 'ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,'
    + 'ytm-ad-slot-renderer,ad-slot-renderer,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,'
    + 'ytd-promoted-sparkles-web-renderer,ytd-banner-promo-renderer,ytm-paid-content-overlay-renderer,'
    + '.ytp-ad-overlay-container,#player-ads,ytd-merch-shelf-renderer,ytm-statement-banner-renderer,'
    + 'ytd-statement-banner-renderer,ytm-mealbar-promo-renderer,ytm-companion-slot,'
    + 'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"]'
    + '{display:none!important;}';
  var addCss = function () {
    if (document.getElementById('__lev_yt')) return;
    var st = document.createElement('style');
    st.id = '__lev_yt';
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
  };
  if (document.documentElement) addCss();
  document.addEventListener('DOMContentLoaded', addCss);

  var mutedByUs = false;
  setInterval(function () {
    try {
      var p = document.querySelector('.ad-showing, .ad-interrupting');
      var v = document.querySelector('video');
      if (p && v) {
        if (!v.muted) { v.muted = true; mutedByUs = true; }
        if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.3) v.currentTime = v.duration;
        var b = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button,'
          + ' .ytp-ad-skip-button-container button');
        if (b) b.click();
      } else if (mutedByUs && v) {
        v.muted = false;
        mutedByUs = false;
      }
    } catch (e) { /* ignore */ }
  }, 400);
})();

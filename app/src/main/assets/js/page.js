/* Лев: запускается до загрузки каждой страницы.
   1) прячет рекламные блоки (CSS от блокировщика);
   2) сообщает браузеру, что играет видео/музыка — для плеера в шторке;
   3) выполняет команды плеера: пауза, следующее, предыдущее. */
(function () {
  if (window.__byssInit) return;
  window.__byssInit = true;
  var N = window.ByssNative;
  if (!N) return;

  try {
    var css = N.css(location.hostname);
    if (css) {
      var addCss = function () {
        if (document.getElementById('__byss_css')) return;
        var st = document.createElement('style');
        st.id = '__byss_css';
        st.textContent = css;
        (document.head || document.documentElement).appendChild(st);
      };
      if (document.documentElement) addCss();
      document.addEventListener('DOMContentLoaded', addCss);
    }
  } catch (e) { /* ignore */ }

  if (window.top !== window) return;

  var last = '';
  function meta(field) {
    try {
      var m = navigator.mediaSession && navigator.mediaSession.metadata;
      if (m && m[field]) return String(m[field]);
    } catch (e) { /* ignore */ }
    return '';
  }
  function title() {
    return meta('title') || (document.title || location.hostname).replace(/ - YouTube$/, '');
  }
  function report(playing) {
    try {
      var key = playing + '|' + title();
      if (key === last) return;
      last = key;
      N.media(playing, title(), meta('artist'));
    } catch (e) { /* ignore */ }
  }
  function isMedia(el) {
    return el && el.tagName && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO');
  }
  function isReal(el) {
    return isMedia(el) && !el.muted && el.volume > 0 && !(el.duration > 0 && el.duration < 3);
  }
  function active() {
    var list = document.querySelectorAll('video, audio');
    var best = null;
    for (var i = 0; i < list.length; i++) {
      if (!list[i].paused) return list[i];
      if (!best && list[i].currentSrc) best = list[i];
    }
    return best;
  }

  document.addEventListener('playing', function (e) { if (isReal(e.target)) report(true); }, true);
  document.addEventListener('pause', function (e) { if (isMedia(e.target)) report(false); }, true);
  document.addEventListener('ended', function (e) { if (isMedia(e.target)) report(false); }, true);
  setInterval(function () {
    var v = active();
    if (v && !v.paused && isReal(v)) report(true);
  }, 2000);

  window.__byssMedia = function (cmd) {
    var v = active();
    if (cmd === 'play' && v) { v.play(); return; }
    if (cmd === 'pause' && v) { v.pause(); return; }
    if (cmd === 'toggle' && v) { if (v.paused) v.play(); else v.pause(); return; }
    if (cmd === 'next') {
      var b = document.querySelector('.ytp-next-button, button.player-control-next-button,'
        + ' [aria-label="Next video"], [aria-label="Следующее видео"], [aria-label="Next"]');
      if (b) { b.click(); return; }
      var a = document.querySelector('ytm-compact-video-renderer a[href*="/watch"],'
        + ' ytm-video-with-context-renderer a[href*="/watch"], ytd-compact-video-renderer a#thumbnail');
      if (a) { a.click(); }
      return;
    }
    if (cmd === 'prev') {
      if (v && v.currentTime > 5) v.currentTime = 0; else history.back();
    }
  };
})();

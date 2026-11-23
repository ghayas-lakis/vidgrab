// VidGrab's video finder. BrowserActivity runs it in every page; it reports to VidGrabFinder.report()
// what the page shows: its address and title, the main video on screen (is it an ad? does it have its
// own link?), direct video sources, and links to videos. It only reports when something changed.
(function () {
  if (window.__vgFinder || window.top !== window) return;
  window.__vgFinder = true;

  // A line that marks an ad next to a video (TikTok, Instagram, Facebook, YouTube; several languages).
  var AD = /^(sponsored|ad|ads|promoted|promotional content|advertisement|paid partnership|publicité|sponsorisé|sponsorisée|contenu sponsorisé|patrocinado|publicidad|anuncio|anzeige|werbung|gesponsert|sponsorlu|reklam|إعلان|اعلان|ممول|مُموَّل|مموّل|محتوى مدفوع)$/i;
  // Links worth checking (VidGrab decides in Kotlin which ones really are videos).
  var LINK = /\/video\/|\/watch|\/shorts\/|\/reels?\/|\/p\/|\/tv\/|\/status\/|\/videos\/|youtu\.be\/|dai\.ly\/|vimeo\.com\/\d/;
  var TIKTOK_VIDEO = /\/video\/\d+/;

  var last = '', n = 0, links = [];
  var lastEl = null, lastKey = '', lastAd = false, lastNear = '';

  function shown(el) {
    var r = el.getBoundingClientRect();
    var w = Math.min(r.right, innerWidth) - Math.max(r.left, 0);
    var h = Math.min(r.bottom, innerHeight) - Math.max(r.top, 0);
    return w > 0 && h > 0 ? w * h : 0;
  }

  // The video that takes most of the screen, and how much of it (0..1).
  function mainVideo() {
    var vs = document.getElementsByTagName('video'), best = null, ba = 0;
    for (var i = 0; i < vs.length; i++) {
      var a = shown(vs[i]);
      if (a > ba) { ba = a; best = vs[i]; }
    }
    return best ? { el: best, share: ba / Math.max(1, innerWidth * innerHeight) } : null;
  }

  function isAd(el) {
    for (var p = el, k = 0; p && k < 8; p = p.parentElement, k++) {
      var t = p.innerText;
      if (!t) continue;
      if (t.length > 3000) break;
      var ls = t.split('\n');
      for (var i = 0; i < ls.length; i++) if (AD.test(ls[i].trim())) return true;
    }
    return false;
  }

  // The video's own link, for feeds whose address doesn't change: the closest single /video/<id> link.
  function nearLink(el) {
    for (var p = el, k = 0; p && k < 7; p = p.parentElement, k++) {
      if (!p.querySelectorAll) continue;
      var as = p.querySelectorAll('a[href]'), found = {}, count = 0, one = '';
      if (as.length > 40) break;
      for (var i = 0; i < as.length; i++) {
        var h = as[i].href;
        if (TIKTOK_VIDEO.test(h) && !found[h]) { found[h] = 1; count++; one = h; }
      }
      if (count === 1) return one;
      if (count > 1) return '';   // several videos around it: can't tell which one is on screen
    }
    return '';
  }

  function tick() {
    try {
      n++;
      var out = { page: location.href, title: document.title || '' };
      var mv = mainVideo();
      if (mv && mv.share > 0.2) {
        var v = mv.el;
        var key = (v.currentSrc || '') + '|' + (v.getAttribute('src') || '') + '|' + (v.getAttribute('poster') || '');
        if (v !== lastEl || key !== lastKey) {
          lastEl = v; lastKey = key; lastAd = isAd(v); lastNear = nearLink(v);
        }
        out.video = true;
        out.ad = lastAd;
        out.near = mv.share > 0.45 ? lastNear : '';
      }
      var media = [], vs = document.querySelectorAll('video, video source');
      for (var i = 0; i < vs.length && media.length < 10; i++) {
        var s = vs[i].currentSrc || vs[i].src || '';
        if (/^https?:/.test(s) && media.indexOf(s) < 0) media.push(s);
      }
      out.media = media;
      if (n % 3 === 1) {             // links change less often, and there can be many
        var as = document.querySelectorAll('a[href]'), ls = [], seen = {};
        for (var j = 0; j < as.length && ls.length < 300; j++) {
          var h = as[j].href;
          if (!h || seen[h] || !LINK.test(h)) continue;
          seen[h] = 1;
          var t = (as[j].getAttribute('aria-label') || as[j].title || as[j].textContent || '')
            .replace(/\s+/g, ' ').trim().slice(0, 120);
          ls.push([h, t]);
        }
        links = ls;
      }
      out.links = links;
      var str = JSON.stringify(out);
      if (str !== last) { last = str; VidGrabFinder.report(str); }
    } catch (e) {}
  }

  setInterval(tick, 800);
  tick();
})();

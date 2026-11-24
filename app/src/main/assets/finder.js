// VidGrab's video finder. BrowserActivity runs it in every page; it reports to VidGrabFinder.report()
// what the page shows: its address and title, the main video on screen (is it an ad? does it have its
// own link? how long is it?), direct video sources, and links to videos with their title and length.
// It only reports when something changed.
(function () {
  if (window.__vgFinder || window.top !== window) return;
  window.__vgFinder = true;

  // A line that marks an ad next to a video (TikTok, Instagram, Facebook, YouTube; several languages).
  var AD = /^(sponsored|ad|ads|promoted|promotional content|advertisement|paid partnership|publicité|sponsorisé|sponsorisée|contenu sponsorisé|patrocinado|publicidad|anuncio|anzeige|werbung|gesponsert|sponsorlu|reklam|إعلان|اعلان|ممول|مُموَّل|مموّل|محتوى مدفوع)$/i;
  // Links worth checking (VidGrab decides in Kotlin which ones really are videos).
  var LINK = /\/video\/|\/watch|\/shorts\/|\/reels?\/|\/p\/|\/tv\/|\/status\/|\/videos\/|youtu\.be\/|dai\.ly\/|vimeo\.com\/\d/;
  // A video's own link inside a feed (nearLink).
  var OWN = /\/video\/\d+|\/shorts\/[\w-]{11}|\/reels?\/[\w-]{6,}|\/status\/\d+/;
  var CLOCK = /^(?:(\d{1,2}):)?(\d{1,3}):(\d{2})$/;                     // 0:45, 12:34, 1:02:03
  var NOISE = /^[\d\s.,:]*[kmb]?$|^(now playing|pinned|new|live|shorts|watch|video card|play|tap to unmute|open app)$|^[\d.,]+[kmb]? (repl|repost|like|view|bookmark)/i;   // not a title

  var last = '', n = 0, links = [], first = location.href;
  var lastEl = null, lastKey = '', lastHref = '', lastAd = false, lastNear = '';
  var infos = {};                        // video id -> [seconds, bytes], read from the page's own data
  var cache = new WeakMap();             // link element -> what is known about it

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

  // The video's own link, for feeds whose address doesn't change: the closest single video link.
  function nearLink(el) {
    for (var p = el, k = 0; p && k < 7; p = p.parentElement, k++) {
      if (!p.querySelectorAll) continue;
      var as = p.querySelectorAll('a[href]'), found = {}, count = 0, one = '';
      if (as.length > 40) break;
      for (var i = 0; i < as.length; i++) {
        var h = as[i].href, id = vid(h);
        if (OWN.test(h) && !found[id]) { found[id] = 1; count++; one = h; }
      }
      if (count === 1) return one;
      if (count > 1) return '';   // several videos around it: can't tell which one is on screen
    }
    return '';
  }

  // ---------- lengths ----------
  // The part of a link that names the video.
  function vid(h) {
    var m = /[?&]v=([\w-]+)/.exec(h) || /\/(?:video|videos|shorts|reels?|status|p|tv)\/([\w-]+)/.exec(h) ||
      /^https?:\/\/[^\/]+\/(?:.*\/)?([\w-]+)/.exec(h.split(/[?#]/)[0]);
    return m ? m[1] : h;
  }

  function clock(t) {
    var m = CLOCK.exec(t);
    return m && +m[3] < 60 ? (+(m[1] || 0)) * 3600 + (+m[2]) * 60 + (+m[3]) : 0;
  }

  // "PT1H2M3S" or plain seconds
  function iso(t) {
    t = String(t || '').trim();
    if (/^\d+(\.\d+)?$/.test(t)) return Math.round(+t);
    var m = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?$/i.exec(t);
    return m ? Math.round((+(m[1] || 0)) * 86400 + (+(m[2] || 0)) * 3600 + (+(m[3] || 0)) * 60 + (+(m[4] || 0))) : 0;
  }

  function texts(el, max) {
    var w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT), out = [], x;
    while (out.length < max && (x = w.nextNode())) {
      var tag = x.parentNode && x.parentNode.nodeName;
      if (tag === 'STYLE' || tag === 'SCRIPT') continue;
      var t = x.nodeValue.replace(/\s+/g, ' ').trim();
      if (t) out.push(t);
    }
    return out;
  }

  // The length written on a video's thumbnail: in the link itself, or around it as long as the
  // surroundings are about this one video only.
  function badge(a, id) {
    for (var p = a, k = 0; p && k < 6; p = p.parentElement, k++) {
      if (k > 0) {
        var as = p.getElementsByTagName('a');
        if (as.length > 12) return 0;
        for (var i = 0; i < as.length; i++) if (LINK.test(as[i].href) && vid(as[i].href) !== id) return 0;
      }
      var ts = texts(p, 25);
      for (var j = 0; j < ts.length; j++) { var d = clock(ts[j]); if (d) return d; }
    }
    return 0;
  }

  // Many sites keep each video's details (with its length) in the data behind what they draw.
  function sec(x) { x = +x; return x > 0 && x < 360000 ? Math.round(x) : 0; }

  function learn(o, depth, st) {
    if (!o || typeof o !== 'object' || st.left <= 0 || st.seen.has(o) || o instanceof Node) return;
    st.left--;
    st.seen.add(o);
    var i;
    if (Array.isArray(o)) {
      for (i = 0; i < o.length && i < 500; i++) learn(o[i], depth, st);
      return;
    }
    var id = o.id || o.xid || o.code || o.videoId || o.video_id;
    if (id && (typeof id === 'string' || typeof id === 'number')) {
      var v = o.video && typeof o.video === 'object' ? o.video : o;
      var d = sec(v.duration) || sec(v.video_duration) || sec(v.lengthSeconds) ||
        sec((v.playable_duration_in_ms || v.duration_ms || 0) / 1000);
      if (d) { infos[id] = [d, +v.size > 10000 ? +v.size : 0]; return; }
    }
    if (depth >= 5) return;
    var ks = Object.keys(o);
    for (i = 0; i < ks.length && i < 60; i++) {
      var k = ks[i];
      if (k[0] === '_' || /^(children|return|child|sibling|alternate|stateNode|ref|style|className)$/.test(k)) continue;
      var x;
      try { x = o[k]; } catch (e) { continue; }
      if (x && typeof x === 'object') learn(x, depth + 1, st);
    }
  }

  function dig(el, id) {
    try {
      var f = null, p = el;
      for (var u = 0; p && !f && u < 4; u++, p = p.parentElement) {
        var ks = Object.keys(p);
        for (var i = 0; i < ks.length; i++) if (ks[i].indexOf('__reactFiber$') === 0) { f = p[ks[i]]; break; }
      }
      for (var hop = 0; f && hop < 22 && !infos[id]; hop++, f = f.return) {
        if (f.memoizedProps) learn(f.memoizedProps, 0, { left: 3000, seen: new Set() });
      }
    } catch (e) {}
  }

  // The length the page states about itself (for sites VidGrab doesn't know).
  function pageLength() {
    var m = document.querySelector('meta[property="og:video:duration"],meta[property="video:duration"],meta[name="duration"]');
    var d = m ? iso(m.content) : 0;
    if (d) return d;
    var ps = document.querySelectorAll('[itemprop="duration"]');
    if (ps.length === 1) d = iso(ps[0].getAttribute('content') || ps[0].getAttribute('datetime'));
    if (d) return d;
    var ss = document.querySelectorAll('script[type="application/ld+json"]'), all = [];
    for (var i = 0; i < ss.length && i < 10; i++) {
      var t = ss[i].textContent;
      if (t.length > 200000 || t.indexOf('VideoObject') < 0) continue;
      var g = t.match(/"duration"\s*:\s*"([^"]+)"/g) || [];
      for (var j = 0; j < g.length; j++) all.push(iso(/"([^"]+)"$/.exec(g[j])[1]));
    }
    return all.length === 1 ? all[0] : 0;      // several videos described: can't tell which is this one
  }

  // ---------- links ----------
  function titleOf(a) {
    var c = [a.getAttribute('aria-label'), a.title].concat(texts(a, 8));
    var img = a.querySelector('img[alt]');
    if (img) c.push(img.alt);
    for (var i = 0; i < c.length; i++) {
      var t = (c[i] || '').replace(/\s+/g, ' ').trim();
      if (t.length > 2 && !NOISE.test(t) && !clock(t)) return t.slice(0, 120);
    }
    return '';
  }

  function readLinks() {
    var as = document.querySelectorAll('a[href]'), by = {}, out = [], ids = [], digs = 3;
    for (var j = 0; j < as.length && out.length < 600; j++) {
      var a = as[j], h = a.href;
      if (!h || h.indexOf('http') !== 0 || !LINK.test(h)) continue;
      var c = cache.get(a);
      if (!c || c.h !== h) { c = { h: h, id: vid(h), t: '', d: 0, tries: 0 }; cache.set(a, c); }
      if ((!c.t || !c.d) && c.tries < 6) {     // parts of a page can arrive later: look again a few times
        var dug = true;
        if (!c.t) c.t = titleOf(a);
        if (!c.d) c.d = badge(a, c.id);
        if (!c.d && !infos[c.id]) { dug = digs > 0; if (dug) { digs--; dig(a, c.id); } }
        if (dug) c.tries++;
      }
      var e = by[h];                           // a thumbnail and a title often are two links to one video
      if (!e) { e = by[h] = [h, '', 0, 0]; out.push(e); ids.push(c.id); }
      if (!e[1]) e[1] = c.t;
      if (!e[2]) e[2] = c.d;
    }
    for (var k = 0; k < out.length; k++) {
      var f = infos[ids[k]];
      if (f) { out[k][2] = f[0]; out[k][3] = f[1]; }
    }
    return out;
  }

  function tick() {
    try {
      n++;
      var out = { page: location.href, title: document.title || '' };
      var mv = mainVideo(), here = vid(location.href);
      if (mv && mv.share > 0.2) {
        var v = mv.el;
        var key = (v.currentSrc || '') + '|' + (v.getAttribute('src') || '') + '|' + (v.getAttribute('poster') || '');
        // the address and the video both stayed: they belong together (while swiping, one changes first)
        out.settled = v === lastEl && key === lastKey && out.page === lastHref;
        if (v !== lastEl || key !== lastKey) {
          lastEl = v; lastKey = key; lastAd = isAd(v); lastNear = nearLink(v);
          if (!infos[here]) dig(v, here);
        }
        out.video = true;
        out.ad = lastAd;
        out.near = mv.share > 0.45 ? lastNear : '';
        var nf = out.near && infos[vid(out.near)];
        if (nf) { out.nearDur = nf[0]; out.nearSize = nf[1]; }
        // not while an ad plays before the video
        if (out.settled && !lastAd && isFinite(v.duration) && !document.querySelector('.ad-showing,.ad-interrupting'))
          out.dur = sec(v.duration);
      }
      lastHref = out.page;
      if (infos[here]) { out.pageDur = infos[here][0]; out.pageSize = infos[here][1]; }
      else if (out.video && out.page === first) out.metaDur = pageLength();   // (stale after that)
      var media = [], vs = document.querySelectorAll('video, video source');
      for (var i = 0; i < vs.length && media.length < 10; i++) {
        var s = vs[i].currentSrc || vs[i].src || '';
        if (/^https?:/.test(s) && media.indexOf(s) < 0) media.push(s);
      }
      out.media = media;
      if (n % 3 === 1) links = readLinks();     // links change less often, and there can be many
      out.links = links;
      var str = JSON.stringify(out);
      if (str !== last) { last = str; VidGrabFinder.report(str); }
    } catch (e) {}
  }

  setInterval(tick, 800);
  tick();
})();

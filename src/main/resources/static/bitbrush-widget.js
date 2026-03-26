(function () {
  'use strict';

  // ── Config ──────────────────────────────────────────────────────────────────
  var cfg = window.bitbrushConfig || {};
  var SERVER = cfg.server || '';
  var CONTAINER_SEL = cfg.container || '#bitbrush-container';
  var TURNSTILE_SITE_KEY = cfg.turnstileSiteKey || '';

  var WIDTH = 250;
  var HEIGHT = 250;
  var SCALE = 2;
  var LS_PREFIX = 'bitbrush_widget_';

  // ── CSS injection ───────────────────────────────────────────────────────────
  var style = document.createElement('style');
  style.textContent = [
    '.bitbrush-widget { font-family: "Share Tech Mono", "Fira Code", monospace; color: #cccccc; background: #0a0a0a; border: 1px solid #1a1a1a; border-radius: 8px; padding: 12px; max-width: 600px; box-sizing: border-box; position: relative; }',
    '.bitbrush-widget * { box-sizing: border-box; }',
    '.bitbrush-widget canvas { display: block; width: 100%; height: auto; image-rendering: pixelated; image-rendering: crisp-edges; cursor: crosshair; touch-action: none; border-radius: 4px; }',

    // Status bar
    '.bbw-status { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 12px; }',
    '.bbw-status-dot { width: 8px; height: 8px; border-radius: 50%; background: #cc0000; flex-shrink: 0; }',
    '.bbw-status-text { opacity: 0.7; }',

    // Bank
    '.bbw-bank { display: flex; align-items: center; gap: 8px; margin: 8px 0; font-size: 13px; }',
    '.bbw-bank-label { white-space: nowrap; min-width: 70px; color: #00cccc; }',
    '.bbw-bank-bar { flex: 1; height: 6px; background: #1a1a1a; border-radius: 3px; overflow: hidden; }',
    '.bbw-bank-fill { height: 100%; width: 0%; background: #00cccc; border-radius: 3px; transition: width 0.3s linear; }',
    '.bbw-bank-fill.progress-indeterminate { width: 100% !important; background: repeating-linear-gradient(90deg, #00cccc 0%, #006666 25%, #00cccc 50%); background-size: 200% 100%; animation: bbw-barber 1.5s linear infinite; }',
    '@keyframes bbw-barber { 0% { background-position: 200% 0; } 100% { background-position: 0% 0; } }',

    // Palette
    '.bbw-palette-wrap { margin: 8px 0; }',
    '.bbw-palette { display: flex; flex-wrap: wrap; gap: 2px; }',
    '.bbw-swatch { width: 20px; height: 20px; border-radius: 3px; cursor: pointer; border: 2px solid transparent; flex-shrink: 0; transition: border-color 0.15s, box-shadow 0.15s; }',
    '.bbw-swatch:hover { transform: scale(1.15); }',
    '.bbw-swatch.active { border-color: #ffffff; box-shadow: 0 0 8px rgba(0,204,204,0.3); }',
    '.bbw-swatch.eraser-swatch { background: #1a1a1a; position: relative; }',
    '.bbw-swatch.eraser-swatch::after { content: ""; position: absolute; top: 50%; left: 25%; right: 25%; height: 2px; background: #ff4444; transform: rotate(-45deg); }',
    '.bbw-more-btn { background: none; border: 1px solid #333; color: #888; font-size: 11px; padding: 2px 8px; border-radius: 3px; cursor: pointer; margin-top: 4px; font-family: inherit; }',
    '.bbw-more-btn:hover { border-color: #555; color: #ccc; }',

    // Username overlay
    '.bbw-overlay { position: absolute; inset: 0; background: rgba(0,0,0,0.85); display: flex; align-items: center; justify-content: center; border-radius: 8px; z-index: 10; }',
    '.bbw-modal { text-align: center; padding: 24px; }',
    '.bbw-modal h3 { margin: 0 0 12px; font-size: 16px; color: #00cccc; }',
    '.bbw-modal input { background: #111; border: 1px solid #333; color: #ccc; padding: 8px 12px; border-radius: 4px; font-family: inherit; font-size: 14px; width: 200px; outline: none; }',
    '.bbw-modal input:focus { border-color: #00cccc; }',
    '.bbw-modal button { background: #00cccc; color: #000; border: none; padding: 8px 16px; border-radius: 4px; cursor: pointer; font-family: inherit; font-size: 13px; margin-top: 8px; margin-left: 4px; }',
    '.bbw-modal button:hover { background: #00eedd; }',
    '.bbw-modal-error { color: #ff6666; font-size: 12px; margin-top: 8px; min-height: 16px; }',

    // Footer
    '.bbw-footer { margin-top: 8px; text-align: center; font-size: 11px; }',
    '.bbw-footer a { color: #00cccc; text-decoration: none; }',
    '.bbw-footer a:hover { text-decoration: underline; }',

    // Zoom indicator
    '.bbw-zoom-badge { position: absolute; top: 36px; right: 12px; background: rgba(0,204,204,0.15); color: #00cccc; font-size: 11px; padding: 2px 6px; border-radius: 3px; cursor: pointer; opacity: 0; transition: opacity 0.3s; z-index: 5; user-select: none; }',
    '.bbw-zoom-badge.visible { opacity: 1; }',
    '.bbw-zoom-badge:hover { background: rgba(0,204,204,0.3); }'
  ].join('\n');
  document.head.appendChild(style);

  // ── Palette (same 216 web-safe colors as PaletteConfig) ─────────────────────
  var PALETTE = [
    "#000000","#000033","#000066","#000099","#0000CC","#0000FF",
    "#003300","#003333","#003366","#003399","#0033CC","#0033FF",
    "#006600","#006633","#006666","#006699","#0066CC","#0066FF",
    "#009900","#009933","#009966","#009999","#0099CC","#0099FF",
    "#00CC00","#00CC33","#00CC66","#00CC99","#00CCCC","#00CCFF",
    "#00FF00","#00FF33","#00FF66","#00FF99","#00FFCC","#00FFFF",
    "#330000","#330033","#330066","#330099","#3300CC","#3300FF",
    "#333300","#333333","#333366","#333399","#3333CC","#3333FF",
    "#336600","#336633","#336666","#336699","#3366CC","#3366FF",
    "#339900","#339933","#339966","#339999","#3399CC","#3399FF",
    "#33CC00","#33CC33","#33CC66","#33CC99","#33CCCC","#33CCFF",
    "#33FF00","#33FF33","#33FF66","#33FF99","#33FFCC","#33FFFF",
    "#660000","#660033","#660066","#660099","#6600CC","#6600FF",
    "#663300","#663333","#663366","#663399","#6633CC","#6633FF",
    "#666600","#666633","#666666","#666699","#6666CC","#6666FF",
    "#669900","#669933","#669966","#669999","#6699CC","#6699FF",
    "#66CC00","#66CC33","#66CC66","#66CC99","#66CCCC","#66CCFF",
    "#66FF00","#66FF33","#66FF66","#66FF99","#66FFCC","#66FFFF",
    "#990000","#990033","#990066","#990099","#9900CC","#9900FF",
    "#993300","#993333","#993366","#993399","#9933CC","#9933FF",
    "#996600","#996633","#996666","#996699","#9966CC","#9966FF",
    "#999900","#999933","#999966","#999999","#9999CC","#9999FF",
    "#99CC00","#99CC33","#99CC66","#99CC99","#99CCCC","#99CCFF",
    "#99FF00","#99FF33","#99FF66","#99FF99","#99FFCC","#99FFFF",
    "#CC0000","#CC0033","#CC0066","#CC0099","#CC00CC","#CC00FF",
    "#CC3300","#CC3333","#CC3366","#CC3399","#CC33CC","#CC33FF",
    "#CC6600","#CC6633","#CC6666","#CC6699","#CC66CC","#CC66FF",
    "#CC9900","#CC9933","#CC9966","#CC9999","#CC99CC","#CC99FF",
    "#CCCC00","#CCCC33","#CCCC66","#CCCC99","#CCCCCC","#CCCCFF",
    "#CCFF00","#CCFF33","#CCFF66","#CCFF99","#CCFFCC","#CCFFFF",
    "#FF0000","#FF0033","#FF0066","#FF0099","#FF00CC","#FF00FF",
    "#FF3300","#FF3333","#FF3366","#FF3399","#FF33CC","#FF33FF",
    "#FF6600","#FF6633","#FF6666","#FF6699","#FF66CC","#FF66FF",
    "#FF9900","#FF9933","#FF9966","#FF9999","#FF99CC","#FF99FF",
    "#FFCC00","#FFCC33","#FFCC66","#FFCC99","#FFCCCC","#FFCCFF",
    "#FFFF00","#FFFF33","#FFFF66","#FFFF99","#FFFFCC","#FFFFFF"
  ];

  // ── HSL sorting (same algorithm as index.html) ──────────────────────────────
  function hexToHsl(hex) {
    var r = parseInt(hex.slice(1, 3), 16) / 255;
    var g = parseInt(hex.slice(3, 5), 16) / 255;
    var b = parseInt(hex.slice(5, 7), 16) / 255;
    var max = Math.max(r, g, b), min = Math.min(r, g, b);
    var h, s, l = (max + min) / 2;
    if (max === min) {
      h = s = 0;
    } else {
      var d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      switch (max) {
        case r: h = (g - b) / d + (g < b ? 6 : 0); break;
        case g: h = (b - r) / d + 2; break;
        case b: h = (r - g) / d + 4; break;
      }
      h /= 6;
    }
    return [h * 360, s * 100, l * 100];
  }

  function isLightColor(hex) {
    var r = parseInt(hex.slice(1, 3), 16);
    var g = parseInt(hex.slice(3, 5), 16);
    var b = parseInt(hex.slice(5, 7), 16);
    return (r + g + b) / 3 > 204;
  }

  function sortPaletteByHsl(palette) {
    var withHsl = palette.map(function (hex, i) {
      return { hex: hex, originalIndex: i, hsl: hexToHsl(hex) };
    });
    var achromatic = withHsl.filter(function (c) { return c.hsl[1] === 0; });
    var nearAchromatic = withHsl.filter(function (c) { return c.hsl[1] > 0 && c.hsl[1] <= 15; });
    var chromatic = withHsl.filter(function (c) { return c.hsl[1] > 15; });

    achromatic.sort(function (a, b) { return a.hsl[2] - b.hsl[2]; });
    nearAchromatic.sort(function (a, b) { return a.hsl[2] - b.hsl[2]; });
    chromatic.sort(function (a, b) {
      var hueBinA = Math.floor(a.hsl[0] / 30);
      var hueBinB = Math.floor(b.hsl[0] / 30);
      if (hueBinA !== hueBinB) return hueBinA - hueBinB;
      return a.hsl[2] - b.hsl[2];
    });

    var eraser = withHsl.filter(function (c) { return c.originalIndex === 0; });
    var achromaticNoEraser = achromatic.filter(function (c) { return c.originalIndex !== 0; });
    return chromatic.concat(nearAchromatic, achromaticNoEraser, eraser);
  }

  // ── Curated 24-color subset for compact palette ─────────────────────────────
  var CURATED_INDICES = [
    186, 180, 174, 150, 144, 138,  // reds / oranges
    108, 102,  96,  72,  66,  60,  // greens
     30,  24,   5,   4,   3,  35,  // blues
    185, 179, 173, 143, 137,       // purples / pinks
    215                             // white
  ];

  // ── Script loader ───────────────────────────────────────────────────────────
  function loadScript(src, timeout) {
    return new Promise(function (resolve, reject) {
      var script = document.createElement('script');
      script.src = src;
      script.onload = resolve;
      script.onerror = function () { reject(new Error('Failed to load ' + src)); };
      document.head.appendChild(script);
      if (timeout) {
        setTimeout(function () { reject(new Error('Timeout loading ' + src)); }, timeout);
      }
    });
  }

  // ── Hex to RGB ──────────────────────────────────────────────────────────────
  function hexToRgb(hex) {
    var n = parseInt(hex.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }

  // ── Bresenham line interpolation ────────────────────────────────────────────
  function bresenhamLine(x0, y0, x1, y1) {
    var points = [];
    var dx = Math.abs(x1 - x0);
    var sx = x0 < x1 ? 1 : -1;
    var dy = -Math.abs(y1 - y0);
    var sy = y0 < y1 ? 1 : -1;
    var error = dx + dy;
    while (true) {
      points.push({ x: x0, y: y0 });
      if (x0 === x1 && y0 === y1) break;
      var e2 = 2 * error;
      if (e2 >= dy) { error += dy; x0 += sx; }
      if (e2 <= dx) { error += dx; y0 += sy; }
    }
    return points;
  }

  // ── Main init ───────────────────────────────────────────────────────────────
  function init() {
    var containerEl = document.querySelector(CONTAINER_SEL);
    if (!containerEl) {
      console.error('[BitBrush Widget] Container not found: ' + CONTAINER_SEL);
      return;
    }

    // ── DOM construction ──────────────────────────────────────────────────────
    var root = document.createElement('div');
    root.className = 'bitbrush-widget';

    // Status bar
    var statusBar = document.createElement('div');
    statusBar.className = 'bbw-status';
    var statusDot = document.createElement('div');
    statusDot.className = 'bbw-status-dot';
    var statusText = document.createElement('span');
    statusText.className = 'bbw-status-text';
    statusText.textContent = 'connecting...';
    statusBar.appendChild(statusDot);
    statusBar.appendChild(statusText);
    root.appendChild(statusBar);

    // Canvas
    var canvas = document.createElement('canvas');
    canvas.width = WIDTH * SCALE;
    canvas.height = HEIGHT * SCALE;
    root.appendChild(canvas);

    var ctx = canvas.getContext('2d');

    // Offscreen buffer holds the full pixel grid; visible canvas renders a viewport into it
    var bufferCanvas = document.createElement('canvas');
    bufferCanvas.width = WIDTH * SCALE;
    bufferCanvas.height = HEIGHT * SCALE;
    var bufferCtx = bufferCanvas.getContext('2d');
    var imageData = bufferCtx.createImageData(WIDTH * SCALE, HEIGHT * SCALE);
    for (var i = 3; i < imageData.data.length; i += 4) {
      imageData.data[i] = 255;
    }
    bufferCtx.putImageData(imageData, 0, 0);

    // Zoom indicator badge (clickable to reset)
    var zoomBadge = document.createElement('div');
    zoomBadge.className = 'bbw-zoom-badge';
    zoomBadge.title = 'Reset zoom';
    root.appendChild(zoomBadge);

    // Initial render of visible canvas
    ctx.drawImage(bufferCanvas, 0, 0);

    // Bank display
    var bankRow = document.createElement('div');
    bankRow.className = 'bbw-bank';
    var bankLabel = document.createElement('span');
    bankLabel.className = 'bbw-bank-label';
    bankLabel.textContent = '5/25';
    var bankBarOuter = document.createElement('div');
    bankBarOuter.className = 'bbw-bank-bar';
    var bankFill = document.createElement('div');
    bankFill.className = 'bbw-bank-fill';
    bankBarOuter.appendChild(bankFill);
    bankRow.appendChild(bankLabel);
    bankRow.appendChild(bankBarOuter);
    root.appendChild(bankRow);

    // Palette
    var paletteWrap = document.createElement('div');
    paletteWrap.className = 'bbw-palette-wrap';
    var paletteEl = document.createElement('div');
    paletteEl.className = 'bbw-palette';
    paletteWrap.appendChild(paletteEl);

    var moreBtn = document.createElement('button');
    moreBtn.className = 'bbw-more-btn';
    moreBtn.textContent = 'More colors...';
    paletteWrap.appendChild(moreBtn);
    root.appendChild(paletteWrap);

    // Footer
    var footer = document.createElement('div');
    footer.className = 'bbw-footer';
    var fullLink = document.createElement('a');
    fullLink.href = SERVER || 'https://bitbrush.fly.dev';
    fullLink.target = '_blank';
    fullLink.rel = 'noopener';
    fullLink.textContent = 'Open full canvas \u2197';
    footer.appendChild(fullLink);
    root.appendChild(footer);

    // Turnstile container (invisible)
    var turnstileContainer = document.createElement('div');
    turnstileContainer.id = 'bbw-turnstile';
    turnstileContainer.style.display = 'none';
    root.appendChild(turnstileContainer);

    containerEl.appendChild(root);

    // ── State ─────────────────────────────────────────────────────────────────
    var selectedPaletteIndex = 0;
    var activeSwatch = null;
    var localBalance = 5;
    var localMaxBalance = 25;
    var showingAllColors = false;
    var stompClient = null;
    var turnstileToken = null;
    var turnstileWidgetId = null;

    // Progress bar state
    var progressLockedUntil = 0;
    var earnCycleDuration = 0;
    var earnCycleKnown = false;
    var bankInitialized = false;

    // ── Viewport state (zoom & pan) ──────────────────────────────────
    var viewZoom = 1;
    var viewPanX = WIDTH / 2;   // logical X at center of viewport
    var viewPanY = HEIGHT / 2;  // logical Y at center of viewport
    var MIN_ZOOM = 1;
    var MAX_ZOOM = 12;
    var activePointers = new Map();
    var gestureState = null;    // non-null during pinch/pan gesture

    // ── Viewport rendering ────────────────────────────────────────────────────
    function flushBuffer() {
      bufferCtx.putImageData(imageData, 0, 0);
    }

    function renderViewport() {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.save();
      ctx.imageSmoothingEnabled = false;
      ctx.translate(canvas.width / 2, canvas.height / 2);
      ctx.scale(viewZoom, viewZoom);
      ctx.translate(-viewPanX * SCALE, -viewPanY * SCALE);
      ctx.drawImage(bufferCanvas, 0, 0);
      ctx.restore();
      // Update zoom badge
      if (viewZoom > 1.05) {
        zoomBadge.textContent = viewZoom.toFixed(1) + 'x';
        zoomBadge.classList.add('visible');
      } else {
        zoomBadge.classList.remove('visible');
      }
    }

    function clampPan() {
      if (viewZoom <= 1) {
        viewPanX = WIDTH / 2;
        viewPanY = HEIGHT / 2;
        return;
      }
      var halfVisW = (WIDTH / 2) / viewZoom;
      var halfVisH = (HEIGHT / 2) / viewZoom;
      viewPanX = Math.max(halfVisW, Math.min(WIDTH - halfVisW, viewPanX));
      viewPanY = Math.max(halfVisH, Math.min(HEIGHT - halfVisH, viewPanY));
    }

    function zoomAtPoint(clientX, clientY, factor) {
      var rect = canvas.getBoundingClientRect();
      var css2c = canvas.width / rect.width;
      var cx = (clientX - rect.left) * css2c;
      var cy = (clientY - rect.top) * css2c;
      // Logical coord under cursor before zoom
      var bufX = (cx - canvas.width / 2) / viewZoom + viewPanX * SCALE;
      var bufY = (cy - canvas.height / 2) / viewZoom + viewPanY * SCALE;
      var newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, viewZoom * factor));
      // Adjust pan so same logical coord stays under cursor
      viewPanX = (bufX - (cx - canvas.width / 2) / newZoom) / SCALE;
      viewPanY = (bufY - (cy - canvas.height / 2) / newZoom) / SCALE;
      viewZoom = newZoom;
      clampPan();
      renderViewport();
    }

    function resetZoom() {
      viewZoom = 1;
      viewPanX = WIDTH / 2;
      viewPanY = HEIGHT / 2;
      renderViewport();
    }

    zoomBadge.addEventListener('click', function (e) {
      e.stopPropagation();
      resetZoom();
    });

    // ── Canvas rendering ──────────────────────────────────────────────────────
    function paintPixelInBuffer(x, y, hexColor, flush) {
      var rgb = hexToRgb(hexColor);
      for (var dy = 0; dy < SCALE; dy++) {
        for (var dx = 0; dx < SCALE; dx++) {
          var idx = ((y * SCALE + dy) * (WIDTH * SCALE) + (x * SCALE + dx)) * 4;
          imageData.data[idx]     = rgb[0];
          imageData.data[idx + 1] = rgb[1];
          imageData.data[idx + 2] = rgb[2];
        }
      }
      if (flush !== false) {
        flushBuffer();
        renderViewport();
      }
    }

    function isPixelEmpty(x, y) {
      var idx = ((y * SCALE) * (WIDTH * SCALE) + (x * SCALE)) * 4;
      return imageData.data[idx] === 0 && imageData.data[idx + 1] === 0 && imageData.data[idx + 2] === 0;
    }

    function isPixelSameColor(x, y, paletteIndex) {
      var idx = ((y * SCALE) * (WIDTH * SCALE) + (x * SCALE)) * 4;
      var rgb = hexToRgb(PALETTE[paletteIndex]);
      return imageData.data[idx] === rgb[0] && imageData.data[idx + 1] === rgb[1] && imageData.data[idx + 2] === rgb[2];
    }

    // ── Canvas load ───────────────────────────────────────────────────────────
    function loadCanvas() {
      fetch(SERVER + '/api/canvas')
        .then(function (resp) {
          if (!resp.ok) throw new Error('HTTP ' + resp.status);
          return resp.json();
        })
        .then(function (pixels) {
          pixels.forEach(function (p) { paintPixelInBuffer(p.x, p.y, p.color, false); });
          flushBuffer();
          renderViewport();
        })
        .catch(function (err) {
          console.error('[BitBrush Widget] Canvas load error:', err);
        });
    }

    // ── Coordinate mapping (viewport-aware) ─────────────────────────────────
    function screenToLogical(e) {
      var rect = canvas.getBoundingClientRect();
      var css2c = canvas.width / rect.width;
      var cx = (e.clientX - rect.left) * css2c;
      var cy = (e.clientY - rect.top) * css2c;
      // Reverse viewport transform: canvas pixel → buffer pixel → logical pixel
      var bufX = (cx - canvas.width / 2) / viewZoom + viewPanX * SCALE;
      var bufY = (cy - canvas.height / 2) / viewZoom + viewPanY * SCALE;
      var lx = Math.floor(bufX / SCALE);
      var ly = Math.floor(bufY / SCALE);
      return { x: Math.max(0, Math.min(WIDTH - 1, lx)), y: Math.max(0, Math.min(HEIGHT - 1, ly)) };
    }

    // ── Palette rendering ─────────────────────────────────────────────────────
    var sortedPalette = sortPaletteByHsl(PALETTE);

    function selectColor(paletteIndex, hex) {
      if (activeSwatch) {
        activeSwatch.classList.remove('active');
        activeSwatch.style.borderColor = 'transparent';
        activeSwatch.style.boxShadow = '';
      }
      var newActive = paletteEl.querySelector('[data-palette-index="' + paletteIndex + '"]');
      if (newActive) {
        newActive.classList.add('active');
        if (paletteIndex === 0) {
          newActive.style.borderColor = '#ff4444';
          newActive.style.boxShadow = '0 0 8px rgba(255,68,68,0.3)';
        } else if (isLightColor(hex)) {
          newActive.style.borderColor = '#000000';
          newActive.style.boxShadow = '0 0 6px rgba(0,0,0,0.4)';
        } else {
          newActive.style.borderColor = '#ffffff';
          newActive.style.boxShadow = '0 0 8px rgba(0,204,204,0.3)';
        }
        activeSwatch = newActive;
      }
      selectedPaletteIndex = paletteIndex;
    }

    function renderPalette(showAll) {
      paletteEl.innerHTML = '';
      var items = showAll ? sortedPalette : sortedPalette.filter(function (item) {
        return item.originalIndex === 0 || CURATED_INDICES.indexOf(item.originalIndex) !== -1;
      });

      items.forEach(function (item) {
        var swatch = document.createElement('div');
        swatch.className = 'bbw-swatch';
        if (item.originalIndex === 0) {
          swatch.classList.add('eraser-swatch');
          swatch.title = 'Eraser';
        } else {
          swatch.style.backgroundColor = item.hex;
          swatch.title = item.hex;
        }
        swatch.setAttribute('data-palette-index', item.originalIndex);
        swatch.addEventListener('click', function () {
          selectColor(item.originalIndex, item.hex);
        });
        paletteEl.appendChild(swatch);
      });

      // Restore active selection
      if (selectedPaletteIndex !== null) {
        var cur = paletteEl.querySelector('[data-palette-index="' + selectedPaletteIndex + '"]');
        if (cur) {
          cur.classList.add('active');
          activeSwatch = cur;
        }
      }
    }

    renderPalette(false);
    // Set initial selection to first chromatic color
    var firstChromatic = sortedPalette.find(function (item) {
      return item.originalIndex !== 0 && hexToHsl(item.hex)[1] > 15;
    });
    selectedPaletteIndex = firstChromatic ? firstChromatic.originalIndex : 1;
    selectColor(selectedPaletteIndex, PALETTE[selectedPaletteIndex]);

    moreBtn.addEventListener('click', function () {
      showingAllColors = !showingAllColors;
      renderPalette(showingAllColors);
      moreBtn.textContent = showingAllColors ? 'Fewer colors' : 'More colors...';
      // Re-apply selection
      selectColor(selectedPaletteIndex, PALETTE[selectedPaletteIndex]);
    });

    // ── Bank display ──────────────────────────────────────────────────────────
    function renderBankDisplay(balance, maxBalance) {
      bankLabel.textContent = balance + '/' + maxBalance;
      canvas.style.cursor = balance <= 0 ? 'not-allowed' : 'crosshair';
    }

    function startProgressBar(seconds) {
      if (Date.now() < progressLockedUntil) return;
      var duration = Math.max(0.5, seconds - 0.3);
      bankFill.classList.remove('progress-indeterminate');
      bankFill.style.transition = 'none';
      bankFill.style.width = '0%';
      void bankFill.offsetWidth;
      bankFill.style.transition = 'width ' + duration + 's linear';
      bankFill.style.width = '100%';
    }

    function showIndeterminateProgress() {
      progressLockedUntil = Date.now() + 500;
      bankFill.style.transition = 'none';
      bankFill.style.width = '';
      bankFill.classList.add('progress-indeterminate');
    }

    function showProgressFull() {
      if (Date.now() < progressLockedUntil) return;
      bankFill.classList.remove('progress-indeterminate');
      bankFill.style.transition = 'none';
      bankFill.style.width = '100%';
    }

    function updateBankDisplay(state) {
      var prevBalance = localBalance;
      localBalance = state.balance;
      localMaxBalance = state.maxBalance;
      renderBankDisplay(state.balance, state.maxBalance);

      if (state.balance > prevBalance) {
        earnCycleDuration = state.secondsUntilNextPoint;
        earnCycleKnown = true;
      }

      if (!bankInitialized) {
        bankInitialized = true;
        showIndeterminateProgress();
      } else if (state.balance >= state.maxBalance) {
        showProgressFull();
      } else if (state.balance > prevBalance) {
        startProgressBar(earnCycleDuration);
      }

      // Flash animations
      if (state.balance > prevBalance) {
        bankLabel.style.color = '#00cc00';
        setTimeout(function () { bankLabel.style.color = '#00cccc'; }, 300);
      } else if (state.balance < prevBalance) {
        bankLabel.style.color = '#666666';
        setTimeout(function () { bankLabel.style.color = '#00cccc'; }, 200);
      }
    }

    function flashBalanceRed() {
      bankLabel.style.color = '#ff6666';
      setTimeout(function () { bankLabel.style.color = '#00cccc'; }, 400);
    }

    // ── Connection status ─────────────────────────────────────────────────────
    function updateConnectionStatus(state) {
      if (state === 'connected') {
        statusDot.style.background = '#00cc00';
        statusDot.style.boxShadow = '0 0 6px #00cc00';
      } else if (state === 'reconnecting') {
        statusDot.style.background = '#cccc00';
        statusDot.style.boxShadow = '0 0 6px #cccc00';
        statusText.textContent = 'reconnecting...';
      } else if (state === 'disconnected') {
        statusDot.style.background = '#cc0000';
        statusDot.style.boxShadow = '0 0 6px #cc0000';
        statusText.textContent = 'disconnected';
      }
    }

    function updateOnlineCount(count) {
      statusText.textContent = count + ' online';
    }

    // ── Identity / username ───────────────────────────────────────────────────
    function getOrCreateUuid() {
      var uuid = localStorage.getItem(LS_PREFIX + 'uuid');
      if (!uuid) {
        uuid = crypto.randomUUID();
        localStorage.setItem(LS_PREFIX + 'uuid', uuid);
      }
      return uuid;
    }

    function showUsernameOverlay(uuid) {
      var overlay = document.createElement('div');
      overlay.className = 'bbw-overlay';
      var modal = document.createElement('div');
      modal.className = 'bbw-modal';
      modal.innerHTML = '<h3>Choose a username</h3>';
      var input = document.createElement('input');
      input.type = 'text';
      input.placeholder = '3-30 characters';
      input.maxLength = 30;
      var btn = document.createElement('button');
      btn.textContent = 'Join';
      var errorEl = document.createElement('div');
      errorEl.className = 'bbw-modal-error';

      modal.appendChild(input);
      modal.appendChild(btn);
      modal.appendChild(errorEl);
      overlay.appendChild(modal);
      root.appendChild(overlay);

      setTimeout(function () { input.focus(); }, 100);

      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') btn.click();
      });

      btn.addEventListener('click', function () {
        var username = input.value.trim();
        errorEl.textContent = '';

        if (username.length < 3 || username.length > 30) {
          errorEl.textContent = 'Username must be 3-30 characters';
          return;
        }
        if (username.toLowerCase() === 'you') {
          errorEl.textContent = "Username 'You' is reserved";
          return;
        }

        var headers = { 'Content-Type': 'application/json' };
        if (turnstileToken) {
          headers['X-Turnstile-Token'] = turnstileToken;
        }

        fetch(SERVER + '/api/users', {
          method: 'POST',
          headers: headers,
          body: JSON.stringify({ uuid: uuid, username: username })
        }).then(function (resp) {
          if (resp.status === 201) {
            localStorage.setItem(LS_PREFIX + 'username', username);
            overlay.remove();
          } else if (resp.status === 403) {
            refreshTurnstileToken();
            errorEl.textContent = 'Verification failed -- please try again';
          } else {
            return resp.json().then(function (err) {
              errorEl.textContent = err.detail || 'Registration failed';
            });
          }
        }).catch(function () {
          errorEl.textContent = 'Network error -- please try again';
        });
      });
    }

    function initIdentity() {
      var uuid = getOrCreateUuid();
      var username = localStorage.getItem(LS_PREFIX + 'username');
      if (!username) {
        showUsernameOverlay(uuid);
      } else {
        // Re-register silently in case DB was reset
        var headers = { 'Content-Type': 'application/json' };
        if (turnstileToken) {
          headers['X-Turnstile-Token'] = turnstileToken;
        }
        fetch(SERVER + '/api/users', {
          method: 'POST',
          headers: headers,
          body: JSON.stringify({ uuid: uuid, username: username })
        }).catch(function () { /* best effort */ });
      }
    }

    // ── Drag-to-place ─────────────────────────────────────────────────────────
    var isDragging = false;
    var dragPixels = new Set();
    var dragPixelList = [];
    var lastDragX = -1, lastDragY = -1;

    function placePixelDuringDrag(x, y) {
      var key = x + ',' + y;
      if (dragPixels.has(key)) return;
      if (localBalance <= 0) return;
      if (selectedPaletteIndex === 0 && isPixelEmpty(x, y)) return;
      if (selectedPaletteIndex !== 0 && isPixelSameColor(x, y, selectedPaletteIndex)) return;

      dragPixels.add(key);
      dragPixelList.push({ x: x, y: y });

      paintPixelInBuffer(x, y, PALETTE[selectedPaletteIndex]);

      var wasAtMax = localBalance >= localMaxBalance;
      localBalance = Math.max(0, localBalance - 1);
      bankLabel.textContent = localBalance + '/' + localMaxBalance;
      canvas.style.cursor = localBalance <= 0 ? 'not-allowed' : 'crosshair';
      if (wasAtMax) showIndeterminateProgress();
    }

    function flushDragPixels() {
      if (dragPixelList.length === 0) return;
      var uuid = localStorage.getItem(LS_PREFIX + 'uuid');
      var pixelsBatch = dragPixelList.slice();
      dragPixels = new Set();
      dragPixelList = [];

      var headers = { 'Content-Type': 'application/json' };
      if (turnstileToken) {
        headers['X-Turnstile-Token'] = turnstileToken;
      }

      fetch(SERVER + '/api/pixels', {
        method: 'POST',
        headers: headers,
        body: JSON.stringify({
          pixels: pixelsBatch,
          paletteIndex: selectedPaletteIndex,
          authorUuid: uuid
        })
      }).then(function (resp) {
        if (!resp.ok) {
          if (resp.status === 402) flashBalanceRed();
          if (resp.status === 403) refreshTurnstileToken();
          loadCanvas();
        }
      }).catch(function () {
        loadCanvas();
      });
    }

    // ── Gesture helpers ──────────────────────────────────────────────────────
    function pointerDist(a, b) {
      var dx = a.x - b.x, dy = a.y - b.y;
      return Math.sqrt(dx * dx + dy * dy);
    }

    function pointerMid(a, b) {
      return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
    }

    function startGesture() {
      var pts = Array.from(activePointers.values());
      if (pts.length < 2) return;
      gestureState = {
        startDist: pointerDist(pts[0], pts[1]),
        startZoom: viewZoom,
        startMidX: (pts[0].x + pts[1].x) / 2,
        startMidY: (pts[0].y + pts[1].y) / 2,
        startPanX: viewPanX,
        startPanY: viewPanY
      };
    }

    function updateGesture() {
      if (!gestureState) return;
      var pts = Array.from(activePointers.values());
      if (pts.length < 2) return;

      var newDist = pointerDist(pts[0], pts[1]);
      var newMid = pointerMid(pts[0], pts[1]);
      var zoomFactor = newDist / gestureState.startDist;
      var newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, gestureState.startZoom * zoomFactor));

      // The logical coord at the initial midpoint should move to the new midpoint
      var rect = canvas.getBoundingClientRect();
      var css2c = canvas.width / rect.width;
      var initCX = (gestureState.startMidX - rect.left) * css2c;
      var initCY = (gestureState.startMidY - rect.top) * css2c;
      var logX = (initCX - canvas.width / 2) / gestureState.startZoom + gestureState.startPanX * SCALE;
      var logY = (initCY - canvas.height / 2) / gestureState.startZoom + gestureState.startPanY * SCALE;

      var newCX = (newMid.x - rect.left) * css2c;
      var newCY = (newMid.y - rect.top) * css2c;
      viewPanX = (logX - (newCX - canvas.width / 2) / newZoom) / SCALE;
      viewPanY = (logY - (newCY - canvas.height / 2) / newZoom) / SCALE;
      viewZoom = newZoom;
      clampPan();
      renderViewport();
    }

    // ── Pointer events (drawing + gestures) ──────────────────────────────────
    canvas.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      e.stopPropagation();
      activePointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

      // Two+ pointers → switch to gesture mode
      if (activePointers.size >= 2) {
        if (isDragging) {
          isDragging = false;
          flushDragPixels();
        }
        startGesture();
        return;
      }

      // Gesture just ended — don't start drawing on the lingering pointer
      if (gestureState) return;

      // Single pointer: start drawing
      if (e.button !== 0) return;
      var username = localStorage.getItem(LS_PREFIX + 'username');
      var uuid = localStorage.getItem(LS_PREFIX + 'uuid');
      if (!username || !uuid) return;

      if (localBalance <= 0) {
        flashBalanceRed();
        return;
      }

      var pos = screenToLogical(e);
      isDragging = true;
      dragPixels = new Set();
      dragPixelList = [];
      lastDragX = pos.x;
      lastDragY = pos.y;
      canvas.setPointerCapture(e.pointerId);

      placePixelDuringDrag(pos.x, pos.y);
    });

    canvas.addEventListener('pointermove', function (e) {
      if (!activePointers.has(e.pointerId)) return;
      activePointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      e.preventDefault();
      e.stopPropagation();

      if (gestureState && activePointers.size >= 2) {
        updateGesture();
        return;
      }

      if (!isDragging) return;
      var pos = screenToLogical(e);
      if (pos.x !== lastDragX || pos.y !== lastDragY) {
        var points = bresenhamLine(lastDragX, lastDragY, pos.x, pos.y);
        for (var i = 1; i < points.length; i++) {
          placePixelDuringDrag(points[i].x, points[i].y);
        }
        lastDragX = pos.x;
        lastDragY = pos.y;
      }
    });

    canvas.addEventListener('pointerup', function (e) {
      activePointers.delete(e.pointerId);

      if (gestureState) {
        if (activePointers.size < 2) gestureState = null;
        return;
      }

      if (!isDragging || e.button !== 0) return;
      isDragging = false;
      flushDragPixels();
    });

    canvas.addEventListener('pointercancel', function (e) {
      activePointers.delete(e.pointerId);
      if (gestureState && activePointers.size < 2) gestureState = null;
      isDragging = false;
      dragPixels = new Set();
      dragPixelList = [];
    });

    // Prevent touch scrolling on canvas
    canvas.addEventListener('touchstart', function (e) { e.preventDefault(); }, { passive: false });

    // ── Mouse wheel zoom ─────────────────────────────────────────────────────
    canvas.addEventListener('wheel', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
      zoomAtPoint(e.clientX, e.clientY, factor);
    }, { passive: false });

    // ── Turnstile ─────────────────────────────────────────────────────────────
    function refreshTurnstileToken() {
      if (window.turnstile && turnstileWidgetId != null) {
        window.turnstile.reset(turnstileWidgetId);
      }
    }

    function initTurnstile() {
      if (!TURNSTILE_SITE_KEY) return Promise.resolve();

      return loadScript('https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit', 10000)
        .then(function () {
          return new Promise(function (resolve) {
            // Turnstile may need a moment after script load
            function tryRender() {
              if (window.turnstile) {
                turnstileContainer.style.display = 'block';
                turnstileWidgetId = window.turnstile.render(turnstileContainer, {
                  sitekey: TURNSTILE_SITE_KEY,
                  callback: function (token) {
                    turnstileToken = token;
                  },
                  'expired-callback': function () {
                    turnstileToken = null;
                  },
                  'error-callback': function () {
                    turnstileToken = null;
                  },
                  size: 'invisible'
                });
                resolve();
              } else {
                setTimeout(tryRender, 100);
              }
            }
            tryRender();
          });
        })
        .catch(function (err) {
          console.warn('[BitBrush Widget] Turnstile load failed:', err);
        });
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────
    function connectWebSocket() {
      var uuid = getOrCreateUuid();

      var client = new StompJs.Client({
        webSocketFactory: function () { return new SockJS(SERVER + '/ws'); },
        connectHeaders: { uuid: uuid },
        reconnectDelay: 5000,
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000
      });

      client.onConnect = function () {
        client.subscribe('/topic/pixels', function (message) {
          var p = JSON.parse(message.body);
          paintPixelInBuffer(p.x, p.y, p.color);
        });

        client.subscribe('/topic/users/count', function (message) {
          updateOnlineCount(parseInt(message.body, 10));
        });

        client.subscribe('/app/users/count', function (message) {
          updateOnlineCount(parseInt(message.body, 10));
        });

        client.subscribe('/user/queue/bank', function (message) {
          updateBankDisplay(JSON.parse(message.body));
        });

        client.subscribe('/app/bank', function (message) {
          updateBankDisplay(JSON.parse(message.body));
        });

        loadCanvas();
        updateConnectionStatus('connected');
      };

      client.onWebSocketClose = function () {
        updateConnectionStatus('reconnecting');
      };

      client.activate();
      stompClient = client;
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────
    Promise.all([
      loadScript('https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js', 10000),
      loadScript('https://cdn.jsdelivr.net/npm/@stomp/stompjs@7/bundles/stomp.umd.min.js', 10000),
      initTurnstile()
    ]).then(function () {
      connectWebSocket();
      initIdentity();
    }).catch(function (err) {
      console.error('[BitBrush Widget] Failed to load dependencies:', err);
      statusText.textContent = 'failed to load';
    });
  }

  // Run when DOM is ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

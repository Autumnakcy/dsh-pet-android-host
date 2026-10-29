/**
 * host-shim.js —— Android 悬浮窗宿主的补丁层（由 /dsh-pet-android/desktop/index.html 在
 * renderer.js 之后加载）。
 *
 * 作用：把「窗口级鼠标」换成「宿主转发的手势」。桌宠页原本靠 DOM 的 pointerdown/move/up
 * 驱动拖拽，而 Android 上：
 *   · 渲染窗整窗穿透（FLAG_NOT_TOUCHABLE），收不到任何触摸；
 *   · 另有一个「触摸窗」贴着宠物身体 + 可点控件，收到触摸后把屏幕坐标转发给本文件。
 * 所以这里直接把坐标喂给 PetSprite 自己的 onPointerDown/Move/Up/onClick/onContextMenu ——
 * 不合成 DOM 事件（合成事件缺真实 pointerId 与 setPointerCapture，行为会飘），
 * 走的是同一批方法，判定/阈值/弹簧/甩抛/菜单语义与桌面端完全一致。
 *
 * 坐标口径：宿主传进来的是**屏幕物理像素**（MotionEvent.getRawX/Y）；页面内部一律 CSS 像素，
 * 故按 toLocal() 折回，再减去「窗口原点」换成窗口内坐标（clientX/Y 的语义，见 sprite.js）。
 */
(function () {
  'use strict';

  // ---- 幂等守卫 ----
  // 从 v4.1 起这个文件有两份：
  //   ① APK assets 里那份（宿主在页面加载完成后注入，**权威**）；
  //   ② 插件目录里那份（/dsh-pet-android/desktop/host-shim.js，页面 <script> 自己引的）。
  // 两份都会执行，必须只让先到的那份生效——否则定时器/监听器全成双份，
  // 表现是"点一下动两次""菜单一次开两个"。谁先谁后用这个标记定，后到的直接退出。
  if (window.__petHostShimInstalled) return;
  window.__petHostShimInstalled = 1;

  function pet() {
    try {
      return typeof sprites !== 'undefined' && sprites && sprites[0] ? sprites[0] : null;
    } catch (e) {
      return null;
    }
  }
  function noop() {}

  // ---- 干掉 WebView 自带的「视频浮层」：那个长方形 + 播放▶键 ----
  //
  // 病根（2026-10-28 用户报「每次换动画都有个长方形显示播放键，像在播视频」）：
  // 她的每个动画都是一段 <video src>。上游换动画的写法是
  //   el.src = 素材 → el.load() → 等 loadeddata → 加 .is-front（淡入）→ el.play()
  // 实测（探针 timeline）：src/loadeddata/加 class 全在 26ms 内完成，但 play() 兑现、
  // 首帧真正合成出来还要几十~几百毫秒。这中间 WebView 把这块 <video> 当成
  // 「需要点一下才能播」的媒体，于是在元素上画 Blink 自带的
  // `::-webkit-media-controls-start-playback-button` —— 一个暗色矩形 + 中央播放三角。
  // 它不是 DOM 节点（getElementById / elementFromPoint / shadowRoot 都摸不到），
  // 宿主侧也拿不到句柄，**只能靠 CSS 伪元素关掉**；再给两块 video 挂 1×1 透明
  // poster，把「加载中默认封面」那层也一起压掉。controls=false 已经有了，挡不住它。
  var TRANSPARENT_GIF = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
  /**
   * 每条伪元素**单独成一条规则**。
   * 关键坑：CSS 一条规则里逗号分隔的选择器列表，只要有**任意一个**选择器不被内核识别，
   * 整条规则会被无声丢弃（不会报错）。Chrome 153 对 `::-webkit-media-controls-*`
   * 的支持是逐条演进的，写成一坨极可能全军覆没。拆开写，认识的生效、不认识的自己丢自己。
   */
  var MEDIA_CHROME_PSEUDOS = [
    '::-webkit-media-controls',
    '::-webkit-media-controls-enclosure',
    '::-webkit-media-controls-panel',
    '::-webkit-media-controls-play-button',
    '::-webkit-media-controls-start-playback-button',
    '::-webkit-media-controls-overlay-play-button',
    '::-webkit-media-controls-overlay-enclosure',
    '::-webkit-media-controls-timeline',
    '::-webkit-media-controls-current-time-display',
    '::-webkit-media-controls-time-remaining-display',
    '::-webkit-media-controls-mute-button',
    '::-webkit-media-controls-volume-slider',
    '::-webkit-media-controls-toggle-closed-captions-button',
    '::-webkit-media-controls-fullscreen-button',
    '::-webkit-media-controls-download-button',
    '::-webkit-media-controls-picture-in-picture-button',
    '::-internal-media-controls-overlay-cast-button',
  ];
  function installVideoChrome() {
    try {
      if (document.getElementById('dsh-pet-video-chrome')) return;
      var css = 'video.pet-video{background:transparent !important;outline:none !important;}';
      for (var i = 0; i < MEDIA_CHROME_PSEUDOS.length; i++) {
        css +=
          'video.pet-video' + MEDIA_CHROME_PSEUDOS[i] + '{' +
          'display:none !important;-webkit-appearance:none !important;' +
          'opacity:0 !important;pointer-events:none !important;' +
          'width:0 !important;height:0 !important;visibility:hidden !important;}';
      }
      // ★ 真正的双保险：**首帧没出来之前，这块 video 一律不许露面**。
      //   上面那套伪元素是"把 WebView 画的播放键盖掉"，但不同 WebView 版本管用程度不一样；
      //   这一条是从根上做——只要 readyState<2（没有当前帧可画），opacity 一律按 0。
      //   具体谁该显示、谁该托底，全部由 installFrameGate() 每帧决定，不看任何事件。
      css += 'video.pet-video.is-front:not(.pet-frame){opacity:0 !important;transition:none !important;}';
      // 放行 = **瞬间**切到首帧，不淡入。
      // 实测：如果放行还走基线那 180ms 淡入，开头那 20~40ms 新块 opacity≈0、
      // 旧块又已经被撤（见下条），画面就是你看到的那一"闪"。首帧既然已经在了，硬切最稳。
      css += 'video.pet-video.is-front.pet-frame{opacity:1 !important;transition:none !important;}';
      // 非前台一律不显示（硬切，不做 180ms 淡出）。
      // 上游换动画会 `old.pause()`，"暂停/播完的 video" 是 WebView 画播放键的另一时机，
      // 而它原本还要淡出 —— 那 180ms 里旧的暂停视频仍看得见（淡着也看得见 ▶）。
      css += 'video.pet-video:not(.is-front){opacity:0 !important;transition:none !important;}';
      // 过渡托底：新的那块首帧还没就绪时，让上一块继续停在最后一帧顶着（见 installFrameGate）。
      // 放最后 = 同权重下后写优先，能盖住上面那条。
      css += 'video.pet-video.pet-hold{opacity:1 !important;}';
      var st = document.createElement('style');
      st.id = 'dsh-pet-video-chrome';
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }

  /**
   * 首帧门闸（v2，**纯逐帧判定，不依赖任何事件时序**）。
   *
   * 前两版用 loadstart / playing / requestVideoFrameCallback 拼时序，实测各有各的漏：
   *   · `src` 赋值 → loadstart 派发之间约 20ms，readyState 已掉到 0 但 class 还在 → 露一帧；
   *   · 首帧到了却还在 0.18s 淡入，开头 20~40ms opacity≈0，旧的又已经撤 → 空两帧；
   *   · 上游 `old.pause()` 发生在 loadeddata 之后，事件顺序不可控。
   *
   * 现在只做一件事：**每帧看一眼两张 <video> 的状态，直接决定谁该显示**。
   *   规则：前台那块只要 readyState≥2（有当前帧可画）就显示；
   *         还没就绪时，让"上一块显示的"继续露着（它 paused 但停在最后一帧），
   *         这样既没有空档，也绝不会露出"没画面的矩形 + ▶"。
   */
  function installFrameGate() {
    if (window.__frameGateLoop) return;
    window.__frameGateLoop = 1;
    var lastShown = null;
    function loop() {
      try {
        var els = document.querySelectorAll('video.pet-video');
        var front = null;
        for (var i = 0; i < els.length; i++) {
          if (els[i].classList.contains('is-front')) {
            front = els[i];
            break;
          }
        }
        if (front) {
          var ready = front.readyState >= 2;
          if (ready) {
            lastShown = front;
          }
          if (window.__gateDbg) {
            window.__gateDbg.push({
              t: Math.round(performance.now()),
              front: front === els[0] ? 'A' : 'B',
              ready: ready,
              last: lastShown === els[0] ? 'A' : lastShown === els[1] ? 'B' : 'null',
              a: els[0] ? els[0].readyState : -1,
              b: els[1] ? els[1].readyState : -1,
            });
            if (window.__gateDbg.length > 400) window.__gateDbg.shift();
          }
          for (var j = 0; j < els.length; j++) {
            var el = els[j];
            var isFront = el === front;
            // 前台且已就绪 → 显示（硬切，不淡入）
            if (isFront) {
              if (ready) {
                if (!el.classList.contains('pet-frame')) el.classList.add('pet-frame');
                el.classList.remove('pet-hold');
              } else {
                if (el.classList.contains('pet-frame')) el.classList.remove('pet-frame');
              }
              continue;
            }
            // 非前台：只在"前台还没就绪"时托底，且**任何一块有帧的都能顶**
            // （不能只认 lastShown ——实测存在"前台那块自己重载"的情况，
            //   此时 lastShown 正是那个没帧的家伙，允许范围太窄就会空两帧）
            if (el.classList.contains('pet-frame')) el.classList.remove('pet-frame');
            var hold = !ready && el.readyState >= 2;
            if (hold) {
              if (!el.classList.contains('pet-hold')) el.classList.add('pet-hold');
              // 托底的这块上游刚 `pause()` 过。暂停的 <video> 是 WebView 画播放键的另一时机，
              // 所以托底期间让它继续播（muted，无声）—— 几十毫秒后就被换下去，代价可忽略。
              if (el.paused) {
                try {
                  var pr = el.play();
                  if (pr && pr.catch) pr.catch(function () {});
                } catch (e) {}
              }
            } else if (el.classList.contains('pet-hold')) {
              el.classList.remove('pet-hold');
              // 托底结束 → 让它重新停住。否则这块会带着 opacity:0 在后台一直循环解码，
              // 白耗电，而且上游下一次换动画只会 pause 当时的前台那块，管不到它。
              if (!el.paused) {
                try {
                  el.pause();
                } catch (e) {}
              }
            }
          }
        }
      } catch (e) {}
      requestAnimationFrame(loop);
    }
    requestAnimationFrame(loop);
  }

  /** 把 <video> 的「加载中默认封面」换成透明像素：poster 是属性，换源后会留着，但要防被清掉。 */
  function installVideoPosters() {
    try {
      var vs = document.querySelectorAll('video.pet-video');
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        v.controls = false;
        if (v.getAttribute('poster') !== TRANSPARENT_GIF) v.setAttribute('poster', TRANSPARENT_GIF);
        if (!v.__chromeProbed) {
          v.__chromeProbed = 1;
          v.addEventListener('loadstart', function () {
            try {
              this.controls = false;
              this.setAttribute('poster', TRANSPARENT_GIF);
            } catch (e) {}
          });
        }
      }
    } catch (e) {}
  }

  function view() {
    return typeof VIEW !== 'undefined' ? VIEW : { x: 0, y: 0, w: innerWidth, h: innerHeight };
  }
  function local(v) {
    try {
      return typeof toLocal === 'function' ? toLocal(v) : v;
    } catch (e) {
      return v;
    }
  }
  /**
   * 窗口原点（页面坐标系的 0,0 在屏幕上的 CSS 位置）。
   * 优先用 window.screenX/screenY —— 那是浏览器内核自己记的真实位置；
   * sprite 里的 pos/VIEW/margin 只是「我们请求窗口去哪」，系统夹取/微调之后就不是真实值了
   * （实测差 84/276 物理像素，点菜单外面收不到关闭信号就是这么来的）。
   * 只有当 screenX 明显离谱时才退回模型值。
   */
  function winOrigin(s) {
    var v = view();
    var mx = s.pos.x + v.x - s.margin.l;
    var my = s.pos.y + v.y - s.margin.t;
    var sx = typeof window.screenX === 'number' ? window.screenX : null;
    var sy = typeof window.screenY === 'number' ? window.screenY : null;
    return {
      x: sx !== null && Math.abs(sx - mx) < 200 ? sx : mx,
      y: sy !== null && Math.abs(sy - my) < 200 ? sy : my,
    };
  }
  /** 屏幕物理像素 → 窗口内 CSS 像素。
   *  优先用宿主推来的窗口真实屏幕位置（__petHostWinXY，物理像素）——这是最可信的基准；
   *  window.screenX 在这个 WebView 里恒为 0，不能信。 */
  /** 窗口原点（屏幕物理像素）。**必须有兜底**：宿主只在窗口位置变化时推 __petHostWinXY，
   *  而新宿主的窗口是钉死的——页面一重载就再也收不到推送，退回 (0,0) 就整整差了状态栏高度
   *  （实测菜单/点击整体下移 40 CSS，就是它）。 */
  function modelOrigin(s) {
    var dpr = window.devicePixelRatio || 1;
    var v = view();
    if (window.__petHostFixedWin === 1) return [Math.round(v.x * dpr), Math.round(v.y * dpr)];
    return [Math.round((s.pos.x + v.x - s.margin.l) * dpr), Math.round((s.pos.y + v.y - s.margin.t) * dpr)];
  }
  var originDirty = true; // 刚请求过移窗：宿主推来的位置还是旧的，先用模型算
  var lastMoveAt = 0;
  function winXYOf(s) {
    var dpr = window.devicePixelRatio || 1;
    var v = view();
    var model = window.__petHostFixedWin === 1
      ? [Math.round(v.x * dpr), Math.round(v.y * dpr)]
      : [Math.round((s.pos.x + v.x - s.margin.l) * dpr), Math.round((s.pos.y + v.y - s.margin.t) * dpr)];
    var xy = window.__petHostWinXY;
    if (!xy || typeof xy[0] !== 'number' || typeof xy[1] !== 'number') return model;
    if (originDirty) {
      var agree = Math.abs(xy[0] - model[0]) <= 3 && Math.abs(xy[1] - model[1]) <= 3;
      if (agree || Date.now() - lastMoveAt > 400) originDirty = false; // 对上了/等太久 → 改信宿主
      else return model;
    }
    return xy;
  }
  function clientOf(s, sx, sy) {
    var dpr = window.devicePixelRatio || 1;
    var xy = winXYOf(s);
    return { x: (sx - xy[0]) / dpr, y: (sy - xy[1]) / dpr };
  }
  /** 菜单命中：**整行都算**。
   *
   *  病根（2026-10-28 实测）：菜单项里的文字是 `<span>`、右边是 `<span class="dsh-pet-menu-arrow">`，
   *  而分支项展开子菜单靠的是 `item.addEventListener('mouseenter')` —— mouseenter **不冒泡**。
   *  点在文字上时 elementFromPoint 给的是那个 span，合成事件只打到 span 上，item 的监听器收不到 →
   *  「动作 / 点击回应 / 小动作…」这些带子菜单的行**只有点空白处才展开**，点文字没反应。
   *  叶子项之所以没事，是 click 会冒泡。
   *
   *  所以：只要落点在这棵菜单里，一律把事件打到 `.dsh-pet-menu-item` **整行**上；
   *  连面板 4px 内边距、行与行之间的缝隙、手指稍微偏出去一点，也按"最近的那一行"算。
   *  全部用实时 getBoundingClientRect 计算 —— 菜单在她左边/中间/右边都能命中，不写死任何坐标。 */
  function menuItemNear(c) {
    try {
      var root = document.querySelector('.dsh-pet-menu');
      if (!root) return null;
      var el = document.elementFromPoint(c.x, c.y);
      if (el && root.contains(el)) {
        var it = el.closest ? el.closest('.dsh-pet-menu-item') : null;
        if (it) return it;
      }
      // 兜底：几何最近行（面板内边距 / 行间缝隙 / 手指偏出几像素）
      var best = null,
        bestD = 1e9;
      var cols = root.querySelectorAll('.dsh-pet-menu-column');
      for (var i = 0; i < cols.length; i++) {
        var cr = cols[i].getBoundingClientRect();
        if (cr.width < 2 || cr.height < 2) continue;
        if (c.x < cr.left - 10 || c.x > cr.right + 10 || c.y < cr.top - 10 || c.y > cr.bottom + 10) continue;
        var items = cols[i].querySelectorAll('.dsh-pet-menu-item');
        for (var j = 0; j < items.length; j++) {
          var r = items[j].getBoundingClientRect();
          if (r.width < 2 || r.height < 2) continue;
          var dx = c.x < r.left ? r.left - c.x : c.x > r.right ? c.x - r.right : 0;
          var dy = c.y < r.top ? r.top - c.y : c.y > r.bottom ? c.y - r.bottom : 0;
          if (dx + dy < bestD) {
            bestD = dx + dy;
            best = items[j];
          }
        }
      }
      return bestD <= 12 ? best : null;
    } catch (e) {
      return null;
    }
  }

  /** 菜单项的子元素一律不吃指针事件：命中永远是整行（真机/合成事件同一条路）。 */
  function ensureMenuHitCss() {
    try {
      if (document.getElementById('dsh-menu-hitfix')) return;
      var st = document.createElement('style');
      st.id = 'dsh-menu-hitfix';
      st.textContent = '.dsh-pet-menu-item>*{pointer-events:none!important}';
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }

  /** 在某个点派发一次 DOM mousedown —— 菜单靠 document 上的 mousedown 判定「点外面了」 */
  function pokeMouseDown(s, sx, sy) {
    try {
      var c = clientOf(s, sx, sy);
      var el = document.elementFromPoint(c.x, c.y) || document.body;
      el.dispatchEvent(
        new MouseEvent('mousedown', {
          bubbles: true,
          cancelable: true,
          clientX: c.x,
          clientY: c.y,
          screenX: sx,
          screenY: sy,
          view: window,
          button: 0,
          buttons: 1,
          detail: 1,
        }),
      );
    } catch (e) {
      /* 合成失败不影响拖拽 */
    }
  }
  function mkEv(s, sx, sy, extra) {
    var c = clientOf(s, sx, sy);
    var e = {
      pointerId: 1,
      pointerType: 'mouse',
      isPrimary: true,
      button: 0,
      buttons: 1,
      screenX: sx,
      screenY: sy,
      clientX: c.x,
      clientY: c.y,
      x: c.x,
      y: c.y,
      pageX: c.x,
      pageY: c.y,
      ctrlKey: false,
      shiftKey: false,
      altKey: false,
      metaKey: false,
      detail: 1,
      preventDefault: noop,
      stopPropagation: noop,
      stopImmediatePropagation: noop,
      target: s.hit,
      currentTarget: s.hit,
    };
    if (extra) for (var k in extra) e[k] = extra[k];
    return e;
  }

  var pressed = false;
  var swallowUp = false; // 这一次按下已经转交给菜单/对话，松手别再当成点她
  var scrollDrag = null; // 菜单上的滑动滚动：{col, lastY}
  var scrollGuardUntil = 0; // 滑动刚结束 → 这一帧的 click 丢弃

  // ---- 身体命中区上报 ----
  // 宿主据此摆放那块「触摸窗」。必须是 .pet-hit 的真实矩形，不是舞台（舞台 300×169，
  // 她身体只占中间一条 113×134，按舞台摆的话点空处也会把她拎起来）。
  // 坐标系：getBoundingClientRect 是**窗口内 CSS px**，乘以 devicePixelRatio 换成物理像素；
  // 宿主再叠加窗口自身的屏幕位置，就得到屏幕物理像素 —— r.x 会随窗口移动而变，所以定时重发。
  // ---- 把窗口收成「她本身」 ----
  // dsh-pet 桌面端给气泡留了 size×WINDOW_MARGIN_RATIO 的四周余量：她身体只有 113×134，
  // 窗口却是 600×483。Android 悬浮窗没法"又透明又穿透"（ROM 会给穿透窗压暗约 19%），
  // 于是反过来做：窗口越小，挡住的面积越小。左右下余量归零，只留上边距（气泡长在那儿），
  // 「窗口原点 = pos − margin」这个坐标模型不变，所以拖动/菜单/气泡的数学全都照旧。
  // 有没有"身外的 UI"要地方：菜单/气泡/提示/对话。它们都画在她身侧，
  // 收窄窗口时会被切掉 —— 所以按需伸缩：平时收成她本身，UI 一出现就恢复原余量。
  // 哪些元素会"画到她身外"：气泡（碎碎念/余额）在孩子节点里，位置可量；
  // 菜单/对话由页面自己上报 inputBusy（桌面端用它表示"现在窗口不能翻穿透"）——
  // 那正好就是"窗口要放大"的时机。早先按 class 猜，被常驻节点误判，窗口再也收不回来。
  function visibleRect(el) {
    if (!el) return null;
    var cs = window.getComputedStyle(el);
    if (cs.display === "none" || cs.visibility === "hidden" || parseFloat(cs.opacity) === 0) return null;
    var r = el.getBoundingClientRect();
    if (!(r.width > 2 && r.height > 2)) return null;
    return r;
  }

  // 三种窗口形态：
  //   body —— 平时收成她本身（最不挡触摸）
  //   wide —— 拖拽/可点气泡：恢复 dsh-pet 原本的余量（600×483），位置模型不变
  //   full —— 菜单/对话：铺满整个工作区。**必须**是工作区，因为菜单靠「窗口 ∩ 工作区」夹取：
  //           窗口只有身体那么大时，菜单会被夹进那个小窗，而窗口一放大原点就左移/上移，
  //           菜单还钉在按旧原点算出的位置 → 整个菜单飞到屏幕左上角、半个在屏幕外（实测）。
  // isInputBusy() = dragState.active || menuOpen || chatOpen || balanceClickable()（见 sprite.js:1412）。
  function uiMode(s) {
    try {
      // 状态位可能残留（对话窗关掉后没清）——没有对应的 DOM 就自愈，否则窗口永远收不回去。
      if (s.chatOpen && !document.querySelector(".dsh-pet-chat")) {
        s.chatOpen = false;
        if (window.__dshPetDebug) window.__dshPetDebug.chatOpen = false;
      }
      if (s.menuOpen && !document.querySelector(".dsh-pet-menu")) s.menuOpen = false;
      if (s.menuOpen || s.chatOpen) return "full";
      if (typeof s.isInputBusy === "function" && s.isInputBusy()) return "wide";
    } catch (e) {}
    return "body";
  }
  function geomOf(s) {
    if (s.__geom) return s.__geom;
    var hr = s.hit.getBoundingClientRect();
    var er = s.el.getBoundingClientRect();
    if (!(hr.width > 1 && er.width > 1)) return null;
    s.__geom = { x: hr.left - er.left, y: hr.top - er.top, w: hr.width, h: hr.height };
    return s.__geom;
  }

  var PAD = 6; // CSS px：窗口四周给自己留的容差
  // 长按弹菜单前先把窗口撑到工作区，撑窗是异步的（宿主改窗 → WebView 重排），这期间不许 tighten 缩回去
  var forceFull = false;

  function marginsFor(s, box) {
    var l = -(box.x - PAD);
    var t = -(box.y - PAD);
    var w = box.w + PAD * 2;
    var h = box.h + PAD * 2;
    return { l: l, t: t, r: w - s.size - l, b: h - s.winH - t };
  }

  /** 窗口铺满整个工作区时的余量（菜单/对话用）。
   *  窗口屏幕位置 = pos − margin + VIEW ⇒ l = pos.x、t = pos.y 时窗口正好落在工作区原点。
   *  **必须铺满**：菜单按「窗口 ∩ 工作区」夹取，窗口窄了她右边放不下子菜单，
   *  级联面板会被强行翻到左边、压在父菜单上（实测窗口 236 宽时子菜单 x=4，压住父菜单 92px）。 */
  function fullMargins(s) {
    var v = view();
    var l = s.pos.x;
    var t = s.pos.y;
    return {
      l: l,
      t: t,
      r: Math.max(0, v.w - s.size - l),
      b: Math.max(0, v.h - s.winH - t),
    };
  }

  // ---- 撑窗/收窗那几帧先把她藏起来 ----
  // 窗口原点一动，"她画布在窗口里的位置"（el.left/top = margin.l/t）就得同步反向补偿。
  // 但宿主改窗要走 JS→Binder→主线程→WindowManager→系统合成，实测 ~40ms（2~3 帧）；
  // 页面里的 el 却是同步改的。于是中间那几帧她被画在窗口右下方、还被窗口裁掉大半 ——
  // 就是"打开菜单向右下角回弹一下、关闭向左上角回弹一下、只露中间一块"。
  // 两件事同步不到同一帧，那就别让人看见中间态：藏起来，等宿主把窗改到位再显示。
  var hideUntil = 0;
  function showIfSettled() {
    if (!hideUntil) return;
    var s = pet();
    if (!s) {
      hideUntil = 0;
      return;
    }
    var wantW = s.size + s.margin.l + s.margin.r;
    var wantH = s.winH + s.margin.t + s.margin.b;
    var settled = Math.abs(window.innerWidth - wantW) <= 2 && Math.abs(window.innerHeight - wantH) <= 2;
    if (!settled && Date.now() < hideUntil) return;
    hideUntil = 0;
    try {
      s.el.style.visibility = "";
    } catch (e) {}
  }

  /** 写余量并推一次窗口；没变化返回 false（调用方据此决定要不要等宿主真的改完窗）。
   *  `hideOnMove` = 这次改动会挪窗口原点（撑开/收回菜单、对话）→ 过渡期先把她藏起来。 */
  function applyMargins(s, want, hideOnMove) {
    var originShift = Math.abs(want.l - s.margin.l) > 0.5 || Math.abs(want.t - s.margin.t) > 0.5;
    if (!originShift && s.margin.l === want.l && s.margin.r === want.r && s.margin.b === want.b && s.margin.t === want.t) return false;
    if (originShift && hideOnMove) {
      try {
        s.el.style.visibility = "hidden";
      } catch (e) {}
      hideUntil = Date.now() + 500; // 兜底：万一宿主没改窗，500ms 后也一定显示回来
    }
    s.margin.l = want.l;
    s.margin.r = want.r;
    s.margin.b = want.b;
    s.margin.t = want.t;
    if (originShift && !fixedWin()) {
      // 窗口原点要动：先别挪画布，等宿主把窗改到位（resize 那一帧）再挪，错位只留不到一帧
      pendingEl = { l: want.l, t: want.t };
      setTimeout(flushPendingEl, 400); // 兜底：万一没等到 resize
    } else {
      pendingEl = null;
      s.el.style.left = want.l + "px";
      s.el.style.top = want.t + "px";
    }
    if (typeof s.sendBounds === "function" && s.pos) {
      try {
        s.sendBounds(s.pos.x, s.pos.y);
      } catch (e2) {}
    }
    return true;
  }

  var pendingEl = null;
  var pendingFull = false; // 正在撑窗等菜单：这期间 tighten 不许缩回去
  function flushPendingEl() {
    var s = pet();
    if (!s || !pendingEl) return;
    var p = pendingEl;
    pendingEl = null;
    try {
      if (Math.abs(s.margin.l - p.l) > 0.5 || Math.abs(s.margin.t - p.t) > 0.5) return; // 余量又变了，以最新为准
      s.el.style.left = p.l + "px";
      s.el.style.top = p.t + "px";
    } catch (e) {}
  }
  window.addEventListener("resize", function () {
    flushPendingEl();
  });
  var lastHostXY = null;
  setInterval(function () {
    var xy = window.__petHostWinXY;
    if (!xy || typeof xy[0] !== "number") return;
    var k = xy[0] + "," + xy[1];
    if (k === lastHostXY) return;
    lastHostXY = k;
    if (pendingEl) flushPendingEl();
  }, 30);

  /** 收窄态的余量：命中区（∪ 可见气泡）四周留 PAD。拿不到几何时返回 null。 */
  function bodyMargins(s) {
    var g = geomOf(s);
    if (!g) return null;
    var box = { x: g.x, y: g.y, w: g.w, h: g.h };
    var br = visibleRect(s.bubble);
    if (br) {
      var er = s.el.getBoundingClientRect();
      var bx = br.left - er.left, by = br.top - er.top;
      var x1 = Math.min(box.x, bx), y1 = Math.min(box.y, by);
      var x2 = Math.max(box.x + box.w, bx + br.width), y2 = Math.max(box.y + box.h, by + br.height);
      box = { x: x1, y: y1, w: x2 - x1, h: y2 - y1 };
    }
    return marginsFor(s, box);
  }

  /** 窗口钉死在工作区之后，她的屏幕位置 = 画布在窗口里的位置，所以每次位移都要同步 el.left/top。
   *  勾在 sprite.sendBounds 上（所有位移——漫游/拖拽/飞行/回角落——都收口在这个函数）。 */
  function hookBounds(s) {
    if (!fixedWin()) return;
    if (!s || !s.el || s.__boundsHooked || typeof s.sendBounds !== "function") return;
    s.__boundsHooked = true;
    try {
      s.el.style.visibility = "";
    } catch (e) {}
    var orig = s.sendBounds;
    s.sendBounds = function () {
      var out = orig.apply(s, arguments);
      try {
        var v = view();
        s.margin.l = s.pos.x;
        s.margin.t = s.pos.y;
        s.margin.r = Math.max(0, v.w - s.size - s.pos.x);
        s.margin.b = Math.max(0, v.h - s.winH - s.pos.y);
        s.el.style.left = s.pos.x + "px";
        s.el.style.top = s.pos.y + "px";
      } catch (e2) {}
      return out;
    };
    try {
      s.sendBounds(s.pos.x, s.pos.y);
    } catch (e3) {}
  }

  /** 新宿主（窗口钉死在工作区）会推这个标记；老宿主没有 → 走旧逻辑，免得页面先热更、
   *  宿主还没升级时把窗口撑成整屏又没有可触摸区域，把整块屏幕吃掉。 */
  function fixedWin() {
    return window.__petHostFixedWin === 1;
  }

  function tighten() {
    try {
      var s = pet();
      if (!s || !s.el || !s.margin) return;
      if (!s.__origMargin) s.__origMargin = { t: s.margin.t, r: s.margin.r, b: s.margin.b, l: s.margin.l };
      var o = s.__origMargin;
      // 窗口固定铺满整个工作区、永不改变大小：余量恒等于"她到窗口原点的距离"。
      // 放不放得下菜单由宿主的「可触摸区域」决定，不再靠改窗口大小 → 没有撑窗/收窗，也就没有回弹。
      // ★ 参照深深桌宠：窗口就是"一块固定大小的板子"，跟着她走、永不改变尺寸。
      //   她画布在窗口里的位置恒为 (margin.l, margin.t)，所以窗口怎么动她都不会被甩；
      //   菜单/气泡/对话永远放得下（600×483 就是 dsh-pet 给它们留的余量）。
      //   透明余量不参与触摸 —— 宿主那边用 touchableRegion 只圈她身体 + 菜单。
      var want = fixedWin() ? fullMargins(s) : o;
      window.__tightenDbg = JSON.stringify({ mode: fixedWin() ? "fixed" : "small", full: mode !== "body", want: { l: Math.round(want.l), r: Math.round(want.r), b: Math.round(want.b), t: Math.round(want.t) }, cur: { l: Math.round(s.margin.l), r: Math.round(s.margin.r), t: Math.round(s.margin.t) }, geom: s.__geom || null, size: s.size, winH: s.winH });
      applyMargins(s, want);
    } catch (e) {}
  }
  // ---- 挂壁：拖到/飘到屏幕左右边缘附近时自动吸附贴边（"挂"在墙上），配一下东张西望 ----
  var EDGE_SNAP = 26; // CSS px：身体离边缘小于这个值就算"到边了"
  var lastHangAt = 0;
  setInterval(function () {
    try {
      var s = pet();
      if (!s || !s.pos || !s.hit) return;
      if (s.dragState && (s.dragState.active || s.dragState.dragging)) return;
      if (s.throwRef || s.moveRef) return;
      if (s.menuOpen || s.chatOpen) return;
      var g = geomOf(s);
      var v = view();
      if (!g) return;
      var left = s.pos.x + g.x;
      var right = left + g.w;
      var target = null;
      if (left <= EDGE_SNAP) target = v.x - g.x; // 身体左边贴住屏幕左缘
      else if (v.x + v.w - right <= EDGE_SNAP) target = v.x + v.w - g.x - g.w; // 右边贴住右缘
      if (target === null || Math.abs(s.pos.x - target) < 0.6) return;
      s.pos.x = target;
      if (s.customPos) s.customPos = { rx: (target + s.halfW) / v.w, ry: s.customPos.ry };
      if (typeof s.sendBounds === "function") s.sendBounds(s.pos.x, s.pos.y);
      var now = Date.now();
      if (now - lastHangAt > 2500) {
        lastHangAt = now;
        try {
          if (typeof s.playOnce === "function") s.playOnce("东张西望");
        } catch (e) {}
      }
    } catch (e2) {}
  }, 400);

  /** 菜单夹取矩形改由我们给：dsh-pet 原版把物理/ CSS 混算，窗口一贴边（窗口本身伸出屏幕）
   *  夹取范围就会跑到屏幕外，菜单被摆到看不见的地方 —— 表现为"菜单点不了"。
   *  这里用"窗口 ∩ 工作区"的窗口内 CSS 矩形，保证菜单永远落在看得见的地方。 */
  /** 所有位移都收口在 sendBounds：每次调用都标脏，保证"菜单刚弹出来那一刻"的坐标
   *  和她现在的位置一致（不然会用手上还停在上一个位置的旧原点，菜单就摆到屏幕中间去了）。 */
  function patchBounds(s) {
    if (!s || s.__boundsWrapped || typeof s.sendBounds !== "function") return;
    s.__boundsWrapped = true;
    var orig = s.sendBounds;
    s.sendBounds = function () {
      originDirty = true;
      lastMoveAt = Date.now();
      return orig.apply(s, arguments);
    };
  }

  function patchClamp(s) {
    if (!s || s.__clampPatched || typeof s.visibleClampRect !== "function") return;
    s.__clampPatched = true;
    s.visibleClampRect = function () {
      try {
        var v = view();
        var d = window.devicePixelRatio || 1;
        var xy = winXYOf(s);
        var wx = xy[0] / d, wy = xy[1] / d;
        var ww = s.size + s.margin.l + s.margin.r;
        var wh = s.winH + s.margin.t + s.margin.b;
        var x0 = Math.max(v.x, wx), y0 = Math.max(v.y, wy);
        var x1 = Math.min(v.x + v.w, wx + ww), y1 = Math.min(v.y + v.h, wy + wh);
        var w = x1 - x0, h = y1 - y0;
        if (w < 40 || h < 40) return null;
        return { x: x0 - wx, y: y0 - wy, w: w, h: h };
      } catch (e) {
        return null;
      }
    };
  }

  /** 对话窗开/关要通知宿主：开着时窗口得变成"可聚焦 + 拉起输入法"，否则 WebView 收不到键盘。 */
  var chatFocusSent = null;
  function syncChatFocus(s) {
    var open = !!(s && s.chatOpen);
    if (open === chatFocusSent) return;
    chatFocusSent = open;
    try {
      if (window.PetHost && window.PetHost.setChatOpen) window.PetHost.setChatOpen(open);
    } catch (e) {}
  }

  /** ================= 对话：原版弹窗 + 隐形输入代理（2026-10-29） =================
   *
   *  用户要求「对话界面和原插件的输入界面一致」——所以**不再**用宿主那个底部输入条，
   *  直接复用 dsh-pet 自己的组件 window.PetShared.mountChatDialog：
   *  DOM 结构 / CHAT_CSS / 宽度自适应（160→340px）/「说点什么…」占位符 / 红色错误行
   *  全部与桌面端是**同一份代码**，不另抄一套样式。
   *
   *  键盘仍然只能由宿主的 Activity 拉起（悬浮窗是 FLAG_NOT_FOCUSABLE，WebView 永远拿不到
   *  IME；改可聚焦会触发 ColorOS 的"可能造成部分操作无响应"）。区别是那个 Activity 现在
   *  **不可见**：它只托住系统输入法，把每次文本变化经 Bridge.setChatDraft() 回灌到这个弹窗；
   *  回车/发送再把整句交回来（deliverChat → __petHost.chatSend → 上游弹窗自己的 doSend）。
   *  IME 高度也回传（setChatIme），弹窗自动抬到键盘上方——她靠下时卡片不会被键盘盖住。
   *
   *  为什么不让弹窗进 s.chatOpen：上游 inputBusy() 会因此为真，宿主把整块触摸层铺满，
   *  连键盘的点击都会被吃掉（输入法在 Activity 窗口里，比悬浮窗低一层）。所以这里用
   *  自己的 chatMounted 记账，触摸区域只登记弹窗矩形 + 她身体，键盘照常可用。
   *
   *  发送/失败/关闭语义全部交给上游组件：回车发送、发送中禁用、失败红字留在弹窗里、
   *  成功才收起——与浏览器/桌面端逐字一致。
   */
  function chatUrl(s) {
    var base = typeof BASE !== 'undefined' ? BASE : '';
    var id = s && s.pet && s.pet.id ? s.pet.id : 'main';
    return base + '/chat?pet=' + encodeURIComponent(id) + '&surface=desktop';
  }

  /** 对话失败要看得见：借她自己的气泡说一句（失败原因来自上游 ok:false 的 message/reason）。 */
  function chatFailed(s, why) {
    var msg = String(why == null ? '未知错误' : why);
    try {
      if (s && typeof s.showWhisper === 'function') s.showWhisper('对话失败：' + msg, undefined, undefined, 8000, true);
    } catch (e) {}
    try {
      window.__jsLast = JSON.stringify({ chat: 'error', why: msg.slice(0, 160) }).slice(0, 600);
      report('CHAT => ' + window.__jsLast);
    } catch (e2) {}
  }

  /** 菜单「对话」→ 原版弹窗 + 系统键盘（键盘由宿主的隐形 Activity 托住）。 */
  var chatMounted = null;     // {close}
  var chatNatural = null;     // {x, y} 弹窗自然位置（窗口内 CSS）
  var chatImePx = 0;          // 输入法高度（物理 px），0 = 收起
  var chatNoActivity = false; // 探针用：只挂弹窗、不拉输入法

  function chatRoot() { return document.querySelector('.dsh-pet-chat'); }
  function chatTextarea() { return document.querySelector('.dsh-pet-chat-input'); }
  /** 挂弹窗用的 URL：与上游 showChatFromMenu 同一条路径（?pet=&surface=desktop 由组件自己拼）。 */
  function chatBaseUrl() { return (typeof BASE !== 'undefined' ? BASE : '') + '/chat'; }

  /** 把她身体右侧/上侧的默认位置夹进窗口，并在输入法弹出后整体抬到键盘上方。 */
  function placeChat() {
    var root = chatRoot();
    if (!root || !chatNatural) return;
    var r = root.getBoundingClientRect();
    var ime = chatImePx / (window.devicePixelRatio || 1);
    var maxTop = innerHeight - r.height - 6;
    if (ime > 0) maxTop = Math.min(maxTop, innerHeight - ime - r.height - 8);
    var top = Math.max(4, Math.min(chatNatural.y, maxTop));
    var left = Math.max(4, Math.min(chatNatural.x, innerWidth - r.width - 4));
    root.style.left = Math.round(left) + 'px';
    root.style.top = Math.round(top) + 'px';
    // 位置变了 → 宿主的可触摸区域也要跟着挪，否则弹窗被抬起后点不到
    var s = pet();
    if (s && typeof s.syncInputRegions === 'function') {
      try { s.syncInputRegions(); } catch (e) {}
    }
  }

  /** 弹窗关掉（发送成功 / 点外面 / 宿主收起 / 探针）→ 收键盘、恢复触摸区域。 */
  function onChatClosed() {
    chatMounted = null;
    chatNatural = null;
    chatImePx = 0;
    try {
      if (window.PetHost && typeof window.PetHost.closeChatInput === 'function') window.PetHost.closeChatInput();
    } catch (e) {}
    var s = pet();
    if (s && typeof s.syncInputRegions === 'function') {
      try { s.syncInputRegions(); } catch (e2) {}
    }
  }

  function closeChat() {
    var m = chatMounted;
    chatMounted = null; // 先清，避免 m.close() → onClose → onChatClosed 递归
    if (m) {
      try { m.close(); } catch (e) {}
    }
  }

  function openChat(s, skipInput) {
    if (chatMounted) { closeChat(); return 'toggle'; }
    if (!s) return 'no-pet';
    if (!window.PetShared || typeof window.PetShared.mountChatDialog !== 'function') return 'no-shared';
    // 菜单默认在动作派发后自己收起；万一没收到（探针/合成点击路径），下一拍补一刀。
    setTimeout(function () {
      try { if (s.menuOpen) s.closeMenu(); } catch (e) {}
    }, 0);
    var hit = s.hit.getBoundingClientRect();
    var clamp = null;
    try { clamp = s.visibleClampRect ? s.visibleClampRect() : null; } catch (e1) {}
    if (!clamp || !isFinite(clamp.x + clamp.y + clamp.w + clamp.h) || clamp.w < 40) {
      clamp = { x: 0, y: 0, w: innerWidth, h: innerHeight };
    }
    var m = window.PetShared.mountChatDialog({
      petId: s.pet.id,
      baseUrl: chatBaseUrl(),
      surface: 'desktop',
      x: Math.max(4, hit.right + 6),
      y: Math.max(4, hit.top + 6),
      clamp: clamp,
      onSent: function () {},
      onClose: function () { onChatClosed(); },
    });
    chatMounted = m;
    var root = chatRoot();
    if (root) {
      var rr = root.getBoundingClientRect();
      chatNatural = { x: rr.left, y: rr.top };
    }
    // 弹窗是"身外的 UI"：登记它的矩形，宿主才会把这些像素交给页面（其余照常穿透）。
    try { if (typeof s.syncInputRegions === 'function') s.syncInputRegions(); } catch (e2) {}
    if (!skipInput && !chatNoActivity) {
      var ta = chatTextarea();
      var draft = ta ? ta.value : '';
      try {
        if (window.PetHost && typeof window.PetHost.openChatInputWith === 'function') {
          window.PetHost.openChatInputWith(draft);
        } else if (window.PetHost && typeof window.PetHost.openChatInput === 'function') {
          window.PetHost.openChatInput();
        }
      } catch (e3) {}
    }
    return 'ok';
  }

  function installChat(s) {
    if (!s || s.__chatInstalled) return;
    s.__chatInstalled = true;
    s.showChatFromMenu = function () {
      return openChat(s);
    };
  }

  function pushBody() {
    try {
      var sp = pet();
      hookBounds(sp);
      patchBounds(sp);
      patchClamp(sp);
      installChat(sp); // 对话：输入走宿主 Activity，发送直连上游 /chat
      installVideoChrome(); // 压掉 WebView 给 <video> 画的「▶ 播放键 + 矩形占位」
      installVideoPosters();
      installFrameGate(); // 首帧没出来之前不许露面（根治那个矩形+播放键）
      syncChatFocus(sp);
      tighten();
      var s = pet();
      if (!s || !s.hit || !window.PetHost || !window.PetHost.setBody) return;
      var r = s.hit.getBoundingClientRect();
      if (!(r.width > 0 && r.height > 0)) return;
      var dpr = window.devicePixelRatio || 1;
      window.PetHost.setBody(r.left * dpr, r.top * dpr, r.width * dpr, r.height * dpr);
    } catch (e) {
      /* 页面还没装好时静默 */
    }
  }
  // ---- 诊断上报：每 5s 把「视频/容器的实际透明度」回传给插件（/report）----
  // 用来判断"她半透明"到底是：① video 的 alpha 通道问题 ② 某个祖先元素的 opacity
  // ③ 两块 video 同时在淡入淡出。这些都不是靠猜能定的，必须看真实计算值。
  /** 把当前前台视频画进 canvas 逐点取 RGBA —— 直接量出 alpha 到底是 255 还是 128。 */
  function samplePixels() {
    try {
      var v = document.querySelector('video.pet-video.is-front');
      if (!v || !v.videoWidth) return null;
      var c = document.createElement('canvas');
      c.width = v.videoWidth;
      c.height = v.videoHeight;
      var ctx = c.getContext('2d');
      if (!ctx) return null;
      ctx.clearRect(0, 0, c.width, c.height);
      ctx.drawImage(v, 0, 0);
      var pts = [
        [0, 0],
        [320, 180],
        [320, 60],
        [160, 180],
        [480, 180],
        [320, 320],
      ];
      var out = [];
      for (var i = 0; i < pts.length; i++) {
        var d = ctx.getImageData(pts[i][0], pts[i][1], 1, 1).data;
        out.push(pts[i][0] + ',' + pts[i][1] + '=' + d[0] + '/' + d[1] + '/' + d[2] + '/a' + d[3]);
      }
      return out;
    } catch (e) {
      return ['ERR ' + e.message];
    }
  }

  var diagAt = 0;
  var pixelAt = 0;
  function pushDiag() {
    try {
      var now = Date.now();
      if (now - diagAt < 5000) return;
      diagAt = now;
      var vids = [];
      var list = document.querySelectorAll('video.pet-video');
      for (var i = 0; i < list.length; i++) {
        var v = list[i];
        var cs = window.getComputedStyle(v);
        vids.push({
          cls: v.className,
          op: cs.opacity,
          vis: cs.visibility,
          w: v.videoWidth,
          h: v.videoHeight,
          t: Math.round(v.currentTime * 100) / 100,
          paused: v.paused,
          rs: v.readyState,
        });
      }
      var sp = pet();
      var info = {
        t: now,
        vids: vids,
        dpr: window.devicePixelRatio,
        iw: innerWidth,
        screenXY: [window.screenX, window.screenY],
        rootOp: window.getComputedStyle(document.getElementById('root')).opacity,
        bodyOp: window.getComputedStyle(document.body).opacity,
        spriteOp: sp ? window.getComputedStyle(sp.el).opacity : null,
        spriteInlineOp: sp ? sp.el.style.opacity || '' : null,
        anim: sp ? sp.anim : null,
        pixels: now - pixelAt > 20000 ? ((pixelAt = now), samplePixels()) : undefined,
        errors: window.__dshPetDebug ? window.__dshPetDebug.errors : null,
        jsLast: window.__jsLast || null,
      };
      fetch('/dsh-pet-android/report', {
        method: 'POST',
        cache: 'no-store',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ upstreamOk: true, error: 'DIAG ' + JSON.stringify(info) }),
      }).catch(function () {});
    } catch (e) {
      /* 诊断失败不影响主流程 */
    }
  }
  // ---- 远程指令通道：跟着 /desktop/tune 的 pageCmd 走 ----
  // 用途：不重装 App、只看一眼就能判断问题在"视频层"还是"窗口合成层"。
  //   pageCmd = "bg:#ff00ff" → 整窗铺品红；"bg:clear" → 还原。
  /** 把一句话推回插件（/report），用来在看不到屏幕时验证行为。 */
  function report(msg) {
    try {
      fetch('/dsh-pet-android/report', {
        method: 'POST',
        cache: 'no-store',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ upstreamOk: true, error: String(msg) }),
      }).catch(function () {});
    } catch (e) {}
  }
  /** 命中区中心点的屏幕物理坐标（宿主推过来的窗口原点 + 窗口内 CSS 位置 × dpr）。 */
  function hitCenterScreen(s) {
    var h = s.hit.getBoundingClientRect();
    var xy = winXYOf(s);
    var dpr = window.devicePixelRatio || 1;
    return { x: xy[0] + (h.left + h.width / 2) * dpr, y: xy[1] + (h.top + h.height / 2) * dpr, rect: h };
  }
  /** 固定探针（CSP 禁 eval，所以只能跑这些写死的自测）。 */
  function runProbe(name) {
    var s = pet();
    if (!s || !s.hit) return { err: 'no-pet' };
    var dpr = window.devicePixelRatio || 1;
    if (name === 'geom') {
      var h = s.hit.getBoundingClientRect();
      return {
        iw: innerWidth,
        ih: innerHeight,
        dpr: dpr,
        hit: { x: Math.round(h.left), y: Math.round(h.top), w: Math.round(h.width), h: Math.round(h.height) },
        margin: s.margin,
        pos: { x: Math.round(s.pos.x), y: Math.round(s.pos.y) },
        win: window.__petHostWinXY || null,
        size: s.size,
        vis: document.visibilityState,
        tighten: window.__tightenDbg || null,
        hidden: document.hidden,
        raf: !!window.requestAnimationFrame,
      };
    }
    if (name === 'drag') {
      var c = hitCenterScreen(s);
      var before = { x: s.pos.x, y: s.pos.y };
      var dx = 40 * dpr;
      var steps = [window.__petHost.down(c.x, c.y)];
      var i = 0;
      // 必须按真实节奏分帧：6 次 move 同步打完，弹簧一帧都没机会跑，量出来永远是 0。
      (function step() {
        i++;
        if (i <= 6) {
          steps.push(window.__petHost.move(c.x + (dx * i) / 6, c.y));
          setTimeout(step, 40);
          return;
        }
        steps.push(window.__petHost.up(c.x + dx, c.y));
        setTimeout(function () {
          var res = {
            before: { x: Math.round(before.x), y: Math.round(before.y) },
            after: { x: Math.round(s.pos.x), y: Math.round(s.pos.y) },
            dxCss: Math.round(s.pos.x - before.x),
            dyCss: Math.round(s.pos.y - before.y),
            steps: steps.join(','),
          };
          window.__jsLast = JSON.stringify(res).slice(0, 900);
          report('PROBE drag => ' + window.__jsLast);
        }, 600);
      })();
      return { scheduled: true, at: { x: Math.round(c.x), y: Math.round(c.y) }, before: before };
    }
    if (name === 'menu') {
      var c2 = hitCenterScreen(s);
      var r1 = window.__petHost.menu(c2.x, c2.y);
      var el = document.querySelector('.dsh-pet-menu');
      var n = el ? el.querySelectorAll('*').length : 0;
      var openOk = !!(el && el.getBoundingClientRect().height > 0);
      var r2 = window.__petHost.clickAt(10 * dpr, 30 * dpr);
      var el2 = document.querySelector('.dsh-pet-menu');
      var closed = !el2 || el2.getBoundingClientRect().height === 0;
      return { open: r1, menu: !!el, items: n, openOk: openOk, away: r2, closed: closed };
    }
    if (name === 'menuopen') {
      var c3 = hitCenterScreen(s);
      var r3 = window.__petHost.menu(c3.x, c3.y);
      setTimeout(function () {
        var el3 = document.querySelector('.dsh-pet-menu');
        var rr = el3 ? el3.getBoundingClientRect() : null;
        var res3 = {
          open: r3,
          at: { x: Math.round(c3.x), y: Math.round(c3.y) },
          menuRect: rr ? { x: Math.round(rr.left), y: Math.round(rr.top), w: Math.round(rr.width), h: Math.round(rr.height) } : null,
          margin: s.margin,
          iw: innerWidth,
          ih: innerHeight,
        };
        window.__jsLast = JSON.stringify(res3).slice(0, 900);
        report('PROBE menuopen => ' + window.__jsLast);
      }, 600);
      return { scheduled: true };
    }
    if (name === 'menupos') {
      // 菜单有没有落在屏幕里：窗口原点 + 菜单面板的窗口内矩形 + 她身体的窗口内矩形，一次拿全。
      var c6 = hitCenterScreen(s);
      var r6 = window.__petHost.menu(c6.x, c6.y);
      setTimeout(function () {
        var el6 = document.querySelector('.dsh-pet-menu');
        var kids = [];
        var shown = 0;
        if (el6) {
          for (var k6 = 0; k6 < el6.children.length; k6++) {
            var cr6 = el6.children[k6].getBoundingClientRect();
            if (cr6.width > 2 && cr6.height > 2) {
              shown++;
              if (kids.length < 4) kids.push({ x: Math.round(cr6.left), y: Math.round(cr6.top), w: Math.round(cr6.width), h: Math.round(cr6.height) });
            }
          }
        }
        var hb = s.hit.getBoundingClientRect();
        var res6 = {
          ret: r6,
          iw: innerWidth,
          ih: innerHeight,
          win: winXYOf(s),
          dpr: dpr,
          margin: { l: Math.round(s.margin.l), t: Math.round(s.margin.t), r: Math.round(s.margin.r), b: Math.round(s.margin.b) },
          hit: { x: Math.round(hb.left), y: Math.round(hb.top), w: Math.round(hb.width), h: Math.round(hb.height) },
          panels: shown,
          kids: kids,
        };
        window.__jsLast = JSON.stringify(res6).slice(0, 1200);
        report('PROBE menupos => ' + window.__jsLast);
      }, 1400);
      return { scheduled: true };
    }
    if (name === 'menutap4') {
      // 精确复现：她贴右缘（pos 157,101）→ 菜单会盖在她身体上半部 → 点那个重叠区域的菜单项
      var v4 = view(), g4 = geomOf(s), d4 = dpr;
      s.pos.x = 157; s.pos.y = 101;
      if (typeof s.sendBounds === "function") s.sendBounds(s.pos.x, s.pos.y);
      var c4 = hitCenterScreen(s);
      window.__petHost.menu(c4.x, c4.y);
      setTimeout(function () {
        var xy4 = winXYOf(s);
        var hr4 = s.hit.getBoundingClientRect();
        var body = [xy4[0] + hr4.left * d4, xy4[1] + hr4.top * d4, hr4.width * d4, hr4.height * d4];
        var el4 = document.querySelector('.dsh-pet-menu');
        var items = el4 ? el4.querySelectorAll('.dsh-pet-menu-item') : [];
        var target = null;
        for (var i4 = 0; i4 < items.length; i4++) {
          var r4 = items[i4].getBoundingClientRect();
          if (r4.width < 2) continue;
          var t4 = (items[i4].textContent || '');
          if (t4.indexOf('查看余额') >= 0) { target = items[i4]; break; }
          if (!target) target = items[i4];
        }
        var out4 = { body: body.map(Math.round), menuWin: xy4, label: target ? (target.textContent || '').slice(0, 8) : null };
        if (!target) { report('PROBE menutap4 => ' + JSON.stringify(out4)); return; }
        var tr4 = target.getBoundingClientRect();
        var sx4 = Math.round(xy4[0] + (tr4.left + tr4.width / 2) * d4);
        var sy4 = Math.round(xy4[1] + (tr4.top + tr4.height / 2) * d4);
        out4.at = [sx4, sy4];
        out4.inBody = sx4 >= body[0] && sx4 <= body[0] + body[2] && sy4 >= body[1] && sy4 <= body[1] + body[3];
        var elAt4 = document.elementFromPoint((sx4 - xy4[0]) / d4, (sy4 - xy4[1]) / d4);
        out4.elAt = elAt4 ? (elAt4.className || elAt4.tagName) : null;
        window.__petHost.down(sx4, sy4);
        window.__petHost.up(sx4, sy4);
        setTimeout(function () {
          out4.menuGone = !document.querySelector('.dsh-pet-menu');
          window.__jsLast = JSON.stringify(out4).slice(0, 900);
          report('PROBE menutap4 => ' + window.__jsLast);
        }, 500);
      }, 900);
      return { scheduled: true };
    }
    if (name === 'menutap3') {
      // 贴右边缘 → 开菜单 → 用真实路径(down/up)点一个**盖在她身体上**的菜单项
      var v3 = view(), g3 = geomOf(s), d3 = dpr;
      s.pos.x = v3.x + v3.w - g3.x - g3.w;
      if (typeof s.sendBounds === "function") s.sendBounds(s.pos.x, s.pos.y);
      var c3 = hitCenterScreen(s);
      window.__petHost.menu(c3.x, c3.y);
      setTimeout(function () {
        var xy3 = winXYOf(s);
        var body = [xy3[0] + g3.x * d3, xy3[1] + g3.y * d3, g3.w * d3, g3.h * d3];
        var el3 = document.querySelector('.dsh-pet-menu');
        var items = el3 ? el3.querySelectorAll('.dsh-pet-menu-item') : [];
        var target = null;
        for (var i3 = 0; i3 < items.length; i3++) {
          var rr = items[i3].getBoundingClientRect();
          if (rr.width < 2) continue;
          if ((items[i3].textContent || '').indexOf('回到初始位置') >= 0) { target = items[i3]; break; }
        }
        var out3 = { body: body.map(Math.round), found: !!target };
        if (target) {
          var tr = target.getBoundingClientRect();
          var sx3 = Math.round(xy3[0] + (tr.left + tr.width / 2) * d3);
          var sy3 = Math.round(xy3[1] + (tr.top + tr.height / 2) * d3);
          out3.at = [sx3, sy3];
          out3.inBody = sx3 >= body[0] && sx3 <= body[0] + body[2] && sy3 >= body[1] && sy3 <= body[1] + body[3];
          window.__petHost.down(sx3, sy3);
          window.__petHost.up(sx3, sy3);
          setTimeout(function () {
            out3.menuGone = !document.querySelector('.dsh-pet-menu');
            out3.menuOpen = !!s.menuOpen;
            window.__jsLast = JSON.stringify(out3).slice(0, 900);
            report('PROBE menutap3 => ' + window.__jsLast);
          }, 500);
          return;
        }
        window.__jsLast = JSON.stringify(out3).slice(0, 900);
        report('PROBE menutap3 => ' + window.__jsLast);
      }, 900);
      return { scheduled: true };
    }
    if (name === 'edgemenu') {
      // 复现"刚吸附到边缘、宿主还没推新位置时立刻长按"
      var vE2 = view(), gE2 = geomOf(s);
      s.pos.x = vE2.x + vE2.w - gE2.x - gE2.w;
      if (typeof s.sendBounds === "function") s.sendBounds(s.pos.x, s.pos.y);
      var cE = hitCenterScreen(s);
      window.__petHost.menu(cE.x, cE.y);
      setTimeout(function () {
        var elE = document.querySelector('.dsh-pet-menu');
        var panels = [];
        var cols = elE ? elE.querySelectorAll('.dsh-pet-menu-column') : [];
        for (var iE = 0; iE < cols.length; iE++) {
          var rE = cols[iE].getBoundingClientRect();
          if (rE.width > 2) panels.push({ x: Math.round(rE.left), y: Math.round(rE.top), w: Math.round(rE.width), h: Math.round(rE.height) });
        }
        var xyE = winXYOf(s), dE = dpr;
        var outE = { win: xyE, pos: Math.round(s.pos.x), panels: panels };
        if (panels.length) {
          var pE = panels[0];
          var sxE = Math.round(xyE[0] + (pE.x + pE.w / 2) * dE);
          var syE = Math.round(xyE[1] + (pE.y + 20) * dE);
          var clE = clientOf(s, sxE, syE);
          var el2 = document.elementFromPoint(clE.x, clE.y);
          var it2 = el2 && el2.closest ? el2.closest('.dsh-pet-menu-item') : null;
          outE.probe = { sx: sxE, sy: syE, hitText: it2 ? (it2.textContent || '').slice(0, 12) : el2 ? el2.className : null };
        }
        window.__jsLast = JSON.stringify(outE).slice(0, 900);
        report('PROBE edgemenu => ' + window.__jsLast);
      }, 800);
      return { scheduled: true };
    }
    if (name === 'menudbg2') {
      var cM2 = hitCenterScreen(s);
      var before = { menuOpen: !!s.menuOpen, justDragged: !!s.justDragged, clamp: null };
      try { before.clamp = s.visibleClampRect ? s.visibleClampRect() : null; } catch (e) {}
      window.__petHost.menu(cM2.x, cM2.y);
      var rows = [];
      [120, 650, 1400].forEach(function (d) {
        setTimeout(function () {
          var el = document.querySelector('.dsh-pet-menu');
          rows.push({ t: d, menuOpen: !!s.menuOpen, kids: el ? el.childElementCount : -1, shown: el ? el.offsetWidth : 0, jd: !!s.justDragged });
          if (d === 1400) {
            window.__jsLast = JSON.stringify({ before: before, rows: rows, pos: { x: Math.round(s.pos.x) }, win: winXYOf(s) }).slice(0, 900);
            report('PROBE menudbg2 => ' + window.__jsLast);
          }
        }, d);
      });
      return { scheduled: true };
    }
    if (name === 'edge') {
      var vE = view(), gE = geomOf(s);
      if (!gE) return { err: "no-geom" };
      var beforeE = Math.round(s.pos.x);
      var targetE = vE.x + vE.w - gE.x - gE.w - 6; // 先放到距右缘 6 CSS
      s.pos.x = targetE;
      if (typeof s.sendBounds === "function") s.sendBounds(s.pos.x, s.pos.y);
      setTimeout(function () {
        var gy = geomOf(s) || gE;
        window.__jsLast = JSON.stringify({
          before: beforeE,
          putTo: Math.round(targetE),
          after: Math.round(s.pos.x),
          bodyRight: Math.round(s.pos.x + gy.x + gy.w),
          screenRight: Math.round(vE.x + vE.w),
          gap: Math.round(vE.x + vE.w - (s.pos.x + gy.x + gy.w)),
        }).slice(0, 600);
        report('PROBE edge => ' + window.__jsLast);
      }, 900);
      return { scheduled: true };
    }
    if (name === 'menuitems') {
      var cM = hitCenterScreen(s);
      window.__petHost.menu(cM.x, cM.y);
      setTimeout(function () {
        var all = document.querySelectorAll('.dsh-pet-menu-item');
        var vis = [];
        for (var i = 0; i < all.length; i++) {
          var r = all[i].getBoundingClientRect();
          var t = (all[i].textContent || '').trim().slice(0, 8);
          if (r.width > 2 && r.height > 2) vis.push(t);
        }
        window.__jsLast = JSON.stringify({ visible: vis }).slice(0, 900);
        report('PROBE menuitems => ' + window.__jsLast);
      }, 900);
      return { scheduled: true };
    }
    if (name === 'chat') {
      // 只报"对话通道是否装好"，不动宿主 UI（不会弹输入框）
      var out0 = {
        installed: !!s.__chatInstalled,
        menuHasChat: false,
        upstream: !!(window.PetShared && typeof window.PetShared.mountChatDialog === 'function'),
        bridge: {
          setChatOpen: !!(window.PetHost && window.PetHost.setChatOpen),
          openChatInput: !!(window.PetHost && typeof window.PetHost.openChatInput === 'function'),
          openChatInputWith: !!(window.PetHost && typeof window.PetHost.openChatInputWith === 'function'),
          closeChatInput: !!(window.PetHost && typeof window.PetHost.closeChatInput === 'function'),
          chatDraft: typeof window.__petHost.chatDraft === 'function',
          chatIme: typeof window.__petHost.chatIme === 'function',
          chatSend: typeof window.__petHost.chatSend === 'function',
        },
        endpoint: chatUrl(s),
        pet: s.pet ? s.pet.id : null,
        base: typeof BASE !== 'undefined' ? BASE : null,
      };
      try {
        var items0 = document.querySelectorAll('.dsh-pet-menu-item');
        for (var i0 = 0; i0 < items0.length; i0++) {
          if ((items0[i0].textContent || '').replace(/\s+/g, '').indexOf('对话') === 0 && items0[i0].offsetParent !== null) out0.menuHasChat = true;
        }
      } catch (e0) {}
      window.__jsLast = JSON.stringify(out0).slice(0, 900);
      report('PROBE chat => ' + window.__jsLast);
      return out0;
    }
    if (name === 'chatopen') {
      // 走「菜单对话」那条路，但**不**拉输入法：验证弹窗本身（上游组件）有没有挂上、位置对不对
      var r1 = 'none';
      try { r1 = openChat(s, true); } catch (e1) { r1 = 'err:' + e1.message; }
      setTimeout(function () {
        var o1 = { ret: String(r1), state: window.__petHost.chatState() };
        try { closeChat(); } catch (e1b) {}
        window.__jsLast = JSON.stringify(o1).slice(0, 1100);
        report('PROBE chatopen => ' + window.__jsLast);
      }, 500);
      return { scheduled: true };
    }
    if (name === 'chatui') {
      // 纯页面侧自检：挂弹窗 → 灌草稿（宽度应变宽）→ 模拟键盘高度 1400px（应抬高）→ 量完关掉。
      // 不碰输入法/Activity，所以旧 APK 也能复现这一半。
      var oU = {};
      try {
        if (chatMounted) closeChat();
        chatNoActivity = true;
        oU.mount = openChat(s, true);
        var rootU = chatRoot();
        var taU = chatTextarea();
        if (rootU) {
          var rU0 = rootU.getBoundingClientRect();
          oU.w0 = Math.round(rU0.width);
          oU.h0 = Math.round(rU0.height);
          oU.pos0 = [Math.round(rU0.left), Math.round(rU0.top)];
          var csU = getComputedStyle(rootU);
          oU.css = { radius: csU.borderRadius, bg: csU.backgroundColor, font: csU.fontSize, z: csU.zIndex };
          oU.placeholder = taU ? taU.placeholder : null;
          oU.inputPh = taU ? taU.placeholder : null;
          oU.className = rootU.className;
        }
        oU.draft = window.__petHost.chatDraft('测试文字一二三四五六七八九十一二三四五六七八九十');
        if (rootU) {
          var rU1 = rootU.getBoundingClientRect();
          oU.w1 = Math.round(rU1.width);
          oU.value = taU ? taU.value : null;
        }
        oU.ime = window.__petHost.chatIme(1400);
        if (rootU) {
          var rU2 = rootU.getBoundingClientRect();
          oU.pos2 = [Math.round(rU2.left), Math.round(rU2.top)];
          oU.lifted = rU2.top <= innerHeight - 1400 / (window.devicePixelRatio || 1) + 4;
        }
        oU.state = window.__petHost.chatState();
        closeChat();
        chatNoActivity = false;
      } catch (eU) {
        oU.err = String(eU && eU.message ? eU.message : eU);
        chatNoActivity = false;
      }
      window.__jsLast = JSON.stringify(oU).slice(0, 1400);
      report('PROBE chatui => ' + window.__jsLast);
      return oU;
    }
    if (name === 'chatlive') {
      // 真机链路：挂弹窗 + 拉起隐形输入代理（键盘应弹出、弹窗应抬到键盘上方）。
      // 之后用户可以直接打字 → 文字回灌到弹窗 → 回车发送。
      var rL = 'none';
      try { rL = openChat(s); } catch (eL) { rL = 'err:' + eL.message; }
      setTimeout(function () {
        var oL = { ret: String(rL), state: window.__petHost.chatState() };
        window.__jsLast = JSON.stringify(oL).slice(0, 1100);
        report('PROBE chatlive => ' + window.__jsLast);
      }, 2600);
      return { scheduled: true };
    }
    if (name === 'chattest') {
      // 宿主整链自检：ChatInputActivity 不打字，直接发一句 → deliverChat → 页面 chatSend → /chat
      var r3 = 'no-bridge';
      try {
        if (window.PetHost && typeof window.PetHost.chatSelfTest === 'function') {
          window.PetHost.chatSelfTest();
          r3 = 'launched';
        }
      } catch (e3) { r3 = 'err:' + e3.message; }
      window.__jsLast = JSON.stringify({ selfTest: r3, bridge: !!(window.PetHost && window.PetHost.chatSelfTest) }).slice(0, 400);
      report('PROBE chattest => ' + window.__jsLast);
      return { selfTest: r3 };
    }
    if (name === 'chatsend') {
      // 模拟「Activity 把文字交回来」：__petHost.chatSend 直连 /chat，然后报气泡里收到了什么
      var r2 = window.__petHost.chatSend('测试一下，收到回个话');
      setTimeout(function () {
        var bub = s && s.bubble ? (s.bubble.textContent || '').slice(0, 60) : null;
        var out2 = {
          ret: r2,
          bubble: bub,
          bubbleOn: !!(s && s.bubbleOn),
          anim: s ? s.anim : null,
          last: window.__jsLast,
        };
        window.__jsLast = JSON.stringify(out2).slice(0, 900);
        report('PROBE chatsend => ' + window.__jsLast);
      }, 6000);
      return { scheduled: true };
    }
    if (name === 'hit') {
      // 按"肉眼看到的菜单项位置"换成屏幕坐标点下去，看命中的是不是它自己
      var cA = hitCenterScreen(s);
      window.__petHost.menu(cA.x, cA.y);
      setTimeout(function () {
        var elA = document.querySelector('.dsh-pet-menu');
        var outA = { win: winXYOf(s), iw: innerWidth, ih: innerHeight };
        var items = [];
        var all = elA ? elA.querySelectorAll('.dsh-pet-menu-item') : [];
        for (var i = 0; i < all.length; i++) {
          var rr = all[i].getBoundingClientRect();
          if (rr.width > 2 && rr.height > 2) items.push({ el: all[i], r: rr, t: (all[i].textContent || '').slice(0, 14) });
        }
        outA.n = items.length;
        var pick = null;
        for (var j = 0; j < items.length; j++) if (items[j].t.indexOf('回到初始位置') >= 0) { pick = items[j]; break; }
        if (!pick) pick = items[1] || items[0];
        if (!pick) {
          window.__jsLast = JSON.stringify(outA).slice(0, 900);
          report('PROBE hit => ' + window.__jsLast);
          return;
        }
        var xy = winXYOf(s), d = dpr;
        var sx = Math.round(xy[0] + (pick.r.left + pick.r.width / 2) * d);
        var sy = Math.round(xy[1] + (pick.r.top + pick.r.height / 2) * d);
        outA.tap = pick.t;
        outA.at = { sx: sx, sy: sy };
        outA.visual = { x: Math.round(pick.r.left), y: Math.round(pick.r.top), h: Math.round(pick.r.height) };
        var cl = clientOf(s, sx, sy);
        var elHit = document.elementFromPoint(cl.x, cl.y);
        var itHit = elHit && elHit.closest ? elHit.closest('.dsh-pet-menu-item') : null;
        outA.client = { x: Math.round(cl.x), y: Math.round(cl.y) };
        outA.hitText = itHit ? (itHit.textContent || '').slice(0, 14) : elHit ? elHit.className || elHit.tagName : null;
        outA.match = !!itHit && itHit === pick.el;
        window.__jsLast = JSON.stringify(outA).slice(0, 1100);
        report('PROBE hit => ' + window.__jsLast);
      }, 1100);
      return { scheduled: true };
    }
    if (name === 'submenu') {
      // 级联子菜单在「钉死左上角」的窗口里还放不放得下：悬停第一个分支项，量父子面板矩形。
      var c9 = hitCenterScreen(s);
      var r9 = window.__petHost.menu(c9.x, c9.y);
      setTimeout(function () {
        var el9 = document.querySelector('.dsh-pet-menu');
        var out = { ret: r9, iw: innerWidth, ih: innerHeight, panels: [] };
        var branch = el9 ? el9.querySelector('.dsh-pet-menu-item.dsh-pet-menu-branch') : null;
        out.label = branch ? (branch.textContent || '').slice(0, 24) : null;
        function collect() {
          var vis = [];
          var ps = el9 ? el9.querySelectorAll('.dsh-pet-menu-column') : [];
          for (var q = 0; q < ps.length; q++) {
            var qr = ps[q].getBoundingClientRect();
            if (qr.width > 2) vis.push({ x: Math.round(qr.left), y: Math.round(qr.top), w: Math.round(qr.width), h: Math.round(qr.height) });
          }
          return vis;
        }
        out.panels = collect();
        if (branch) {
          var br9 = branch.getBoundingClientRect();
          branch.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, clientX: br9.left + 10, clientY: br9.top + 10, view: window }));
          branch.dispatchEvent(new MouseEvent('mouseenter', { bubbles: false, clientX: br9.left + 10, clientY: br9.top + 10, view: window }));
          setTimeout(function () {
            out.child = collect();
            window.__jsLast = JSON.stringify(out).slice(0, 1200);
            report('PROBE submenu => ' + window.__jsLast);
          }, 350);
          return;
        }
        window.__jsLast = JSON.stringify(out).slice(0, 900);
        report('PROBE submenu => ' + window.__jsLast);
      }, 1200);
      return { scheduled: true };
    }
    if (name === 'bounce') {
      // 撑窗/收窗时她有没有被"甩"到别处：每 16ms 采「画布窗口内坐标 + 窗口真实屏幕位置」，
      // 只记状态变化的帧（t 不计入判重），看 scr（= 她实际的屏幕位置）抖不抖。
      var t0 = Date.now();
      var cap = { open: [], close: [] };
      var lastKey = null;
      function snapB(phase, tag) {
        var elb = s.el.getBoundingClientRect();
        var xyb = window.__petHostWinXY || [0, 0];
        var d = window.devicePixelRatio || 1;
        var row = {
          t: Date.now() - t0,
          iw: innerWidth,
          win: [xyb[0], xyb[1]],
          el: [Math.round(elb.left), Math.round(elb.top)],
          scr: [Math.round(xyb[0] + elb.left * d), Math.round(xyb[1] + elb.top * d)],
          vis: s.el.style.visibility || 'visible',
        };
        var key = JSON.stringify([row.iw, row.win, row.el, row.scr, row.vis]);
        if (tag || key !== lastKey) {
          row.tag = tag || '';
          cap[phase].push(row);
          lastKey = key;
        }
      }
      snapB('open', 'pre');
      var c8 = hitCenterScreen(s);
      window.__petHost.menu(c8.x, c8.y);
      var iv8 = setInterval(function () {
        snapB('open', '');
      }, 16);
      setTimeout(function () {
        clearInterval(iv8);
        snapB('open', 'end');
        snapB('close', 'pre');
        try {
          if (typeof s.closeMenu === 'function') s.closeMenu();
        } catch (e8) {}
        var iv9 = setInterval(function () {
          snapB('close', '');
        }, 16);
        setTimeout(function () {
          clearInterval(iv9);
          snapB('close', 'end');
          window.__jsLast = JSON.stringify({ open: cap.open, close: cap.close }).slice(0, 1500);
          report('PROBE bounce => ' + window.__jsLast);
        }, 800);
      }, 800);
      return { scheduled: true };
    }
    if (name === 'menutap') {
      // 端到端：开菜单 → 点「回到初始位置」→ 看菜单是否收起。验证菜单真的点得到（不只是画在屏幕里）。
      var c7 = hitCenterScreen(s);
      var r7 = window.__petHost.menu(c7.x, c7.y);
      setTimeout(function () {
        var out = { ret: r7, found: false };
        var el7 = document.querySelector('.dsh-pet-menu');
        var target = null;
        if (el7) {
          var items7 = el7.querySelectorAll('.dsh-pet-menu-item');
          for (var i7 = 0; i7 < items7.length; i7++) {
            if ((items7[i7].textContent || '').indexOf('回到初始位置') >= 0) {
              target = items7[i7];
              break;
            }
          }
        }
        if (!target) {
          out.items = el7 ? el7.querySelectorAll('.dsh-pet-menu-item').length : 0;
          window.__jsLast = JSON.stringify(out).slice(0, 900);
          report('PROBE menutap => ' + window.__jsLast);
          return;
        }
        out.found = true;
        var tr = target.getBoundingClientRect();
        var xy7 = window.__petHostWinXY || [0, 0];
        var d7 = window.devicePixelRatio || 1;
        out.item = { x: Math.round(tr.left), y: Math.round(tr.top), w: Math.round(tr.width), h: Math.round(tr.height) };
        out.at = { sx: Math.round(xy7[0] + (tr.left + tr.width / 2) * d7), sy: Math.round(xy7[1] + (tr.top + tr.height / 2) * d7) };
        out.hitEl = window.__petHost.clickAt(out.at.sx, out.at.sy);
        setTimeout(function () {
          out.menuGone = !document.querySelector('.dsh-pet-menu');
          out.busy = typeof s.isInputBusy === 'function' ? s.isInputBusy() : null;
          out.iw = innerWidth;
          window.__jsLast = JSON.stringify(out).slice(0, 900);
          report('PROBE menutap => ' + window.__jsLast);
        }, 800);
      }, 1200);
      return { scheduled: true };
    }
    if (name === 'menudbg') {
      var c4 = hitCenterScreen(s);
      var r4 = window.__petHost.menu(c4.x, c4.y);
      var busyErr = 'none';
      try {
        window.PetHost.setInputBusy(true);
      } catch (e4) {
        busyErr = String(e4.message);
      }
      setTimeout(function () {
        var el4 = document.querySelector('.dsh-pet-menu');
        var info = { open: r4, busyErr: busyErr, hookSeen: !!window.__inputBusy, iw: innerWidth, ih: innerHeight };
        if (el4) {
          var cs4 = window.getComputedStyle(el4);
          info.disp = cs4.display;
          info.pos = cs4.position;
          info.vis = cs4.visibility;
          info.op = cs4.opacity;
          info.tr = cs4.transform;
          info.cls = el4.className;
          info.kids = el4.childElementCount;
          var shown = 0, maxr = 0, minx = 1e9, maxx = -1e9;
          for (var k = 0; k < el4.children.length; k++) {
            var cr = el4.children[k].getBoundingClientRect();
            if (cr.width > 2 && cr.height > 2) {
              shown++;
              var a = Math.round(cr.left), z = Math.round(cr.left + cr.width);
              if (a < minx) minx = a;
              if (z > maxx) maxx = z;
            }
          }
          info.shownKids = shown;
          info.spanX = shown ? minx + '..' + maxx : null;
        }
        window.__jsLast = JSON.stringify(info).slice(0, 900);
        report('PROBE menudbg => ' + window.__jsLast);
      }, 700);
      return { scheduled: true };
    }
    if (name === 'cover') {
      // 整行命中自测：菜单项里**文字/箭头**都比整行小，事件必须落在行本体上才算数。
      // 这里按「文字的矩形中心」逐级点下去（分支→分支→叶子），并记录每一级是否真的展开。
      var shownCols = function () {
        var n = 0, cs = document.querySelectorAll('.dsh-pet-menu-column');
        for (var i = 0; i < cs.length; i++) {
          var r = cs[i].getBoundingClientRect();
          if (r.width > 2 && r.height > 2) n++;
        }
        return n;
      };
      var findItem = function (txt) {
        var items = document.querySelectorAll('.dsh-pet-menu-item');
        for (var i = 0; i < items.length; i++) {
          var r = items[i].getBoundingClientRect();
          if (r.width < 2 || r.height < 2) continue;
          var lb = items[i].querySelector('span');
          var t = ((lb ? lb.textContent : items[i].textContent) || '').trim();
          if (t === txt) return items[i];
        }
        return null;
      };
      var tapLabel = function (txt) {
        var it = findItem(txt);
        if (!it) return { txt: txt, hit: 'not-found' };
        var lb = it.querySelector('span') || it;
        var lr = lb.getBoundingClientRect();
        var xy = winXYOf(s);
        var px = Math.round(xy[0] + (lr.left + lr.width / 2) * dpr);
        var py = Math.round(xy[1] + (lr.top + lr.height / 2) * dpr);
        var elAt = document.elementFromPoint((lr.left + lr.width / 2), (lr.top + lr.height / 2));
        var out = {
          txt: txt,
          at: [px, py],
          // 这块就是病根：点在文字上时 elementFromPoint 给的是 <span>，不是 .dsh-pet-menu-item
          elAt: elAt ? (elAt.className || elAt.tagName) : null,
          onItem: !!(elAt && elAt.closest && elAt.closest('.dsh-pet-menu-item') === it),
        };
        window.__petHost.down(px, py);
        window.__petHost.up(px, py);
        out.cols = shownCols();
        return out;
      };
      s.pos.x = 40;
      s.pos.y = 120;
      if (typeof s.sendBounds === 'function') s.sendBounds(s.pos.x, s.pos.y);
      setTimeout(function () {
        var cC = hitCenterScreen(s);
        window.__petHost.menu(cC.x, cC.y);
        setTimeout(function () {
          var outC = { step: {} };
          outC.step.before = shownCols();
          outC.step.l1 = tapLabel('动作');
          setTimeout(function () {
            outC.step.l2 = tapLabel('点击回应');
            setTimeout(function () {
              // 第三级：点第一个具体动画（叶子）→ 菜单应当整棵关闭
              var leafName = null;
              try {
                var colsC = document.querySelectorAll('.dsh-pet-menu-column');
                for (var i = 0; i < colsC.length; i++) {
                  var rc = colsC[i].getBoundingClientRect();
                  if (rc.width < 2) continue;
                  var its = colsC[i].querySelectorAll('.dsh-pet-menu-item');
                  if (its.length && rc.left > 0) {
                    var lbC = its[0].querySelector('span');
                    leafName = lbC ? (lbC.textContent || '').trim() : null;
                    if (leafName) break;
                  }
                }
              } catch (eC) {}
              if (leafName) outC.step.l3 = tapLabel(leafName);
              setTimeout(function () {
                outC.step.after = shownCols();
                outC.step.menuGone = !document.querySelector('.dsh-pet-menu');
                outC.step.menuOpenFlag = !!s.menuOpen;
                outC.step.anim = s.anim;
                outC.step.win = winXYOf(s);
                window.__jsLast = JSON.stringify(outC).slice(0, 1400);
                report('PROBE cover => ' + window.__jsLast);
              }, 420);
            }, 420);
          }, 420);
        }, 700);
      }, 350);
      return { scheduled: true };
    }
    if (name === 'cover2') {
      // 左/中/右三个位置各测一遍：点「动作」这一行的**文字**，看子面板有没有真的展开。
      // 位置由几何现算（不写死像素），菜单位置随她走。
      var sh2 = function () {
        var n = 0, cs = document.querySelectorAll('.dsh-pet-menu-column');
        for (var i = 0; i < cs.length; i++) {
          var r = cs[i].getBoundingClientRect();
          if (r.width > 2 && r.height > 2) n++;
        }
        return n;
      };
      var find2 = function (txt) {
        var items = document.querySelectorAll('.dsh-pet-menu-item');
        for (var i = 0; i < items.length; i++) {
          var r = items[i].getBoundingClientRect();
          if (r.width < 2 || r.height < 2) continue;
          var lb = items[i].querySelector('span');
          var t = ((lb ? lb.textContent : items[i].textContent) || '').trim();
          if (t === txt) return items[i];
        }
        return null;
      };
      var v2 = view(), g2 = geomOf(s);
      var xs = [
        ['left', Math.round(v2.x - g2.x)],
        ['mid', Math.round((v2.w - s.size) / 2)],
        ['right', Math.round(v2.x + v2.w - g2.x - g2.w)],
      ];
      var res2 = { pos: {}, panels: {}, win: {} };
      var run2 = function (i) {
        if (i >= xs.length) {
          window.__jsLast = JSON.stringify(res2).slice(0, 1400);
          report('PROBE cover2 => ' + window.__jsLast);
          return;
        }
        var tag = xs[i][0];
        s.pos.x = xs[i][1];
        s.pos.y = 120;
        if (typeof s.sendBounds === 'function') s.sendBounds(s.pos.x, s.pos.y);
        setTimeout(function () {
          var c2 = hitCenterScreen(s);
          window.__petHost.menu(c2.x, c2.y);
          setTimeout(function () {
            var it = find2('动作');
            if (!it) {
              res2.panels[tag] = 'no-item';
              res2.pos[tag] = [Math.round(s.pos.x), Math.round(s.pos.y)];
              try { s.closeMenu(); } catch (e2) {}
              setTimeout(function () { run2(i + 1); }, 300);
              return;
            }
            var lb = it.querySelector('span') || it;
            var lr = lb.getBoundingClientRect();
            var xy = winXYOf(s);
            var px = Math.round(xy[0] + (lr.left + lr.width / 2) * dpr);
            var py = Math.round(xy[1] + (lr.top + lr.height / 2) * dpr);
            // 记录：这一行的窗口内矩形、菜单面板矩形、她身体矩形 —— 位置全算出来的，没有常量
            res2.pos[tag] = [Math.round(s.pos.x), Math.round(s.pos.y)];
            res2.win[tag] = winXYOf(s);
            window.__petHost.down(px, py);
            window.__petHost.up(px, py);
            setTimeout(function () {
              res2.panels[tag] = sh2();
              try { s.closeMenu(); } catch (e3) {}
              setTimeout(function () { run2(i + 1); }, 400);
            }, 400);
          }, 800);
        }, 800);
      };
      run2(0);
      return { scheduled: true };
    }
    if (name === 'cover3') {
      // 边界覆盖：① 点在第三级面板顶部 4px 内边距（行的正上方 2px）→ 应命中最上面那一行；
      //            ② 点在第二行的最右边缘（箭头位置）→ 应命中第二行。菜单应当关闭并播放对应动画。
      var cnt3 = function () {
        var n = 0, cs = document.querySelectorAll('.dsh-pet-menu-column');
        for (var i = 0; i < cs.length; i++) {
          var r = cs[i].getBoundingClientRect();
          if (r.width > 2 && r.height > 2) n++;
        }
        return n;
      };
      var find3 = function (txt) {
        var items = document.querySelectorAll('.dsh-pet-menu-item');
        for (var i = 0; i < items.length; i++) {
          var r = items[i].getBoundingClientRect();
          if (r.width < 2 || r.height < 2) continue;
          var lb = items[i].querySelector('span');
          var t = ((lb ? lb.textContent : items[i].textContent) || '').trim();
          if (t === txt) return items[i];
        }
        return null;
      };
      var tapAt3 = function (txt) {
        var it = find3(txt);
        if (!it) return null;
        var lr = it.getBoundingClientRect();
        var xy = winXYOf(s);
        var px = Math.round(xy[0] + (lr.left + lr.width / 2) * dpr);
        var py = Math.round(xy[1] + (lr.top + lr.height / 2) * dpr);
        window.__petHost.down(px, py);
        window.__petHost.up(px, py);
        return true;
      };
      // 找到"最后一级"面板（DOM 里最后挂上的那一个可见面板）
      var deepest3 = function () {
        var cs = document.querySelectorAll('.dsh-pet-menu-column'), last = null;
        for (var i = 0; i < cs.length; i++) {
          var r = cs[i].getBoundingClientRect();
          if (r.width > 2 && r.height > 2) last = cs[i];
        }
        return last;
      };
      var out3 = { path: [] };
      s.pos.x = 40;
      s.pos.y = 140;
      if (typeof s.sendBounds === 'function') s.sendBounds(s.pos.x, s.pos.y);
      setTimeout(function () {
        var c3 = hitCenterScreen(s);
        window.__petHost.menu(c3.x, c3.y);
        setTimeout(function () {
          tapAt3('动作');
          setTimeout(function () {
            tapAt3('点击回应');
            setTimeout(function () {
              var col = deepest3();
              if (!col) { out3.err = 'no-deep-panel'; window.__jsLast = JSON.stringify(out3); report('PROBE cover3 => ' + window.__jsLast); return; }
              var its = col.querySelectorAll('.dsh-pet-menu-item');
              var r0 = its[0].getBoundingClientRect(), r1 = its[1].getBoundingClientRect();
              var name0 = (its[0].textContent || '').trim(), name1 = (its[1].textContent || '').trim();
              var xy = winXYOf(s);
              // ① 行上方 2px（面板 4px 内边距里）
              var ax = Math.round(xy[0] + (r0.left + r0.width / 2) * dpr);
              var ay = Math.round(xy[1] + (r0.top - 2) * dpr);
              out3.padTap = { at: [ax, ay], expect: name0, colsBefore: cnt3() };
              window.__petHost.down(ax, ay);
              window.__petHost.up(ax, ay);
              setTimeout(function () {
                out3.padTap.gone = !document.querySelector('.dsh-pet-menu');
                out3.padTap.anim = s.anim;
                out3.padTap.ok = out3.padTap.gone && s.anim === name0;
                // ② 重开，点第二行最右边缘
                var c3b = hitCenterScreen(s);
                window.__petHost.menu(c3b.x, c3b.y);
                setTimeout(function () {
                  tapAt3('动作');
                  setTimeout(function () {
                    tapAt3('点击回应');
                    setTimeout(function () {
                      var col2 = deepest3();
                      if (!col2) { out3.err2 = 'no-deep-panel2'; window.__jsLast = JSON.stringify(out3); report('PROBE cover3 => ' + window.__jsLast); return; }
                      var it2 = col2.querySelectorAll('.dsh-pet-menu-item')[1];
                      var rr = it2.getBoundingClientRect();
                      var nm = (it2.textContent || '').trim();
                      var xy2 = winXYOf(s);
                      var bx = Math.round(xy2[0] + (rr.right - 3) * dpr);
                      var by = Math.round(xy2[1] + (rr.top + rr.height / 2) * dpr);
                      out3.rightTap = { at: [bx, by], expect: nm, rect: [Math.round(rr.left), Math.round(rr.top), Math.round(rr.width), Math.round(rr.height)] };
                      window.__petHost.down(bx, by);
                      window.__petHost.up(bx, by);
                      setTimeout(function () {
                        out3.rightTap.gone = !document.querySelector('.dsh-pet-menu');
                        out3.rightTap.anim = s.anim;
                        out3.rightTap.ok = out3.rightTap.gone && s.anim === nm;
                        out3.win = winXYOf(s);
                        window.__jsLast = JSON.stringify(out3).slice(0, 1400);
                        report('PROBE cover3 => ' + window.__jsLast);
                      }, 420);
                    }, 420);
                  }, 420);
                }, 700);
              }, 420);
            }, 420);
          }, 420);
        }, 700);
      }, 400);
      return { scheduled: true };
    }
    if (name === 'scroll') {
      // 长列表滚动自测：动作→玩耍（27 个动画）在 460px 封顶的面板里放不下。
      // ① 滑动应当能滚 ② 滑动松手那一下不许被当成点击 ③ 滚到底之后最后一项要能点到。
      var deep = function () {
        var cs = document.querySelectorAll('.dsh-pet-menu-column'), last = null;
        for (var i = 0; i < cs.length; i++) {
          var r = cs[i].getBoundingClientRect();
          if (r.width > 2 && r.height > 2) last = cs[i];
        }
        return last;
      };
      var tapTxt = function (t) {
        var its = document.querySelectorAll('.dsh-pet-menu-item');
        for (var i = 0; i < its.length; i++) {
          var r = its[i].getBoundingClientRect();
          if (r.width < 2 || r.height < 2) continue;
          var lb = its[i].querySelector('span');
          var s2 = ((lb ? lb.textContent : '') || '').trim();
          if (s2 !== t) continue;
          var lr = (lb || its[i]).getBoundingClientRect();
          var xy = winXYOf(s);
          var px = Math.round(xy[0] + (lr.left + lr.width / 2) * dpr);
          var py = Math.round(xy[1] + (lr.top + lr.height / 2) * dpr);
          window.__petHost.down(px, py);
          window.__petHost.up(px, py);
          return true;
        }
        return false;
      };
      var o5 = {};
      s.pos.x = 40;
      s.pos.y = 140;
      if (typeof s.sendBounds === 'function') s.sendBounds(s.pos.x, s.pos.y);
      setTimeout(function () {
        var c5 = hitCenterScreen(s);
        window.__petHost.menu(c5.x, c5.y);
        setTimeout(function () {
          o5.a = tapTxt('动作');
          setTimeout(function () {
            o5.b = tapTxt('玩耍');
            setTimeout(function () {
              var col = deep();
              if (!col) { o5.err = 'no-panel'; window.__jsLast = JSON.stringify(o5); report('PROBE scroll => ' + window.__jsLast); return; }
              var its = col.querySelectorAll('.dsh-pet-menu-item');
              var xy = winXYOf(s);
              var cr = col.getBoundingClientRect();
              var px = Math.round(xy[0] + (cr.left + cr.width / 2) * dpr);
              var py = Math.round(xy[1] + (cr.top + cr.height / 2) * dpr);
              o5.items = its.length;
              o5.clientH = col.clientHeight;
              o5.scrollH = col.scrollHeight;
              o5.scrollTop0 = col.scrollTop;
              o5.lastName = (its[its.length - 1].textContent || '').trim();
              var rLast = its[its.length - 1].getBoundingClientRect();
              o5.lastVisible0 = rLast.bottom <= cr.bottom + 1;
              var animBefore = s.anim;
              // 模拟宿主的一串 move（手指往上滑 → 内容往下滚）
              for (var k = 1; k <= 6; k++) window.__petHost.move(px, py - k * 30 * dpr);
              o5.clickRet = window.__petHost.clickAt(px, py); // 宿主松手时会这么做
              o5.scrollTop1 = col.scrollTop;
              setTimeout(function () {
                o5.animAfterScroll = s.anim;
                o5.noAccidentalPlay = s.anim === animBefore;
                // 再滚到底，然后点最后一项
                for (var m = 1; m <= 20; m++) window.__petHost.move(px, py - m * 40 * dpr);
                o5.scrollTop2 = col.scrollTop;
                setTimeout(function () {
                  var its2 = col.querySelectorAll('.dsh-pet-menu-item');
                  var last = its2[its2.length - 1];
                  var lr2 = last.getBoundingClientRect();
                  var cr2 = col.getBoundingClientRect();
                  o5.lastVisibleAfter = lr2.bottom <= cr2.bottom + 1 && lr2.top >= cr2.top - 1;
                  var lx = Math.round(xy[0] + (lr2.left + lr2.width / 2) * dpr);
                  var ly = Math.round(xy[1] + (lr2.top + lr2.height / 2) * dpr);
                  o5.tapLastAt = [lx, ly];
                  window.__petHost.down(lx, ly);
                  window.__petHost.up(lx, ly);
                  setTimeout(function () {
                    o5.tapLastGone = !document.querySelector('.dsh-pet-menu');
                    o5.anim = s.anim;
                    o5.tapLastOk = o5.tapLastGone && s.anim === o5.lastName;
                    window.__jsLast = JSON.stringify(o5).slice(0, 1400);
                    report('PROBE scroll => ' + window.__jsLast);
                  }, 420);
                }, 420);
              }, 420);
            }, 420);
          }, 420);
        }, 700);
      }, 400);
      return { scheduled: true };
    }
    if (name === 'reset') {
      try {
        if (typeof s.closeMenu === 'function') s.closeMenu();
      } catch (e5) {}
      try { closeChat(); } catch (e5b) {}
      s.chatOpen = false;
      if (s.dragState) {
        s.dragState.active = false;
        s.dragState.dragging = false;
      }
      pressed = false;
      setTimeout(function () {
        var r5 = { busy: typeof s.isInputBusy === 'function' ? s.isInputBusy() : null, menuOpen: !!s.menuOpen, dragActive: !!(s.dragState && s.dragState.active), iw: innerWidth, ih: innerHeight, margin: s.margin };
        window.__jsLast = JSON.stringify(r5).slice(0, 900);
        report('PROBE reset => ' + window.__jsLast);
      }, 800);
      return { scheduled: true };
    }
    return { err: 'unknown probe: ' + name };
  }
  var lastCmd = null;
  function pollCmd() {
    fetch('/dsh-pet-android/desktop/tune', { cache: 'no-store' })
      .then(function (r) {
        return r.json();
      })
      .then(function (o) {
        var cmd = (o && o.tune && o.tune.pageCmd) || '';
        if (cmd === lastCmd) return;
        lastCmd = cmd;
        if (o && o.tune && o.tune.refPatch) {
          var d = document.getElementById('refpatch');
          if (!d) {
            d = document.createElement('div');
            d.id = 'refpatch';
            document.body.appendChild(d);
            d.style.cssText = 'position:fixed;left:150px;top:60px;width:80px;height:40px;z-index:2147483001;' +
              'background:linear-gradient(90deg,#000 0 50%,#fff 50% 100%);';
          }
        }
        if (cmd.indexOf('bg:') === 0) {
          document.body.style.backgroundColor = cmd === 'bg:clear' ? '' : cmd.slice(3);
        }
        // 远程自测：pageCmd = "probe:geom|drag|menu"。
        // 页面有 CSP（script-src 'self'），eval 被禁，所以这里只跑固定探针。
        // 宿主的调试端口不一定活着，而页面每 3s 自己来拉一次——这条路永远在。
        if (cmd.indexOf('probe:') === 0) {
          var out;
          try {
            out = JSON.stringify(runProbe(cmd.slice(6)));
          } catch (e3) {
            out = 'ERR ' + e3.message;
          }
          window.__jsLast = String(out).slice(0, 900);
          report('PROBE ' + cmd + ' => ' + String(out).slice(0, 900));
        }
      })
      .catch(function () {});
  }
  // 尽早挂 sendBounds 钩子：窗口钉死之后，她的屏幕位置完全靠 el，晚挂一秒她就会先出现在错误位置
  var bootHook = setInterval(function () {
    var s0 = pet();
    if (!s0) return;
    hookBounds(s0);
    if (s0.__boundsHooked) clearInterval(bootHook);
  }, 50);
  setInterval(pollCmd, 3000);
  setTimeout(pollCmd, 800);
  setInterval(pushDiag, 5000);
  setInterval(showIfSettled, 16);
  setTimeout(pushDiag, 2500);
  setInterval(pushBody, 250);
  // 心跳（v5.1 宿主看门狗靠它判活）：老宿主没有这个方法，typeof 判一下即可，老版无感。
  function beat() {
    try {
      var H = window.PetHost;
      if (H && typeof H.heartbeat === 'function') H.heartbeat();
    } catch (e) {}
  }
  setInterval(beat, 5000);
  setTimeout(beat, 1200);
  setTimeout(pushBody, 1200);

  window.__petHost = {
    version: 'android-1',
    down: function (sx, sy) {
      var s = pet();
      if (!s) return 'no-pet';
      scrollDrag = null; // 新的一次按下 → 上一次的滑动结束
      // 对话弹窗：落点在卡片上 → 这一下交给卡片（点输入框 = 重新拉起输入法）。
      // 必须先于 pokeMouseDown 判断：pokeMouseDown 会派发 document 的 mousedown，
      // 上游弹窗的"点外面就关"会把它当成外部点击，卡片一戳就自己没了。
      if (chatMounted) {
        var cD = clientOf(s, sx, sy);
        var elD = document.elementFromPoint(cD.x, cD.y);
        if (elD && elD.closest && elD.closest('.dsh-pet-chat')) {
          swallowUp = true;
          pressed = false;
          try { window.__petHost.clickAt(sx, sy); } catch (eD) {}
          return 'chat';
        }
      }
      // 菜单/对话开着时：这一下必须给菜单。宿主是按"有没有落在她身体上"来分流的，
      // 而菜单常常就盖在她身上 —— 不拦的话点击会被身体吃掉，表现就是"菜单点不了"。
      if (s.menuOpen || s.chatOpen) {
        swallowUp = true;
        pressed = false;
        window.__petHost.clickAt(sx, sy);
        return 'menu';
      }
      swallowUp = false;
      pressed = true;
      // 菜单/对话窗开着时按到她身上，桌面端也会先触发 document 的 mousedown → 菜单关闭。
      pokeMouseDown(s, sx, sy);
      try {
        s.onPointerDown(mkEv(s, sx, sy));
      } catch (e) {
        return 'err:' + e.message;
      }
      return 'ok';
    },
    move: function (sx, sy) {
      var s = pet();
      if (!s) return 'idle';
      // 菜单开着、这次又不是拖她：这就是"在菜单上滑动" → 滚那一列。
      // 长列表（玩耍 27 项、时节 21 项）在 460px 封顶的面板里放不下，没有滚动的话
      // 下面那截选项**永远点不到**。宿主只在移动超过 18px 时才发 move，天然带防抖。
      if (s.menuOpen && !pressed) {
        try {
          if (!scrollDrag) {
            var cS = clientOf(s, sx, sy);
            var elS = document.elementFromPoint(cS.x, cS.y);
            var colS = elS && elS.closest ? elS.closest('.dsh-pet-menu-column') : null;
            if (!colS) return 'idle';
            scrollDrag = { col: colS, lastY: sy };
            return 'scroll-start';
          }
          var dyCss = (scrollDrag.lastY - sy) / (window.devicePixelRatio || 1);
          scrollDrag.lastY = sy;
          scrollDrag.col.scrollTop += dyCss;
          scrollGuardUntil = Date.now() + 250; // 松手那一帧的 click 不算点击
          return 'scroll';
        } catch (eS) {
          return 'scroll-err';
        }
      }
      if (!pressed) return 'idle';
      try {
        s.onPointerMove(mkEv(s, sx, sy));
      } catch (e) {
        return 'err:' + e.message;
      }
      return 'ok';
    },
    up: function (sx, sy) {
      var s = pet();
      if (!s) return 'no-pet';
      if (swallowUp) { // 这一下已经交给菜单了，别再当成点她
        swallowUp = false;
        pressed = false;
        return 'menu';
      }
      var wasDrag = !!(s.dragState && s.dragState.dragging);
      pressed = false;
      try {
        s.onPointerUp(mkEv(s, sx, sy));
      } catch (e) {
        return 'err:' + e.message;
      }
      if (!wasDrag) {
        try {
          s.onClick(mkEv(s, sx, sy));
        } catch (e) {
          /* 点击处理失败不影响拖拽链 */
        }
      }
      return wasDrag ? 'drag' : 'tap';
    },
    /** 长按 = 桌面端的右键菜单。
     *  **先撑窗、再弹菜单**：菜单是按「窗口 ∩ 工作区」夹取的，窗口还是她身体那么大时，
     *  菜单会被夹进那个小窗；等窗口放大到工作区，原点一左移，菜单就钉在旧坐标上 →
     *  整个菜单跑到屏幕左上角、半个在屏幕外点不到（2026-10-28 实测坐标）。
     *  所以这里先把余量换成工作区、等宿主真的改完窗（innerWidth/Height 到位），再派发右键。 */
    menu: function (sx, sy) {
      var s = pet();
      if (!s) return 'no-pet';
      var diagM = null;
      try {
        var cM = clientOf(s, sx, sy);
        diagM = {
          sx: Math.round(sx), sy: Math.round(sy),
          pos: [Math.round(s.pos.x), Math.round(s.pos.y)],
          margin: [Math.round(s.margin.l), Math.round(s.margin.t)],
          model: modelOrigin(s),
          host: window.__petHostWinXY || null,
          used: winXYOf(s),
          dirty: originDirty,
          client: [Math.round(cM.x), Math.round(cM.y)],
          iw: innerWidth, ih: innerHeight,
        };
      } catch (eD) {}
      pressed = false;
      try {
        s.onPointerUp(mkEv(s, sx, sy));
      } catch (e) {
        /* 先收尾再弹菜单，避免拖拽态卡住 */
      }
      // 窗口永远是她身体 + 600×483 余量：直接弹，不用撑窗、不用等
      try {
        s.onContextMenu(mkEv(s, sx, sy, { button: 2, buttons: 2 }));
      } catch (e2) {
        if (diagM) {
          diagM.err = String(e2.message);
          window.__jsLast = JSON.stringify(diagM).slice(0, 900);
          report('MENU => ' + window.__jsLast);
        }
        return 'err:' + e2.message;
      }
      if (diagM) {
        setTimeout(function () {
          try {
            var elM = document.querySelector('.dsh-pet-menu');
            var colsM = elM ? elM.querySelectorAll('.dsh-pet-menu-column') : [];
            for (var iM = 0; iM < colsM.length; iM++) {
              var rM = colsM[iM].getBoundingClientRect();
              if (rM.width > 2) {
                diagM.panel = [Math.round(rM.left), Math.round(rM.top), Math.round(rM.width), Math.round(rM.height)];
                diagM.panelScreen = [Math.round(diagM.used[0] + rM.left * (window.devicePixelRatio || 1)), Math.round(diagM.used[1] + rM.top * (window.devicePixelRatio || 1))];
                break;
              }
            }
            diagM.menuOpen = !!s.menuOpen;
            window.__jsLast = JSON.stringify(diagM).slice(0, 900);
            report('MENU => ' + window.__jsLast);
          } catch (eM) {}
        }, 400);
      }
      return 'ok';
    },
    /** 非身体区域（菜单项 / 可展开气泡 / 对话按钮）→ 在该点合成一次完整的鼠标序列。
     *  只发 click 不够：级联菜单的**分支项**是靠 mouseenter 展开子面板的（叶子项才是 click），
     *  而且菜单通过 document 上的 mousedown 做「点外面关掉」，缺 mousedown 会让状态不干净。 */
    clickAt: function (sx, sy) {
      var s = pet();
      if (!s) return 'no-pet';
      scrollDrag = null;
      // 刚在菜单上滑动过 → 松手这一下不算点击（否则滚一下就把某个动画点播了）
      if (Date.now() < scrollGuardUntil) return 'scrolled';
      var c = clientOf(s, sx, sy);
      ensureMenuHitCss();
      // 菜单：整行命中（文字/箭头/内边距/缝隙都算），而不是 elementFromPoint 给的那个 span
      var item = menuItemNear(c);
      var el = item || document.elementFromPoint(c.x, c.y);
      if (!el) return 'no-el';
      var base = {
        bubbles: true,
        cancelable: true,
        clientX: c.x,
        clientY: c.y,
        screenX: sx,
        screenY: sy,
        view: window,
        button: 0,
        buttons: 1,
        detail: 1,
      };
      var up = { bubbles: true, cancelable: true, clientX: c.x, clientY: c.y, screenX: sx, screenY: sy, view: window, button: 0, buttons: 0, detail: 1 };
      try {
        el.dispatchEvent(new MouseEvent('mouseover', base));
        el.dispatchEvent(new MouseEvent('mouseenter', { bubbles: false, cancelable: false, clientX: c.x, clientY: c.y, view: window }));
        el.dispatchEvent(new PointerEvent('pointerdown', base));
        el.dispatchEvent(new MouseEvent('mousedown', base));
      } catch (e) {
        /* 合成失败也继续试后面的 */
      }
      try {
        el.dispatchEvent(new PointerEvent('pointerup', up));
        el.dispatchEvent(new MouseEvent('mouseup', up));
        el.dispatchEvent(new MouseEvent('click', base));
      } catch (e) {
        return 'err:' + e.message;
      }
      // 点对话窗 → 拉起系统输入框（悬浮窗里的 WebView 拿不到输入法，硬抢焦点会触发 ROM 报警）。
      // 草稿一起带过去：上一次发送失败时卡片里还有字，重开键盘不能把它们清空。
      try {
        if (el.closest && el.closest('.dsh-pet-chat') && window.PetHost) {
          var taC = chatTextarea();
          if (typeof window.PetHost.openChatInputWith === 'function') {
            window.PetHost.openChatInputWith(taC ? taC.value : '');
          } else if (window.PetHost.openChatInput) {
            window.PetHost.openChatInput();
          }
        }
      } catch (e2) {}
      return String(el.className || el.tagName);
    },
    /** 系统输入框把文字交回来。
     *  ① 页面里有上游弹窗 → 照桌面端的方式塞进去回车（弹窗自己 POST、自己关）；
     *  ② 没有弹窗（Android 的正常路径）→ 直接 POST 上游 /chat。 */
    chatSend: function (text) {
      var s = pet();
      var t = String(text == null ? '' : text).trim();
      if (!s) return 'no-pet';
      if (!t) return 'empty';
      var ta = document.querySelector('.dsh-pet-chat-input');
      if (ta) {
        try {
          ta.focus();
          ta.value = t;
          ta.dispatchEvent(new Event('input', { bubbles: true }));
          ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
          return 'popup';
        } catch (e) {
          /* 弹窗这条路坏了就落到下面的直连 */
        }
      }
      var url = chatUrl(s);
      var t0 = Date.now();
      try {
        fetch(url, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ text: t }),
          signal: AbortSignal.timeout(60000),
        })
          .then(function (r) { return r.json().catch(function () { return null; }); })
          .then(function (o) {
            if (o && o.ok === true) {
              window.__jsLast = JSON.stringify({ chat: 'ok', ms: Date.now() - t0, pet: s.pet.id, text: t.slice(0, 24) }).slice(0, 600);
              report('CHAT => ' + window.__jsLast);
              return;
            }
            chatFailed(s, (o && (o.message || o.reason)) || '响应非法');
          })
          .catch(function (e) { chatFailed(s, e && e.message ? e.message : String(e)); });
      } catch (e) {
        return 'err:' + e.message;
      }
      return 'sent';
    },
    /** 隐形输入代理把文字回灌进来（每次 TextWatcher 一次）。
     *  只写 value + 触发 input：上游 mountChatDialog 自己的 resizeInput 会把宽度/高度撑好，
     *  我们不碰它的排版逻辑。 */
    chatDraft: function (text) {
      var ta = chatTextarea();
      if (!ta) return 'no-chat';
      var v = String(text == null ? '' : text);
      if (ta.value === v) return 'same';
      try {
        ta.value = v;
        ta.dispatchEvent(new Event('input', { bubbles: true }));
      } catch (e) {
        return 'err:' + e.message;
      }
      return 'ok';
    },
    /** 输入法高度（物理 px）→ 弹窗抬到键盘上方；0 = 键盘收起，回原位。 */
    chatIme: function (px) {
      chatImePx = Math.max(0, Number(px) || 0);
      try { placeChat(); } catch (e) {}
      return chatImePx;
    },
    /** 输入代理结束（返回键/取消）→ 页面把弹窗一起关掉，别留下一个没键盘的卡片。 */
    chatClosed: function () {
      if (chatMounted) closeChat();
      return 'ok';
    },
    /** 探针/调试：当前对话状态。 */
    chatState: function () {
      var root = chatRoot();
      var ta = chatTextarea();
      var r = root ? root.getBoundingClientRect() : null;
      var s = pet();
      return JSON.stringify({
        mounted: !!chatMounted,
        root: !!root,
        style: root ? {
          left: root.style.left, top: root.style.top, width: root.style.width,
          radius: getComputedStyle(root).borderRadius,
          bg: getComputedStyle(root).backgroundColor,
          font: getComputedStyle(root).fontSize,
        } : null,
        rect: r ? [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)] : null,
        value: ta ? ta.value : null,
        ph: ta ? ta.placeholder : null,
        imePx: chatImePx,
        natural: chatNatural,
        iw: innerWidth, ih: innerHeight,
        chatOpenFlag: s ? !!s.chatOpen : null,
        busy: s && typeof s.isInputBusy === 'function' ? s.isInputBusy() : null,
      }).slice(0, 1200);
    },
    info: function () {
      var s = pet();
      return JSON.stringify({
        ok: !!s,
        pressed: pressed,
        base: typeof BASE !== 'undefined' ? BASE : null,
        view: view(),
        origin: pet() ? winOrigin(pet()) : null,
        screenXY: [window.screenX, window.screenY],
        pos: s ? { x: s.pos.x, y: s.pos.y, size: s.size, winH: s.winH } : null,
        margin: s ? s.margin : null,
        anim: s ? s.anim : null,
        debug: window.__dshPetDebug || null,
      });
    },
  };
})();

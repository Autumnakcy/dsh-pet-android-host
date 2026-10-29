/*!
 * dsh-pet-android · 页面增量补丁
 *
 * 干这些事（摘要，细节见 host-shim.js 与 HANDOFF.md）：
 *   · 菜单第一项「打开网站 / 打开软件」→「打开 DSH」，点了真的把 DSH 本体切到前台；
 *   · 藏掉行尾那个「Ctrl+左键」提示；
 *   · 把 Ctrl/Cmd+左键 呼出 DSH 的快捷键废掉（退化成本体点击）。
 *
 * 两个来源，同一份代码（HANDOFF §0.11「配置零外部存储依赖」）：
 *   1) 插件路由伺服本文件（普通浏览器预览、以及插件优先）；
 *   2) **APK 的 assets/host-extra.js** —— 页面跑完发现 `window.__petExtraInstalled` 没被置位，
 *      宿主就把 assets 里那份推进来兜底。新设备/插件目录丢了也照样是修好的行为。
 * 幂等守卫在下面：两份都执行时只让先到的生效（否则监听器/定时器成双份）。
 */
(function () {
  'use strict';
  if (window.__petExtraInstalled) return;
  window.__petExtraInstalled = 1;
  var LABEL = '打开 DSH';
  var RE = /^(打开网站|打开网页|打开软件|唤起桌面应用|打开\s*DeepSeek\s*Harness)$/i;

  function items() {
    return document.querySelectorAll('.dsh-pet-menu-item');
  }

  /* 这一行的"文字"只取标题那一段。
   * 坑（2026-10-29 修）：上游的菜单行是 <span>标题</span> + <span class="dsh-pet-menu-hint">Ctrl+左键</span>，
   * 而 .textContent 会把隐藏的提示也算进去 —— 于是整行读到 "打开 DSHCtrl+左键"，
   * 拿它跟 LABEL 全等比较永远不成立，点击既没被拦下也没唤起 App（日志只有
   * "openDshSite ignored"），表现就是"按钮点了没反应"。 */
  function labelOf(it) {
    var first = it.firstElementChild;
    if (first && !first.classList.contains('dsh-pet-menu-hint')) {
      return (first.textContent || '').trim();
    }
    return (it.textContent || '').trim();
  }

  // 菜单项右侧那个「Ctrl+左键」提示（<span class="dsh-pet-menu-hint">）：用户明说不要
  function stripHint(it) {
    var hints = it.querySelectorAll('.dsh-pet-menu-hint');
    for (var j = 0; j < hints.length; j++) {
      var h = hints[j];
      if (/^(Ctrl|Cmd|⌘)\+左键$/i.test((h.textContent || '').trim())) h.style.display = 'none';
    }
  }

  function relabel() {
    var list = items();
    for (var i = 0; i < list.length; i++) {
      var it = list[i];
      stripHint(it);
      var label = it.firstElementChild || it;
      var t = labelOf(it);
      if (!t || t === LABEL) continue;
      if (RE.test(t)) label.textContent = LABEL;
    }
  }

  /** 这一项被按下后把菜单收起来（上游原生行为是「先 close() 再 onAction」）。 */
  function closeMenu() {
    try {
      if (typeof sprites === 'undefined' || !sprites || !sprites[0]) return;
      var s = sprites[0];
      if (s.menuOpen) s.closeMenu();
    } catch (err) {}
  }

  function openDsh(e) {
    closeMenu();
    try {
      var H = window.PetHost;
      if (!H) return;
      // v5.7 起：只有菜单项走 openDsh()；openDshSite() 在宿主里已经变成"只记日志"，
      // 这样上游的 Ctrl+左键 就算绕过页面补丁也唤不起 DSH。
      if (typeof H.openDsh === 'function') H.openDsh();
      else if (typeof H.openDshSite === 'function') H.openDshSite('http://127.0.0.1:3080/');
    } catch (err) {
      /* 老宿主没有这个桥：什么也不做 */
    }
  }

  // 捕获阶段拦：上游那一项本来没有可用桥（window.petBridge 不存在），点了也没反应
  document.addEventListener(
    'click',
    function (e) {
      var el = e.target;
      if (!el || !el.closest) return;
      var it = el.closest('.dsh-pet-menu-item');
      if (!it) return;
      var t = labelOf(it);
      // 认「打开 DSH」；万一上游改了文案 / 这一行没被 relabel，也认原始那几个名字
      if (t !== LABEL && !RE.test(t)) return;
      e.preventDefault();
      e.stopPropagation();
      openDsh(e);
    },
    true,
  );

  /* ───── 宠物头顶那个白色名字气泡（.pet-tooltip）：按用户要求去掉 ─────
   * 上游 sprite.js 的 setTip/renderTip 会在**光标悬停在宠物身上**时显示宠物名，
   * 这是给鼠标端的 hover 提示。手机上根本没有 hover：触摸结束后指针仍"留在"宠物身上，
   * 于是气泡一直亮着、还跟着宠物走，看上去就是一个甩不掉的白框。
   *
   * 2026-10-29 二次修（§0.13 那一版**不是万无一失**，真机上白框又回来了）：
   *   第一版只往 head 塞一条 `.pet-tooltip{display:none!important}`，靠 head 的 MutationObserver 看门。
   *   现场实测到过：「`__petExtraInstalled`=1，可 `#pet-hide-tooltip` 根本不在文档里」，
   *   样式没了就没有第二道防线，气泡照样挂回来还跟着她走。
   *   现在三层一起上，任意一层失效都还看不见它：
   *     ① CSS：head 里那条规则（保留，最省事）；
   *     ② 元素：直接把 .pet-tooltip 的内联样式压死（display/opacity/pointer-events 三连 !important），
   *        head 被重建、CSS 被谁抹掉都照样按得住；
   *     ③ 定时器：1s 一次自愈 —— ②只管新冒出来的元素，③负责样式丢了以后补回来。
   *   `.pet-tooltip` 全域只服务这一个名字提示，隐藏零副作用（菜单/气泡/积分都不用它）。 */
  function hideTipEls() {
    var list = document.querySelectorAll(".pet-tooltip");
    for (var i = 0; i < list.length; i++) {
      var t = list[i];
      try {
        if (t.classList.contains("is-on")) t.classList.remove("is-on");
        // 只在需要时写：每帧重复写 style 会白白触发重排/观察器回调
        if (t.style.display !== "none") {
          t.style.setProperty("display", "none", "important");
          t.style.setProperty("opacity", "0", "important");
          t.style.setProperty("pointer-events", "none", "important");
        }
      } catch (err) {}
    }
  }

  function hideNameTip() {
    if (!document.getElementById("pet-hide-tooltip")) {
      var st = document.createElement("style");
      st.id = "pet-hide-tooltip";
      st.textContent = ".pet-tooltip{display:none!important}";
      (document.head || document.documentElement).appendChild(st);
      try { window.PetHost && PetHost.log && PetHost.log("extra: name tooltip hidden"); } catch (err) {}
    }
    hideTipEls();
  }
  hideNameTip();
  try {
    new MutationObserver(hideNameTip).observe(document.head || document.documentElement, { childList: true });
  } catch (err) {}
  // 气泡挂在宠物元素里（sprite.js 重建 el 时一起重建），所以 body 子树也要盯
  try {
    new MutationObserver(hideTipEls).observe(document.body || document.documentElement, {
      childList: true,
      subtree: true,
    });
  } catch (err) {}
  setInterval(hideNameTip, 1000);

  // 菜单是每次右击重建的，用 MutationObserver + 兜底定时器都盯着
  try {
    new MutationObserver(relabel).observe(document.body, { childList: true, subtree: true });
  } catch (err) {}
  setInterval(relabel, 1000);
  relabel();

  /* ───── Ctrl / Cmd + 左键 = 呼出 DSH：按用户要求去掉 ─────
   * 上游 sprite.js 里 isOpenShortcut(e) 判 e.ctrlKey || e.metaKey：
   *   · onPointerDown 里用它"不弹点击积分"
   *   · onClick 里用它"呼出 DSH，不播点击动画"
   * 我们不改上游代码，只把这两个方法包一层：带修饰键时换成一个 ctrlKey/metaKey 都为 false
   * 的代理事件再交给原方法 —— 于是修饰键点击退化成普通点击（有动画、有积分），不再唤起 DSH。
   */
  function fakeEvent(e) {
    if (!e || (!e.ctrlKey && !e.metaKey)) return e;
    try {
      var f = Object.create(e);
      Object.defineProperty(f, 'ctrlKey', { value: false, configurable: true });
      Object.defineProperty(f, 'metaKey', { value: false, configurable: true });
      f.preventDefault = function () { try { e.preventDefault(); } catch (err) {} };
      f.stopPropagation = function () { try { e.stopPropagation(); } catch (err) {} };
      return f;
    } catch (err) {
      return e;
    }
  }

  function killCtrlShortcut() {
    try {
      if (typeof PetSprite === 'undefined' || !PetSprite.prototype) return false;
      var P = PetSprite.prototype;
      if (P.__noCtrlOpen) return true;
      ['onClick', 'onPointerDown'].forEach(function (name) {
        var orig = P[name];
        if (typeof orig !== 'function') return;
        P[name] = function (e) {
          return orig.call(this, fakeEvent(e));
        };
      });
      P.__noCtrlOpen = true;
      try { window.PetHost && PetHost.log && PetHost.log('extra: ctrl+click shortcut disabled'); } catch (err) {}
      return true;
    } catch (err) {
      return false;
    }
  }
  if (!killCtrlShortcut()) {
    var iv = setInterval(function () { if (killCtrlShortcut()) clearInterval(iv); }, 500);
    setTimeout(function () { clearInterval(iv); }, 30000);
  }
})();

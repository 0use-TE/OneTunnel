(() => {
  const root = document.documentElement;
  const toggle = document.querySelector('#theme-toggle');
  const label = document.querySelector('#theme-label');
  const color = document.querySelector('meta[name="theme-color"]');
  const system = window.matchMedia('(prefers-color-scheme: dark)');
  const tabs = [...document.querySelectorAll('[data-screen]')];
  const panel = document.querySelector('#screen-panel');
  const image = document.querySelector('#app-screenshot');
  const link = document.querySelector('#screenshot-link');
  const description = document.querySelector('#screen-description');
  const screens = {
    connections: { name: '连接', description: '三种连接，统一管理。', alt: 'OneTunnel 连接页面，分别管理订阅、WireGuard 和 Tailscale。' },
    proxies: { name: '代理组', description: '按组查看节点，切换出口、测试延迟。', alt: 'OneTunnel 代理组页面，展示节点网格、选择状态和测速入口。' },
    rules: { name: '分流规则', description: '自定义流量去向，长按拖动调整顺序。', alt: 'OneTunnel 分流规则页面，展示国内直连、MATCH 出口和规则列表。' },
    forwarding: { name: '端口转发', description: '访问本机服务，每条转发独立开关。', alt: 'OneTunnel 端口转发页面，展示 WireGuard 和 Tailscale 的独立映射开关。' },
  };
  let selected = 'connections';
  let choice;
  try { choice = localStorage.getItem('onetunnel-theme'); } catch {}
  if (choice !== 'light' && choice !== 'dark') choice = null;

  function showScreen() {
    const screen = screens[selected];
    const source = `assets/screenshots/${selected}-${root.dataset.theme}.webp`;
    image.src = source;
    image.alt = screen.alt;
    link.href = source;
    link.setAttribute('aria-label', `查看${screen.name}页面原图`);
    description.textContent = screen.description;
    panel.setAttribute('aria-labelledby', `tab-${selected}`);
    tabs.forEach(tab => {
      const active = tab.dataset.screen === selected;
      tab.setAttribute('aria-selected', String(active));
      tab.tabIndex = active ? 0 : -1;
    });
  }
  function apply(theme) {
    root.dataset.theme = theme;
    const dark = theme === 'dark';
    label.textContent = dark ? '浅色' : '深色';
    toggle.setAttribute('aria-pressed', String(dark));
    toggle.setAttribute('aria-label', dark ? '切换浅色主题' : '切换深色主题');
    color.setAttribute('content', dark ? '#101419' : '#ffffff');
    showScreen();
  }
  apply(choice || (system.matches ? 'dark' : 'light'));
  toggle.addEventListener('click', () => {
    choice = root.dataset.theme === 'dark' ? 'light' : 'dark';
    try { localStorage.setItem('onetunnel-theme', choice); } catch {}
    apply(choice);
  });
  system.addEventListener('change', event => {
    if (!choice) apply(event.matches ? 'dark' : 'light');
  });
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => { selected = tab.dataset.screen; showScreen(); });
    tab.addEventListener('keydown', event => {
      let target;
      if (event.key === 'ArrowRight') target = (index + 1) % tabs.length;
      if (event.key === 'ArrowLeft') target = (index + tabs.length - 1) % tabs.length;
      if (event.key === 'Home') target = 0;
      if (event.key === 'End') target = tabs.length - 1;
      if (target === undefined) return;
      event.preventDefault();
      tabs[target].focus();
      tabs[target].click();
    });
  });
})();

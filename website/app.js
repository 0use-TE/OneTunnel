(() => {
  const root = document.documentElement;
  const toggle = document.querySelector('#theme-toggle');
  const label = document.querySelector('#theme-label');
  const color = document.querySelector('meta[name="theme-color"]');
  const system = window.matchMedia('(prefers-color-scheme: dark)');
  let choice;
  try { choice = localStorage.getItem('onetunnel-theme'); } catch {}
  if (choice !== 'light' && choice !== 'dark') choice = null;

  function apply(theme) {
    root.dataset.theme = theme;
    const dark = theme === 'dark';
    label.textContent = dark ? '浅色' : '深色';
    toggle.setAttribute('aria-pressed', String(dark));
    toggle.setAttribute('aria-label', dark ? '切换浅色主题' : '切换深色主题');
    color.setAttribute('content', dark ? '#10151d' : '#f7f9fc');
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
})();

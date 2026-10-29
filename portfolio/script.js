(() => {
  const menu = document.querySelector('.menu-button');
  const nav = document.getElementById('site-nav');
  const links = [...nav.querySelectorAll('a')];
  const sections = links.map(link => document.querySelector(link.hash)).filter(Boolean);
  const closeMenu = () => {
    nav.classList.remove('open');
    menu.setAttribute('aria-expanded', 'false');
  };
  menu.addEventListener('click', () => {
    const open = menu.getAttribute('aria-expanded') !== 'true';
    menu.setAttribute('aria-expanded', String(open));
    nav.classList.toggle('open', open);
  });
  links.forEach(link => link.addEventListener('click', closeMenu));
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && menu.getAttribute('aria-expanded') === 'true') {
      closeMenu();
      menu.focus();
    }
  });
  document.addEventListener('click', event => {
    if (!event.target.closest('.site-header')) closeMenu();
  });
  window.matchMedia('(min-width: 781px)').addEventListener('change', event => {
    if (event.matches) closeMenu();
  });
  let scheduled = false;
  function updateActiveSection() {
    scheduled = false;
    const current = [...sections].reverse().find(section => section.getBoundingClientRect().top < 180);
    links.forEach(link => {
      const active = current?.id === link.hash.slice(1);
      link.classList.toggle('active', active);
      if (active) link.setAttribute('aria-current', 'location');
      else link.removeAttribute('aria-current');
    });
  }
  window.addEventListener('scroll', () => {
    if (!scheduled) {
      scheduled = true;
      requestAnimationFrame(updateActiveSection);
    }
  }, { passive: true });
  updateActiveSection();
  const filmTabs = [...document.querySelectorAll('.film-tabs [role="tab"]')];
  const selectFilm = (selectedTab) => {
    filmTabs.forEach(tab => {
      const selected = tab === selectedTab;
      const panel = document.getElementById(tab.getAttribute('aria-controls'));
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
      if (!selected) panel.querySelector('video')?.pause();
      panel.hidden = !selected;
    });
  };
  filmTabs.forEach((tab, index) => {
    tab.addEventListener('click', () => selectFilm(tab));
    tab.addEventListener('keydown', event => {
      let next;
      if (event.key === 'ArrowRight') next = (index + 1) % filmTabs.length;
      else if (event.key === 'ArrowLeft') next = (index - 1 + filmTabs.length) % filmTabs.length;
      else if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = filmTabs.length - 1;
      else return;
      event.preventDefault();
      selectFilm(filmTabs[next]);
      filmTabs[next].focus();
    });
  });
  document.getElementById('year').textContent = new Date().getFullYear();
})();

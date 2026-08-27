(() => {
  const header = document.querySelector('.site-header');
  const progress = document.querySelector('.page-progress span');
  const menuButton = document.querySelector('.menu-button');
  const nav = document.querySelector('.site-nav');
  const navLinks = [...document.querySelectorAll('.site-nav a')];
  const sections = navLinks
    .map((link) => document.querySelector(link.getAttribute('href')))
    .filter(Boolean);
  const year = document.getElementById('year');
  const promoVideo = document.getElementById('promo-video');
  const parallaxItems = [...document.querySelectorAll('[data-parallax]')];
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let frameRequested = false;

  if (year) year.textContent = new Date().getFullYear();
  if (promoVideo) promoVideo.volume = 0.5;

  const closeMenu = () => {
    nav?.classList.remove('open');
    menuButton?.setAttribute('aria-expanded', 'false');
  };

  const updateScrollUI = () => {
    frameRequested = false;
    const scrollTop = window.scrollY || document.documentElement.scrollTop;
    const max = document.documentElement.scrollHeight - window.innerHeight;
    const ratio = max > 0 ? Math.min(1, scrollTop / max) : 0;

    if (progress) progress.style.width = `${ratio * 100}%`;
    header?.classList.toggle('scrolled', scrollTop > 12);

    const activeSection = [...sections]
      .reverse()
      .find((section) => section.getBoundingClientRect().top <= window.innerHeight * 0.42);

    navLinks.forEach((link) => {
      const isActive = activeSection && link.getAttribute('href') === `#${activeSection.id}`;
      link.classList.toggle('active', Boolean(isActive));
    });

    if (!reducedMotion.matches) {
      parallaxItems.forEach((item) => {
        const rect = item.getBoundingClientRect();
        const speed = Number(item.dataset.parallax) || 0;
        const offset = (window.innerHeight * .5 - (rect.top + rect.height * .5)) * speed;
        item.style.setProperty('--parallax-y', `${Math.max(-32, Math.min(32, offset))}px`);
      });
    }
  };

  const requestScrollUpdate = () => {
    if (frameRequested) return;
    frameRequested = true;
    requestAnimationFrame(updateScrollUI);
  };

  menuButton?.addEventListener('click', () => {
    const isOpen = menuButton.getAttribute('aria-expanded') === 'true';
    menuButton.setAttribute('aria-expanded', String(!isOpen));
    nav?.classList.toggle('open', !isOpen);
  });

  navLinks.forEach((link) => link.addEventListener('click', closeMenu));
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeMenu();
  });

  document.querySelectorAll('a[href="#top"]').forEach((link) => {
    link.addEventListener('click', (event) => {
      event.preventDefault();
      window.scrollTo({ top: 0, behavior: reducedMotion.matches ? 'auto' : 'smooth' });
    });
  });

  document.querySelectorAll('[data-delay]').forEach((element) => {
    element.style.setProperty('--delay', `${element.dataset.delay}ms`);
  });

  if ('IntersectionObserver' in window && !reducedMotion.matches) {
    const revealObserver = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('visible');
        revealObserver.unobserve(entry.target);
      });
    }, { threshold: .08, rootMargin: '0px 0px -32px' });

    document.querySelectorAll('.reveal').forEach((element) => revealObserver.observe(element));
  } else {
    document.querySelectorAll('.reveal').forEach((element) => element.classList.add('visible'));
  }

  updateScrollUI();
  window.addEventListener('scroll', requestScrollUpdate, { passive: true });
  window.addEventListener('resize', requestScrollUpdate, { passive: true });
})();

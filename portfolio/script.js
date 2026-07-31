(() => {
  const header = document.querySelector('.site-header');
  const progress = document.querySelector('.page-progress span');
  const menuButton = document.querySelector('.menu-button');
  const nav = document.querySelector('.site-nav');
  const year = document.getElementById('year');
  const promoVideo = document.getElementById('promo-video');

  if (year) year.textContent = new Date().getFullYear();
  if (promoVideo) promoVideo.volume = 0.5;

  const updateScrollUI = () => {
    const scrollTop = window.scrollY || document.documentElement.scrollTop;
    const max = document.documentElement.scrollHeight - window.innerHeight;
    const ratio = max > 0 ? Math.min(1, scrollTop / max) : 0;
    if (progress) progress.style.width = `${ratio * 100}%`;
    header?.classList.toggle('scrolled', scrollTop > 16);
  };

  updateScrollUI();
  window.addEventListener('scroll', updateScrollUI, { passive: true });

  menuButton?.addEventListener('click', () => {
    const isOpen = menuButton.getAttribute('aria-expanded') === 'true';
    menuButton.setAttribute('aria-expanded', String(!isOpen));
    nav?.classList.toggle('open', !isOpen);
  });

  nav?.querySelectorAll('a').forEach((link) => {
    link.addEventListener('click', () => {
      nav.classList.remove('open');
      menuButton?.setAttribute('aria-expanded', 'false');
    });
  });

  document.querySelectorAll('[data-delay]').forEach((el) => {
    el.style.setProperty('--delay', `${el.dataset.delay}ms`);
  });

  if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.1, rootMargin: '0px 0px -24px' });
    document.querySelectorAll('.reveal').forEach((el) => observer.observe(el));
  } else {
    document.querySelectorAll('.reveal').forEach((el) => el.classList.add('visible'));
  }

})();

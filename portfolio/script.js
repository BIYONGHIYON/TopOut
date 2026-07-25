(() => {
  const header = document.querySelector('.site-header');
  const progress = document.querySelector('.page-progress span');
  const menuButton = document.querySelector('.menu-button');
  const nav = document.querySelector('.site-nav');
  const video = document.getElementById('promo-video');
  const videoPlaceholder = document.getElementById('video-placeholder');

  document.getElementById('year').textContent = new Date().getFullYear();

  const updateScrollUI = () => {
    const scrollTop = window.scrollY || document.documentElement.scrollTop;
    const max = document.documentElement.scrollHeight - window.innerHeight;
    const ratio = max > 0 ? Math.min(1, scrollTop / max) : 0;
    progress.style.width = `${ratio * 100}%`;
    header.classList.toggle('scrolled', scrollTop > 16);
  };
  updateScrollUI();
  window.addEventListener('scroll', updateScrollUI, { passive: true });

  menuButton?.addEventListener('click', () => {
    const isOpen = menuButton.getAttribute('aria-expanded') === 'true';
    menuButton.setAttribute('aria-expanded', String(!isOpen));
    nav.classList.toggle('open', !isOpen);
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

  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add('visible');
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -30px' });

  document.querySelectorAll('.reveal').forEach((el) => observer.observe(el));

  // 로고 파일이 실제로 존재하는 슬롯만 이미지로 전환합니다.
  document.querySelectorAll('.logo-slot img').forEach((img) => {
    const markLoaded = () => img.closest('.logo-slot')?.classList.add('has-image');
    if (img.complete && img.naturalWidth > 0) markLoaded();
    img.addEventListener('load', markLoaded);
  });

  // 프로모션 영상이 없을 때는 안내용 플레이스홀더를 유지합니다.
  if (video) {
    video.addEventListener('loadedmetadata', () => videoPlaceholder?.classList.add('hidden'));
    video.addEventListener('error', () => videoPlaceholder?.classList.remove('hidden'));
    videoPlaceholder?.addEventListener('click', () => {
      if (video.readyState >= 1) {
        videoPlaceholder.classList.add('hidden');
        video.play().catch(() => videoPlaceholder.classList.remove('hidden'));
      }
    });
  }
})();

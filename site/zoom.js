// Click-to-zoom for screenshots. Any `main figure img` opens in a full-screen dialog; clicking the
// enlarged image toggles between fit-to-screen and actual size (scrollable). An optional
// `data-full` attribute points at a higher-resolution file that is only fetched on zoom.
(() => {
  const images = document.querySelectorAll('main figure img');
  if (!images.length || typeof HTMLDialogElement !== 'function') return;

  const dialog = document.createElement('dialog');
  dialog.className = 'zoom-dialog';
  dialog.setAttribute('aria-label', 'Enlarged screenshot');
  dialog.innerHTML =
    '<button class="zoom-close" type="button" aria-label="Close">×</button>' +
    '<div class="zoom-stage"><img alt="" /></div>';
  document.body.appendChild(dialog);
  const big = dialog.querySelector('img');
  const stage = dialog.querySelector('.zoom-stage');

  const setFull = (full, event) => {
    // Keep the clicked point under the cursor when switching to actual size.
    const rect = big.getBoundingClientRect();
    const rx = event ? (event.clientX - rect.left) / rect.width : 0.5;
    const ry = event ? (event.clientY - rect.top) / rect.height : 0.5;
    dialog.classList.toggle('is-full', full);
    if (full) {
      dialog.scrollLeft = big.offsetLeft + rx * big.offsetWidth - window.innerWidth / 2;
      dialog.scrollTop = big.offsetTop + ry * big.offsetHeight - window.innerHeight / 2;
    }
  };

  const open = (img) => {
    dialog.classList.remove('is-full');
    big.src = img.dataset.full || img.currentSrc || img.src;
    big.alt = img.alt;
    dialog.showModal();
  };

  images.forEach((img) => {
    img.classList.add('zoomable');
    img.tabIndex = 0;
    img.setAttribute('role', 'button');
    img.setAttribute('aria-label', `${img.alt || 'Screenshot'} (open larger)`);
    img.addEventListener('click', () => open(img));
    img.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        open(img);
      }
    });
  });

  big.addEventListener('click', (e) => {
    e.stopPropagation();
    setFull(!dialog.classList.contains('is-full'), e);
  });
  // A click anywhere outside the image (backdrop, empty stage, close button) closes.
  dialog.addEventListener('click', (e) => {
    if (e.target === dialog || e.target === stage || e.target.classList.contains('zoom-close')) dialog.close();
  });
  dialog.addEventListener('close', () => big.removeAttribute('src'));
})();

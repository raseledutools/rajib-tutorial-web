// Empty-state screen of the PDF reader (shown until a document is opened).
const box = document.createElement('div');
box.id = 'rtLanding';
box.innerHTML = '<div class="rt-box"><div class="rt-ico">PDF</div><h2>PDF Reader</h2>'
  + '<p>PDF file ekhane drag &amp; drop korun, ba niche chapen.</p><button type="button">Open PDF</button></div>';
document.body.appendChild(box);
box.querySelector('button').addEventListener('click', () => {
  const b = document.getElementById('secondaryOpenFile');
  if (b) b.click();
});
let depth = 0;
window.addEventListener('dragenter', () => { depth++; box.classList.add('rt-drag'); });
window.addEventListener('dragleave', () => { if (--depth <= 0) { depth = 0; box.classList.remove('rt-drag'); } });
window.addEventListener('drop', () => { depth = 0; box.classList.remove('rt-drag'); });
(async () => {
  try {
    const app = window.PDFViewerApplication;
    await app.initializedPromise;
    app.eventBus.on('documentloaded', () => box.classList.add('rt-hide'));
    app.eventBus.on('documentinit', () => box.classList.add('rt-hide'));
  } catch (_) {}
})();

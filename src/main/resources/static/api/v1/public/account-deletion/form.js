'use strict';
const el = id => document.getElementById(id);
const steps = ['start', 'verify', 'password', 'confirm'];
let email, proof, choices = [];

function show(step, message = '') {
  for (const id of steps) el(id).hidden = id !== step;
  el('feedback').textContent = message;
  el(step)?.querySelector('input,select,button')?.focus();
}

function reset() {
  email = proof = undefined;
  choices = [];
  el('code').value = '';
  el('current-password').value = '';
  el('bands').replaceChildren();
  el('account').textContent = '';
  show('start');
}

async function post(action, body) {
  const response = await fetch('./' + action, {
    method: 'POST', credentials: 'omit', cache: 'no-store',
    headers: {'Content-Type':'application/json'}, body: JSON.stringify(body)
  });
  if (response.ok) return response;
  if (response.status === 429) throw new Error('Çok sayıda deneme yapıldı. Bir süre sonra tekrar dene.');
  if (response.status === 403) throw new Error(action === 'password' ? 'E-posta veya şifre doğrulanamadı. E-posta koduyla da devam edebilirsin.' : 'Doğrulamanın süresi dolmuş veya kod hatalı. Gerekirse yeni kod iste.');
  if (response.status === 409) throw new Error('Hesap veya band bilgileri değişmiş olabilir. Güncel seçenekler için yeniden kod iste.');
  throw new Error('İşlem tamamlanamadı. Tekrar dene.');
}

function submit(id, run) {
  const form = el(id);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (form.dataset.busy || form.hidden) return;
    form.dataset.busy = 'true';
    const buttons = [...form.querySelectorAll('button')];
    buttons.forEach(button => button.disabled = true);
    el('feedback').textContent = 'İşlem sürüyor…';
    try { await run(); }
    catch (error) { el('feedback').textContent = error instanceof TypeError ? 'Bağlantı kurulamadı. Tekrar dene.' : error.message; }
    finally { delete form.dataset.busy; buttons.forEach(button => button.disabled = false); }
  });
}

submit('start', async () => {
  email = el('email').value.trim();
  await post('request', {email});
  show('verify');
});

submit('verify', async () => {
  const response = await post('verify', {email, code: el('code').value});
  renderPreview(await response.json());
});

submit('password', async () => {
  email = el('password-email').value.trim();
  const response = await post('password', {email, currentPassword: el('current-password').value});
  el('current-password').value = '';
  renderPreview(await response.json());
});

function renderPreview(preview) {
  proof = preview.proof;
  el('account').textContent = '@' + preview.username;
  el('bands').replaceChildren();
  choices = preview.bands.map(band => {
    const group = document.createElement('fieldset');
    const name = document.createElement('legend');
    name.textContent = band.bandName;
    const label = document.createElement('label');
    label.textContent = 'Son kurucusun. Band için bir işlem seç.';
    const select = document.createElement('select');
    select.required = true;
    select.add(new Option('İşlem seç', ''));
    for (const member of band.successors) select.add(new Option('Kuruculuğu devret · @' + member.username, member.userId));
    select.add(new Option('Bandı kalıcı olarak kapat', 'CLOSE'));
    label.append(select);
    group.append(name, label);
    el('bands').append(group);
    return {bandId: band.bandId, select};
  });
  el('code').value = '';
  show('confirm');
}

submit('confirm', async () => {
  const bandDecisions = choices.map(({bandId, select}) => select.value === 'CLOSE'
    ? {bandId, action: 'CLOSE'} : {bandId, action: 'TRANSFER', successorUserId: select.value});
  await post('confirm', {email, proof, confirmation: 'DELETE', bandDecisions});
  reset();
  el('email').value = '';
  show(null, 'SoundConnect hesabın kalıcı olarak silindi.');
});
el('cancel').addEventListener('click', reset);
el('restart').addEventListener('click', reset);
el('use-password').addEventListener('click', () => {
  el('password-email').value = el('email').value;
  show('password');
});
el('use-code').addEventListener('click', () => {
  el('email').value = el('password-email').value;
  reset();
});

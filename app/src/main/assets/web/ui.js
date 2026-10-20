const $ = id => document.getElementById(id);
let current = '', mode = 'video', lastState = '', selectedQuality = 'best', pendingQuality = 'best';
let qualities = [], settingsOpen = false, homeScroll = 0;
let preferences = {theme: 'dark', defaultMode: 'video', askQuality: false};

function native(name, ...args) {
  try { return window.QuartzNative[name](...args); }
  catch { notice('Quartz is still starting. Please try again.'); return ''; }
}
function notice(message, signIn) {
  $('notice').replaceChildren();
  const text = document.createElement('span');
  text.textContent = message;
  $('notice').append(text);
  if (['x', 'facebook'].includes(signIn)) {
    const button = document.createElement('button');
    button.className = 'notice-action';
    button.textContent = signIn === 'x' ? 'Sign in to X' : 'Sign in to Facebook';
    button.onclick = () => native('signIn', signIn);
    $('notice').append(button);
  }
  $('notice').classList.toggle('show', !!message);
}
function site(url) {
  try { return new URL(url).hostname.replace(/^www\./, ''); } catch { return 'Video'; }
}
function sourceName(url){const host=site(url).toLowerCase();for(const [domain,name] of [['youtube.com','YouTube'],['youtu.be','YouTube'],['tiktok.com','TikTok'],['x.com','X'],['twitter.com','X'],['facebook.com','Facebook'],['fb.watch','Facebook'],['instagram.com','Instagram'],['soundcloud.com','SoundCloud']])if(host===domain||host.endsWith('.'+domain))return name;return host;}
function inspect(openOptions=preferences.askQuality) {
  const url = $('link').value.trim();
  if (!url) { notice('Paste a video link first.'); return; }
  current = '';
  window.AppUI?.navigate('downloads');
  if (/open\.spotify\.com\//.test(url)) window.AppUI?.navigate('spotify');
  closeQuality();
  $('preview-card').classList.add('hidden');
  if(openOptions)window.DownloadSheet?.shared(url);
  native('inspect', url);
}
function qualityLabel(q) {
  const resolution = q.width > 0 && q.width < q.height ? q.width : q.height;
  return `${resolution}p${q.fps > 30 ? ' · ' + q.fps + ' fps' : ''}`;
}
function qualityDescription(q) {
  const dimensions = q.width > 0 ? `${q.width} × ${q.height}` : `${q.height} pixels high`;
  return `${dimensions}${q.fps > 0 ? ' · ' + q.fps + ' fps' : ''}`;
}
function choices() {
  return [{value: 'best', label: 'Best available', detail: qualities.length ? 'Highest available · ' + qualityLabel(qualities[0]) : 'Highest quality this website offers', badge: 'AUTO'},
    ...qualities.map(q => ({value: String(q.height), label: qualityLabel(q), detail: qualityDescription(q), badge: Math.min(q.width || q.height, q.height) >= 720 ? 'HD' : ''}))];
}
function updateQualityTrigger() {
  const chosen = choices().find(q => q.value === selectedQuality) || choices()[0];
  $('quality-label').textContent = chosen.label;
  $('quality-detail').textContent = chosen.detail;
  $('quality-badge').textContent = chosen.badge;
  $('quality-badge').classList.toggle('hidden', !chosen.badge);
}
function renderPreview(data) {
  current = data.url;
  qualities = data.qualities || [];
  selectedQuality = 'best';
  $('link').value = data.url;
  $('title').textContent = data.title;
  $('source').textContent = sourceName(data.url);
  $('thumb').src = /^https:\/\//.test(data.thumbnail) ? data.thumbnail : '';
  $('sheet-thumb').src = $('thumb').getAttribute('src') || '';
  $('sheet-title').textContent = data.title;
  $('quality-note').textContent = /tiktok\.com$/.test(site(data.url))
    ? 'Quartz excludes formats marked as watermarked when a clean stream is available.'
    : (qualities.length ? 'Only qualities found for this link are listed.' : 'Quartz will try the best format this site offers.');
  $('audio-mode').disabled=data.hasAudio===false;$('video-mode').disabled=data.hasVideo===false;
  setMode(data.hasAudio===false?'video':data.hasVideo===false?'audio':preferences.defaultMode);
  updateQualityTrigger();
  $('preview-card').classList.remove('hidden');
  notice('');
  if (window.DownloadSheet?.preview(data)) return;
  $('preview-card').scrollIntoView({behavior: 'smooth', block: 'start'});
  if (preferences.askQuality && mode === 'video') openQuality();
}
function setMode(value) {
  mode = value;
  $('video-mode').classList.toggle('selected', value === 'video');
  $('audio-mode').classList.toggle('selected', value === 'audio');
  $('quality-wrap').classList.toggle('hidden', value === 'audio');
  $('audio-format-wrap').classList.toggle('hidden', value !== 'audio');
}
function renderQualityOptions(focusValue) {
  const group = $('quality-options');
  const previousScroll = group.scrollTop;
  group.replaceChildren();
  const rows = choices();
  for (const [index, q] of rows.entries()) {
    const button = document.createElement('button');
    button.className = 'quality-option' + (q.value === pendingQuality ? ' is-selected' : '');
    button.setAttribute('role', 'radio');
    button.setAttribute('aria-checked', String(q.value === pendingQuality));
    button.tabIndex = q.value === pendingQuality ? 0 : -1;
    button.dataset.value = q.value;
    const dot = document.createElement('span'); dot.className = 'quality-dot'; dot.setAttribute('aria-hidden', 'true');
    const text = document.createElement('span'); text.className = 'quality-text';
    const label = document.createElement('strong'); label.textContent = q.label;
    const detail = document.createElement('small'); detail.textContent = q.detail;
    text.append(label, detail);
    button.append(dot, text);
    if (q.badge) { const badge = document.createElement('span'); badge.className = 'quality-badge'; badge.textContent = q.badge; button.append(badge); }
    button.onclick = () => { pendingQuality = q.value; renderQualityOptions(q.value); };
    button.onkeydown = event => {
      if (!['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight'].includes(event.key)) return;
      event.preventDefault();
      const next = (index + (['ArrowDown', 'ArrowRight'].includes(event.key) ? 1 : -1) + rows.length) % rows.length;
      pendingQuality = rows[next].value;
      renderQualityOptions(pendingQuality);
    };
    group.append(button);
  }
  group.scrollTop = previousScroll;
  if (focusValue) [...group.children].find(button => button.dataset.value === focusValue)?.focus({preventScroll: true});
  $('quality-confirm').textContent = 'Use ' + (rows.find(q => q.value === pendingQuality)?.label || 'best available');
}
function openQuality() {
  if (!current) return;
  pendingQuality = selectedQuality;
  renderQualityOptions();
  if (!$('quality-sheet').open) $('quality-sheet').showModal();
  document.body.classList.add('modal-open');
}
function closeQuality() {
  if ($('quality-sheet').open) $('quality-sheet').close();
  document.body.classList.remove('modal-open');
}
function showSettings(show) {
  if(window.AppUI){window.AppUI.navigate(show?'settings':'downloads');return;}
  closeQuality();
  if (show && !settingsOpen) homeScroll = window.scrollY;
  settingsOpen = show;
  $('home-page').classList.toggle('hidden', show);
  $('settings-page').classList.toggle('hidden', !show);
  $('settings-open').classList.toggle('hidden', show);
  window.scrollTo({top: show ? 0 : homeScroll, behavior: 'instant'});
  if (show) refresh();
}
function updateSettings(state) {
  $('parallel-downloads').value = String(state.parallelDownloads || 2);
  document.body.classList.toggle('share-only', !!state.shareOnly);
  document.documentElement.classList.toggle('share-only', !!state.shareOnly);
  $('audio-format').value = state.videoAudioFormat || 'original';
  preferences = {theme: state.theme || 'dark', defaultMode: state.defaultMode || 'video', askQuality: !!state.askQuality};
  document.body.classList.toggle('light', preferences.theme === 'light');
  for (const theme of ['dark', 'light']) $('theme-' + theme).setAttribute('aria-pressed', String(theme === preferences.theme));
  for (const value of ['video', 'audio']) $('default-' + value).setAttribute('aria-pressed', String(value === preferences.defaultMode));
  $('ask-quality').checked = preferences.askQuality;
  for (const name of ['x', 'facebook', 'instagram']) {
    const saved = !!state.sessions?.[name];
    $(name + '-status').textContent = saved ? 'Session saved on this device' : 'No session saved';
    $(name + '-status').classList.toggle('connected', saved);
  }
  $('app-version').textContent = 'Version ' + (state.version || '0.2.0');
  const enabled = state.notifications?.enabled && state.notifications?.progress && state.notifications?.results;
  $('notification-status').textContent = enabled ? 'Progress and download results are enabled' : 'Notifications are off or a download channel is disabled';
  let notificationLabel=$('notification-enable').querySelector('span');
  if(!notificationLabel){notificationLabel=document.createElement('span');$('notification-enable').replaceChildren(notificationLabel);const arrow=document.createElement('img');arrow.src='icons/chevron_right.svg';arrow.className='ui-icon';arrow.alt='';$('notification-enable').append(arrow);}
  notificationLabel.textContent = enabled ? 'Manage notifications' : 'Enable notifications';
}
let downloadFilter = 'all';
function renderJobs(rows) {
  rows = rows.filter(j=>!j.historyHidden);
  const active = j=>['queued','preparing','downloading','processing','saving','waiting','paused'].includes(j.state);
  rows.sort((a,b)=>Number(active(b))-Number(active(a)));
  const filtered = rows.filter(j=>downloadFilter==='all'||(downloadFilter==='active'?active(j):j.state==='complete'));
  $('count').textContent = filtered.length;
  const host=$('jobs');host.replaceChildren();
  if(!filtered.length){const empty=document.createElement('div');empty.className='empty';empty.textContent=downloadFilter==='complete'?'Completed files will appear here.':'No downloads here yet.';host.append(empty);return;}
  for(const job of filtered){
    const card=document.createElement('article');card.className='job transfer-job';card.dataset.jobId=job.id;card.dataset.selectScope='downloads';card.dataset.selectId=job.id;
    const top=document.createElement('div');top.className='transfer-heading';
    const cover=document.createElement('img');cover.className='transfer-cover';cover.alt='';
    cover.src=/^\/data\/(?:user\/\d+|data)\/com\.quartz\.downloader\/files\/covers\/[\w-]+\.jpg$/.test(job.artworkPath||'')?'file://'+job.artworkPath:/^https:\/\//.test(job.thumbnail||'')?job.thumbnail:'logo.png';
    cover.onerror=()=>{cover.onerror=null;cover.src='logo.png'};
    const info=document.createElement('div');info.className='transfer-info';
    const title=document.createElement('strong');title.textContent=job.spotifyTitle||job.musicTitle||job.title||site(job.url);
    const artist=document.createElement('small');artist.textContent=job.spotifyArtists||job.musicArtist||sourceName(job.url);
    const state=document.createElement('span');state.className='transfer-state '+job.state;
    state.textContent=(job.state==='complete'?'Saved':(job.state||'queued').replace(/^./,c=>c.toUpperCase()))+(active(job)&&job.state!=='queued'?' · '+(job.progress||0)+'%':'');
    info.append(title,artist,state);top.append(cover,info);card.append(top);
    if(active(job)){const bar=document.createElement('div');bar.className='bar';const fill=document.createElement('i');fill.style.width=(job.progress||0)+'%';bar.append(fill);card.append(bar)}
    const message=document.createElement('p');message.textContent=job.message||'';card.append(message);
    const actions=document.createElement('div');actions.className='job-actions';
    const controls=job.state==='complete'?[['Open','open'],['Copy link','copy'],['Delete file','delete']]
      :job.state==='paused'?[['Resume','resume'],['Remove','delete']]
      :active(job)?[['Pause','pause'],['Cancel','delete']]:[['Retry','retry'],['Remove','delete']];
    for(const [label,action] of controls){const button=document.createElement('button');button.className=action==='resume'?'resume-button':'';button.textContent=label;button.onclick=()=>{
      if(action==='pause'||action==='resume')native('downloadControl',action,job.id);else native(action,job.id);setTimeout(refresh,200);
    };actions.append(button)}
    card.append(actions);host.append(card);
  }
}
function refresh() {
  let state;
  try { state = JSON.parse(native('state')); } catch { return; }
  const encoded = JSON.stringify(state.jobs || []);
  if (encoded !== lastState) { lastState = encoded; renderJobs(state.jobs || []); }
  updateSettings(state);
  window.SpotifyUI?.render(state.spotify, state.jobs, state.spotifyAudioFormat);
  window.BackgroundUI?.render(state);
  window.AppUI?.render(state);
  window.BulkUI?.render(state);
  window.AppUpdateUI?.render(state);
}
window.Quartz = {
  receive(event, data) {
    if(event==='navigate')window.AppUI?.navigate(data.tab);
    if(event==='sharedLink'||event==='focusDownload')window.AppUI?.navigate(event==='sharedLink'&&/open\.spotify\.com\//.test(data.url)?'spotify':'downloads');
    window.SpotifyUI?.receive(event, data);
    if (event === 'sharedLink') { if (settingsOpen) showSettings(false); $('link').value = data.url; if (!window.DownloadSheet?.shared(data.url)) inspect(); }
    if (event === 'previewLoading') { notice(data.message || 'Finding available video formats…'); window.DownloadSheet?.status(data.message); }
    if (event === 'preview') renderPreview(data);
    if (event === 'error') { notice(data.message, data.signIn); window.DownloadSheet?.error(data); }
    if (event === 'queued') { notice('Added to downloads. You can keep using your phone.'); refresh(); }
    if (event === 'focusDownload') { if (settingsOpen) showSettings(false); refresh(); const card = [...document.querySelectorAll('[data-job-id]')].find(x => x.dataset.jobId === data.id); (card || $('jobs')).scrollIntoView({block:'start'}); }
  },
  back() {
    if (window.AppUpdateUI?.back()) return true;
    if (window.BulkUI?.back()) return true;
    if (window.DownloadSheet?.back()) return true;
    if (window.SpotifyUI?.back()) return true;
    if ($('quality-sheet').open) { closeQuality(); return true; }
    if(window.BackgroundUI?.back())return true;
    if(window.AppUI?.back())return true;
    if (settingsOpen) { showSettings(false); return true; }
    return false;
  }
};
$('settings-open').onclick = () => showSettings(true);
$('settings-back').onclick = () => showSettings(false);
$('quality-open').onclick = () => window.DownloadSheet?.openCurrent() || openQuality();
$('quality-close').onclick = closeQuality;
$('quality-confirm').onclick = () => { selectedQuality = pendingQuality; updateQualityTrigger(); closeQuality(); };
$('quality-sheet').addEventListener('close', () => document.body.classList.remove('modal-open'));
$('quality-sheet').addEventListener('click', event => {
  if (event.target !== $('quality-sheet')) return;
  const rect = $('quality-sheet').getBoundingClientRect();
  if (event.clientY < rect.top || event.clientX < rect.left || event.clientX > rect.right) closeQuality();
});
for (const theme of ['dark', 'light']) $('theme-' + theme).onclick = () => { native('theme', theme); refresh(); };
for (const value of ['video', 'audio']) $('default-' + value).onclick = () => { native('setting', 'defaultMode', value); refresh(); };
$('ask-quality').onchange = () => { native('setting', 'askQuality', String($('ask-quality').checked)); refresh(); };
for (const name of ['x', 'facebook', 'instagram']) $('sign-in-' + name).onclick = () => native('signIn', name);
$('preview').onclick = () => inspect(true);
$('link').addEventListener('keydown', event => { if (event.key === 'Enter') inspect(); });
$('paste').onclick = () => { $('link').value = native('paste') || ''; if ($('link').value) inspect(); };
$('browse').onclick = () => native('browser', $('link').value || 'https://www.youtube.com');
$('video-mode').onclick = () => setMode('video');
$('audio-mode').onclick = () => setMode('audio');
$('download').onclick = () => {
  if (!current) { notice('Preview a video first.'); return; }
  if(!window.DownloadSheet?.openCurrent())inspect();
};
$('notification-enable').onclick = () => native('notificationSettings');
$('audio-format').onchange = () => native('setting', 'videoAudioFormat', $('audio-format').value);
refresh();
$('parallel-downloads').onchange = () => { native('setting', 'parallelDownloads', $('parallel-downloads').value); refresh(); };
setInterval(() => { if (!document.hidden) refresh(); }, 1500);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });

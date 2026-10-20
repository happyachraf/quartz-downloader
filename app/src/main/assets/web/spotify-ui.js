(() => {
  const panel = document.getElementById('spotify-panel');
  const rows = document.getElementById('spotify-tracks');
  const dialog = document.getElementById('spotify-matches');
  let playlist = null, jobs = [], encoded = '', lastUrl = '', autoSelect = true, matchingKey = '', search = '', format = 'original';
  const selected = new Set(), seen = new Set();
  const seconds = n => `${Math.floor((Number(n) || 0) / 60)}:${String(Math.floor((Number(n) || 0) % 60)).padStart(2, '0')}`;
  const chosen = track => (track.candidates || []).find(c => c.url === track.selectedUrl);
  const allowed = track => !!chosen(track) && (track.confirmed || (chosen(track).score >= .82 && chosen(track).duration > 0));
  const jobFor = track => jobs.find(job => job.spotifyTrackUri === track.uri && (job.audioFormat || 'mp3') === format &&
    (format === 'original' || job.audioQuality === 320) && ['complete', 'queued', 'preparing', 'downloading', 'processing', 'saving', 'paused', 'waiting'].includes(job.state))
    || jobs.find(job => job.id === track.jobId);
  const activeOrSaved = track => {
    const job = jobFor(track);
    return job && (job.audioFormat || 'mp3') === format && (format === 'original' || job.audioQuality === 320) &&
      ['complete', 'queued', 'preparing', 'downloading', 'processing', 'saving', 'paused', 'waiting'].includes(job.state);
  };
  function button(label, click, className = 'secondary') {
    const item = document.createElement('button'); item.textContent = label; item.className = className; item.onclick = click; return item;
  }
  function selection() {
    const count = [...selected].filter(key => playlist?.tracks?.some(t => t.key === key && allowed(t) && !activeOrSaved(t))).length;
    const download = document.getElementById('spotify-download');
    download.textContent = `Download selected ${format === 'mp3' ? 'MP3s' : 'audio'}${count ? ' · ' + count : ''}`;
    download.disabled = count === 0;
  }
  function review(track) {
    matchingKey = track.key;
    document.getElementById('spotify-match-title').textContent = track.name;
    const host = document.getElementById('spotify-match-options'); host.replaceChildren();
    for (const candidate of track.candidates || []) {
      const item = document.createElement('article'); item.className = 'spotify-candidate';
      const title = document.createElement('strong'); title.textContent = candidate.title;
      const detail = document.createElement('p'); detail.textContent = `${candidate.channel || 'YouTube'} · ${seconds(candidate.duration)} · ${candidate.score >= .82 ? 'Strong match' : 'Check this version'}`;
      item.append(title, detail, button('Open video', () => native('browser', candidate.url)), button('Use this audio', () => {
        native('chooseSpotifyMatch', track.key, candidate.url); selected.add(track.key); dialog.close(); refresh();
      }, 'primary'));
      host.append(item);
    }
    dialog.showModal(); document.body.classList.add('modal-open');
  }
  function render(state, downloadJobs, audioFormat = format) {
    jobs = downloadJobs || [];
    format = audioFormat === 'mp3' ? 'mp3' : 'original';
    for (const value of ['original', 'mp3']) document.getElementById('spotify-format-' + value).setAttribute('aria-pressed', String(format === value));
    document.getElementById('spotify-format-note').textContent = format === 'original'
      ? 'Original stream · no MP3 conversion.'
      : 'Best source exported as MP3 at 320 kbps.';
    const next = JSON.stringify([state, format, jobs.filter(j => j.spotifyTrackUri).map(j => [j.id, j.state, j.progress])]);
    if (next === encoded) return;
    encoded = next;
    if (!state?.tracks) { panel.classList.add('hidden'); playlist=state; return; }
    playlist = state;
    panel.classList.remove('hidden');
    if (state.url !== lastUrl) { lastUrl = state.url; selected.clear(); seen.clear(); panel.classList.remove('hidden'); }
    document.getElementById('spotify-title').textContent = state.title;
    document.getElementById('spotify-cover').src = /^https:\/\//.test(state.cover) ? state.cover : 'logo.png';
    const matched = state.tracks.filter(allowed).length;
    const reviewCount = state.tracks.filter(t => t.status === 'review').length;
    const missingCount = state.tracks.filter(t => t.status === 'unmatched').length;
    const unavailable = state.unavailable ? ` · ${state.unavailable} unavailable entries` : '';
    document.getElementById('spotify-summary').textContent = `${state.tracks.length} songs loaded${unavailable} · ${matched} ready${reviewCount ? ' · ' + reviewCount + ' to review' : ''}${missingCount ? ' · ' + missingCount + ' no match' : ''}`;
    document.getElementById('spotify-progress').textContent = state.message || 'Find audio matches, review the versions, then choose songs to download.';
    document.getElementById('spotify-find').disabled = ['matching','queued','loading'].includes(state.status);
    document.getElementById('spotify-find').textContent=['paused','waiting'].includes(state.status)?'Resume search':'Find YouTube matches';
    const searched=state.tracks.filter(t=>(t.candidates||[]).length||t.searched).length;
    document.getElementById('spotify-match-fill').style.width=(100*searched/Math.max(1,state.tracks.length))+'%';
    document.getElementById('spotify-stop').classList.toggle('hidden', !['matching','queued','loading','waiting'].includes(state.status));
    const previousScroll = rows.scrollTop;
    rows.replaceChildren();
    for (const track of state.tracks) {
      if (search && !`${track.name} ${(track.artists || []).join(' ')}`.toLocaleLowerCase().includes(search)) continue;
      if (!seen.has(track.key) && allowed(track) && !activeOrSaved(track)) { if (autoSelect) selected.add(track.key); seen.add(track.key); }
      if (activeOrSaved(track)) selected.delete(track.key);
      const row = document.createElement('article'); row.className = 'spotify-track';row.dataset.selectScope='spotifyTracks';row.dataset.selectId=track.key;row.dataset.selectionContext=state.url;
      const picture=document.createElement('img');picture.className='spotify-track-cover';picture.alt='';picture.src=/^https:\/\//.test(track.thumbnail||'')?track.thumbnail:'logo.png';picture.onerror=()=>{picture.onerror=null;picture.src='logo.png'};row.append(picture);
      const header = document.createElement('label'); header.className = 'spotify-track-heading';
      const check = document.createElement('input'); check.type = 'checkbox'; check.checked = selected.has(track.key);
      check.disabled = !allowed(track) || activeOrSaved(track); check.setAttribute('aria-label', 'Select ' + track.name);
      check.onchange = () => { autoSelect = false; check.checked ? selected.add(track.key) : selected.delete(track.key); selection(); };
      const text = document.createElement('span');
      const title = document.createElement('strong'); title.textContent = track.name;
      const artists = document.createElement('small'); artists.textContent = `${(track.artists || []).join(', ')} · ${seconds(track.duration)}`;
      text.append(title, artists); header.append(check, text); row.append(header);
      const match = chosen(track);
      const message = document.createElement('p'); message.className = 'spotify-track-match';
      const job = jobFor(track);
      message.textContent = activeOrSaved(track) ? (job.state === 'complete' ? 'Audio saved in Downloads' : `${job.state} · ${job.progress || 0}%`)
        : match ? `${track.confirmed ? 'Chosen by you' : allowed(track) ? 'YouTube match saved' : 'Review required'}: ${match.title}`
        : track.message || (track.status === 'unmatched' ? 'No audio match found.' : 'Waiting for a match');
      row.append(message);
      if(match){const open=document.createElement('button');open.className='saved-youtube-link';open.textContent='Open saved YouTube link';open.onclick=()=>native('browser',match.url);row.append(open);}
      const actions = document.createElement('div'); actions.className = 'spotify-track-actions';
      if ((track.candidates || []).length) actions.append(button(track.status === 'review' ? 'Review match' : 'Change match', () => review(track)));
      else actions.append(button(track.status === 'unmatched' ? 'Try search again' : 'Find match', () => native('retrySpotifyMatch', track.key)));
      if (job?.state === 'complete') actions.append(button(job.filename?.endsWith('.mp3') ? 'Open MP3' : 'Open audio', () => native('open', job.id)));
      row.append(actions); rows.append(row);
    }
    rows.scrollTop = previousScroll;
    selection();
  }
  document.getElementById('spotify-find').onclick = () => { native('findSpotifyMatches'); refresh(); };
  document.getElementById('spotify-search').oninput = event => { search = event.target.value.trim().toLocaleLowerCase(); encoded = ''; rows.scrollTop = 0; render(playlist, jobs); };
  document.getElementById('spotify-stop').onclick = () => { native('stopSpotify'); refresh(); };
  document.getElementById('spotify-select').onclick = () => {
    for (const track of playlist?.tracks || []) if (allowed(track) && !activeOrSaved(track)) selected.add(track.key);
    encoded = ''; render(playlist, jobs);
  };
  document.getElementById('spotify-clear').onclick = () => { autoSelect = false; selected.clear(); encoded = ''; render(playlist, jobs); };
  for (const value of ['original', 'mp3']) document.getElementById('spotify-format-' + value).onclick = () => {
    native('setting', 'spotifyAudioFormat', value); selected.clear(); seen.clear(); encoded = ''; refresh();
  };
  document.getElementById('spotify-download').onclick = () => native('downloadSpotify', JSON.stringify([...selected]), format);
  document.getElementById('spotify-match-close').onclick = () => dialog.close();
  document.getElementById('spotify-match-retry').onclick = () => { native('retrySpotifyMatch', matchingKey); dialog.close(); refresh(); };
  dialog.addEventListener('close', () => document.body.classList.remove('modal-open'));
  window.SpotifyUI = {
    render,
    hide() { panel.classList.add('hidden'); if (dialog.open) dialog.close(); },
    back() { if (!dialog.open) return false; dialog.close(); return true; },
    receive(event, data) {
      if (event === 'playlistLoading') { search = ''; document.getElementById('spotify-search').value = ''; autoSelect = true; selected.clear(); seen.clear(); lastUrl = ''; encoded = ''; playlist = null; panel.classList.add('hidden'); notice('Loading the complete Spotify playlist…'); }
      if (event === 'playlist') { encoded='';render(data,jobs);window.BackgroundUI?.openDetail();window.AppUI?.navigate('spotify');notice('');refresh(); }
      if (event === 'spotifyQueued') { window.AppUI?.navigate('downloads'); selected.clear(); notice(`${data.count} ${data.format === 'mp3' ? 'MP3' : 'audio'} downloads added${data.existing ? ' · ' + data.existing + ' already saved or queued' : ''}. Progress is shown in Downloads.`); refresh(); }
    }
  };
  refresh();
})();

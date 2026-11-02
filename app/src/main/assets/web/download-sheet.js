(() => {
  const sheet = $('download-sheet'), content = $('download-sheet-content'), confirm = $('download-sheet-confirm');
  let data = null, selected = 'video:best', expanded = false, popup = false, selectedMatch = '', confirmedMatch = false;
  const isShare = () => document.body.classList.contains('share-only');
  const setConfirm=label=>{const mark=document.createElement('img');mark.className='ui-icon';mark.src='icons/download-outline.svg';mark.alt='';confirm.replaceChildren(mark,document.createTextNode(label));};
  const size = bytes => bytes > 0 ? '≈ ' + (bytes / 1000000).toFixed(1) + ' MB' : '';
  function show() {
    if (!sheet.open) sheet.showModal();
    document.body.classList.add('modal-open');
  }
  function close() {
    popup = false;
    sheet.close();
    document.body.classList.remove('modal-open');
    if (isShare()) native('closeShare');
  }
  function loading(url) {
    popup = true; data = null; expanded = false;
    content.replaceChildren();
    const loader = document.createElement('div'); loader.className = 'download-loading';
    const song=/open\.spotify\.com\/(?:intl-[a-z]{2}\/)?track\//i.test(url);
    loader.innerHTML = song
      ? '<span class="download-spinner" aria-hidden="true"></span><strong>Finding your song…</strong><p>Loading song details and finding an audio match.</p>'
      : '<span class="download-spinner" aria-hidden="true"></span><strong>Finding your video…</strong><p>Checking available audio and video formats.</p>';
    const source = document.createElement('small'); source.textContent = sourceName(url); loader.append(source); content.append(loader);
    confirm.disabled = true; setConfirm('Finding formats…');
    $('download-sheet-note').textContent = '';
    show();
  }
  function row(group, id, label, detail, bytes, audio, badge) {
    const button = document.createElement('button'); button.className = 'download-choice';
    button.setAttribute('role', 'radio'); button.setAttribute('aria-checked', String(id.startsWith('match:') ? selectedMatch === id.slice(6) : selected === id)); button.dataset.option = id;
    const text = document.createElement('span'); text.className = 'download-choice-text';
    const title = document.createElement('strong'); title.textContent = label; text.append(title);
    if (detail) { const sub = document.createElement('small'); sub.textContent = detail; text.append(sub); }
    const end = document.createElement('span'); end.className = 'download-choice-end';
    if (badge) { const tag = document.createElement('span'); tag.className = 'quality-badge'; tag.textContent = badge; end.append(tag); }
    if (bytes > 0) { const estimate = document.createElement('small'); estimate.textContent = size(bytes); end.append(estimate); }
    const radio = document.createElement('span'); radio.className = 'download-radio'; radio.setAttribute('aria-hidden', 'true');
    button.append(radio, text, end);
    button.onclick = () => { if(id.startsWith('match:')){selectedMatch=id.slice(6);confirmedMatch=true;}else selected = id; render(); [...content.querySelectorAll('[data-option]')].find(x => x.dataset.option === id)?.focus({preventScroll:true}); };
    button.onkeydown = event => {
      if (!['ArrowDown','ArrowUp','ArrowLeft','ArrowRight'].includes(event.key)) return;
      event.preventDefault(); const buttons = [...content.querySelectorAll('[data-option]')];
      const i = buttons.indexOf(button), step = ['ArrowDown','ArrowRight'].includes(event.key) ? 1 : -1;
      buttons[(i + step + buttons.length) % buttons.length].click();
    };
    group.append(button);
  }
  function section(label) {
    const heading = document.createElement('h3'); heading.className = 'download-section-label'; heading.textContent = label; content.append(heading);
    const group = document.createElement('div'); group.setAttribute('role', 'radiogroup'); group.setAttribute('aria-label', label + ' formats'); content.append(group); return group;
  }
  function render() {
    if (!data) return;
    const scroll = content.scrollTop; content.replaceChildren();
    const media = document.createElement('div'); media.className = 'download-media';
    if (/^https:\/\//.test(data.thumbnail)) { const thumb = document.createElement('img'); thumb.src = data.thumbnail; thumb.alt = ''; media.append(thumb); }
    const info = document.createElement('div'), title = document.createElement('strong'), source = document.createElement('small');
    title.textContent = data.title; source.textContent = (data.artist ? data.artist+' · ' : '') + sourceName(data.url) + (data.duration > 0 ? ' · ' + Math.floor(data.duration/60) + ':' + String(Math.floor(data.duration)%60).padStart(2,'0') : '');
    media.classList.toggle('spotify-song-media',!!data.spotifyTrack);
    info.append(title, source); media.append(info); content.append(media);
    if(data.spotifyTrack){
      const matches=section('Audio match');
      for(const candidate of data.matches||[])row(matches,'match:'+candidate.url,candidate.title,(candidate.channel||'YouTube')+' · '+(candidate.score>=.82&&candidate.duration>0?'Strong match':'Choose to confirm this version'),0,false);
    }
    if(data.hasAudio!==false){const audio = section('Audio');
    row(audio, 'audio:original', 'Original audio', 'Best source quality', 0, true, 'BEST');
    row(audio, 'audio:mp3', 'MP3 · 320 kbps', 'Compatible with most players', 0, true);}
    if(data.hasVideo!==false){
    const video = section('Video'), all = data.qualities || [];
    const resolution=q=>{const h=Math.min(q.width||q.height,q.height);return h+'p'+(q.fps>30?q.fps:'')};
    row(video, 'video:best', 'Best available' + (all.length ? ' · '+resolution(all[0]) : ''), '', all[0]?.bytes, false);
    const short = all.filter((q,i) => i===0 || [360,720].includes(Math.min(q.width || q.height,q.height)));
    const shown = expanded ? all : all.filter(q=>short.includes(q)||selected==='video:'+q.height);
    shown.forEach(q => row(video,'video:'+q.height,resolution(q),expanded?qualityDescription(q):'',q.bytes,false));
    if (all.some(q => !short.includes(q))) {
      const more = document.createElement('button'); more.className = 'download-more';
      const mark=document.createElement('img');mark.className='ui-icon';mark.src='icons/queue_music.svg';mark.alt='';
      const label=document.createElement('span');label.textContent=expanded?'Fewer formats':'More formats';
      const arrow=document.createElement('img');arrow.className='ui-icon';arrow.src='icons/chevron_right.svg';arrow.alt='';more.append(mark,label,arrow);
      more.setAttribute('aria-expanded',String(expanded)); more.onclick=()=>{expanded=!expanded;render();};content.append(more);
    }
    }
    $('download-sheet-note').textContent = data.spotifyTrack ? (selectedMatch ? 'Audio comes from the YouTube match shown above. Spotify cover and song tags are included when available.' : 'Choose the correct audio match to continue.') : 'Sizes marked ≈ are estimates.';
    confirm.disabled = !!data.spotifyTrack&&!selectedMatch; setConfirm(data.spotifyTrack?'Download song':selected.startsWith('audio:') ? 'Download audio' : 'Download video');
    content.scrollTop = scroll;
  }
  window.DownloadSheet = {
    status(message){if(!data&&sheet.open&&message){const title=content.querySelector('.download-loading strong');if(title)title.textContent=message;}},
    openCurrent(){if(!data||data.url!==current)return false;popup=true;render();show();return true;},
    shared(url) {
      if (/open\.spotify\.com\/(?:intl-[^/]+\/)?playlist\//.test(url)) {
        if (isShare()) { native('openSharedPlaylist', url); return true; }
        if(sheet.open)close();
        return false;
      }
      loading(url);
    },
    preview(value) {
      data = value; expanded = false;
      selectedMatch = value.selectedMatch || ''; confirmedMatch = false;
      selected = data.hasAudio===false?'video:best':(data.hasVideo===false||preferences.defaultMode === 'audio') ? 'audio:'+($('audio-format').value||'original') : 'video:best';
      if(data.spotifyTrack)selected='audio:'+(preferences.spotifyAudioFormat||'original');
      if (!popup) return false;
      render(); show(); return true;
    },
    error(value) {
      if (!sheet.open) return;
      data = null; content.replaceChildren();
      const box = document.createElement('div'); box.className = 'download-loading';
      const song=/open\.spotify\.com\/(?:intl-[a-z]{2}\/)?track\//i.test($('link').value);
      const title = document.createElement('strong'); title.textContent = value.signIn==='facebook'?'Facebook sign-in needed':song?'Could not load this song':'Could not load this video';
      const message = document.createElement('p'); message.textContent = value.message; box.append(title,message);
      const retry = document.createElement('button'); retry.className = 'secondary'; retry.textContent = 'Try again'; retry.onclick=()=>{loading($('link').value);inspect();}; box.append(retry);
      const facebook=/^https:\/\/(?:[^/]+\.)?facebook\.com\/|^https:\/\/fb\.watch\//i.test($('link').value);
      if(value.signIn || (!facebook&&!song)){const browser = document.createElement('button'); browser.className = 'secondary'; browser.textContent = value.signIn === 'x' ? 'Sign in to X' : value.signIn==='facebook'?'Sign in to Facebook':'Open in Quartz browser';
      browser.onclick=()=>value.signIn?native('signIn',value.signIn):native('browser',$('link').value);box.append(browser);}content.append(box);
      confirm.disabled=true;setConfirm('Choose a format to download');$('download-sheet-note').textContent='';
    },
    back(){if(!sheet.open)return false;close();return true;}
  };
  confirm.onclick=()=>{
    if(!data||confirm.disabled)return;
    confirm.disabled=true;setConfirm('Adding download…');
    const [kind,choice]=selected.split(':');
    if(data.spotifyTrack)native('downloadSpotifySong',data.url,selectedMatch,choice,confirmedMatch);
    else native('download',data.url,kind==='video'?choice:'best',kind,kind==='audio'?choice:'original');
    if(!isShare()){close();notice('Added to downloads.');}
  };
  $('download-sheet-close').onclick=close;
  sheet.addEventListener('cancel',event=>{event.preventDefault();close();});
  sheet.addEventListener('click',event=>{
    if(event.target!==sheet)return;
    const rect=sheet.getBoundingClientRect();if(event.clientY<rect.top||event.clientX<rect.left||event.clientX>rect.right)close();
  });
})();

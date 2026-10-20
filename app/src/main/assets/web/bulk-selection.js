(()=>{
  const selector='[data-select-scope][data-select-id]';
  let scope='',context='',selected=new Set(),snapshot={},press=null,timer=0,swallow=null,paint=0,busy=false;
  const toolbar=$('bulk-toolbar'),dialog=$('bulk-dialog');
  const rows=()=>[...document.querySelectorAll(selector)].filter(el=>el.dataset.selectScope===scope&&el.getClientRects().length);
  const scopeNames={musicPlaylists:'playlists',musicSongs:'songs',downloads:'downloads',spotifyHistory:'playlist entries',spotifyTracks:'songs'};
  function status(message){$('bulk-status').textContent=message;$('bulk-status').classList.remove('hidden');setTimeout(()=>$('bulk-status').classList.add('hidden'),5000);}
  function decorate(){
    for(const el of document.querySelectorAll(selector)){
      el.classList.add('bulk-selectable');
      const inMode=!!scope&&el.dataset.selectScope===scope;
      el.classList.toggle('bulk-in-mode',inMode);el.classList.toggle('bulk-marked',inMode&&selected.has(el.dataset.selectId));
      if(!el.querySelector(':scope > .bulk-check')){const mark=document.createElement('span');mark.className='bulk-check';mark.setAttribute('aria-hidden','true');const icon=document.createElement('img');icon.src='icons/check.svg';icon.alt='';mark.append(icon);el.append(mark);}
      if(inMode)el.setAttribute('aria-selected',String(selected.has(el.dataset.selectId)));else el.removeAttribute('aria-selected');
    }
  }
  function reconcile(){
    if(scope){const visible=new Set(rows().map(el=>el.dataset.selectId));for(const key of selected)if(!visible.has(key))selected.delete(key);}
    toolbar.classList.toggle('hidden',!scope);document.body.classList.toggle('bulk-active',!!scope);
    $('bulk-count').textContent=selected.size+' selected';$('bulk-delete').disabled=!selected.size||busy;
    const available=rows();$('bulk-all').textContent=available.length&&selected.size===available.length?'Deselect all':'Select all';
    decorate();
  }
  function schedule(){if(!paint)paint=requestAnimationFrame(()=>{paint=0;reconcile();});}
  function cancel(){if(busy)return;scope='';context='';selected.clear();if(dialog.open)dialog.close();reconcile();}
  function begin(el){scope=el.dataset.selectScope;context=el.dataset.selectionContext||'';selected=new Set([el.dataset.selectId]);reconcile();}
  function toggle(el){if(!scope)begin(el);else if(el.dataset.selectScope===scope){const key=el.dataset.selectId;selected.has(key)?selected.delete(key):selected.add(key);reconcile();}}
  function stopTimer(){clearTimeout(timer);timer=0;press=null;}
  document.addEventListener('pointerdown',e=>{
    if(e.button!==0||busy||dialog.open)return;
    const row=e.target.closest(selector);if(!row||e.target.closest('input[type=range]'))return;
    stopTimer();press={x:e.clientX,y:e.clientY,id:row.dataset.selectId,scope:row.dataset.selectScope};
    timer=setTimeout(()=>{const found=[...document.querySelectorAll(selector)].find(el=>el.dataset.selectId===press?.id&&el.dataset.selectScope===press?.scope&&el.getClientRects().length);if(found){swallow={id:found.dataset.selectId,scope:found.dataset.selectScope,until:Date.now()+800};if(scope===found.dataset.selectScope)toggle(found);else begin(found);}timer=0;},480);
  },true);
  document.addEventListener('pointermove',e=>{if(press&&Math.hypot(e.clientX-press.x,e.clientY-press.y)>12)stopTimer();},true);
  for(const event of ['pointerup','pointercancel','scroll'])document.addEventListener(event,stopTimer,true);
  document.addEventListener('contextmenu',e=>{const row=e.target.closest(selector);if(row){e.preventDefault();if(!scope)begin(row);}},true);
  document.addEventListener('click',e=>{
    if(e.target.closest('[data-tab],#all-songs,#music-playlists,[data-download-filter],#spotify-library-back')){cancel();return;}
    const row=e.target.closest(selector);if(!row)return;
    if(swallow&&swallow.id===row.dataset.selectId&&swallow.scope===row.dataset.selectScope&&Date.now()<swallow.until){swallow=null;e.preventDefault();e.stopImmediatePropagation();return;}
    if(scope&&row.dataset.selectScope===scope){e.preventDefault();e.stopImmediatePropagation();toggle(row);}
  },true);
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&scope){e.preventDefault();cancel();}else if(e.key===' '&&(scope||e.shiftKey)){const row=e.target.closest(selector);if(row){e.preventDefault();toggle(row);}}},true);
  function actionButton(label,files,primary){const b=document.createElement('button');b.className=primary?'primary':'secondary';b.textContent=label;b.onclick=()=>execute(files);return b;}
  function confirm(){
    if(!selected.size||busy)return;
    const count=selected.size,host=$('bulk-confirm-actions');host.replaceChildren();
    $('bulk-confirm-title').textContent='Remove '+count+' '+scopeNames[scope]+'?';
    let message='';
    if(scope==='musicPlaylists'){
      const songs=(snapshot.jobs||[]).filter(j=>j.state==='complete'&&j.mode==='audio'&&!j.musicPlaylistHidden&&selected.has(j.spotifyPlaylist||'Other audio')).length;
      message=songs+' downloaded songs in these playlists. Remove playlists keeps the songs in All songs and Downloads. Delete files too removes their audio files from your phone.';
      host.append(actionButton('Remove playlists',false,true),actionButton('Delete files too',true,false));
    }else if(scope==='musicSongs'){
      message='Delete the selected audio files from your phone and remove them from Music and Downloads?';host.append(actionButton('Delete files',true,true));
    }else if(scope==='downloads'){
      message='Remove history keeps completed files and cancels unfinished downloads. Delete files too also deletes selected saved files.';
      host.append(actionButton('Remove history',false,true),actionButton('Delete files too',true,false));
    }else if(scope==='spotifyHistory'){
      message='Clear these Spotify history cards? Downloaded songs and Music playlists stay available. Pause running searches before clearing their history.';host.append(actionButton('Clear history',false,true));
    }else{
      message='Remove the selected songs from this Quartz playlist workspace? Downloaded audio files are kept. Your playlist on Spotify is unchanged.';host.append(actionButton('Remove from this list',false,true));
    }
    $('bulk-confirm-message').textContent=message;
    const keep=document.createElement('button');keep.className='secondary';keep.textContent='Cancel';keep.onclick=()=>dialog.close();host.append(keep);
    dialog.showModal();document.body.classList.add('modal-open');
  }
  function execute(files){
    if(busy)return;busy=true;const keys=[...selected],list=scope,url=context;
    $('bulk-confirm-message').textContent='Applying changes…';dialog.querySelectorAll('button').forEach(b=>b.disabled=true);
    setTimeout(()=>{
      let result;try{result=JSON.parse(native('bulkAction',list,JSON.stringify(keys),files,url));}catch{result={done:0,failed:keys.length};}
      busy=false;dialog.close();cancel();refresh();
      const message=files?(result.done||0)+' files deleted':(result.done||0)+' items removed';status(message+(result.failed?' · '+result.failed+' could not be removed.':''));
    },50);
  }
  $('bulk-done').onclick=cancel;$('bulk-all').onclick=()=>{const all=rows().map(el=>el.dataset.selectId);selected=selected.size===all.length?new Set():new Set(all);reconcile();};$('bulk-delete').onclick=confirm;
  dialog.addEventListener('close',()=>{if(!document.querySelector('dialog[open]'))document.body.classList.remove('modal-open');});
  for(const id of ['music-list','jobs','spotify-library','spotify-tracks'])new MutationObserver(schedule).observe($(id),{childList:true,subtree:true});
  window.BulkUI={render(state){snapshot=state;schedule();},cancel,begin, get scope(){return scope},back(){if(dialog.open){if(!busy)dialog.close();return true}if(scope){cancel();return true}return false;}};
  refresh();schedule();
})();

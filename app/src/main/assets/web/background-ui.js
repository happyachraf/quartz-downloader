(()=>{
  let snapshot={},libraryKey='',detail=false;
  const active=j=>['queued','preparing','downloading','processing','saving','waiting'].includes(j.state);
  function openDetail(){detail=true;$('spotify-page').classList.add('spotify-detail-open');$('spotify-detail').classList.remove('hidden');$('spotify-library').classList.add('hidden');}
  function library(){detail=false;$('spotify-page').classList.remove('spotify-detail-open');$('spotify-detail').classList.add('hidden');$('spotify-library').classList.remove('hidden');}
  function render(state){
    snapshot=state;
    const importing=$('spotify-import-status');
    importing.classList.toggle('hidden',!!state.spotify?.tracks||!state.spotify?.url);
    importing.querySelector('p').textContent=state.spotify?.message||'Loading your playlist. You can leave Quartz.';
    importing.querySelector('button').textContent=state.spotify?.status==='paused'?'Resume import':'Pause import';
    importing.querySelector('button').onclick=()=>{native(state.spotify?.status==='paused'?'findSpotifyMatches':'stopSpotify');setTimeout(refresh,200)};
    $('pause-all').disabled=!(state.jobs||[]).some(active);
    $('resume-all').disabled=!(state.jobs||[]).some(j=>j.state==='paused');
    const key=JSON.stringify([state.playlists,(state.jobs||[]).map(j=>[j.spotifyPlaylist,j.state])]);if(key===libraryKey)return;libraryKey=key;
    const host=$('spotify-library');host.replaceChildren();
    for(const row of state.playlists||[]){
      const item=document.createElement('article');item.className='saved-playlist';item.dataset.selectScope='spotifyHistory';item.dataset.selectId=row.url;
      const top=document.createElement('div');top.className='saved-playlist-top';
      const image=document.createElement('img');image.alt='';image.src=/^https:\/\//.test(row.cover||'')?row.cover:'logo.png';image.onerror=()=>{image.onerror=null;image.src='logo.png'};
      const text=document.createElement('div'),title=document.createElement('strong'),count=document.createElement('p');title.textContent=row.title;
      const saved=(state.jobs||[]).filter(j=>j.spotifyPlaylist===row.title&&j.state==='complete').length;
      count.textContent=(row.count||0)+' songs · '+(row.searched||0)+' searched'+(saved?' · '+saved+' saved':'');text.append(title,count);top.append(image,text);item.append(top);
      const message=document.createElement('p');message.className='playlist-state';message.textContent=row.message||'Playlist saved on this phone';item.append(message);
      if(row.count){const bar=document.createElement('div');bar.className='bar';const fill=document.createElement('i');fill.style.width=(100*(row.searched||0)/row.count)+'%';bar.append(fill);item.append(bar);}
      const controls=document.createElement('div');controls.className='playlist-actions';
      const open=document.createElement('button');open.className='primary';open.textContent='Open playlist';open.onclick=()=>{native('selectPlaylist',row.url);openDetail();refresh();};controls.append(open);
      if(['matching','queued','loading','waiting'].includes(row.status)){const pause=document.createElement('button');pause.className='secondary';pause.textContent='Pause search';pause.onclick=()=>{native('selectPlaylist',row.url);native('stopSpotify');setTimeout(refresh,200)};controls.append(pause);}
      const clear=document.createElement('button');clear.className='secondary';clear.textContent='Clear history';
      clear.disabled=['matching','queued','loading','waiting'].includes(row.status);
      clear.title=clear.disabled?'Pause or finish this search before clearing its history.':'Remove this entry. Your downloaded songs stay in Music.';
      clear.onclick=()=>{if(native('clearSpotifyHistory',row.url)){library();refresh();}};
      controls.append(clear);
      item.append(controls);host.append(item);
    }
    if(!host.children.length){const note=document.createElement('p');note.className='browse-welcome';note.textContent='Add a Spotify playlist. Matches and chosen YouTube links will stay saved here.';host.append(note);}
  }
  $('spotify-add-form').onsubmit=e=>{e.preventDefault();const url=$('spotify-add-link').value.trim();if(!url)return;native('loadPlaylist',url);window.AppUI.navigate('spotify');openDetail();setTimeout(refresh,250);};
  $('spotify-library-back').onclick=library;
  $('background-settings').onclick=()=>native('backgroundSettings');
  $('pause-all').onclick=()=>{native('downloadControl','pauseAll','');setTimeout(refresh,200)};
  $('resume-all').onclick=()=>{native('downloadControl','resumeAll','');setTimeout(refresh,200)};
  $('clear-history').onclick=()=>{native('clearHistory');refresh()};
  document.querySelectorAll('[data-download-filter]').forEach(button=>button.onclick=()=>{downloadFilter=button.dataset.downloadFilter;document.querySelectorAll('[data-download-filter]').forEach(b=>b.classList.toggle('active',b===button));renderJobs(snapshot.jobs||[])});
  window.BackgroundUI={render,openDetail,library,back(){if(detail&&window.AppUI?.tab==='spotify'){library();window.scrollTo(0,0);return true}return false}};refresh();
})();

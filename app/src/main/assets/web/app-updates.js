(()=>{
  let state={},manual=false,seen='';const dialog=$('app-update-dialog');
  function later(){if(state.version)native('updateLater',state.version);manual=false;if(dialog.open)dialog.close();}
  function show(){if(!dialog.open)dialog.showModal();document.body.classList.add('modal-open');}
  function render(snapshot){
    state=snapshot.updates||{};const status=state.status||'idle';
    $('update-installed').textContent='Installed version '+(state.installedVersion||snapshot.version||'');
    $('update-settings-status').textContent=state.message||'Updates come directly from GitHub Releases.';
    $('update-check').disabled=status==='checking';
    if(snapshot.shareOnly)return;
    if(!manual&&state.prompt&&state.version!==seen&&!document.querySelector('dialog[open]')&&!window.BulkUI?.scope){seen=state.version;show();}
    const heading=({available:'Update available',downloading:'Downloading update',verifying:'Verifying update',ready:'Ready to install',checking:'Checking for updates',upToDate:'You’re up to date',error:'Could not check for updates'})[status]||'Quartz Downloader updates';
    $('app-update-title').textContent=heading;
    $('app-update-version').textContent=state.version&&['available','downloading','verifying','ready'].includes(status)?'Quartz Downloader '+state.version+' · '+(state.size/1e6).toFixed(1)+' MB':'';
    $('app-update-message').textContent=state.message||'Check GitHub for the newest Quartz Downloader release.';
    $('app-update-notes').textContent=state.notes||'';
    $('app-update-notes').classList.toggle('hidden',!state.notes||!['available','ready'].includes(status));
    const progress=['downloading','verifying'].includes(status);$('app-update-progress-wrap').classList.toggle('hidden',!progress);
    $('app-update-progress').style.width=(state.progress||0)+'%';$('app-update-percent').textContent=status==='verifying'?'Checking installer…':(state.progress||0)+'%';
    const primary=$('app-update-primary');primary.classList.toggle('hidden',!['available','ready','error','upToDate','idle'].includes(status));
    primary.textContent=status==='ready'?'Install update':status==='available'?'Download update':status==='upToDate'?'Done':'Try again';
    primary.onclick=()=>{if(status==='upToDate'){later();return}native('updateAction',status==='ready'?'install':status==='available'?'download':'check');setTimeout(refresh,100);};
    $('app-update-cancel').classList.toggle('hidden',!progress);
    $('app-update-install-note').classList.toggle('hidden',status!=='ready');
  }
  $('update-check').onclick=()=>{manual=true;native('updateAction','check');show();refresh();};
  $('update-open').onclick=()=>{manual=true;show();refresh();};
  $('update-github').onclick=()=>native('updateAction','releases');
  $('app-update-later').onclick=later;$('app-update-close').onclick=later;
  $('app-update-cancel').onclick=()=>{native('updateAction','cancel');refresh();};
  dialog.addEventListener('cancel',()=>later());dialog.addEventListener('close',()=>{manual=false;if(!document.querySelector('dialog[open]'))document.body.classList.remove('modal-open');});
  window.AppUpdateUI={render,back(){if(dialog.open){later();return true}return false}};refresh();
})();

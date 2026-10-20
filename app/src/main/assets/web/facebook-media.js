(() => {
  const page = {url:location.href, canonical:document.querySelector('link[rel="canonical"]')?.href||'',
    ogUrl:document.querySelector('meta[property="og:url"]')?.content||'', loginForm:!!document.querySelector('input[type=password]')};
  const safe = value => {try {const u=new URL(value);return u.protocol==='https:'&&!u.username&&!u.password&&/(^|\.)(fbcdn\.net|fbsbx\.com)$/.test(u.hostname)?u.href:'';}catch{return '';}};
  const fb = value => {try {const u=new URL(value);return u.protocol==='https:'&&/(^|\.)facebook\.com$/.test(u.hostname)?u.href:'';}catch{return '';}};
  const wanted = /\/(?:reels?|videos)\/(\d+)(?:\/|$)/.exec(location.pathname)?.[1] || new URL(location.href).searchParams.get('v');
  const candidates = new Map(); let budget=160000, chars=0;
  for(const script of document.querySelectorAll('script[type="application/json"]')) {
    const text=script.textContent||''; chars+=text.length;
    if(chars>12000000)break;
    if(text.length>4000000||!/videoDelivery|playable_url|browser_native/.test(text))continue;
    try {
      const queue=[{v:JSON.parse(text),path:''}];
      while(queue.length&&budget-->0) {
        const {v,path}=queue.pop(); if(!v||typeof v!=='object')continue;
        if(v.__typename==='Video'&&/^\d+$/.test(String(v.id))&&(v.videoDeliveryResponseFragment||v.videoDeliveryLegacyFields||v.playable_url)) {
          const delivery=v.videoDeliveryResponseFragment?.videoDeliveryResponseResult||{};
          const legacy=v.videoDeliveryLegacyFields||v;
          const formats=[]; const seen=new Set();
          const add=f=>{f.url=safe(f.url);if(!f.url||seen.has(f.url)||formats.length>=64)return;seen.add(f.url);formats.push(f);};
          const manifests=[...(delivery.dash_manifests||[]),{manifest_xml:legacy.dash_manifest||legacy.dash_manifest_xml_string}];
          for(let {manifest_xml:xml} of manifests) {
            if(typeof xml==='string'&&xml.startsWith('%3C'))try{xml=decodeURIComponent(xml.replace(/\+/g,' '));}catch{}
            if(typeof xml!=='string'||xml.length>500000||/<!DOCTYPE|<!ENTITY/i.test(xml))continue;
            const doc=new DOMParser().parseFromString(xml,'application/xml');
            if(doc.querySelector('parsererror')||doc.querySelector('ContentProtection'))continue;
            for(const rep of doc.querySelectorAll('Representation')) {
              const set=rep.parentElement, mime=rep.getAttribute('mimeType')||set?.getAttribute('mimeType')||'';
              const codec=rep.getAttribute('codecs')||set?.getAttribute('codecs')||'';
              const audio=mime.startsWith('audio/')||set?.getAttribute('contentType')==='audio';
              const rate=(rep.getAttribute('frameRate')||set?.getAttribute('frameRate')||'0').split('/').map(Number);
              add({format_id:'fb-dash-'+(rep.getAttribute('id')||formats.length),url:rep.querySelector('BaseURL')?.textContent||'',
                ext:audio?'m4a':'mp4',protocol:'https',width:Number(rep.getAttribute('width'))||0,height:Number(rep.getAttribute('height'))||0,
                fps:rate[0]/(rate[1]||1),tbr:(Number(rep.getAttribute('bandwidth'))||0)/1000,
                vcodec:audio?'none':codec||'h264',acodec:audio?codec||'aac':'none'});
            }
          }
          for(const item of delivery.progressive_urls||[]) {
            const q=String(item.metadata?.quality||'sd').toLowerCase();
            add({format_id:'fb-'+q,url:item.progressive_url,ext:'mp4',protocol:'https',vcodec:'h264',acodec:'aac',quality:q==='hd'?-2:-3});
          }
          for(const [key,q]of [['browser_native_hd_url','hd'],['playable_url_quality_hd','hd'],['browser_native_sd_url','sd'],['playable_url','sd']]) {
            if(legacy[key])add({format_id:'fb-'+q,url:legacy[key],ext:'mp4',protocol:'https',vcodec:'h264',acodec:'aac',quality:q==='hd'?-2:-3});
          }
          if(formats.length) {
            const previous=candidates.get(String(v.id));
            const inPost=/\.node(?:_v2)?\.comet_sections\.content\.story\.(?:attached_story\.)?attachments\./.test(path)?3:/\.attached_story\.(?:.*\.)?attachments\./.test(path)?2:/\.story\.attachments\./.test(path)?1:0;
            const item={id:String(v.id),title:String(v.name||v.savable_description?.text||'Facebook video').slice(0,500),
              webpage_url:fb(v.permalink_url||v.url)||'https://www.facebook.com/watch/?v='+v.id,
              duration:(Number(v.playable_duration_in_ms)||0)/1000,thumbnail:safe(v.preferred_thumbnail?.image?.uri||v.thumbnailImage?.uri||''),
              extractor:'facebook:browser',extractor_key:'FacebookBrowser',formats,inPost};
            if(!previous||inPost>previous.inPost||inPost===previous.inPost&&formats.length>previous.formats.length)candidates.set(String(v.id),item);
          }
        }
        for(const [k,x]of Object.entries(v))if(x&&typeof x==='object')queue.push({v:x,path:path+'.'+k});
      }
    }catch{}
  }
  const rows=[...candidates.values()], exact=rows.find(v=>v.id===wanted), rank=Math.max(0,...rows.map(v=>v.inPost)), attached=rows.filter(v=>rank>0&&v.inPost===rank);
  // Ambiguous feeds never silently choose an unrelated recommendation.
  const selected=exact||(attached.length===1?attached[0]:null);
  if(selected){delete selected.inPost;page.media=selected;}
  page.videoCandidates=rows.length;
  return page;
})()

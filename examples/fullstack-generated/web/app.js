(function(){
'use strict';
const ORIGIN=location.origin;
let csrfToken='';
let currentFeature=null;

const $=id=>document.getElementById(id);
const setStatus=(el,msg,type)=>{
  el.textContent=msg;
  el.className=type==='error'?'status-error':type==='ok'?'status-ok':type==='warn'?'status-warn':'';
};

function showView(name){
  $('login-view').hidden=name!=='login';
  $('features-view').hidden=name!=='features';
}

async function apiFetch(path,opts={}){
  const headers={'Content-Type':'application/json',...(opts.headers||{})};
  if(csrfToken)headers['X-CSRF-Token']=csrfToken;
  if(opts.withCreds!==false)headers['Origin']=ORIGIN;
  const res=await fetch(path,{
    credentials:'include',
    ...opts,
    headers
  });
  return res;
}

async function getSession(){
  const res=await apiFetch('/api/session',{method:'GET',withCreds:false,headers:{}});
  if(!res.ok)return null;
  const d=await res.json();
  csrfToken=d.csrfToken||'';
  return d;
}

async function loadFeatures(){
  const st=$('features-status');
  setStatus(st,'Loading…','');
  const res=await apiFetch('/api/features',{method:'GET'});
  if(res.status===401){
    const d=await res.json().catch(()=>({}));
    if(d.error==='SESSION_REQUIRED'){setStatus(st,'Session expired. Please sign in again.','error');showView('login');return;}
  }
  if(!res.ok){setStatus(st,'Failed to load features.','error');return;}
  const d=await res.json();
  const list=d.features||[];
  const feat=list.find(f=>f.featureId==='homepage_recommendation')||list[0]||null;
  if(!feat){setStatus(st,'No features found.','warn');return;}
  currentFeature=feat;
  renderFeature(feat);
  setStatus(st,'','');
}

function renderFeature(f){
  $('feature-id-heading').textContent=f.featureId;
  $('f-enabled').value=String(f.enabled);
  $('f-min-version').value=f.minVersion||'';
  $('f-revision').textContent=f.configRevision!=null?String(f.configRevision):'';
  $('revision-display').classList.remove('hidden');
  setStatus($('save-status'),'','');
  $('preview-result').hidden=true;
  setStatus($('preview-status'),'','');
}

$('login-page-btn').addEventListener('click',()=>{
  location.assign('/login');
});

$('feature-form').addEventListener('submit',async e=>{
  e.preventDefault();
  if(!currentFeature)return;
  const st=$('save-status');
  const btn=$('save-btn');
  btn.disabled=true;
  setStatus(st,'Saving…','');
  const body={
    enabled:$('f-enabled').value==='true',
    minVersion:$('f-min-version').value.trim(),
    expectedRevision:currentFeature.configRevision
  };
  try{
    const res=await apiFetch('/api/features/'+encodeURIComponent(currentFeature.featureId),{
      method:'PUT',
      body:JSON.stringify(body)
    });
    if(res.status===409){
      const d=await res.json().catch(()=>({}));
      if(d.error==='REVISION_CONFLICT'){
        setStatus(st,'Conflict: another update was made. Reload and retry.','warn');
        return;
      }
    }
    if(res.status===401){
      const d=await res.json().catch(()=>({}));
      setStatus(st,d.error==='SESSION_REQUIRED'?'Session expired. Sign in again.':'Unauthorized.','error');
      showView('login');return;
    }
    if(res.status===403){
      const d=await res.json().catch(()=>({}));
      const msg=d.error==='CSRF_REJECTED'?'CSRF check failed.':d.error==='ORIGIN_REJECTED'?'Origin rejected.':'Forbidden.';
      setStatus(st,msg,'error');return;
    }
    if(!res.ok){const d=await res.json().catch(()=>({}));setStatus(st,d.error||'Save failed.','error');return;}
    const updated=await res.json();
    currentFeature=Object.assign({},currentFeature,updated);
    renderFeature(currentFeature);
    setStatus(st,'Saved.','ok');
  }catch(err){
    setStatus(st,'Network error.','error');
  }finally{
    btn.disabled=false;
  }
});

$('reload-btn').addEventListener('click',async()=>{
  await loadFeatures();
});

$('preview-form').addEventListener('submit',async e=>{
  e.preventDefault();
  if(!currentFeature)return;
  const st=$('preview-status');
  const btn=e.target.querySelector('button[type=submit]');
  btn.disabled=true;
  setStatus(st,'Running preview…','');
  $('preview-result').hidden=true;
  const clientVersion=$('client-version').value.trim();
  try{
    const res=await apiFetch('/api/features/'+encodeURIComponent(currentFeature.featureId)+'/preview',{
      method:'POST',
      body:JSON.stringify({clientVersion})
    });
    if(res.status===401){
      const d=await res.json().catch(()=>({}));
      setStatus(st,d.error==='SESSION_REQUIRED'?'Session expired. Sign in again.':'Unauthorized.','error');
      showView('login');return;
    }
    if(res.status===403){
      const d=await res.json().catch(()=>({}));
      const msg=d.error==='CSRF_REJECTED'?'CSRF check failed.':d.error==='ORIGIN_REJECTED'?'Origin rejected.':'Forbidden.';
      setStatus(st,msg,'error');return;
    }
    if(!res.ok){const d=await res.json().catch(()=>({}));setStatus(st,d.error||'Preview failed.','error');return;}
    const d=await res.json();
    $('pr-feature-id').textContent=d.featureId!=null?String(d.featureId):'';
    $('pr-effective').textContent=d.effective!=null?String(d.effective):'';
    $('pr-reason').textContent=d.reasonCode!=null?String(d.reasonCode):'';
    $('pr-revision').textContent=d.configRevision!=null?String(d.configRevision):'';
    $('preview-result').hidden=false;
    setStatus(st,'','');
  }catch(err){
    setStatus(st,'Network error.','error');
  }finally{
    btn.disabled=false;
  }
});

$('logout-btn').addEventListener('click',()=>{
  location.assign('/login');
});

(async()=>{
  const sess=await getSession();
  if(sess){
    showView('features');
    await loadFeatures();
  }else{
    showView('login');
  }
})();
})();

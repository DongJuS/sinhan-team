const $ = id => document.getElementById(id);
let config, token, current, timer, approving=false;
const money = n => new Intl.NumberFormat('ko-KR').format(n)+'원';
const notice = text => { $('notice').textContent=text; };
const b64 = bytes => btoa(String.fromCharCode(...bytes)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
async function login(){
 const verifier=b64(crypto.getRandomValues(new Uint8Array(32))), state=crypto.randomUUID();
 sessionStorage.setItem('pkce',verifier);sessionStorage.setItem('oauth-state',state);
 const challenge=b64(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(verifier))));
 const params=new URLSearchParams({client_id:config.clientId,redirect_uri:location.origin+'/',response_type:'code',scope:'openid',code_challenge_method:'S256',code_challenge:challenge,state});
 location.assign(config.issuer+'/protocol/openid-connect/auth?'+params);
}
async function api(path,options={}){
 if(!token)throw new Error('먼저 로그인해주세요.');
 const response=await fetch(path,{...options,headers:{'Authorization':'Bearer '+token,'Content-Type':'application/json',...(options.headers||{})}});
 if(response.status===401){token=null;$('login').hidden=false;throw new Error('로그인이 만료되었습니다. 다시 로그인해주세요.');}
 const data=await response.json();if(!response.ok)throw new Error(data.message||'요청을 처리할 수 없습니다.');return data;
}
function render(state){
 current=state;const p=state.proposal;
 if(state.status==='COMPLETED')notice('주문과 모의 결제가 완료되었습니다.');
 if(state.status==='FAILED_REFUNDED')notice('주문을 완료하지 못해 결제 금액을 환불했습니다.');
 const labels={SEARCHING:'탐색 중',COMPARING:'혜택 비교',AWAITING_APPROVAL:'승인 대기',EXECUTING:'실행 중',COMPLETED:'주문 완료',REPLANNING:'새 주문안 작성',RETRYING:'재시도 중',RECOVERY_PENDING:'결과 확인 중',FAILED_REFUNDED:'환불 완료',FAILED:'처리 실패',NEEDS_INPUT:'조건 확인 필요'};
 $('status-label').textContent=labels[state.status]||state.status;$('progress').textContent=state.message;
 $('result-title').textContent=state.status==='COMPLETED'?'주문이 완료됐어요':p?'추천 주문안':'추천을 준비하고 있어요';
 const stage=state.status==='AWAITING_APPROVAL'?2:['EXECUTING','COMPLETED','FAILED_REFUNDED','RECOVERY_PENDING'].includes(state.status)?3:state.status==='COMPARING'?2:1;
 document.querySelectorAll('.steps li').forEach((li,i)=>li.classList.toggle('active',i<=stage));
 $('proposal').hidden=!p;if(!p)return;
 $('merchant').textContent=p.quote.basket.merchantName;
 $('items').replaceChildren(...p.quote.basket.items.map(i=>{const li=document.createElement('li');li.textContent=`${i.name} × ${i.quantity} · ${money(i.unitPriceKrw*i.quantity)}`;return li;}));
 $('delivery').textContent=`${p.address} · 예상 ${p.quote.basket.deliveryMinutes}분 · 배달비 ${money(p.quote.basket.deliveryFeeKrw)}`;
 $('explanation').textContent=p.explanation;$('charge').textContent=money(p.quote.chargeKrw);$('effective').textContent=money(p.quote.effectiveCostKrw);
 $('benefits').replaceChildren();
 const benefits=[['결제수단',p.quote.paymentMethod],['즉시 할인',money(p.quote.instantDiscountKrw)],['청구 할인',money(p.quote.statementDiscountKrw)],['캐시백',money(p.quote.cashbackKrw)],['적립 포인트',`${p.quote.pointsEarned}P (환산 ${money(p.quote.pointValueKrw)})`]];
 benefits.forEach(([name,value])=>{const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=name;dd.textContent=value;$('benefits').append(dt,dd);});
 $('expiry').textContent=`주문안 v${p.version} · ${new Date(p.expiresAt).toLocaleTimeString('ko-KR')}까지 유효`;
 $('alternatives').replaceChildren(...state.candidates.slice(0,8).map(q=>{const e=document.createElement('p');e.textContent=`${q.basket.merchantName} · ${q.paymentMethod} · 결제 ${money(q.chargeKrw)} / 예상 지출 ${money(q.effectiveCostKrw)}`;return e;}));
 $('approve').disabled=state.status!=='AWAITING_APPROVAL'||approving;
 $('approve').textContent=state.status==='COMPLETED'?'주문·결제 완료':'이 주문안으로 주문·결제 승인';
}
async function poll(id){
 clearTimeout(timer);
 try{const s=await api('/v1/purchase-requests/'+id);render(s);if(!['COMPLETED','FAILED_REFUNDED','NEEDS_INPUT','FAILED'].includes(s.status))timer=setTimeout(()=>poll(id),1500);}
 catch(e){notice(e.message);if(token)timer=setTimeout(()=>poll(id),3000);}
}
$('login').onclick=()=>login();
$('logout').onclick=()=>{token=null;clearTimeout(timer);location.assign(config.issuer+'/protocol/openid-connect/logout?'+new URLSearchParams({client_id:config.clientId,post_logout_redirect_uri:location.origin+'/'}));};
$('request-form').onsubmit=async e=>{e.preventDefault();$('search').disabled=true;clearTimeout(timer);
 try{const value=$('budget').value;const data=await api('/v1/purchase-requests',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({message:$('message').value,address:$('address').value,region:$('region').value,budgetKrw:value?Number(value):null,people:null,excludedIngredients:$('excluded').value.split(',').map(x=>x.trim()).filter(Boolean),cuisine:null})});notice('Agent가 조건에 맞는 메뉴와 혜택을 비교합니다.');render(data);poll(data.id);}
 catch(e){notice(e.message);}finally{$('search').disabled=false;}
};
$('approve').onclick=async()=>{if(!current?.proposal||approving)return;approving=true;$('approve').disabled=true;const p=current.proposal;
 try{const s=await api('/v1/proposals/'+p.id+'/approvals',{method:'POST',body:JSON.stringify({version:p.version})});render(s);notice('최종 승인했습니다. 주문·결제 결과를 확인하고 있습니다.');}
 catch(e){notice(e.message);}finally{approving=false;poll(current.id);}
};
(async()=>{try{
 config=await (await fetch('/config.json')).json();const params=new URLSearchParams(location.search);
 if(params.has('code')){
  if(params.get('state')!==sessionStorage.getItem('oauth-state'))throw new Error('로그인 상태를 검증할 수 없습니다. 다시 로그인해주세요.');
  const verifier=sessionStorage.getItem('pkce');sessionStorage.removeItem('pkce');sessionStorage.removeItem('oauth-state');
  const r=await fetch(config.issuer+'/protocol/openid-connect/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'authorization_code',client_id:config.clientId,redirect_uri:location.origin+'/',code:params.get('code'),code_verifier:verifier})});
  if(!r.ok)throw new Error('로그인을 완료할 수 없습니다.');token=(await r.json()).access_token;history.replaceState({},'',location.pathname);$('login').hidden=true;$('logout').hidden=false;notice('로그인했습니다. 원하는 식사를 알려주세요.');
 }
 }catch(e){notice(e.message);history.replaceState({},'',location.pathname);}})();

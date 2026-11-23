(function(){
if(window.__rtUI)return;window.__rtUI=1;
var VID="__VID__",AUTO=__AUTOPLAY__;
var d=document;
/* ---- hide everything YouTube draws except the <video> (same list/idea as the exe's WV_CSS) ---- */
var HIDE=['.ytp-chrome-top','.ytp-chrome-bottom','.ytp-gradient-top','.ytp-gradient-bottom','.ytp-title','.ytp-title-channel','.ytp-title-beacon',
'.ytp-watermark','.ytp-youtube-button','.ytp-share-button','.ytp-watch-later-button','.ytp-copylink-button','.ytp-pause-overlay','.ytp-pause-overlay-container',
'.ytp-endscreen-content','.html5-endscreen','.ytp-ce-element','.ytp-ce-covering-overlay','.ytp-cards-teaser','.ytp-cards-button','.ytp-large-play-button',
'.ytp-spinner','.ytp-popup','.ytp-contextmenu','.ytp-iv-video-content','.annotation','.ytp-suggested-action','.ytp-paid-content-overlay','.ytp-videowall-still',
'.ytp-impression-link','.ytp-tooltip','.ytp-bezel','.ytp-bezel-text-wrapper','.ytp-expand-pause-overlay','.ytp-suggestion-set',
'[class*="ytp-pause-overlay"]','[class*="ytp-ce-"]','[class*="ytp-endscreen"]','[class*="ytp-chrome-"]','[class*="ytp-cards"]','[class*="ytp-gradient"]',
'.ytp-caption-window-container','.caption-window','.ytp-caption-segment','.captions-text','.ytp-subtitles-button',
'ytd-masthead','#masthead-container','#secondary','#below','#related','#comments','#chat','ytd-watch-metadata','ytd-miniplayer','tp-yt-app-drawer',
'ytd-mini-guide-renderer','#guide','#guide-wrapper','ytd-popup-container','ytd-consent-bump-v2-lightbox','tp-yt-iron-overlay-backdrop',
'ytm-mobile-topbar-renderer','.mobile-topbar-header','ytm-engagement-panel','ytm-pivot-bar-renderer'];
var CSS='html,body{margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important;cursor:default!important}'+
'.html5-video-player{background:#000!important;cursor:default!important}ytd-app,#page-manager{background:#000!important;margin:0!important}'+
'ytd-watch-flexy #primary,ytd-watch-flexy #primary-inner,ytd-watch-flexy #columns{margin:0!important;padding:0!important;max-width:none!important}'+
'body.rt-watch #movie_player,body.rt-watch #player-container-outer,body.rt-watch #player-container-inner,body.rt-watch #player-container,body.rt-watch #player,body.rt-watch #ytd-player,body.rt-watch .html5-video-container{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;z-index:1!important}'+
'body.rt-watch .html5-main-video{width:100%!important;height:100%!important;left:0!important;top:0!important;object-fit:contain!important}'+
HIDE.join(',')+'{display:none!important}'+
'body *{visibility:hidden!important}body video{visibility:visible!important}'+
'#__rt_ui,#__rt_ui *{visibility:visible!important}'+
/* our own controls (look copied from assets/player.html) */
'#__rt_ui{position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;font-family:sans-serif;-webkit-user-select:none;user-select:none;-webkit-tap-highlight-color:transparent}'+
'#__rt_ui *{box-sizing:border-box}'+
'#__rt_ui .rs{position:absolute;inset:0;z-index:2}'+
'#__rt_ui .rc{position:absolute;inset:0;z-index:3;background:#000 center/cover no-repeat;display:flex;align-items:center;justify-content:center;transition:opacity .2s}'+
'#__rt_ui .rc.off{opacity:0;pointer-events:none}'+
'#__rt_ui .rb{width:64px;height:64px;border-radius:50%;background:#e8365d;color:#fff;display:flex;align-items:center;justify-content:center;box-shadow:0 8px 24px rgba(0,0,0,.45)}'+
'#__rt_ui .rb svg{width:28px;height:28px;margin-left:3px}'+
'#__rt_ui .rbar{position:absolute;left:0;right:0;bottom:0;z-index:4;display:flex;align-items:center;gap:8px;padding:22px 12px 8px;background:linear-gradient(transparent,rgba(0,0,0,.8));color:#fff;font-size:12px;transition:opacity .25s}'+
'#__rt_ui .rbar.rhide{opacity:0;pointer-events:none}'+
'#__rt_ui .rbar button{background:none;border:0;color:#fff;font-size:12.5px;font-weight:700;min-width:32px;height:32px;display:flex;align-items:center;justify-content:center;padding:0}'+
'#__rt_ui .rbar button svg{width:18px;height:18px}'+
'#__rt_ui .rbar input{flex:1;min-width:0;accent-color:#e8365d}'+
'#__rt_ui .rspin{position:absolute;inset:0;z-index:5;display:none;align-items:center;justify-content:center;pointer-events:none}'+
'#__rt_ui .rspin.on{display:flex}'+
'#__rt_ui .rspin i{width:46px;height:46px;border-radius:50%;border:4px solid rgba(255,255,255,.25);border-top-color:#e8365d;animation:rtsp .8s linear infinite;background:rgba(0,0,0,.35)}'+
'@keyframes rtsp{to{transform:rotate(360deg)}}'+
'#__rt_ui .rqm{position:absolute;right:10px;bottom:52px;z-index:6;display:none;min-width:84px;max-height:70%;overflow:auto;background:rgba(20,20,30,.95);border-radius:10px;padding:4px;color:#fff;font-size:13px;font-weight:600}'+
'#__rt_ui .rqm div{padding:9px 14px;border-radius:7px;text-align:center}'+
'#__rt_ui .rqm div.on{background:#e8365d}';
var PLAY='<svg viewBox="0 0 24 24" fill="currentColor"><path d="M7 4.5v15l13-7.5z"/></svg>';
var PAUSE='<svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 4h4v16H6zM14 4h4v16h-4z"/></svg>';

function P(){return d.getElementById('movie_player')||d.querySelector('.html5-video-player')}
function V(){return d.querySelector('video')}
function F(n,a){try{var p=P();if(p&&typeof p[n]==='function')return p[n].apply(p,a||[])}catch(e){}}
function ST(){var s=F('getPlayerState');if(typeof s==='number')return s;var v=V();return !v?-1:v.ended?0:v.paused?2:(v.readyState<3?3:1)}
function DUR(){var x=F('getDuration');if(!(x>0)){var v=V();x=(v&&isFinite(v.duration))?v.duration:0}return x||0}
function CUR(){var x=F('getCurrentTime');if(typeof x!=='number'){var v=V();x=v?v.currentTime:0}return x||0}
function fmt(t){t=Math.max(0,Math.floor(t||0));return Math.floor(t/60)+':'+('0'+(t%60)).slice(-2)}

/* read by the app (Media.java) every 400 ms: no YouTube IFrame API involved */
window.__rtProbe=function(){try{
  var vd=F('getVideoData')||{},e=d.querySelector('.ytp-error,yt-player-error-message-renderer'),m=location.search.match(/[?&]v=([\w-]{11})/);
  return {ui:1,st:ST(),d:DUR(),t:CUR(),live:!!vd.isLive,err:!!(e&&e.getClientRects().length),v:m?m[1]:'',path:location.pathname};
}catch(x){return null}};

var playing=false,started=false,drag=false,hideT=null,ticks=0,curQ='auto',qRestore=null,si=0,speeds=[1,1.25,1.5,2,0.75];
var QL={hd2160:'2160p',hd1440:'1440p',hd1080:'1080p',hd720:'720p',large:'480p',medium:'360p',small:'240p',tiny:'144p'};
var cover,bar,pp,sk,tt,dd,sp,qb,qm,spin;
function $(i){return d.getElementById(i)}
function setSpin(on){if(spin)spin.classList.toggle('on',!!on)}
function showBar(){bar.classList.remove('rhide');clearTimeout(hideT);if(playing)hideT=setTimeout(function(){bar.classList.add('rhide')},2800)}
function toggle(){if(!playing)setSpin(true);if(playing){F('pauseVideo');var v=V();if(v&&ST()===1&&!P())v.pause()}else{F('playVideo');var w=V();if(w&&!P())w.play()}}

function mount(){
  if(!d.body||!d.head)return false;
  if($('__rt_ui'))return true;
  var st=$('__rt_wvcss');
  if(!st){st=d.createElement('style');st.id='__rt_wvcss';st.textContent=CSS;d.head.appendChild(st)}
  var ui=d.createElement('div');ui.id='__rt_ui';
  ui.innerHTML='<div class="rs" id="rt_sh"></div><div class="rc" id="rt_cv" style="background-image:url(https://i.ytimg.com/vi/'+VID+'/hqdefault.jpg)"><div class="rb" id="rt_big"></div></div>'+
   '<div class="rbar rhide" id="rt_bar"><button id="rt_pp"></button><span id="rt_t">0:00</span><input id="rt_sk" type="range" min="0" max="1000" value="0"><span id="rt_d">0:00</span><button id="rt_sp">1x</button><button id="rt_qb" style="min-width:44px">Auto</button></div>'+
   '<div class="rspin" id="rt_spin"><i></i></div><div class="rqm" id="rt_qm"></div>';
  d.body.appendChild(ui);
  cover=$('rt_cv');bar=$('rt_bar');pp=$('rt_pp');sk=$('rt_sk');tt=$('rt_t');dd=$('rt_d');sp=$('rt_sp');qb=$('rt_qb');qm=$('rt_qm');spin=$('rt_spin');
  $('rt_big').innerHTML=PLAY;pp.innerHTML=PLAY;
  if(AUTO)setSpin(true);
  $('rt_sh').onclick=function(){if(qm.style.display==='block'){qm.style.display='none';return}bar.classList.contains('rhide')?showBar():toggle()};
  cover.onclick=toggle;
  pp.onclick=function(e){e.stopPropagation();toggle();showBar()};
  bar.onclick=function(e){e.stopPropagation()};
  sp.onclick=function(e){e.stopPropagation();si=(si+1)%speeds.length;F('setPlaybackRate',[speeds[si]]);var v=V();if(v)v.playbackRate=speeds[si];sp.textContent=speeds[si]+'x';showBar()};
  qb.onclick=function(e){
    e.stopPropagation();
    if(qm.style.display==='block'){qm.style.display='none';return}
    var lv=[];try{lv=F('getAvailableQualityLevels')||[]}catch(_){}
    lv=lv.filter(function(x){return QL[x]});if(!lv.length)lv=['hd1080','hd720','large','medium','small','tiny'];
    qm.innerHTML=['auto'].concat(lv).map(function(x){return '<div data-q="'+x+'" class="'+(x===curQ?'on':'')+'">'+(x==='auto'?'Auto':QL[x])+'</div>'}).join('');
    qm.style.display='block';showBar();
  };
  qm.onclick=function(e){
    e.stopPropagation();
    var x=e.target&&e.target.getAttribute&&e.target.getAttribute('data-q');if(!x)return;
    qm.style.display='none';curQ=x;qb.textContent=x==='auto'?'Auto':QL[x];
    var qq=x==='auto'?'default':x;
    qRestore={t:CUR(),rate:speeds[si],play:playing};
    F('setPlaybackQualityRange',[qq,qq]);F('setPlaybackQuality',[qq]);
    F('loadVideoById',[{videoId:VID,startSeconds:qRestore.t,suggestedQuality:qq}]);
  };
  sk.oninput=function(){drag=true;showBar()};
  sk.onchange=function(){var dur=DUR(),s=dur*sk.value/1000;if(!F('seekTo',[s,true])){var v=V();if(v)v.currentTime=s}drag=false};
  return true;
}
window.__fsPrep=function(){window.__wp=playing};
window.__fsRestore=function(){setTimeout(function(){try{if(window.__wp&&ST()!==1)F('playVideo')}catch(_){}},350)};

setInterval(function(){
  try{
    ticks++;
    if(d.body&&location.pathname==='/watch')d.body.classList.add('rt-watch');
    if(!mount())return;
    F('unloadModule',['captions']);F('setOption',['captions','track',{}]);
    var s=ST();playing=(s===1||s===3);
    if(s===1){started=true;if(qRestore){var r=qRestore;qRestore=null;F('setPlaybackRate',[r.rate]);if(!r.play)F('pauseVideo')}}
    if(AUTO&&!started&&(ticks===4||ticks===12||ticks===25))F('playVideo');
    cover.classList.toggle('off',started);
    if(s===3)setSpin(true);else if(s===1||s===2||s===0||s===5)setSpin(false);
    pp.innerHTML=playing?PAUSE:PLAY;
    if(s===1){if(bar.classList.contains('rhide')===false&&!hideT)showBar()}else{bar.classList.remove('rhide');clearTimeout(hideT);hideT=null}
    var dur=DUR(),c=CUR();
    sk.style.visibility=dur>0?'visible':'hidden';sp.style.display=dur>0?'flex':'none';qb.style.display=dur>0?'flex':'none';
    tt.textContent=fmt(c);dd.textContent=dur>0?fmt(dur):'LIVE';
    if(!drag&&dur>0)sk.value=Math.round(c/dur*1000);
  }catch(e){}
},300);
window.addEventListener('keydown',function(e){e.stopImmediatePropagation();e.preventDefault()},true);
window.addEventListener('contextmenu',function(e){e.preventDefault()},true);
})();

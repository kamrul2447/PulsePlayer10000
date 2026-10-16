/* ============================================================================
   Pulse Player — full-screen now-playing surface.

   This file owns presentation only. Android Media3 remains the playback engine
   and authoritative source of track, queue and session state.
   ========================================================================== */
(function(global){
 'use strict';
 var P=global.Pulse=P||{},U=P.util,S=P.store;
 var player,art,artImg,artFallback,title,artist,album,bg,playIcon,favIcon,playBtn,shuffleBtn,repeatBtn,volumeRow,volumeSlider,volumeValue,sleepLabel,sleepBtn;
 var mounted=false,opened=false,lastUid=null,lastFav=null;
 function setIcon(el,name){if(!el)return;var use=el.querySelector('use');if(use)use.setAttribute('href','#'+(name.indexOf('i-')===0?name:'i-'+name));}
 function activeBtn(el,on){if(el){el.classList.toggle('is-on',!!on);el.setAttribute('aria-pressed',String(!!on));}}
 function current(){return S.state.currentTrack;}
 function render(){
  if(!player)return;var t=current(),ps=S.state.player;
  player.classList.toggle('is-open',opened);
  player.setAttribute('aria-hidden',String(!opened));
  if(P.miniPlayer){var mini=document.getElementById('miniPlayer');if(mini)mini.classList.toggle('is-hidden-by-player',opened);}
  if(!t){
   title.textContent='Nothing playing';artist.textContent='Unknown artist';album.textContent='';
   artImg.hidden=true;artFallback.hidden=false;bg.style.backgroundImage='none';bg.classList.remove('is-ready');
   setIcon(playIcon,'i-play');playBtn.setAttribute('aria-label','Play');activeBtn(favIcon?favIcon.parentNode:null,false);
   document.body.classList.remove('has-track');return;
  }
  document.body.classList.add('has-track');
  if(t.uid!==lastUid){
   lastUid=t.uid;title.textContent=U.trackTitle(t);artist.textContent=U.trackArtist(t);album.textContent=U.trackAlbum(t)||'';
   P.artwork.mount(artImg,t,500,function(){artImg.hidden=false;artFallback.hidden=true;});
   artFallback.hidden=true;
   bg.style.backgroundImage=P.artwork.background(t);bg.classList.add('is-ready');
   title.classList.remove('is-changing');U.replay(title,'is-changing');
  }
  var fav=!!(t.uid&&P.favorites.has(t.uid));activeBtn(document.getElementById('playerFav'),fav);
  if(fav!==lastFav){lastFav=fav;setIcon(favIcon,fav?'i-heart-fill':'i-heart');}
  setIcon(playIcon,ps.playing?'i-pause':'i-play');playBtn.setAttribute('aria-label',ps.playing?'Pause':'Play');
  activeBtn(shuffleBtn,ps.shuffle);activeBtn(repeatBtn,ps.repeat!==0);
  setIcon(repeatBtn,ps.repeat===2?'i-repeat-one':'i-repeat');
  var label=document.getElementById('playerKicker');if(label)label.textContent=ps.buffering?'Buffering':ps.playing?'Now playing':'Paused';
  if(volumeSlider)volumeSlider.value=String(ps.volume==null?1:ps.volume);
  if(volumeValue)volumeValue.textContent=String(Math.round((ps.volume==null?1:ps.volume)*100));
  if(sleepLabel)sleepLabel.textContent=P.sleep.isActive()?P.sleep.label():'Sleep';
  if(sleepBtn)sleepBtn.classList.toggle('is-on',P.sleep.isActive());
  art.classList.toggle('is-playing',!!ps.playing);
 }
 var PlayerUI={
  mount:function(){
   if(mounted)return;mounted=true;
   player=document.getElementById('fullPlayer');art=document.getElementById('playerArt');artImg=document.getElementById('playerArtImg');artFallback=document.getElementById('playerArtFallback');
   title=document.getElementById('playerTitle');artist=document.getElementById('playerArtist');album=document.getElementById('playerAlbum');bg=document.getElementById('playerBg');
   playIcon=document.getElementById('btnPlayIcon');playBtn=document.getElementById('btnPlay');favIcon=document.getElementById('playerFavIcon');shuffleBtn=document.getElementById('btnShuffle');repeatBtn=document.getElementById('btnRepeat');
   volumeRow=document.getElementById('volumeRow');volumeSlider=document.getElementById('volumeSlider');volumeValue=document.getElementById('volumeValue');sleepLabel=document.getElementById('sleepPillLabel');sleepBtn=document.getElementById('btnSleep');
   document.getElementById('playerClose').addEventListener('click',PlayerUI.close);
   document.getElementById('playerQueue').addEventListener('click',function(){P.queue.open();});
   document.getElementById('miniPlayer').addEventListener('dblclick',PlayerUI.open);
   playBtn.addEventListener('click',function(){P.playback.toggle();});
   document.getElementById('miniPlay').addEventListener('click',function(){});
   document.getElementById('btnPrev').addEventListener('click',P.playback.prev);
   document.getElementById('btnNext').addEventListener('click',P.playback.next);
   shuffleBtn.addEventListener('click',function(){P.playback.setShuffle(!S.state.player.shuffle);});
   repeatBtn.addEventListener('click',function(){P.playback.cycleRepeat();});
   document.getElementById('playerFav').addEventListener('click',P.playback.toggleFavorite);
   document.getElementById('btnVolumeToggle').addEventListener('click',function(){volumeRow.hidden=!volumeRow.hidden;});
   volumeSlider.addEventListener('input',function(){P.playback.setVolume(Number(volumeSlider.value));});
   sleepBtn.addEventListener('click',openSleep);
   var closeY=0;
   player.addEventListener('touchstart',function(e){if(e.target.closest('button,input,.seek'))return;closeY=e.touches[0].clientY;},{passive:true});
   player.addEventListener('touchend',function(e){if(!opened||!closeY)return;var end=e.changedTouches[0].clientY;if(end-closeY>115)PlayerUI.close();closeY=0;},{passive:true});
   P.progress.mount(function(ms){P.playback.seek(ms);});
   PlayerUI.render();
  },
  open:function(){
   if(!player)PlayerUI.mount();
   if(!current()){P.toast.show('Choose a song first');return;}
   opened=true;player.classList.add('is-open');player.setAttribute('aria-hidden','false');
   PlayerUI.render();P.bus.emit('playerOpen',true);
  },
  close:function(){
   if(!opened)return false;opened=false;
   if(player){player.classList.remove('is-open');player.setAttribute('aria-hidden','true');}
   if(P.miniPlayer)P.miniPlayer.render();
   P.bus.emit('playerOpen',false);return true;
  },
  isOpen:function(){return opened;},
  render:render
 };
 function openSleep(){
  var mins=[10,15,30,45,60,90,120],body=U.h('div');
  body.appendChild(U.h('p.apihelp',{text:'The timer is managed by Android, so it remains active when Pulse Player is in the background.'}));
  body.appendChild(U.h('div.timergrid',mins.map(function(m){return U.h('button.timerchip',{type:'button',onclick:function(){P.sleep.start(m*60000);P.sheet.close();render();P.toast.show('Sleep timer set');}},m<60?m+' min':(m/60)+' hr');})));
  var custom=U.h('input.sleep-custom__input',{type:'number',min:'1',max:'720',value:'20','aria-label':'Timer minutes'});
  body.appendChild(U.h('div.sleep-custom',U.h('div.sleep-custom__copy',U.h('div.sleep-custom__title',{text:'Custom timer'}),U.h('div.sleep-custom__sub',{text:'1 to 720 minutes'})),U.h('div.sleep-custom__controls',custom,U.h('button.actionbtn.sleep-custom__button',{type:'button',onclick:function(){var n=Number(custom.value);if(n<1||n>720||!isFinite(n)){P.toast.show('Enter 1–720 minutes');return;}P.sleep.start(n*60000);P.sheet.close();render();}},'Start'))));
  if(P.sleep.isActive())body.appendChild(P.sheet.row({icon:'i-close',label:'Cancel timer',onClick:function(){P.sleep.cancel();P.sheet.close();render();}}));
  P.sheet.open({title:'Sleep timer',body:body});
 }
 P.playerUI=PlayerUI;
 P.bus.on('state',render);
 P.bus.on('settings',render);
 P.bus.on('sleep',render);
 P.bus.on('favorites',render);
})(window);
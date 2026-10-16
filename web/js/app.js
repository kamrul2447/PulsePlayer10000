/* Pulse Player — app bootstrap, state fan-out and compatibility fixes. */
(function(global){
 'use strict';
 var P=global.Pulse=global.Pulse||{},U=P.util,S=P.store,rafId=null,secondTimer=null;

 // Archived rows need their stable archive key so Play/Restore use the native
 // archive commands instead of being mistaken for fresh online search results.
 var nativeArchiveTracks=P.library.archiveTracks;
 P.library.archiveTracks=function(){
  return nativeArchiveTracks.apply(P.library,arguments).map(function(t){
   if(!t.archiveKey){
    var provider=String(t.provider||'youtube'),id=String(t.trackId||t.id||t.localUid||t.uid||'');
    t.archiveKey=provider==='local'?'local:'+String(t.localUid||id):provider+':'+id;
   }
   return t;
  });
 };

 function frame(){if(S.state.player.playing&&!document.hidden){P.progress.tick();P.miniPlayer.tick();}rafId=requestAnimationFrame(frame);}
 function startLoops(){if(rafId==null)rafId=requestAnimationFrame(frame);if(secondTimer==null)secondTimer=setInterval(function(){P.sleep.tick();P.bus.emit('tick');},1000);}
 function stopLoops(){if(rafId!=null){cancelAnimationFrame(rafId);rafId=null;}if(secondTimer!=null){clearInterval(secondTimer);secondTimer=null;}}
 document.addEventListener('visibilitychange',function(){if(document.hidden)stopLoops();else startLoops();});
 function renderInitial(){
  P.nav.go('home',{force:true});P.homeUI.render();P.libraryUI.render();P.searchUI.render();P.settingsUI.render();P.miniPlayer.render();P.playerUI.render();
  // Initializing hidden pages must not leave their title on the visible Home tab.
  if(P.nav.current()==='home')P.nav.setAppbar('Home','');
 }
 function boot(){
  try{
   P.theme.apply(S.state.settings.theme||'system');P.nav.mount();P.miniPlayer.mount();P.playerUI.mount();renderInitial();
   var lastLibraryUid=null,lastLibraryPlaying=null;
   P.bus.on('state',function(pl){
    P.progress.setDuration(pl.duration);P.progress.setPosition(pl.position);P.miniPlayer.render();
    if(P.nav.current()==='library'){
     var uidChanged=S.state.currentUid!==lastLibraryUid,playingChanged=!!S.state.player.playing!==lastLibraryPlaying;
     if(uidChanged||playingChanged){lastLibraryUid=S.state.currentUid;lastLibraryPlaying=!!S.state.player.playing;P.libraryUI.refreshRows();}
    }
    if(P.nav.current()==='home'&&S.state.currentUid)keepHomeFresh();
   });
   P.bus.on('position',function(pl){P.progress.setPosition(pl.position);});
   var lastLibrarySignature=null;
   P.bus.on('library',function(tracks){
    var list=tracks||[],signature=list.map(function(t){return t.uid+':'+(t.dateModified||0)+':'+(t.hidden?1:0);}).join('|');
    if(signature===lastLibrarySignature)return;lastLibrarySignature=signature;
    if(P.nav.current()==='library')P.libraryUI.render();
    else if(P.nav.current()==='home')P.homeUI.render();
    else if(P.nav.current()==='search')P.searchUI.render();
   });
   P.bus.on('queue',function(){if(P.queue&&P.sheet.isOpen())P.queue.render();});
   P.bus.on('nativeError',function(e){P.toast.show(e&&e.message?e.message:'Something went wrong',2600);});
   P.bus.on('permission',function(state){P.onboarding.render(state);if(state==='granted')P.homeUI.render();});
   P.ads.init();startLoops();P.bridge.ready();setTimeout(function(){P.splash.hide();},3000);
  }catch(err){console.error('[Pulse] boot failed',err);P.splash.hide(true);}
 }
 var lastResumeUid=null;
 function keepHomeFresh(){if(lastResumeUid===S.state.currentUid)return;lastResumeUid=S.state.currentUid;P.homeUI.render();}
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',boot);else boot();
 P.app={renderAll:renderInitial,startLoops:startLoops,stopLoops:stopLoops};
})(window);
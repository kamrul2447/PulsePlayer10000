/* Pulse Player — playback facade; Android Media3 owns actual playback. */
(function(global){
 'use strict';
 var P=global.Pulse=P||{},U=P.util,S=P.store;
 var Playback={
  playList:function(list,index,source,shuffle){
   list=list||[];if(!list.length)return;
   var uids=list.map(function(t){return t.uid;});S.state.remoteQueue=[];S.state.remoteQueueTitle='';
   var i=U.clamp(index|0,0,uids.length-1);if(shuffle)P.bridge.setShuffle(true);
   P.bridge.playTracks(uids,i,source||'library');S.setCurrent(uids[i]);P.bus.emit('state',S.state.player);
  },
  playTrack:function(track,source,list){
   if(!track)return;var l=list||P.library.songs(),idx=l.findIndex(function(t){return t.uid===track.uid;});
   if(idx<0){l=[track];idx=0;}Playback.playList(l,idx,source||'library');
  },
  playRemote:function(item){
   if(!item)return;S.state.remoteQueue=[];S.state.remoteQueueTitle='';
   P.bridge.send('playRemote',{item:item});P.toast.show('Loading “'+(item.title||'track')+'”…');
   // Open immediately: native stream resolution is asynchronous, so waiting
   // for the first state event made the full player fail to open on first play.
   if(P.playerUI&&P.playerUI.open)P.playerUI.open();
  },
  play:function(){P.bridge.play();},pause:function(){P.bridge.pause();},
  toggle:function(){if(!S.state.currentUid&&S.state.tracks.length)return Playback.playTrack(S.state.tracks[0],'library');P.bridge.togglePlay();},
  next:function(){P.bridge.next();},prev:function(){P.bridge.prev();},
  seek:function(ms){P.bridge.seek(ms);P.progress.setPosition(ms);},
  seekBy:function(deltaMs){Playback.seek(Math.max(0,P.progress.estimate()+deltaMs));},
  setVolume:function(v){v=U.clamp(v,0,1);S.state.player.volume=v;P.bridge.setVolume(v);P.bus.emit('volume',v);},
  volumeUp:function(){Playback.setVolume((S.state.player.volume||0)+.1);},
  volumeDown:function(){Playback.setVolume((S.state.player.volume||0)-.1);},
  setShuffle:function(on){P.bridge.setShuffle(on);S.state.player.shuffle=!!on;P.bus.emit('state',S.state.player);P.toast.show(on?'Shuffle on':'Shuffle off');},
  cycleRepeat:function(){var n=(S.state.player.repeat+1)%3;P.bridge.setRepeat(n);S.state.player.repeat=n;P.bus.emit('state',S.state.player);P.toast.show(['Repeat off','Repeat all','Repeat one'][n]);},
  toggleFavorite:function(){var uid=S.state.currentUid;if(!uid)return;var fav=P.favorites.toggle(uid),btn=document.getElementById('playerFav');if(btn)P.favorites.burst(btn);P.toast.show(fav?'Added to favorites':'Removed from favorites');},
  repeatLabel:function(){return ['Repeat off','Repeat all','Repeat one'][S.state.player.repeat]||'Repeat off';}
 };
 P.playback=Playback;
})(window);
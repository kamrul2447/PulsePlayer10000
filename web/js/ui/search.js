/* Pulse Player — Search UI. Local searches stay instant; online playback stays native. */
(function (global) {
 'use strict';
 var P=global.Pulse=P||{},U=P.util,S=P.store;
 var root,input,field,results,tools,built=false,lastQuery='',playlist=null,items=[],loading=false,complete=false,errorText='',queueActive=false,queueShuffle=false,queueStart='',queued=Object.create(null),requestSeq=0;
 var SAVED_KEY='pulse.savedYouTubePlaylists.v1';
 function byId(x){return String(x&&(x.id||x.trackId||x.url)||'');}
 function getSaved(){try{var a=JSON.parse(global.localStorage.getItem(SAVED_KEY)||'[]');return Array.isArray(a)?a.filter(function(x){return x&&x.id&&x.url;}).slice(0,20):[];}catch(e){return[];}}
 function setSaved(a){try{global.localStorage.setItem(SAVED_KEY,JSON.stringify(a.slice(0,20)));}catch(e){P.toast.show('Could not save playlist shortcut');}}
 function parsePlaylist(raw){try{var u=new URL(String(raw||'').trim()),host=u.hostname.toLowerCase().replace(/^www\./,'');if(['youtube.com','m.youtube.com','music.youtube.com','youtu.be'].indexOf(host)<0)return null;var id=u.searchParams.get('list')||'';if(!/^[a-zA-Z0-9_-]{8,128}$/.test(id))return null;return{id:id,url:'https://www.youtube.com/playlist?list='+encodeURIComponent(id),title:''};}catch(e){return null;}}
 function parseVideo(raw){try{var u=new URL(String(raw||'').trim()),host=u.hostname.toLowerCase().replace(/^www\./,'');if(['youtube.com','m.youtube.com','music.youtube.com','youtu.be'].indexOf(host)<0)return null;var id=host==='youtu.be'?u.pathname.split('/').filter(Boolean)[0]:u.searchParams.get('v');if(!id){var p=u.pathname.split('/').filter(Boolean);if(p.length>1&&['shorts','embed','live'].indexOf(p[0])>=0)id=p[1];}return /^[\w-]{11}$/.test(id||'')?{id:id,title:'YouTube video',artist:'YouTube',provider:'youtube',source:'online',artworkUrl:'https://i.ytimg.com/vi/'+id+'/hqdefault.jpg',streamUrl:'pulse-yt://'+id,durationMs:0}:null;}catch(e){return null;}}
 function build(){
  if(built)return;root=document.getElementById('searchScroll');if(!root)return;built=true;
  input=U.h('input',{type:'search',placeholder:'Search songs or paste a YouTube link',autocomplete:'off',enterkeyhint:'search','aria-label':'Search local music or YouTube',onkeydown:function(e){if(e.key==='Enter'){e.preventDefault();searchNow(input.value);input.blur();}},oninput:U.debounce(function(){field.classList.toggle('has-value',!!input.value);if(!input.value.trim())resetSearch();},160)});
  var clear=U.h('button.iconbtn.searchfield__clear',{type:'button','aria-label':'Clear search',onclick:function(){input.value='';field.classList.remove('has-value');searchNow('');input.focus();}},U.icon('i-close','ico--sm'));
  var go=U.h('button.iconbtn.searchfield__more',{type:'button','aria-label':'Search',onclick:function(){searchNow(input.value);}},U.icon('i-search','ico--sm'));
  field=U.h('div.searchfield',U.icon('i-search','ico--sm'),input,clear,go);
  tools=buildTools();results=U.h('div.search__results');
  root.appendChild(field);root.appendChild(tools);root.appendChild(results);renderSuggestions();
 }
 function buildTools(){
  var space=U.h('button.searchtool',{type:'button',onclick:function(){P.bridge.openAppSpace();}},
    U.h('div.searchtool__icon.searchtool__icon--space',{text:'S'}),
    U.h('div.searchtool__body',U.h('div.searchtool__title',{text:'SnapTube App Space'}),U.h('div.searchtool__sub',{text:'Open the installed SnapTube app'})));
  var manage=U.h('button.searchtool',{type:'button',onclick:showConnections},
    U.h('div.searchtool__icon',{},U.icon('i-sparkle','ico--sm')),
    U.h('div.searchtool__body',U.h('div.searchtool__title',{text:'Music sources'}),U.h('div.searchtool__sub',{text:'View online connection status'})));
  return U.h('div.searchtools',space,manage);
 }
 function showConnections(){
  var enabled=S.state.settings.onlineSearchEnabled!==false;
  P.sheet.open({title:'Music sources',body:[
   P.sheet.row({icon:'i-wave',label:'YouTube via NewPipe Extractor',value:enabled?'Enabled':'Disabled',onClick:function(){P.sheet.close();P.toast.show('YouTube uses the built-in native extractor; no API key is required.');}}),
   P.sheet.row({icon:enabled?'i-pause':'i-play',label:enabled?'Disable online search':'Enable online search',onClick:function(){P.bridge.setSetting('onlineSearchEnabled',!enabled);P.sheet.close();P.toast.show(!enabled?'Online search enabled':'Online search disabled');}}),
   P.sheet.row({icon:'i-refresh',label:'Test online connection',onClick:function(){P.sheet.close();if(!S.state.online.available){P.toast.show('Internet status is unavailable. Try a YouTube search.');return;}P.toast.show(S.state.online.available?'Network is available':'No network connection');}}),
   U.h('p.apihelp',{text:'The current Android backend has one active online provider: YouTube. Custom API endpoints are not shown as working providers unless the native playback bridge supports their stream format.'})
  ]});
 }
 function searchNow(raw){
  var q=String(raw||'').trim();lastQuery=q;S.state.search.query=q;requestSeq++;
  playlist=null;items=[];loading=false;complete=false;errorText='';queueActive=false;queueShuffle=false;queueStart='';queued=Object.create(null);
  P.search.cancel();
  if(!q){P.search.reset();renderSuggestions();return;}
  var pl=parsePlaylist(q);
  if(pl){playlist=pl;loading=true;renderPlaylist();P.bridge.loadYouTubePlaylist(pl.url);return;}
  var video=parseVideo(q);
  if(video){
   P.search.reset();
   results.replaceChildren();
   results.appendChild(P.ui.section('YouTube video'));
   results.appendChild(onlineRow(video));
   results.appendChild(U.h('p.apihelp',{text:'Play the video to resolve its audio stream in the native player.'}));
   return;
  }
  renderLocal(q);
  if(S.state.settings.onlineSearchEnabled!==false)P.search.online(q);
 }
 function resetSearch(){if(!input)return;lastQuery='';playlist=null;items=[];loading=false;complete=false;errorText='';queueActive=false;P.search.cancel();P.search.reset();renderSuggestions();}
 function renderSuggestions(){
  if(!results)return;U.clear(results);
  var saved=getSaved();
  results.appendChild(P.ui.section('Saved playlist links'));
  if(saved.length){
   saved.forEach(function(x){
    results.appendChild(U.h('div.onlinerow.savedplaylist',
     U.h('div.onlinerow__art',U.icon('i-queue','ico--sm')),
     U.h('button.grow.savedplaylist__open',{type:'button',onclick:function(){input.value=x.url;field.classList.add('has-value');searchNow(x.url);}},U.h('div.track__title',{text:x.title||'YouTube playlist'}),U.h('div.track__sub',{text:'Saved link · loads online'})),
     U.h('button.iconbtn',{type:'button','aria-label':'Remove saved playlist',onclick:function(){setSaved(getSaved().filter(function(y){return y.id!==x.id;}));renderSuggestions();P.toast.show('Playlist shortcut removed');}},U.icon('i-close','ico--sm'))));
   });
  }else results.appendChild(U.h('p.apihelp',{text:'Paste a YouTube playlist link once and save it here for quicker access next time.'}));
  var songs=P.library.songs().slice(0,8);
  results.appendChild(P.ui.section('Your music'));
  if(songs.length)songs.forEach(function(t,i){results.appendChild(P.ui.trackRow(t,{list:songs,source:'search',index:i}));});
  else results.appendChild(P.ui.empty({icon:'i-search',title:'No local songs found',text:'Scan your device library after adding music.'}));
 }
 function renderLocal(q){
  if(!results)return;U.clear(results);
  var local=P.search.local(q),rows=local.songs||[];
  results.appendChild(P.ui.section('Songs on this device'));
  if(rows.length){var wrap=U.h('div.stagger');rows.forEach(function(t,i){wrap.appendChild(P.ui.trackRow(t,{list:rows,source:'search',index:i}));});results.appendChild(wrap);}
  else results.appendChild(P.ui.empty({icon:'i-search',title:'No local matches',text:'No local song matches “'+q+'”.'+(S.state.settings.onlineSearchEnabled===false?' Enable online search to try YouTube.':'')}));
  if(S.state.settings.onlineSearchEnabled!==false)results.appendChild(onlineBlock());
 }
 function onlineBlock(){
  var wrap=U.h('div.online-results'),data=S.state.search.online||{},st=data.state,rows=data.items||[];
  wrap.appendChild(P.ui.section('YouTube results'));
  if(st==='loading'){wrap.appendChild(P.ui.loading(4));return wrap;}
  if(st==='error'||st==='offline'||st==='disabled'){wrap.appendChild(P.ui.empty({icon:'i-offline',title:st==='offline'?'You are offline':st==='disabled'?'Online search is disabled':'YouTube search failed',text:data.error||(st==='disabled'?'Enable online search in settings.':'Check your connection and try again.')}));return wrap;}
  if(st!=='results'||!rows.length){wrap.appendChild(U.h('p.apihelp',{text:'Results appear here when you search for a title or artist.'}));return wrap;}
  rows.filter(function(x){return !S.isArchivedOnline(x.provider||'youtube',x.id||x.trackId);}).slice(0,30).forEach(function(x){x.provider=x.provider||'youtube';x.source='online';x.trackId=x.trackId||x.id;wrap.appendChild(onlineRow(x));});
  return wrap;
 }
 function onlineRow(item){
  var row=U.h('div.onlinerow',{role:'button',tabindex:'0',onclick:function(e){if(e.target.closest('button'))return;P.playback.playRemote(item);},onkeydown:function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();P.playback.playRemote(item);}}});
  var art=U.h('div.onlinerow__art');
  if(item.artworkUrl){var img=U.h('img',{alt:'',loading:'lazy'});img.src=item.artworkUrl;img.onerror=function(){img.remove();};art.appendChild(img);}else art.appendChild(U.icon('i-note','ico--sm'));
  row.appendChild(art);
  row.appendChild(U.h('div.grow',U.h('div.track__title',{text:item.title||'Unknown title'}),U.h('div.track__sub',{text:(item.artist||'YouTube')+(item.album?' · '+item.album:'')}),U.h('div.rowitem__sub',{text:'YouTube'+(item.durationMs?' · '+U.fmtTime(item.durationMs/1000):'')})));
  row.appendChild(U.h('button.iconbtn',{type:'button','aria-label':'Options',onclick:function(e){e.stopPropagation();P.sheet.trackOptions(item);}},U.icon('i-more','ico--sm')));
  return row;
 }
 function savePlaylist(){
  if(!playlist)return;var saved=getSaved();
  if(saved.some(function(x){return x.id===playlist.id;})){P.toast.show('Playlist already saved');return;}
  saved.unshift({id:playlist.id,url:playlist.url,title:playlist.title||'YouTube playlist',savedAt:Date.now()});setSaved(saved);
  P.toast.show('Playlist link saved on this device');renderPlaylist();
 }
 function renderPlaylist(){
  if(!playlist||!results)return;U.clear(results);
  var panel=U.h('section.playlist-panel');
  panel.appendChild(U.h('div.playlist-panel__head',
    U.h('div.grow',U.h('div.playlist-panel__eyebrow',{text:'YOUTUBE PLAYLIST'}),U.h('h2.playlist-panel__title',{text:playlist.title||'YouTube playlist'}),U.h('p.playlist-panel__meta',{text:errorText||(loading?items.length+' tracks loaded · loading more':items.length+' tracks')})),
    U.h('button.iconbtn',{type:'button','aria-label':'Save playlist',onclick:savePlaylist},U.icon('i-plus','ico--sm'))));
  if(errorText)panel.appendChild(U.h('p.playlist-panel__error',{text:errorText}));
  panel.appendChild(U.h('div.playlist-panel__actions',
   U.h('button.actionbtn',{type:'button',disabled:!items.length,onclick:function(){startPlaylist(0,false);}},U.icon('i-play','ico--sm'),'Play playlist'),
   U.h('button.pillbtn',{type:'button',disabled:!items.length,onclick:function(){startPlaylist(0,true);}},U.icon('i-shuffle','ico--sm'),'Shuffle'),
   U.h('button.pillbtn',{type:'button',onclick:savePlaylist},U.icon('i-plus','ico--sm'),'Save link')));
  if(loading)panel.appendChild(U.h('p.playlist-panel__hint',{text:'You can start listening while the rest loads. YouTube playlists are capped at 150 tracks.'}));
  var rows=U.h('div.stagger');
  items.slice(0,150).forEach(function(item,i){
   var row=U.h('div.onlinerow.playlistrow',{role:'button',tabindex:'0',onclick:function(){startPlaylist(i,false);},onkeydown:function(e){if(e.key==='Enter'||e.key===' '){e.preventDefault();startPlaylist(i,false);}}});
   var art=U.h('div.onlinerow__art');
   if(item.artworkUrl){var im=U.h('img',{alt:'',loading:'lazy'});im.src=item.artworkUrl;im.onerror=function(){im.remove();};art.appendChild(im);}else art.appendChild(U.icon('i-note','ico--sm'));
   row.appendChild(art);row.appendChild(U.h('div.grow',U.h('div.track__title',{text:item.title||'Unknown title'}),U.h('div.track__sub',{text:item.artist||'YouTube'}),U.h('div.playlistrow__meta',{text:(i+1)+ (item.durationMs?' · '+U.fmtTime(item.durationMs/1000):'')})));
   row.appendChild(U.h('button.iconbtn',{type:'button','aria-label':'Play from this track',onclick:function(e){e.stopPropagation();startPlaylist(i,false);}},U.icon('i-play','ico--sm')));
   rows.appendChild(row);
  });
  panel.appendChild(rows);results.appendChild(panel);
 }
 function startPlaylist(index,shuffle){
  if(!items.length||!playlist)return;var all=items.slice();
  if(shuffle){for(var i=all.length-1;i>0;i--){var j=Math.floor(Math.random()*(i+1)),tmp=all[i];all[i]=all[j];all[j]=tmp;}index=0;}
  var queue=all.slice(Math.max(0,Math.min(index|0,all.length-1)));if(!queue.length)return;
  queueActive=loading;queueShuffle=!!shuffle;queueStart=byId(queue[0]);queued=Object.create(null);
  queue.forEach(function(x){queued[byId(x)]=true;});
  S.state.remoteQueue=queue.slice();S.state.remoteQueueTitle=playlist.title||'YouTube playlist';
  P.bridge.playRemoteQueue(queue);P.toast.show('Playing '+queue.length+' playlist tracks');P.playerUI.open();
 }
 function acceptPlaylist(payload){
  if(!playlist||!payload||(payload.url&&payload.url!==playlist.url))return;
  var oldCount=items.length;
  items=Array.isArray(payload.items)?payload.items.slice(0,150):[];
  playlist.title=payload.title||playlist.title;loading=!payload.complete;complete=!!payload.complete;errorText='';
  if(queueActive){
   var start=items.findIndex(function(x){return byId(x)===queueStart;});if(start<0)start=0;
   var more=items.slice(start).filter(function(x){var id=byId(x);if(!id||queued[id])return false;queued[id]=true;return true;});
   if(queueShuffle){for(var n=more.length-1;n>0;n--){var k=Math.floor(Math.random()*(n+1)),sw=more[n];more[n]=more[k];more[k]=sw;}}
   if(more.length){P.bridge.appendRemoteQueue(more);S.state.remoteQueue=(S.state.remoteQueue||[]).concat(more);P.bus.emit('queue',S.state.remoteQueue);}
   if(complete)queueActive=false;
  }
  if(P.nav.current()==='search')renderPlaylist();
 }
 var SearchUI={
  render:function(){build();P.nav.setAppbar('Search','',[]);if(playlist)renderPlaylist();else if(!lastQuery)renderSuggestions();else renderLocal(lastQuery);},
  focus:function(){build();if(input)input.focus();},
  search:function(q){build();if(input)input.value=q||'';searchNow(q);}
 };
 P.searchUI=SearchUI;
 P.bus.on('page',function(name){if(name==='search')SearchUI.render();});
 P.bus.on('playlistResults',acceptPlaylist);
 P.bus.on('searchResults',function(){if(playlist)return;if(P.nav.current()==='search'&&lastQuery)renderLocal(lastQuery);});
 P.bus.on('library',function(){if(P.nav.current()==='search'){if(playlist)renderPlaylist();else if(lastQuery)renderLocal(lastQuery);else renderSuggestions();}});
 P.bus.on('archive',function(){if(P.nav.current()==='search'&&lastQuery&&!playlist)renderLocal(lastQuery);});
 P.bus.on('settings',function(){if(P.nav.current()==='search'&&results&&!playlist){if(lastQuery)renderLocal(lastQuery);else renderSuggestions();}});
})(window);
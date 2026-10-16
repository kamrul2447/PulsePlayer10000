/* Pulse Player — library UI. Media3/Room own all durable state. */
(function (global) {
  'use strict';
  var P=global.Pulse=P||{}, U=P.util, S=P.store;
  var root=null, tabs=null, body=null, activeView='songs', sort='title', query='', filter='all', built=false;
  var views=[
    {id:'songs',label:'Songs',icon:'i-note'},
    {id:'albums',label:'Albums',icon:'i-album'},
    {id:'artists',label:'Artists',icon:'i-artist'},
    {id:'favorites',label:'Favorites',icon:'i-heart'},
    {id:'recent',label:'Recent',icon:'i-refresh'},
    {id:'played',label:'Played',icon:'i-wave'},
    {id:'most',label:'Top played',icon:'i-sparkle'},
    {id:'archived',label:'Archive',icon:'i-archive'}
  ];
  function songsForView(){
    var list;
    if(activeView==='favorites') list=P.library.favorites();
    else if(activeView==='recent') list=P.library.recentlyAdded();
    else if(activeView==='played') list=P.library.recentlyPlayed();
    else if(activeView==='most') list=P.library.mostPlayed();
    else if(activeView==='archived') list=P.library.archiveTracks();
    else list=P.library.songs(sort);
    if(query){
      var q=query.toLowerCase();
      list=list.filter(function(t){return [t.title,t.artist,t.album,t.displayName,t.path].join(' ').toLowerCase().indexOf(q)>=0;});
    }
    return list;
  }
  function setView(id){
    if(!views.some(function(v){return v.id===id;})) id='songs';
    activeView=id; S.state.library.view=id;
    views.forEach(function(v){var b=tabs.querySelector('[data-lib-view="'+v.id+'"]');if(b)b.classList.toggle('is-active',id===v.id);});
    renderBody();
  }
  function build(){
    if(built)return;
    root=document.getElementById('libraryScroll'); if(!root)return;
    tabs=document.getElementById('filterBar'); body=document.getElementById('libraryBody'); if(!tabs||!body)return;
    built=true; U.clear(tabs);
    views.forEach(function(v){
      tabs.appendChild(U.h('button.chip'+(v.id===activeView?'.is-active':''),{type:'button','data-lib-view':v.id,onclick:function(){setView(v.id);}},U.icon(v.icon,'ico--sm'),v.label));
    });
    renderBody();
  }
  function renderBody(){
    if(!body)return;
    U.clear(body);
    var songs=P.library.songs();
    if(activeView==='albums'){
      var albums=P.library.albums(sort);
      if(query) albums=albums.filter(function(a){return (a.name+' '+a.artist).toLowerCase().indexOf(query.toLowerCase())>=0;});
      body.appendChild(header(albums.length,'Albums',true));
      if(!albums.length){body.appendChild(P.ui.empty({icon:'i-album',title:'No albums found',text:'Albums appear from the tags in your local music files.'}));return;}
      var grid=U.h('div.grid.stagger');
      albums.forEach(function(a){
        grid.appendChild(P.ui.gridCard(a,function(group){activeView='album:'+group.id;renderAlbum(group);},false));
      });
      body.appendChild(grid);return;
    }
    if(activeView==='artists'){
      var artists=P.library.artists();
      if(query)artists=artists.filter(function(a){return a.name.toLowerCase().indexOf(query.toLowerCase())>=0;});
      body.appendChild(header(artists.length,'Artists',false));
      if(!artists.length){body.appendChild(P.ui.empty({icon:'i-artist',title:'No artists found',text:'Artist names are read from your music tags.'}));return;}
      var ag=U.h('div.grid.stagger');
      artists.forEach(function(a){ag.appendChild(P.ui.gridCard(a,function(group){activeView='artist:'+group.id;renderArtist(group);},true));});
      body.appendChild(ag);return;
    }
    var list=songsForView();
    if(activeView==='archived') list=P.library.archiveTracks().filter(function(t){return !query||(t.title+' '+t.artist+' '+t.album).toLowerCase().indexOf(query.toLowerCase())>=0;});
    body.appendChild(header(list.length,P.library.viewLabel(activeView)||'Songs',true));
    if(!list.length){
      var message=activeView==='archived'?'Songs you archive will appear here.':activeView==='favorites'?'Tap the heart on any song to keep it here.':'Add music to your device, then scan your library.';
      body.appendChild(P.ui.empty({icon:activeView==='favorites'?'i-heart':activeView==='archived'?'i-archive':'i-note',title:'Nothing here yet',text:message,action:activeView==='songs'?{label:'Rescan music',onClick:function(){P.bridge.scanLibrary(true);P.toast.show('Scanning your music…');}}:null}));
      return;
    }
    var actions=U.h('div', {style:{display:'flex',gap:'8px',padding:'4px 12px 10px'}},
      U.h('button.chip',{type:'button',onclick:function(){P.playback.playList(list,0,activeView);}},U.icon('i-play','ico--sm'),'Play all'),
      U.h('button.chip',{type:'button',onclick:function(){P.playback.playList(P.library.shuffled(list),0,activeView,true);P.playback.setShuffle(true);}},U.icon('i-shuffle','ico--sm'),'Shuffle'),
      U.h('button.chip',{type:'button',onclick:function(){showSort();}},U.icon('i-sort','ico--sm'),'Sort'));
    body.appendChild(actions);
    var wrap=U.h('div.stagger');
    list.slice(0,5000).forEach(function(t,i){wrap.appendChild(P.ui.trackRow(t,{list:list,source:activeView,index:i,showIndex:true}));});
    body.appendChild(wrap);
    if(list.length>5000)body.appendChild(U.h('p.apihelp',{text:'Showing the first 5,000 songs to keep scrolling responsive.'}));
  }
  function header(count,label,withSearch){
    var row=U.h('div.listhead',
      U.h('span.listhead__count',{text:U.fmtCount(count,label.toLowerCase().replace(/s$/,''))}),
      U.h('span.listhead__hint',{text:sortLabel(sort)}));
    if(withSearch){
      var input=U.h('input',{type:'search',placeholder:'Filter '+label.toLowerCase(),value:query,'aria-label':'Filter '+label.toLowerCase(),oninput:U.debounce(function(){query=input.value;renderBody();},120)});
      // Query filtering is available from the dedicated input below instead of replacing focused DOM while typing.
      row.appendChild(U.h('button.iconbtn',{type:'button','aria-label':'Search library',onclick:function(){showFilter();}},U.icon('i-search','ico--sm')));
    }
    return row;
  }
  function showFilter(){
    var input=U.h('input.apiinput',{type:'search',placeholder:'Song, artist, album or filename',value:query,autocomplete:'off'});
    var apply=U.h('button.actionbtn',{type:'button',onclick:function(){query=input.value.trim();P.sheet.close();renderBody();}},'Filter library');
    var clear=U.h('button.pillbtn',{type:'button',onclick:function(){query='';P.sheet.close();renderBody();}},'Clear filter');
    P.sheet.open({title:'Filter library',body:U.h('div.apiform',input,apply,clear)});
    setTimeout(function(){input.focus();},100);
  }
  function sortLabel(v){return ({title:'Title A–Z',artist:'Artist A–Z',album:'Album A–Z',dateAdded:'Recently added',duration:'Duration'})[v]||'Title A–Z';}
  function showSort(){
    P.sheet.open({title:'Sort music',body:[
      P.sheet.row({icon:'i-note',label:'Title A–Z',value:sort==='title'?'Selected':'',onClick:function(){sort='title';P.sheet.close();renderBody();}}),
      P.sheet.row({icon:'i-artist',label:'Artist',value:sort==='artist'?'Selected':'',onClick:function(){sort='artist';P.sheet.close();renderBody();}}),
      P.sheet.row({icon:'i-album',label:'Album',value:sort==='album'?'Selected':'',onClick:function(){sort='album';P.sheet.close();renderBody();}}),
      P.sheet.row({icon:'i-refresh',label:'Recently added',value:sort==='dateAdded'?'Selected':'',onClick:function(){sort='dateAdded';P.sheet.close();renderBody();}}),
      P.sheet.row({icon:'i-wave',label:'Duration',value:sort==='duration'?'Selected':'',onClick:function(){sort='duration';P.sheet.close();renderBody();}})
    ]});
  }
  function renderAlbum(album){
    U.clear(body);
    body.appendChild(U.h('button.pillbtn',{type:'button',onclick:function(){activeView='albums';renderBody();}},U.icon('i-chevron-left','ico--sm'),'All albums'));
    var tracks=P.library.albumTracks(album.id);
    body.appendChild(U.h('div.hero',U.h('div.hero__art',{},P.ui.groupArt(album,180)),U.h('div.grow',U.h('div.hero__title',{text:album.name}),U.h('div.hero__sub',{text:album.artist+' · '+U.fmtCount(tracks.length)}))));
    body.appendChild(U.h('button.actionbtn',{type:'button',onclick:function(){P.playback.playList(tracks,0,'album');}},U.icon('i-play','ico--sm'),'Play album'));
    tracks.forEach(function(t,i){body.appendChild(P.ui.trackRow(t,{list:tracks,source:'album',index:i,showIndex:true}));});
  }
  function renderArtist(artist){
    U.clear(body);
    body.appendChild(U.h('button.pillbtn',{type:'button',onclick:function(){activeView='artists';renderBody();}},U.icon('i-chevron-left','ico--sm'),'All artists'));
    var tracks=P.library.artistTracks(artist.id), albums=P.library.artistAlbums(artist.id);
    body.appendChild(U.h('div.hero',U.h('div.hero__art',{},P.ui.groupArt(artist,180,'',true)),U.h('div.grow',U.h('div.hero__title',{text:artist.name}),U.h('div.hero__sub',{text:U.fmtCount(tracks.length)+' · '+U.fmtCount(albums.length,'album')}))));
    body.appendChild(U.h('button.actionbtn',{type:'button',onclick:function(){P.playback.playList(tracks,0,'artist');}},U.icon('i-play','ico--sm'),'Play artist'));
    if(albums.length){body.appendChild(P.ui.section('Albums'));var grid=U.h('div.grid');albums.forEach(function(a){grid.appendChild(P.ui.gridCard(a,function(g){renderAlbum(g);},false));});body.appendChild(grid);}
    body.appendChild(P.ui.section('Songs'));
    tracks.forEach(function(t,i){body.appendChild(P.ui.trackRow(t,{list:tracks,source:'artist',index:i,showIndex:true}));});
  }
  var LibraryUI={
    render:function(){build();if(P.nav.current()==='library')P.nav.setAppbar('Library',U.fmtCount(P.library.count()));renderBody();},
    refreshRows:function(){if(P.nav.current()==='library')renderBody();},
    setView:function(id){build();if(id==='songs'||id==='albums'||id==='artists'||id==='favorites'||id==='recent'||id==='played'||id==='most'||id==='archived')setView(id);P.nav.go('library');},
    currentView:function(){return activeView;}
  };
  P.libraryUI=LibraryUI;
  P.bus.on('page',function(name){if(name==='library')LibraryUI.render();});
  P.bus.on('library',function(){if(P.nav.current()==='library')LibraryUI.render();});
  P.bus.on('archive',function(){if(P.nav.current()==='library')LibraryUI.render();});
  P.bus.on('favorites',function(){if(P.nav.current()==='library')LibraryUI.render();});
})(window);
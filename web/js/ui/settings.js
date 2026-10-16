/* Pulse Player — preferences. Android DataStore remains authoritative. */
(function(global){
 'use strict';
 var P=global.Pulse=P||{},U=P.util,S=P.store,root;
 function row(icon,title,sub,action,tail){
  return U.h('button.rowitem',{type:'button',onclick:action},
   U.h('span.rowitem__icon',{},U.icon(icon,'ico--sm')),
   U.h('span.rowitem__text',U.h('span.rowitem__title',{text:title}),U.h('span.rowitem__sub',{text:sub||''})),
   tail||U.icon('i-chevron-right','ico--sm'));
 }
 function toggleRow(icon,title,sub,key,defaultValue){
  var on=S.state.settings[key]===undefined?!!defaultValue:!!S.state.settings[key];
  var knob=U.h('span.toggle'+(on?'.is-on':''),{'aria-hidden':'true'});
  return U.h('button.rowitem',{type:'button','aria-pressed':String(on),onclick:function(){
   on=!on;knob.classList.toggle('is-on',on);this.setAttribute('aria-pressed',String(on));
   S.state.settings[key]=on;P.bridge.setSetting(key,on);
   if(key==='onlineSearchEnabled'&&P.searchUI&&P.nav.current()==='search')P.searchUI.render();
   P.toast.show(title+(on?' enabled':' disabled'));
  }},U.h('span.rowitem__icon',{},U.icon(icon,'ico--sm')),U.h('span.rowitem__text',U.h('span.rowitem__title',{text:title}),U.h('span.rowitem__sub',{text:sub})),knob);
 }
 function group(title,children){var box=U.h('section.group',U.h('div.group__label',{text:title}));children.forEach(function(x){box.appendChild(x);});return box;}
 function showAbout(){
  var content=U.h('div',{style:{padding:'0 16px 20px',fontSize:'13px',lineHeight:'1.6',color:'var(--text-2)'},
   text:'Pulse Player uses Android Media3 for playback and session controls. Music stays on your device unless you explicitly choose online playback. Saved YouTube playlist links are shortcuts only; they do not download audio.'});
  P.sheet.open({title:'About Pulse Player',body:content});
 }
 function render(){
  root=root||document.getElementById('settingsScroll');if(!root)return;
  U.clear(root);
  if(P.nav.current()==='settings')P.nav.setAppbar('Settings','',[]);
  var logo=U.h('svg',{viewBox:'0 0 120 120',html:'<defs><linearGradient id="pg" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#8B5CF6"/><stop offset="1" stop-color="#4C1D95"/></linearGradient></defs><rect x="4" y="4" width="112" height="112" rx="32" fill="url(#pg)"/><g fill="#fff"><rect x="28" y="52" width="8" height="16" rx="4"/><rect x="42" y="43" width="8" height="34" rx="4"/><rect x="56" y="32" width="8" height="56" rx="4"/><rect x="70" y="43" width="8" height="34" rx="4"/><rect x="84" y="52" width="8" height="16" rx="4"/></g>'});
  root.appendChild(U.h('div.about',U.h('div.about__mark',{},logo),U.h('div.about__name',{text:'Pulse Player'}),U.h('div.about__version',{text:S.state.appVersion?'Version '+S.state.appVersion:'Your music. Your device.'}),U.h('div.about__tag',{text:'Offline-first music · Powered by Android Media3'})));
  root.appendChild(group('Appearance',[
   row('i-sparkle','Theme','Current: '+P.theme.label(),function(){var next=P.theme.cycle();S.state.settings.theme=next;P.bridge.setSetting('theme',next);render();P.toast.show(next.charAt(0).toUpperCase()+next.slice(1)+' theme');},U.h('span.rowitem__value',{text:P.theme.label()})),
   toggleRow('i-wave','Reduce motion','Reduce transitions and animation.','reduceMotion',false)
  ]));
  root.appendChild(group('Library',[
   toggleRow('i-refresh','Scan on startup','Check for new local music when the app opens.','scanOnStart',true),
   toggleRow('i-note','Hide short clips','Prefer songs over notification sounds and very short clips.','hideShortClips',true),
   toggleRow('i-search','Search local music','Include your local collection in searches.','localSearchEnabled',true),
   row('i-refresh','Rescan music library','Refresh music files indexed by Android',function(){P.bridge.scanLibrary(true);P.toast.show('Scanning your music…');}),
   row('i-trash','Clear artwork cache','Remove cached artwork thumbnails; they regenerate as needed.',function(){P.bridge.clearArtCache();P.toast.show('Artwork cache cleared');})
  ]));
  root.appendChild(group('Online music',[
   toggleRow('i-search','YouTube search','Enable or disable online search. Local music always works.','onlineSearchEnabled',true),
   row('i-wave','YouTube connection','Uses the built-in NewPipe Extractor; no API key required.',function(){P.sheet.open({title:'YouTube connection',body:[
    P.sheet.row({icon:'i-refresh',label:'Search a test query',onClick:function(){P.sheet.close();P.nav.go('search');P.searchUI.search('Daft Punk');}}),
    P.sheet.row({icon:'i-pause',label:'Disable online search',onClick:function(){P.bridge.setSetting('onlineSearchEnabled',false);S.state.settings.onlineSearchEnabled=false;P.sheet.close();render();}})
   ]});},U.h('span.statuspill'+(S.state.settings.onlineSearchEnabled===false?'.is-off':'.is-on'),{text:S.state.settings.onlineSearchEnabled===false?'Off':'On'})),
   row('i-sparkle','SnapTube App Space','Open the installed SnapTube application.',function(){P.bridge.openAppSpace();})
  ]));
  root.appendChild(group('Playback',[
   toggleRow('i-volume-high','Keep screen awake','Prevent display sleep during playback.','keepScreenAwake',false),
   toggleRow('i-refresh','Resume last session','Restore the last listening position when supported.','resumeOnOpen',true),
   row('i-timer','Sleep timer','Stop playback after a set interval.',openSleep),
   row('i-queue','Up next queue','View or manage queued songs.',function(){P.queue.open();})
  ]));
  root.appendChild(group('Privacy and permissions',[
   row('i-lock','Music permissions','Manage access to local audio files in Android settings.',function(){P.bridge.openAppSettings();}),
   row('i-note','About Pulse Player','Playback uses native Android media controls and keeps local music on-device.',showAbout)
  ]));
 }
 function openSleep(){
  var choices=[15,30,45,60,90,120],box=U.h('div');
  box.appendChild(U.h('p.apihelp',{text:'Playback will stop when the timer reaches zero, including in the background.'}));
  var chips=U.h('div.timergrid');
  choices.forEach(function(min){chips.appendChild(U.h('button.timerchip',{type:'button',onclick:function(){P.sleep.start(min*60000);P.sheet.close();P.toast.show('Sleep timer set for '+min+' min');render();}},min<60?min+' min':(min/60)+' hr'));});
  box.appendChild(chips);
  var custom=U.h('input.sleep-custom__input',{type:'number',min:'1',max:'720',value:'20','aria-label':'Custom timer minutes'});
  var start=U.h('button.actionbtn.sleep-custom__button',{type:'button',onclick:function(){var n=Number(custom.value);if(!isFinite(n)||n<1||n>720){P.toast.show('Enter 1–720 minutes');return;}P.sleep.start(n*60000);P.sheet.close();P.toast.show('Sleep timer set');}},'Start');
  box.appendChild(U.h('div.sleep-custom',U.h('div.sleep-custom__copy',U.h('div.sleep-custom__title',{text:'Custom timer'}),U.h('div.sleep-custom__sub',{text:'1 to 720 minutes'})),U.h('div.sleep-custom__controls',custom,start)));
  if(S.state.sleep.active)box.appendChild(P.sheet.row({icon:'i-close',label:'Cancel active timer',onClick:function(){P.sleep.cancel();P.sheet.close();render();}}));
  P.sheet.open({title:'Sleep timer',body:box});
 }
 P.settingsUI={render:render,mount:render};
 P.bus.on('page',function(n){if(n==='settings')render();});
 P.bus.on('settings',function(){if(P.nav.current()==='settings')render();});
})(window);
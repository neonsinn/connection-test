(() => {
"use strict";
const $ = id => document.getElementById(id);
const cfg = window.DRIVE_LIBRARY_CONFIG || {};
const scope = "https://www.googleapis.com/auth/drive.readonly";
let token = null, tokenClient = null, videos = [], pageToken = null, selectedFolder = null, folderTrail = [{id:"root",name:"My Drive"}], currentBrowse = "root", loading = false;
const setup = $("setup"), library = $("library");
const saved = (() => { try { return JSON.parse(localStorage.getItem("driveLibraryFolder") || "null"); } catch { return null; } })();
selectedFolder = saved && typeof saved.id === "string" ? saved : null;
const msg = (s) => { $("message").textContent = s; };
const folderMsg = (s) => { $("folder-message").textContent = s; };
function configured(){ return !!cfg.clientId && !cfg.clientId.includes("PASTE_"); }
$("config-warning").hidden = configured();
$("sign-in").disabled = !configured();
function setConnected(connected){
  setup.hidden = connected; library.hidden = !connected;
  $("connection").textContent = connected ? "Google Drive connected" : "Not connected";
}
function initTokenClient(){
  if(!window.google?.accounts?.oauth2){throw Error("Google sign-in could not load. Check your internet connection or content blocker.");}
  tokenClient = google.accounts.oauth2.initTokenClient({
    client_id:cfg.clientId, scope, callback:(response)=>{
      if(response.error){ msg("Sign-in failed: "+response.error); return; }
      token = response.access_token; setConnected(true);
      if(selectedFolder) loadVideos(true); else openFolderPicker();
    }
  });
}
$("sign-in").onclick = () => {
  try { if(!tokenClient) initTokenClient(); tokenClient.requestAccessToken({prompt:"consent"}); }
  catch(e){ alert(e.message); }
};
async function api(path, params={}){
  if(!token) throw Error("Not connected. Please sign in again.");
  const url = new URL("https://www.googleapis.com/drive/v3/"+path);
  for(const [k,v] of Object.entries(params)) if(v!==undefined&&v!==null) url.searchParams.set(k,String(v));
  const response = await fetch(url,{headers:{Authorization:"Bearer "+token}});
  if(response.status===401){token=null;setConnected(false);throw Error("Google session expired. Connect again.");}
  if(!response.ok){let detail="";try{detail=(await response.json()).error?.message||"";}catch{}throw Error(detail||"Google Drive request failed ("+response.status+")");}
  return response.json();
}
const escapeQuery = value => value.replace(/\\/g,"\\\\").replace(/'/g,"\\'");
async function loadVideos(reset){
  if(!selectedFolder||loading) return;
  loading=true; $("refresh").disabled=true; $("load-more").disabled=true;
  if(reset){videos=[];pageToken=null;$("videos").replaceChildren();}
  $("folder-name").textContent=selectedFolder.name; msg("Loading videos…");
  try {
    const data=await api("files",{
      q:"'"+escapeQuery(selectedFolder.id)+"' in parents and trashed = false and (mimeType = 'video/mp4' or name contains '.mp4' or name contains '.MP4')",
      fields:"nextPageToken,files(id,name,mimeType,size,modifiedTime,webViewLink)",
      orderBy:"name",pageSize:100,pageToken
    });
    videos.push(...(data.files||[])); pageToken=data.nextPageToken||null;
    renderVideos(); msg(videos.length ? "" : "No MP4 videos found directly in this folder.");
  } catch(e){msg(e.message);} finally {loading=false;$("refresh").disabled=false;$("load-more").disabled=false;$("load-more").hidden=!pageToken;}
}
function renderVideos(){
  const term=$("search").value.trim().toLowerCase();
  const visible=videos.filter(f=>f.name.toLowerCase().includes(term));
  $("count").textContent=visible.length+" shown"+(pageToken?" · more available":"");
  const frag=document.createDocumentFragment();
  for(const f of visible){
    const btn=document.createElement("button");btn.className="video-card";
    const thumb=document.createElement("div");thumb.className="thumb";thumb.textContent="▶";
    const info=document.createElement("div");info.className="video-info";
    const name=document.createElement("div");name.className="video-name";name.textContent=f.name;
    const meta=document.createElement("div");meta.className="video-meta";
    meta.textContent=f.size? (Number(f.size)/1048576).toFixed(0)+" MB":"MP4 video";
    info.append(name,meta);btn.append(thumb,info);btn.onclick=()=>openPlayer(f);frag.append(btn);
  }
  $("videos").replaceChildren(frag);
}
$("search").oninput=renderVideos;
$("refresh").onclick=()=>loadVideos(true);
$("load-more").onclick=()=>loadVideos(false);
$("sign-out").onclick=()=>{
  const old=token;token=null;if(old&&window.google?.accounts?.oauth2) google.accounts.oauth2.revoke(old,()=>{});
  setConnected(false);$("player-frame").src="about:blank";$("player-dialog").close();
};
function openPlayer(file){
  $("player-title").textContent=file.name;
  $("player-frame").src="https://drive.google.com/file/d/"+encodeURIComponent(file.id)+"/preview";
  $("open-drive").href="https://drive.google.com/file/d/"+encodeURIComponent(file.id)+"/view";
  $("player-dialog").showModal();
}
$("close-player").onclick=()=>{$("player-frame").src="about:blank";$("player-dialog").close();};
$("player-dialog").addEventListener("close",()=>{$("player-frame").src="about:blank";});
function openFolderPicker(){folderTrail=[{id:"root",name:"My Drive"}];currentBrowse="root";$("folder-dialog").showModal();browseFolders("root",false);}
$("choose-folder").onclick=openFolderPicker;
$("close-folders").onclick=()=>$("folder-dialog").close();
async function browseFolders(id,forward,name){
  if(forward) folderTrail.push({id,name});
  currentBrowse=id; $("folder-path").textContent=folderTrail.map(f=>f.name).join(" / ");
  folderMsg("Loading folders…");$("folder-list").replaceChildren();
  try {
    const data=await api("files",{q:"'"+escapeQuery(id)+"' in parents and trashed = false and mimeType = 'application/vnd.google-apps.folder'",fields:"nextPageToken,files(id,name)",pageSize:1000,orderBy:"name"});
    const frag=document.createDocumentFragment();
    if(folderTrail.length>1){
      const back=document.createElement("button");back.className="secondary";back.textContent="← Back";
      back.onclick=()=>{folderTrail.pop();browseFolders(folderTrail[folderTrail.length-1].id,false);};frag.append(back);
    }
    for(const f of data.files||[]){
      const row=document.createElement("div");row.className="folder-row";
      const btn=document.createElement("button");btn.textContent="📁 "+f.name;
      btn.onclick=()=>browseFolders(f.id,true,f.name);row.append(btn);frag.append(row);
    }
    $("folder-list").replaceChildren(frag);
    folderMsg(data.nextPageToken?"Showing first 1,000 folders. Narrow your search by opening a folder.":(data.files?.length?"":"No subfolders here."));
  } catch(e){folderMsg(e.message);}
}
$("use-folder").onclick=()=>{
  selectedFolder={id:currentBrowse,name:folderTrail.map(f=>f.name).join(" / ")};
  localStorage.setItem("driveLibraryFolder",JSON.stringify(selectedFolder));
  $("folder-dialog").close();$("search").value="";loadVideos(true);
};
})();

package com.neonsinn.drivelibrary;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import android.text.*;
import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.auth.GoogleAuthUtil;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int SIGN_IN = 100;
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails = Executors.newFixedThreadPool(3);
    private final android.util.LruCache<String,Bitmap> thumbnailCache = new android.util.LruCache<>(40);
    private GoogleSignInAccount account;
    private String token, folderId, folderName, nextPage;
    private String browsingId, browsingName;
    private final ArrayList<String[]> folderTrail=new ArrayList<>();
    private final ArrayList<String[]> subfolders=new ArrayList<>();
    private final ArrayList<Video> videos = new ArrayList<>();
    private LinearLayout root;
    private GridLayout cards;
    private LinearLayout folderSection;
    private TextView status, heading;
    private Spinner sortBy;
    private static final String[] SORT_OPTIONS={"Name A–Z","Name Z–A","Newest first","Oldest first","Longest first","Shortest first"};
    private Button more;
    private boolean loading = false;

    static class Video {
        String id, name, size, thumbnail, created;
        long durationMs;
        Video(String i,String n,String s,String t,String c,long d){id=i;name=n;size=s;thumbnail=t;created=c;durationMs=d;}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(16,19,27));
        getWindow().setNavigationBarColor(Color.rgb(16,19,27));
        getWindow().getDecorView().setSystemUiVisibility(0);
        folderId=getPreferences(0).getString("folderId",null);
        folderName=getPreferences(0).getString("folderName","Choose a folder");
        browsingId=folderId;browsingName=folderName;
        account=GoogleSignIn.getLastSignedInAccount(this);
        buildUI();
        if(account!=null) authorize(); else setStatus("Connect your Google account to begin.");
    }

    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n);}
    private TextView text(String value,int size){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setPadding(0,dp(8),0,dp(8));return t;
    }
    private Button button(String value,View.OnClickListener listener){
        Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setOnClickListener(listener);return b;
    }
    private void buildUI(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(16,19,27));
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(18),dp(18),dp(24));scroll.addView(root);setContentView(scroll);
        root.addView(text("PRIVATE COLLECTION",12));
        root.addView(text("Video Library",30));
        status=text("",14);root.addView(status);
        root.addView(button("Connect Google Drive",v->signIn()));
        root.addView(button("Choose Drive folder",v->chooseFolder("root","My Drive",new ArrayList<>())));
        root.addView(button("Refresh videos",v->loadVideos(true)));
        heading=text(browsingName,20);root.addView(heading);
        folderSection=new LinearLayout(this);folderSection.setOrientation(LinearLayout.VERTICAL);root.addView(folderSection);
        sortBy=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,SORT_OPTIONS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sortBy.setAdapter(adapter);
        sortBy.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffdce4f5));
        sortBy.setOnTouchListener((v,event)->{if(event.getAction()==android.view.MotionEvent.ACTION_DOWN){sortBy.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xff293148));}return false;});
        sortBy.setSelection(getPreferences(0).getInt("sortMode",0));
        sortBy.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                getPreferences(0).edit().putInt("sortMode",position).apply();
                if(view instanceof TextView)((TextView)view).setTextColor(Color.WHITE);
                render();
            }
        });
        root.addView(sortBy);
        cards=new GridLayout(this);cards.setColumnCount(2);root.addView(cards);
        more=button("Load more",v->loadVideos(false));root.addView(more);more.setVisibility(View.GONE);
    }
    private void setStatus(String s){status.setText(s);}
    private void signIn(){
        GoogleSignInOptions opts=new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestScopes(new Scope(DRIVE_SCOPE)).build();
        startActivityForResult(GoogleSignIn.getClient(this,opts).getSignInIntent(),SIGN_IN);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==SIGN_IN){
            try {
                account=GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
                authorize();
            } catch(Exception e){setStatus("Google sign-in failed: "+e.getMessage());}
        }
    }
    private void authorize(){
        if(account==null){setStatus("Connect your Google account first.");return;}
        setStatus("Connecting to Google Drive…");
        io.execute(()->{
            try {
                String newToken=GoogleAuthUtil.getToken(getApplicationContext(),account.getAccount(), "oauth2:"+DRIVE_SCOPE);
                token=newToken;
                runOnUiThread(()->{
                    setStatus("Connected: "+account.getEmail());
                    if(folderId!=null)loadVideos(true);
                });
            }catch(Exception e){runOnUiThread(()->setStatus("Authorization failed: "+e.getMessage()+" — try Connect Google Drive again."));}
        });
    }
    private JSONObject drive(String endpoint,Map<String,String> args) throws Exception{
        if(token==null)throw new IOException("Connect Google Drive first.");
        StringBuilder url=new StringBuilder("https://www.googleapis.com/drive/v3/"+endpoint+"?");
        for(Map.Entry<String,String> p:args.entrySet())url.append(URLEncoder.encode(p.getKey(),"UTF-8")).append("=").append(URLEncoder.encode(p.getValue(),"UTF-8")).append("&");
        HttpURLConnection c=(HttpURLConnection)new URL(url.toString()).openConnection();
        c.setRequestProperty("Authorization","Bearer "+token);c.setConnectTimeout(15000);c.setReadTimeout(25000);
        int code=c.getResponseCode();InputStream stream=code<400?c.getInputStream():c.getErrorStream();
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
        if(stream!=null){while((n=stream.read(buf))!=-1)out.write(buf,0,n);stream.close();}
        c.disconnect();
        String body=out.toString("UTF-8");
        if(code>=400)throw new IOException("Drive error "+code+": "+body.substring(0,Math.min(180,body.length())));
        return new JSONObject(body);
    }
    private Map<String,String> params(String... items){
        Map<String,String> m=new LinkedHashMap<>();for(int i=0;i<items.length;i+=2)m.put(items[i],items[i+1]);return m;
    }
    private String quoted(String value){return value.replace("\\","\\\\").replace("'","\\'");}
    private void chooseFolder(String id,String name,ArrayList<String[]> ancestors){
        if(token==null){setStatus("Connect Google Drive first.");return;}
        setStatus("Loading folders in "+name+"…");
        io.execute(()->{
            try{
                JSONObject result=drive("files",params("q","'"+quoted(id)+"' in parents and trashed = false and mimeType = 'application/vnd.google-apps.folder'","fields","nextPageToken,files(id,name)","pageSize","1000","orderBy","name"));
                JSONArray arr=result.getJSONArray("files");
                ArrayList<String> names=new ArrayList<>();ArrayList<String> ids=new ArrayList<>();
                if(!ancestors.isEmpty()){names.add("← Back");ids.add("__back");}
                for(int i=0;i<arr.length();i++){JSONObject f=arr.getJSONObject(i);names.add("📁 "+f.getString("name"));ids.add(f.getString("id"));}
                runOnUiThread(()->{
                    setStatus("Choose a folder, then tap Use this folder.");
                    AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(name).setPositiveButton("Use this folder",(d,w)->{
                        folderId=id;folderName=name;folderTrail.clear();browsingId=id;browsingName=name;
                        getPreferences(0).edit().putString("folderId",id).putString("folderName",name).apply();heading.setText(name);loadVideos(true);
                    }).setNegativeButton("Cancel",null);
                    builder.setItems(names.toArray(new String[0]),(d,index)->{
                        if(ids.get(index).equals("__back")){
                            String[] parent=ancestors.get(ancestors.size()-1);
                            ArrayList<String[]> prev=new ArrayList<>(ancestors);prev.remove(prev.size()-1);
                            chooseFolder(parent[0],parent[1],prev);
                        }else{
                            ArrayList<String[]> next=new ArrayList<>(ancestors);next.add(new String[]{id,name});
                            chooseFolder(ids.get(index),names.get(index).substring(3),next);
                        }
                    }).show();
                });
            }catch(Exception e){runOnUiThread(()->setStatus("Folder error: "+e.getMessage()));}
        });
    }
    private void loadVideos(boolean reset){
        if(token==null){setStatus("Connect Google Drive first.");return;}
        if(browsingId==null){setStatus("Choose a Drive folder first.");return;}
        if(loading)return;
        loading=true;if(reset){videos.clear();subfolders.clear();nextPage=null;render();renderFolders();}
        setStatus("Loading videos…");more.setEnabled(false);
        String page=nextPage;
        io.execute(()->{
            try{
                Map<String,String> p=params("q","'"+quoted(browsingId)+"' in parents and trashed = false and (mimeType = 'application/vnd.google-apps.folder' or mimeType = 'video/mp4' or name contains '.mp4' or name contains '.MP4')","fields","nextPageToken,files(id,name,size,mimeType,thumbnailLink,createdTime,videoMediaMetadata(durationMillis))","pageSize","100","orderBy","name");
                if(page!=null)p.put("pageToken",page);
                JSONObject response=drive("files",p);
                JSONArray items=response.getJSONArray("files");ArrayList<Video> found=new ArrayList<>();
                ArrayList<String[]> foundFolders=new ArrayList<>();
                for(int i=0;i<items.length();i++){
                    JSONObject f=items.getJSONObject(i);
                    if("application/vnd.google-apps.folder".equals(f.optString("mimeType"))){
                        foundFolders.add(new String[]{f.getString("id"),f.getString("name")});
                    }else{
                        found.add(new Video(f.getString("id"),f.getString("name"),f.optString("size",""),f.optString("thumbnailLink",""),f.optString("createdTime",""),parseDuration(f.optJSONObject("videoMediaMetadata"))));
                    }
                }
                String next=response.optString("nextPageToken",null);
                runOnUiThread(()->{
                    videos.addAll(found);subfolders.addAll(foundFolders);nextPage=next;loading=false;more.setEnabled(true);more.setVisibility(nextPage==null?View.GONE:View.VISIBLE);
                    setStatus(videos.size()+" videos loaded · "+subfolders.size()+" folders");renderFolders();render();
                });
            }catch(Exception e){runOnUiThread(()->{loading=false;more.setEnabled(true);setStatus("Video list error: "+e.getMessage());});}
        });
    }
    private void openSubfolder(String id,String name){
        if(loading)return;
        folderTrail.add(new String[]{browsingId,browsingName});
        browsingId=id;browsingName=name;heading.setText(name);
        loadVideos(true);
    }
    private void goBackFolder(){
        if(loading || folderTrail.isEmpty())return;
        String[] parent=folderTrail.remove(folderTrail.size()-1);
        browsingId=parent[0];browsingName=parent[1];heading.setText(browsingName);
        loadVideos(true);
    }
    private void renderFolders(){
        if(folderSection==null)return;
        folderSection.removeAllViews();
        if(!folderTrail.isEmpty()){
            Button back=button("← Back to parent folder",v->goBackFolder());
            folderSection.addView(back);
        }
        ArrayList<String[]> sorted=new ArrayList<>(subfolders);
        sorted.sort((a,b)->a[1].compareToIgnoreCase(b[1]));
        for(String[] folder:sorted){
            Button entry=button("📁  "+folder[1],v->openSubfolder(folder[0],folder[1]));
            entry.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            folderSection.addView(entry);
        }
    }
    @Override public void onBackPressed(){
        if(!folderTrail.isEmpty()){goBackFolder();return;}
        super.onBackPressed();
    }
    private long parseDuration(JSONObject metadata){
        if(metadata==null)return -1;
        try{return Long.parseLong(metadata.optString("durationMillis","-1"));}
        catch(NumberFormatException e){return -1;}
    }
    private String durationText(long millis){
        if(millis<0)return "Duration unavailable";
        long seconds=millis/1000;
        if(seconds>=3600)return String.format(Locale.US,"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);
        return String.format(Locale.US,"%d:%02d",seconds/60,seconds%60);
    }
    private void play(Video video){
        Intent intent=new Intent(this,PlayerActivity.class);
        intent.putExtra("id",video.id);intent.putExtra("name",video.name);intent.putExtra("token",token);
        startActivity(intent);
    }
    private void showThumbnail(Video video,ImageView image){
        if(video.thumbnail==null || video.thumbnail.isEmpty())return;
        Bitmap cached=thumbnailCache.get(video.id);
        if(cached!=null){image.setImageBitmap(cached);return;}
        thumbnails.execute(()->{
            try{
                HttpURLConnection conn=(HttpURLConnection)new URL(video.thumbnail).openConnection();
                conn.setConnectTimeout(10000);conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization","Bearer "+token);
                try(InputStream input=conn.getInputStream()){
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                    Bitmap bitmap=BitmapFactory.decodeStream(input,null,options);
                    if(bitmap!=null){thumbnailCache.put(video.id,bitmap);
                        runOnUiThread(()->{if(video.id.equals(image.getTag()))image.setImageBitmap(bitmap);});}
                }finally{conn.disconnect();}
            }catch(Exception ignored){} // Keep the play icon when Drive has no preview.
        });
    }
    private void render(){
        if(cards==null)return;
        cards.removeAllViews();
        int width=(getResources().getDisplayMetrics().widthPixels-dp(48))/2;
        ArrayList<Video> sorted=new ArrayList<>(videos);
        int mode=sortBy==null?0:sortBy.getSelectedItemPosition();
        Comparator<Video> byName=(a,b)->a.name.compareToIgnoreCase(b.name);
        if(mode==1)sorted.sort(byName.reversed());
        else if(mode==2)sorted.sort((a,b)->b.created.compareTo(a.created));
        else if(mode==3)sorted.sort((a,b)->a.created.compareTo(b.created));
        else if(mode==4)sorted.sort((a,b)->Long.compare(b.durationMs,a.durationMs));
        else if(mode==5)sorted.sort((a,b)->Long.compare(a.durationMs<0?Long.MAX_VALUE:a.durationMs,b.durationMs<0?Long.MAX_VALUE:b.durationMs));
        else sorted.sort(byName);
        int count=0;
        for(Video video:sorted){
            count++;
            LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(4),dp(5),dp(4),dp(14));
            ImageView image=new ImageView(this);image.setTag(video.id);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundColor(0xff293148);
            image.setImageResource(android.R.drawable.ic_media_play);
            tile.addView(image,new LinearLayout.LayoutParams(-1,dp(126)));
            TextView title=text(video.name,13);title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(title,new LinearLayout.LayoutParams(-1,-2));
            TextView duration=text(durationText(video.durationMs),12);
            duration.setTextColor(0xffb6c3d9);
            duration.setPadding(0,dp(4),0,dp(5));
            tile.addView(duration,new LinearLayout.LayoutParams(-1,-2));
            tile.setOnClickListener(v->play(video));
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams();
            lp.width=width;lp.height=-2;
            lp.setMargins(0,dp(3),0,dp(3));cards.addView(tile,lp);
            showThumbnail(video,image);
        }
        if(count==0){TextView empty=text("No videos in this folder.",14);
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=-1;cards.addView(empty,lp);}
    }
    private void disconnect(){
        token=null;account=null;videos.clear();nextPage=null;render();
        GoogleSignIn.getClient(this,new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestScopes(new Scope(DRIVE_SCOPE)).build()).signOut();
        setStatus("Disconnected. Sign in to continue.");
    }
    @Override protected void onDestroy(){io.shutdownNow();thumbnails.shutdownNow();super.onDestroy();}
}

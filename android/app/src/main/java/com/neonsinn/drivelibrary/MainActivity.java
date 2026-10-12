package com.neonsinn.drivelibrary;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
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
    private GoogleSignInAccount account;
    private String token, folderId, folderName, nextPage;
    private final ArrayList<Video> videos = new ArrayList<>();
    private LinearLayout root, cards;
    private TextView status, heading;
    private EditText search;
    private Button more;
    private boolean loading = false;

    static class Video {
        String id, name, size;
        Video(String i,String n,String s){id=i;name=n;size=s;}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(16,19,27));
        getWindow().setNavigationBarColor(Color.rgb(16,19,27));
        getWindow().getDecorView().setSystemUiVisibility(0);
        folderId=getPreferences(0).getString("folderId",null);
        folderName=getPreferences(0).getString("folderName","Choose a folder");
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
        root.addView(button("Disconnect account",v->disconnect()));
        heading=text(folderName,20);root.addView(heading);
        search=new EditText(this);search.setSingleLine(true);search.setTextColor(Color.WHITE);search.setHintTextColor(0xffabb1c2);search.setHint("Search video filenames");root.addView(search);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){render();}public void afterTextChanged(Editable e){}});
        cards=new LinearLayout(this);cards.setOrientation(LinearLayout.VERTICAL);root.addView(cards);
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
                        folderId=id;folderName=name;getPreferences(0).edit().putString("folderId",id).putString("folderName",name).apply();heading.setText(name);loadVideos(true);
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
        if(folderId==null){setStatus("Choose a Drive folder first.");return;}
        if(loading)return;
        loading=true;if(reset){videos.clear();nextPage=null;render();}
        setStatus("Loading videos…");more.setEnabled(false);
        String page=nextPage;
        io.execute(()->{
            try{
                Map<String,String> p=params("q","'"+quoted(folderId)+"' in parents and trashed = false and (mimeType = 'video/mp4' or name contains '.mp4' or name contains '.MP4')","fields","nextPageToken,files(id,name,size,mimeType)","pageSize","100","orderBy","name");
                if(page!=null)p.put("pageToken",page);
                JSONObject response=drive("files",p);
                JSONArray items=response.getJSONArray("files");ArrayList<Video> found=new ArrayList<>();
                for(int i=0;i<items.length();i++){JSONObject f=items.getJSONObject(i);found.add(new Video(f.getString("id"),f.getString("name"),f.optString("size","")));}
                String next=response.optString("nextPageToken",null);
                runOnUiThread(()->{
                    videos.addAll(found);nextPage=next;loading=false;more.setEnabled(true);more.setVisibility(nextPage==null?View.GONE:View.VISIBLE);
                    setStatus(videos.size()+" videos loaded");render();
                });
            }catch(Exception e){runOnUiThread(()->{loading=false;more.setEnabled(true);setStatus("Video list error: "+e.getMessage());});}
        });
    }
    private void render(){
        if(cards==null)return;
        cards.removeAllViews();String query=search.getText().toString().toLowerCase(Locale.ROOT);
        int count=0;
        for(Video video:videos){
            if(!video.name.toLowerCase(Locale.ROOT).contains(query))continue;
            count++;
            Button row=button("▶  "+video.name,v->{
                Intent intent=new Intent(this,PlayerActivity.class);
                intent.putExtra("id",video.id);intent.putExtra("name",video.name);intent.putExtra("token",token);
                startActivity(intent);
            });
            row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            row.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff293148));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(66));lp.setMargins(0,dp(5),0,dp(5));cards.addView(row,lp);
        }
        if(count==0)cards.addView(text(videos.isEmpty()?"No videos loaded yet.":"No matching filenames.",14));
    }
    private void disconnect(){
        token=null;account=null;videos.clear();nextPage=null;render();
        GoogleSignIn.getClient(this,new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestScopes(new Scope(DRIVE_SCOPE)).build()).signOut();
        setStatus("Disconnected. Sign in to continue.");
    }
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

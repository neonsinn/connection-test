package com.neonsinn.drivelibrary;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import android.view.*;
import android.graphics.Color;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import java.util.*;

public class PlayerActivity extends Activity {
    private ExoPlayer player;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(Color.BLACK);
        TextView title=new TextView(this);title.setText(getIntent().getStringExtra("name"));title.setTextColor(Color.WHITE);title.setTextSize(18);title.setPadding(20,24,20,24);layout.addView(title);
        PlayerView view=new PlayerView(this);layout.addView(view,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(layout);
        String id=getIntent().getStringExtra("id"),token=getIntent().getStringExtra("token");
        if(id==null||token==null){Toast.makeText(this,"Missing video authorization",Toast.LENGTH_LONG).show();finish();return;}
        Map<String,String> headers=new HashMap<>();headers.put("Authorization","Bearer "+token);
        DataSource.Factory factory=new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers).setAllowCrossProtocolRedirects(true);
        player=new ExoPlayer.Builder(this).build();view.setPlayer(player);
        Uri url=Uri.parse("https://www.googleapis.com/drive/v3/files/"+Uri.encode(id)+"?alt=media");
        player.setMediaSource(new ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url)));
        player.prepare();player.play();
    }
    @Override protected void onStop(){if(player!=null){player.release();player=null;}super.onStop();}
}

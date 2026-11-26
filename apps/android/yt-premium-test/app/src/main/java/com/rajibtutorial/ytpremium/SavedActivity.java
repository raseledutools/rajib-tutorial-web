package com.rajibtutorial.ytpremium;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Saved videos: tap to play offline, long-press to delete. */
public class SavedActivity extends Activity {

    private final List<File> files = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private VideoView video;
    private TextView empty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("📁 Saved videos");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setPadding(dp(16), dp(16), dp(16), dp(10));
        root.addView(title);

        video = new VideoView(this);
        video.setVisibility(View.GONE);
        MediaController mc = new MediaController(this);
        mc.setAnchorView(video);
        video.setMediaController(mc);
        root.addView(video, new LinearLayout.LayoutParams(-1, dp(220)));

        empty = new TextView(this);
        empty.setText("Nothing saved yet.\nOpen a video and tap ⬇ Save.");
        empty.setTextColor(0xFFAAAAAA);
        empty.setPadding(dp(16), dp(24), dp(16), dp(24));
        root.addView(empty);

        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>()) {
            @Override public View getView(int pos, View cv, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTextColor(Color.WHITE);
                t.setTextSize(15);
                return t;
            }
        };
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        list.setOnItemClickListener((p, v, pos, id) -> play(files.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            final File f = files.get(pos);
            new AlertDialog.Builder(this)
                .setTitle("Delete this video?")
                .setMessage(label(f))
                .setPositiveButton("Delete", (d, w) -> {
                    if (video.isPlaying()) video.stopPlayback();
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
            return true;
        });
        reload();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private static String label(File f) {
        String n = f.getName();
        int i = n.lastIndexOf(" [");
        String name = i > 0 ? n.substring(0, i) : n;
        return String.format(Locale.US, "%s  (%.1f MB)", name, f.length() / 1048576.0);
    }

    private void reload() {
        files.clear();
        File[] fs = YtSaver.dir(this).listFiles();
        if (fs != null) {
            for (File f : fs) if (f.getName().endsWith(".mp4") && !f.getName().startsWith(".tmp_") && f.length() > 0) files.add(f);
        }
        Collections.sort(files, (a, c) -> Long.compare(c.lastModified(), a.lastModified()));
        adapter.clear();
        for (File f : files) adapter.add(label(f));
        adapter.notifyDataSetChanged();
        empty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        if (files.isEmpty()) video.setVisibility(View.GONE);
    }

    private void play(File f) {
        video.setVisibility(View.VISIBLE);
        video.setVideoPath(f.getAbsolutePath());
        video.start();
    }

    @Override protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) video.pause();
    }
}

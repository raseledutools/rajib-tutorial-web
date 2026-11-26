package com.rajibtutorial;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Study Tools — fully native (no WebView). PDF / photo / QR utilities that run 100% offline.
 * Design follows the WPS Office look: light background, white cards, tinted icon tiles, blue pill buttons.
 */
public class StudyToolsActivity extends AppCompatActivity {

    static final String SAVE_FOLDER = "StudyTools";
    private static final int REQ_CAMERA = 710;
    private static final int REQ_STORAGE = 711;

    // ───────── small shared types ─────────
    interface Cb<T> { void on(T value); }
    interface Updater { void msg(String m); }
    interface Task { Runnable run(Updater u) throws Exception; }
    interface Factory { ToolPage make(StudyToolsActivity a); }

    static final class Picked {
        Uri uri; String name; long size; File file;
    }

    static final class Saved {
        String name, uri, mime; long size;
    }

    private static final class ToolDef {
        final String emoji, label, section; final int color; final Factory f;
        ToolDef(String emoji, int color, String label, String section, Factory f) {
            this.emoji = emoji; this.color = color; this.label = label; this.section = section; this.f = f;
        }
    }

    // ───────── state ─────────
    final ExecutorService io = Executors.newSingleThreadExecutor();
    private FrameLayout host;
    private View homeView;
    private LinearLayout sectionsBox, recentBox;
    private EditText searchBox;
    private final ArrayList<ToolPage> stack = new ArrayList<>();
    private final List<ToolDef> tools = new ArrayList<>();

    private AlertDialog busyDlg;
    private TextView busyTv;
    private Cb<List<Picked>> pickCb;
    private Runnable camGranted;
    private boolean viewMode;                 // launched from a file manager to read a PDF
    private TextView homeTitle;
    private FrameLayout tabBody;
    private View toolsScroll, readerScroll, docsScroll;
    private LinearLayout pdfRecentBox, docRecentBox;
    private final TextView[] tabIc = new TextView[3], tabLb = new TextView[3];

    private final ActivityResultLauncher<String[]> pickOne =
        registerForActivityResult(new KeepOpenDoc(), uri -> {
            if (uri == null) { pickCb = null; return; }
            deliver(Collections.singletonList(uri));
        });

    private final ActivityResultLauncher<String[]> pickMany =
        registerForActivityResult(new KeepOpenDocs(), uris -> {
            if (uris == null || uris.isEmpty()) { pickCb = null; return; }
            deliver(uris);
        });

    /** File picker that asks for a lasting grant, so a recent file keeps opening straight from its folder. */
    static final class KeepOpenDoc extends ActivityResultContracts.OpenDocument {
        @Override public Intent createIntent(Context c, String[] input) {
            return super.createIntent(c, input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
    }

    static final class KeepOpenDocs extends ActivityResultContracts.OpenMultipleDocuments {
        @Override public Intent createIntent(Context c, String[] input) {
            return super.createIntent(c, input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PDFBoxResourceLoader.init(getApplicationContext());

        getWindow().setStatusBarColor(W.SURFACE);
        getWindow().setNavigationBarColor(W.SURFACE);
        if (Build.VERSION.SDK_INT >= 23) {
            int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(f);
        }

        cleanCache();

        Intent launch = getIntent();
        if (launch != null && Intent.ACTION_VIEW.equals(launch.getAction()) && launch.getData() != null) {
            // Opened as the phone's PDF reader (file manager → PDF): go straight to the reader.
            viewMode = true;
            host = new FrameLayout(this);
            host.setBackgroundColor(0xFF15171B);
            setContentView(host);
            String nm0 = queryName(launch.getData());
            String mime0 = launch.getType() != null ? launch.getType() : getContentResolver().getType(launch.getData());
            if (OfficeReaderDialog.isOffice(OfficeReaderDialog.ext(nm0)) || OfficeReaderDialog.isOfficeMime(mime0)) {
                if (!OfficeReaderDialog.isOffice(OfficeReaderDialog.ext(nm0))) nm0 = nm0 + officeExtFor(mime0);
                openDocUri(launch.getData(), nm0, true);
            } else {
                openPdfUri(launch.getData(), null, true);
            }
            return;
        }
        registerTools();

        host = new FrameLayout(this);
        host.setBackgroundColor(W.BG);
        setContentView(host);

        homeView = buildHome();
        host.addView(homeView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stack.isEmpty()) refreshRecents();
    }

    @Override
    protected void onPause() {
        for (ToolPage p : stack) p.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        for (ToolPage p : stack) p.onDestroy();
        stack.clear();
        idle();
        io.shutdownNow();
        if (!viewMode) cleanCache();
        super.onDestroy();
    }

    // ═════════════════════════ tool registry ═════════════════════════
    private void registerTools() {
        String pdf = "PDF Tools", photo = "Photo Tools", more = "QR & More";
        tools.add(new ToolDef("📖", W.BLUE, "PDF Reader", pdf, a -> { a.selectTab(1); return null; }));
        tools.add(new ToolDef("📑", W.TEAL, "Documents", pdf, a -> { a.selectTab(2); return null; }));
        tools.add(new ToolDef("📚", W.RED, "Merge PDF", pdf, PdfTools.Merge::new));
        tools.add(new ToolDef("✂️", W.ORANGE, "Split PDF", pdf, PdfTools.Split::new));
        tools.add(new ToolDef("🗜️", W.PURPLE, "Compress PDF", pdf, PdfTools.Compress::new));
        tools.add(new ToolDef("🖼️", W.PINK, "PDF to Photo", pdf, PdfTools.ToPhoto::new));
        tools.add(new ToolDef("📄", W.GREEN, "Photo to PDF", pdf, PdfTools.FromPhotos::new));

        tools.add(new ToolDef("📐", W.BLUE, "Photo Resizer", photo, a -> new PhotoTools.Resize(a, 0)));
        tools.add(new ToolDef("👤", W.TEAL, "Job Photo", photo, a -> new PhotoTools.Resize(a, 1)));
        tools.add(new ToolDef("✍️", W.PURPLE, "Signature", photo, a -> new PhotoTools.Resize(a, 2)));

        tools.add(new ToolDef("📱", W.ORANGE, "QR Generator", more, QrTools.Generator::new));
        tools.add(new ToolDef("📷", W.TEAL, "QR Reader", more, QrTools.Reader::new));
        tools.add(new ToolDef("📅", W.LIME, "Age Calculator", more, AgeTool::new));
    }

    // ═════════════════════════ home screen ═════════════════════════
    private View buildHome() {
        LinearLayout root = W.column(this);

        // top bar
        LinearLayout bar = W.row(this);
        bar.setBackgroundColor(W.SURFACE);
        TextView back = W.text(this, "←", 24, W.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, W.lp(W.dp(this, 56), W.dp(this, 56)));
        homeTitle = W.text(this, "Study Tools", 20, W.TEXT, true);
        bar.addView(homeTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, W.dp(this, 56)));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = W.column(this);
        int pad = W.dp(this, 16);
        col.setPadding(pad, W.dp(this, 4), pad, pad);
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        toolsScroll = sv;
        tabBody = new FrameLayout(this);
        tabBody.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(tabBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildTabBar(), W.lp(ViewGroup.LayoutParams.MATCH_PARENT, W.dp(this, 58)));

        // search (white strip under the bar, like WPS)
        searchBox = W.input(this, "🔍  Search tools", android.text.InputType.TYPE_CLASS_TEXT);
        searchBox.setBackground(W.rect(this, W.SURFACE, 22));
        searchBox.setPadding(W.dp(this, 18), W.dp(this, 11), W.dp(this, 18), W.dp(this, 11));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { rebuildSections(s.toString()); }
        });
        col.addView(searchBox, W.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 14));

        // banner
        LinearLayout banner = W.row(this);
        int bp = W.dp(this, 18);
        banner.setPadding(bp, bp, bp, bp);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xFF3B7BFF, 0xFF2455E6});
        g.setCornerRadius(W.dp(this, 18));
        banner.setBackground(g);
        LinearLayout bt = W.column(this);
        bt.addView(W.text(this, "PDF · Photo · QR", 18, 0xFFFFFFFF, true));
        TextView bs = W.text(this, "Everything you need for study & forms.\nWorks 100% offline.", 12.5f, 0xDDFFFFFF, false);
        bs.setLineSpacing(0, 1.15f);
        bt.addView(bs, W.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        banner.addView(bt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView bi = W.text(this, "🧰", 38, 0xFFFFFFFF, false);
        banner.addView(bi);
        col.addView(banner, W.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 16));

        recentBox = W.column(this);
        col.addView(recentBox, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sectionsBox = W.column(this);
        col.addView(sectionsBox, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rebuildSections("");
        refreshRecents();
        return root;
    }

    // ═════════════════════════ bottom tabs (Tools · PDF Reader) ═════════════════════════
    private View buildTabBar() {
        LinearLayout bar = W.row(this);
        bar.setBackgroundColor(W.SURFACE);
        bar.setElevation(W.dp(this, 8));
        String[] ic = {"🧰", "📖", "📄"}, lb = {"Tools", "PDF Reader", "Documents"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout t = W.column(this);
            t.setGravity(Gravity.CENTER);
            TextView icon = W.text(this, ic[i], 20, W.TEXT, false);
            icon.setGravity(Gravity.CENTER);
            TextView label = W.text(this, lb[i], 11, W.SUB, true);
            label.setGravity(Gravity.CENTER);
            t.addView(icon);
            t.addView(label);
            tabIc[i] = icon;
            tabLb[i] = label;
            W.ripple(t, 0x00000000, 0);
            t.setOnClickListener(v -> selectTab(idx));
            bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        styleTabs(0);
        return bar;
    }

    private void styleTabs(int active) {
        for (int i = 0; i < 3; i++) {
            tabLb[i].setTextColor(i == active ? W.BLUE : W.SUB);
            tabIc[i].setAlpha(i == active ? 1f : 0.55f);
        }
    }

    void selectTab(int i) {
        if (tabBody == null) return;
        hideKeyboard();
        tabBody.removeAllViews();
        if (i == 0) {
            homeTitle.setText("Study Tools");
            tabBody.addView(toolsScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            refreshRecents();
        } else if (i == 2) {
            if (docsScroll == null) docsScroll = buildDocsTab();
            homeTitle.setText("Documents");
            tabBody.addView(docsScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            refreshDocRecents();
        } else {
            if (readerScroll == null) readerScroll = buildReaderTab();
            homeTitle.setText("PDF Reader");
            tabBody.addView(readerScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            refreshPdfRecents();
        }
        styleTabs(i);
    }

    private View buildReaderTab() {
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = W.column(this);
        int pad = W.dp(this, 16);
        col.setPadding(pad, W.dp(this, 8), pad, pad);
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(W.pickBox(this, "📖", "Open a PDF", "Read, zoom and select text — no OCR needed",
            v -> pick(false, new String[]{"application/pdf"}, list -> {
                Picked p = list.get(0);
                addPdfRecent(p.name, p.uri.toString());
                PdfReaderDialog.show(this, p.file, p.name, null);
            })));

        pdfRecentBox = W.column(this);
        col.addView(pdfRecentBox, W.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));

        LinearLayout tips = W.card(this);
        tips.addView(W.text(this, "Make it your default PDF reader", 14, W.TEXT, true));
        tips.addView(W.text(this, "Open any PDF from your file manager, choose this app and tap “Always”. After that, tapping a PDF in any folder opens it here directly.", 12.5f, W.SUB, false),
            W.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        tips.addView(W.text(this, "In the reader", 14, W.TEXT, true),
            W.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        tips.addView(W.text(this, "• Header and tabs hide by themselves after 3 seconds and while you scroll — tap the page to bring them back\n• 👓 Reading: clean full screen, no header/footer, no text selection, warm paper colour\n• Aa Text: the whole PDF as selectable text\n• Long-press a word to select, drag the blue handles", 12.5f, W.SUB, false),
            W.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        col.addView(tips, W.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        return sv;
    }

    // ═════════════════════════ Documents tab (Word · PowerPoint · Excel) ═════════════════════════
    private static final String[] DOC_MIMES = {
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/msword", "application/vnd.ms-powerpoint", "application/vnd.ms-excel"
    };

    private View buildDocsTab() {
        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = W.column(this);
        int pad = W.dp(this, 16);
        col.setPadding(pad, W.dp(this, 8), pad, pad);
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(W.pickBox(this, "📄", "Open a document", "Word · PowerPoint · Excel — opens instantly from any folder",
            v -> pickDoc()));

        docRecentBox = W.column(this);
        col.addView(docRecentBox, W.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));

        LinearLayout tips = W.card(this);
        tips.addView(W.text(this, "Make it your default for documents", 14, W.TEXT, true));
        tips.addView(W.text(this, "Open any .docx, .pptx or .xlsx from your file manager, choose this app and tap “Always”. After that, tapping a document in any folder opens it here directly.", 12.5f, W.SUB, false),
            W.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        tips.addView(W.text(this, "Needs internet — the file is prepared once, then shown right away. Use “Open with” at the top to edit it in another app.", 12.5f, W.SUB, false),
            W.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        col.addView(tips, W.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        return sv;
    }

    /** Picker straight into the phone's folders; the chosen file opens right away (no copy). */
    private void pickDoc() {
        pickCb = null;
        try {
            docPicker.launch(DOC_MIMES);
        } catch (Exception e) {
            toast("No file picker found");
        }
    }

    private final ActivityResultLauncher<String[]> docPicker =
        registerForActivityResult(new KeepOpenDoc(), uri -> {
            if (uri == null) return;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) { }
            String nm = queryName(uri);
            if (!OfficeReaderDialog.isOffice(OfficeReaderDialog.ext(nm))) {
                toast("Choose a Word, PowerPoint or Excel file");
                return;
            }
            openDocUri(uri, nm, false);
        });

    void openDocUri(final Uri uri, final String name, final boolean finishOnClose) {
        if (!finishOnClose) addDocRecent(name, uri.toString());
        OfficeReaderDialog.show(this, uri, name, finishOnClose ? this::finish : null);
    }

    private static String officeExtFor(String mime) {
        if (mime == null) return "";
        if (mime.contains("wordprocessingml")) return ".docx";
        if (mime.contains("presentationml")) return ".pptx";
        if (mime.contains("spreadsheetml")) return ".xlsx";
        if (mime.equals("application/msword")) return ".doc";
        if (mime.equals("application/vnd.ms-powerpoint")) return ".ppt";
        if (mime.equals("application/vnd.ms-excel")) return ".xls";
        return "";
    }

    private void addDocRecent(String name, String uri) {
        try {
            JSONArray old = new JSONArray(prefs().getString("recent_doc", "[]"));
            JSONArray neu = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("uri", uri);
            neu.put(o);
            for (int i = 0; i < old.length() && neu.length() < 8; i++) {
                JSONObject x = old.getJSONObject(i);
                if (!uri.equals(x.optString("uri"))) neu.put(x);
            }
            prefs().edit().putString("recent_doc", neu.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void refreshDocRecents() {
        if (docRecentBox == null) return;
        docRecentBox.removeAllViews();
        final List<String[]> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("recent_doc", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new String[]{o.optString("name"), o.optString("uri")});
            }
        } catch (Exception ignored) { }
        if (list.isEmpty()) return;

        LinearLayout card = W.card(this);
        LinearLayout head = W.row(this);
        head.addView(W.sectionTitle(this, "Recent documents"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = W.text(this, "Clear", 13, W.SUB, true);
        clear.setPadding(W.dp(this, 8), W.dp(this, 4), 0, W.dp(this, 4));
        clear.setOnClickListener(v -> { prefs().edit().remove("recent_doc").apply(); refreshDocRecents(); });
        head.addView(clear);
        card.addView(head);
        for (final String[] it : list) {
            String e = OfficeReaderDialog.ext(it[0]);
            LinearLayout r = W.row(this);
            r.setPadding(0, W.dp(this, 10), 0, W.dp(this, 2));
            int c = e.startsWith("x") ? W.GREEN : e.startsWith("p") ? W.ORANGE : W.BLUE;
            String tag = e.startsWith("x") ? "XLS" : e.startsWith("p") ? "PPT" : "DOC";
            r.addView(W.badge(this, tag, c), W.lp(W.dp(this, 40), W.dp(this, 40)));
            r.addView(recentText(it[0], it[1]), W.lp(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 12, 0, 0, 0));
            ((LinearLayout.LayoutParams) r.getChildAt(1).getLayoutParams()).weight = 1f;
            r.addView(folderBtn(Uri.parse(it[1])));
            r.setClickable(true);
            r.setOnClickListener(v -> {
                Uri u = Uri.parse(it[1]);
                if (!canRead(u)) {
                    forgetRecent("recent_doc", it[1]);
                    toast("This file was moved or deleted. Open it again from its folder.");
                    refreshDocRecents();
                    return;
                }
                openDocUri(u, it[0], false);
            });
            card.addView(r);
        }
        docRecentBox.addView(card);
    }

    // ───────── recent PDFs (re-openable via persisted picker permission) ─────────
    private void addPdfRecent(String name, String uri) {
        try {
            JSONArray old = new JSONArray(prefs().getString("recent_pdf", "[]"));
            JSONArray neu = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("uri", uri);
            neu.put(o);
            for (int i = 0; i < old.length() && neu.length() < 8; i++) {
                JSONObject x = old.getJSONObject(i);
                if (!uri.equals(x.optString("uri"))) neu.put(x);
            }
            prefs().edit().putString("recent_pdf", neu.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void refreshPdfRecents() {
        if (pdfRecentBox == null) return;
        pdfRecentBox.removeAllViews();
        final List<String[]> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("recent_pdf", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new String[]{o.optString("name"), o.optString("uri")});
            }
        } catch (Exception ignored) { }
        if (list.isEmpty()) return;

        LinearLayout card = W.card(this);
        LinearLayout head = W.row(this);
        head.addView(W.sectionTitle(this, "Recent PDFs"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = W.text(this, "Clear", 13, W.SUB, true);
        clear.setPadding(W.dp(this, 8), W.dp(this, 4), 0, W.dp(this, 4));
        clear.setOnClickListener(v -> { prefs().edit().remove("recent_pdf").apply(); refreshPdfRecents(); });
        head.addView(clear);
        card.addView(head);
        for (final String[] it : list) {
            LinearLayout r = W.row(this);
            r.setPadding(0, W.dp(this, 10), 0, W.dp(this, 2));
            r.addView(W.badge(this, "PDF", W.RED), W.lp(W.dp(this, 40), W.dp(this, 40)));
            r.addView(recentText(it[0], it[1]), W.lp(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 12, 0, 0, 0));
            ((LinearLayout.LayoutParams) r.getChildAt(1).getLayoutParams()).weight = 1f;
            r.addView(folderBtn(Uri.parse(it[1])));
            r.setClickable(true);
            r.setOnClickListener(v -> {
                Uri u = Uri.parse(it[1]);
                if (!canRead(u)) {
                    forgetRecent("recent_pdf", it[1]);
                    toast("This PDF was moved or deleted. Open it again from its folder.");
                    refreshPdfRecents();
                    return;
                }
                openPdfUri(u, it[0], false);
            });
            card.addView(r);
        }
        pdfRecentBox.addView(card);
    }

    /** Copies a PDF (from a picker / file manager) into the cache and opens it in the reader. */
    void openPdfUri(final Uri uri, final String givenName, final boolean finishOnClose) {
        backgroundQuiet(u -> {
            String nm = givenName != null ? givenName : queryName(uri);
            File tmp = tempOut(nm);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(tmp)) {
                if (in == null) throw new Exception("Can't open this file");
                copy(in, os);
            } catch (Exception e) {
                throw new Exception("Couldn't open this PDF. It may have been moved or deleted.");
            }
            final String shown = nm;
            return () -> {
                if (!finishOnClose) addPdfRecent(shown, uri.toString());
                PdfReaderDialog.show(this, tmp, shown, null, finishOnClose ? this::finish : null);
            };
        });
    }

    /** True when the original file can still be read (moved / deleted / lost access → false). */
    boolean canRead(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Folder of a picked file, e.g. "Download/Books" (empty when the provider doesn't say). */
    static String folderOf(Uri uri) {
        try {
            String id = DocumentsContract.getDocumentId(uri);        // "primary:Download/Books/a.pdf"
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            int slash = path.lastIndexOf('/');
            return slash > 0 ? path.substring(0, slash) : (colon >= 0 && "primary".equals(id.substring(0, colon)) ? "Internal storage" : "");
        } catch (Exception e) {
            return "";
        }
    }

    private View recentText(String name, String uri) {
        LinearLayout t = W.column(this);
        TextView n = W.text(this, name, 14, W.TEXT, true);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.addView(n);
        String f = folderOf(Uri.parse(uri));
        if (!f.isEmpty()) {
            TextView sub = W.text(this, "📁 " + f, 11.5f, W.SUB, false);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.START);
            t.addView(sub);
        }
        return t;
    }

    private View folderBtn(final Uri uri) {
        TextView b = W.text(this, "📁", 18, W.TEXT, false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(v -> openFolderOf(uri));
        b.setLayoutParams(W.lp(W.dp(this, 40), W.dp(this, 40)));
        return b;
    }

    /** Opens the folder that holds a recent file in the file manager. */
    void openFolderOf(Uri uri) {
        try {
            String id = DocumentsContract.getDocumentId(uri);
            int slash = id.lastIndexOf('/');
            if (slash > 0) {
                Uri dir = DocumentsContract.buildDocumentUri(uri.getAuthority(), id.substring(0, slash));
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(dir, DocumentsContract.Document.MIME_TYPE_DIR);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
                return;
            }
        } catch (Exception ignored) { }
        openFolder();
    }

    /** Drops a dead entry from a recent list (pref key = recent / recent_pdf / recent_doc). */
    private void forgetRecent(String key, String uri) {
        try {
            JSONArray old = new JSONArray(prefs().getString(key, "[]"));
            JSONArray neu = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject x = old.getJSONObject(i);
                if (!uri.equals(x.optString("uri"))) neu.put(x);
            }
            prefs().edit().putString(key, neu.toString()).apply();
        } catch (Exception ignored) { }
    }

    void shareFile(File f, String mime) {
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".provider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share"));
        } catch (Exception e) {
            toast("Can't share this file");
        }
    }

    void openFile(File f, String mime) {
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".provider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Open with"));
        } catch (Exception e) {
            toast("No app found to open this file");
        }
    }

    private void rebuildSections(String query) {
        sectionsBox.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String[] order = {"PDF Tools", "Photo Tools", "QR & More"};
        boolean any = false;
        for (String sec : order) {
            List<ToolDef> list = new ArrayList<>();
            for (ToolDef d : tools) {
                if (!d.section.equals(sec)) continue;
                if (!q.isEmpty() && !d.label.toLowerCase(Locale.ROOT).contains(q)) continue;
                list.add(d);
            }
            if (list.isEmpty()) continue;
            any = true;
            LinearLayout card = W.card(this);
            card.addView(W.sectionTitle(this, sec));
            card.addView(W.space(this, 8));
            card.addView(grid(list));
            sectionsBox.addView(card, W.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 14));
        }
        if (!any) {
            TextView t = W.text(this, "No tools found", 14, W.SUB, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, W.dp(this, 30), 0, 0);
            sectionsBox.addView(t);
        }
    }

    private View grid(List<ToolDef> list) {
        LinearLayout g = W.column(this);
        final int cols = 4;
        for (int i = 0; i < list.size(); i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < cols; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i + j < list.size()) {
                    final ToolDef d = list.get(i + j);
                    row.addView(W.tile(this, d.emoji, d.color, d.label, v -> openPage(d.f.make(this))), lp);
                } else {
                    row.addView(new View(this), lp);
                }
            }
            g.addView(row);
        }
        return g;
    }

    // ───────── recent files ─────────
    private SharedPreferences prefs() { return getSharedPreferences("study_tools", MODE_PRIVATE); }

    void addRecent(Saved s) {
        try {
            JSONArray old = new JSONArray(prefs().getString("recent", "[]"));
            JSONArray neu = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("name", s.name); o.put("uri", s.uri); o.put("mime", s.mime); o.put("size", s.size);
            neu.put(o);
            for (int i = 0; i < old.length() && neu.length() < 6; i++) {
                JSONObject x = old.getJSONObject(i);
                if (!s.uri.equals(x.optString("uri"))) neu.put(x);
            }
            prefs().edit().putString("recent", neu.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void refreshRecents() {
        if (recentBox == null) return;
        recentBox.removeAllViews();
        final List<Saved> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs().getString("recent", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Saved s = new Saved();
                s.name = o.optString("name"); s.uri = o.optString("uri");
                s.mime = o.optString("mime"); s.size = o.optLong("size");
                list.add(s);
            }
        } catch (Exception ignored) { }
        if (list.isEmpty() || !searchBox.getText().toString().trim().isEmpty()) return;

        LinearLayout card = W.card(this);
        LinearLayout head = W.row(this);
        head.addView(W.sectionTitle(this, "Recent files"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView folder = W.text(this, "📁 Folder", 13, W.BLUE, true);
        folder.setPadding(W.dp(this, 8), W.dp(this, 4), W.dp(this, 8), W.dp(this, 4));
        folder.setOnClickListener(v -> openFolder());
        head.addView(folder);
        TextView clear = W.text(this, "Clear", 13, W.SUB, true);
        clear.setPadding(W.dp(this, 8), W.dp(this, 4), 0, W.dp(this, 4));
        clear.setOnClickListener(v -> { prefs().edit().remove("recent").apply(); refreshRecents(); });
        head.addView(clear);
        card.addView(head);
        for (final Saved s : list) {
            LinearLayout r = W.row(this);
            r.setPadding(0, W.dp(this, 10), 0, W.dp(this, 2));
            boolean isPdf = s.mime != null && s.mime.contains("pdf");
            TextView bd = W.badge(this, isPdf ? "PDF" : "IMG", isPdf ? W.RED : W.BLUE);
            r.addView(bd, W.lp(W.dp(this, 40), W.dp(this, 40)));
            LinearLayout t = W.column(this);
            TextView n = W.text(this, s.name, 14, W.TEXT, true);
            n.setSingleLine(true); n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            t.addView(n);
            t.addView(W.text(this, W.size(s.size) + " · Downloads/" + SAVE_FOLDER, 11.5f, W.SUB, false));
            r.addView(t, W.lp(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 12, 0, 0, 0));
            ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1f;
            r.setOnClickListener(v -> {
                if (!canRead(Uri.parse(s.uri))) {
                    forgetRecent("recent", s.uri);
                    toast("This file was moved or deleted from Downloads/" + SAVE_FOLDER);
                    refreshRecents();
                    return;
                }
                viewSaved(list, list.indexOf(s));
            });
            r.setClickable(true);
            card.addView(r);
        }
        recentBox.addView(card, W.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 14));
    }

    // ═════════════════════════ navigation ═════════════════════════
    void openPage(ToolPage p) {
        if (p == null) return;
        hideKeyboard();
        stack.add(p);
        host.removeAllViews();
        host.addView(buildPage(p));
    }

    private void closeTop() {
        hideKeyboard();
        if (stack.isEmpty()) { finish(); return; }
        ToolPage p = stack.remove(stack.size() - 1);
        p.onDestroy();
        host.removeAllViews();
        if (stack.isEmpty()) {
            host.addView(homeView);
            refreshRecents();
        } else {
            host.addView(buildPage(stack.get(stack.size() - 1)));
        }
    }

    private View buildPage(ToolPage p) {
        LinearLayout root = W.column(this);
        root.setBackgroundColor(W.BG);

        LinearLayout bar = W.row(this);
        bar.setBackgroundColor(W.SURFACE);
        TextView back = W.text(this, "←", 24, W.TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, W.lp(W.dp(this, 56), W.dp(this, 56)));
        bar.addView(W.text(this, p.title(), 18, W.TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, W.dp(this, 56)));

        ScrollView sv = new ScrollView(this);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setFillViewport(true);
        LinearLayout wrap = W.column(this);
        int pad = W.dp(this, 16);
        wrap.setPadding(pad, W.dp(this, 8), pad, pad);
        wrap.addView(p.body(), W.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(wrap, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View foot = p.footer();
        if (foot != null) {
            LinearLayout strip = W.column(this);
            strip.setBackgroundColor(W.SURFACE);
            strip.setElevation(W.dp(this, 6));
            int fp = W.dp(this, 14);
            strip.setPadding(fp, W.dp(this, 10), fp, W.dp(this, 10));
            strip.addView(foot, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, W.dp(this, 52)));
            root.addView(strip, W.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return root;
    }

    @Override
    public void onBackPressed() {
        if (stack.isEmpty()) { finish(); return; }
        ToolPage top = stack.get(stack.size() - 1);
        if (top.onBack()) return;
        closeTop();
    }

    private void hideKeyboard() {
        try {
            View f = getCurrentFocus();
            if (f != null) {
                InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.hideSoftInputFromWindow(f.getWindowToken(), 0);
            }
        } catch (Exception ignored) { }
    }

    // ═════════════════════════ busy / background work ═════════════════════════
    void busy(String msg) {
        if (isFinishing()) return;
        if (busyDlg == null) {
            LinearLayout l = W.row(this);
            int p = W.dp(this, 22);
            l.setPadding(p, p, p, p);
            ProgressBar pb = new ProgressBar(this);
            l.addView(pb, W.lp(W.dp(this, 36), W.dp(this, 36)));
            busyTv = W.text(this, msg, 15, W.TEXT, false);
            l.addView(busyTv, W.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16, 0, 0, 0));
            busyDlg = new AlertDialog.Builder(this).setView(l).setCancelable(false).create();
            busyDlg.show();
        } else {
            busyTv.setText(msg);
            if (!busyDlg.isShowing()) busyDlg.show();
        }
    }

    private void busyText(String m) { if (busyTv != null) busyTv.setText(m); }

    void idle() {
        try { if (busyDlg != null && busyDlg.isShowing()) busyDlg.dismiss(); } catch (Exception ignored) { }
        busyDlg = null;
        busyTv = null;
    }

    /** Runs work off the main thread; the Runnable it returns is executed on the UI thread afterwards. */
    void background(String msg, final Task t) {
        busy(msg);
        io.execute(() -> {
            try {
                final Runnable done = t.run(m -> runOnUiThread(() -> busyText(m)));
                runOnUiThread(() -> { idle(); if (done != null) done.run(); });
            } catch (final Throwable e) {
                runOnUiThread(() -> { idle(); showError(e); });
            }
        });
    }

    /** Like background(), but shows nothing unless the work takes noticeably long (recent files open as if direct). */
    void backgroundQuiet(final Task t) {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable late = () -> busy("Opening…");
        h.postDelayed(late, 800);
        io.execute(() -> {
            try {
                final Runnable done = t.run(m -> { });
                runOnUiThread(() -> { h.removeCallbacks(late); idle(); if (done != null) done.run(); });
            } catch (final Throwable e) {
                runOnUiThread(() -> { h.removeCallbacks(late); idle(); showError(e); });
            }
        });
    }

    void showError(Throwable e) {
        String m;
        if (e instanceof OutOfMemoryError) m = "Not enough memory for this file. Try a smaller file or lower quality.";
        else if ("PERMISSION".equals(e.getMessage())) m = "Storage permission is needed. Allow it, then try again.";
        else if (e.getMessage() != null && !e.getMessage().isEmpty()) m = e.getMessage();
        else m = "Something went wrong.";
        if (isFinishing()) return;
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Couldn't finish").setMessage(m)
            .setPositiveButton("OK", null).create();
        dlg.setOnDismissListener(x -> { if (viewMode) finish(); });
        dlg.show();
    }

    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    // ═════════════════════════ file picking ═════════════════════════
    void pick(boolean multiple, String[] mimes, Cb<List<Picked>> cb) {
        pickCb = cb;
        try {
            if (multiple) pickMany.launch(mimes); else pickOne.launch(mimes);
        } catch (Exception e) {
            pickCb = null;
            toast("No file picker found");
        }
    }

    private void deliver(final List<Uri> uris) {
        final Cb<List<Picked>> cb = pickCb;
        pickCb = null;
        if (cb == null) return;
        background("Loading…", u -> {
            final List<Picked> out = new ArrayList<>();
            File dir = new File(getCacheDir(), "picked");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            for (Uri uri : uris) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) { }
                Picked p = new Picked();
                p.uri = uri;
                p.name = queryName(uri);
                File f = new File(dir, System.nanoTime() + "_" + safeName(p.name));
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream os = new FileOutputStream(f)) {
                    if (in == null) throw new Exception("Can't open " + p.name);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                p.file = f;
                p.size = f.length();
                out.add(p);
            }
            return () -> cb.on(out);
        });
    }

    private String queryName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "file";
    }

    private void cleanCache() {
        for (String d : new String[]{"picked", "out"}) {
            File dir = new File(getCacheDir(), d);
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    /** Scratch file for tool output (inside the cache; cleaned on next start). */
    File tempOut(String name) {
        File dir = new File(getCacheDir(), "out");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, System.nanoTime() + "_" + safeName(name));
    }

    // ═════════════════════════ camera permission ═════════════════════════
    void requestCamera(Runnable granted) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            granted.run();
            return;
        }
        camGranted = granted;
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == REQ_CAMERA) {
            Runnable r = camGranted;
            camGranted = null;
            if (ok && r != null) r.run(); else if (!ok) toast("Camera permission denied");
        } else if (code == REQ_STORAGE) {
            toast(ok ? "Storage allowed — tap the button again" : "Storage permission denied");
        }
    }

    // ═════════════════════════ saving / opening ═════════════════════════
    /** Copies a finished file into Downloads/StudyTools. Call from a background thread. */
    Saved saveFile(File src, String name, String mime) throws Exception {
        name = safeName(name);
        if (mime == null || mime.isEmpty()) mime = guessMime(name);
        Saved s = new Saved();
        s.mime = mime;
        s.size = src.length();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER);
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("Can't create the file in Downloads");
            try (OutputStream os = getContentResolver().openOutputStream(uri);
                 InputStream in = new FileInputStream(src)) {
                if (os == null) throw new Exception("Can't write to Downloads");
                copy(in, os);
            } catch (Exception e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            s.uri = uri.toString();
            s.name = queryName(uri);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
                throw new Exception("PERMISSION");
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SAVE_FOLDER);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("Can't create Downloads/" + SAVE_FOLDER);
            File dst = uniqueFile(dir, name);
            try (OutputStream os = new FileOutputStream(dst); InputStream in = new FileInputStream(src)) {
                copy(in, os);
            }
            MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);
            s.name = dst.getName();
            s.uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", dst).toString();
        }
        //noinspection ResultOfMethodCallIgnored
        src.delete();
        final Saved fin = s;
        runOnUiThread(() -> addRecent(fin));
        return s;
    }

    private static void copy(InputStream in, OutputStream os) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
    }

    void openSaved(Saved s) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(s.uri), s.mime == null || s.mime.isEmpty() ? "*/*" : s.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("No app found to open this file (or it was moved)");
        }
    }

    /** Opens the in-app viewer (PDF / photo) for saved files. */
    void viewSaved(List<Saved> list, int index) {
        ToolViewer.show(this, list, index);
    }

    /** Opens Downloads/StudyTools in the phone's file manager. */
    void openFolder() {
        try {
            Uri u = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                "primary:" + Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, DocumentsContract.Document.MIME_TYPE_DIR);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
            return;
        } catch (Exception ignored) { }
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
            toast("Open the “" + SAVE_FOLDER + "” folder inside Downloads");
        } catch (Exception e) {
            toast("No file manager found. Look in Downloads/" + SAVE_FOLDER);
        }
    }

    void shareSaved(List<Saved> list) {
        try {
            Intent i;
            if (list.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.setType(list.get(0).mime);
                i.putExtra(Intent.EXTRA_STREAM, Uri.parse(list.get(0).uri));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.setType("*/*");
                ArrayList<Uri> us = new ArrayList<>();
                for (Saved s : list) us.add(Uri.parse(s.uri));
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, us);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share"));
        } catch (Exception e) {
            toast("Can't share this file");
        }
    }

    void copyText(String t) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Study Tools", t == null ? "" : t));
        toast("Copied");
    }

    void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())));
        } catch (Exception e) {
            toast("Can't open this link");
        }
    }

    // ═════════════════════════ shared result card ═════════════════════════
    /** Green "done" card with Open / Share buttons. */
    View resultCard(String headline, String detail, final List<Saved> files) {
        LinearLayout card = W.card(this);
        LinearLayout head = W.row(this);
        TextView ok = W.text(this, "✓", 18, 0xFFFFFFFF, true);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(W.rect(this, W.GREEN, 16));
        head.addView(ok, W.lp(W.dp(this, 32), W.dp(this, 32)));
        head.addView(W.text(this, headline, 16, W.TEXT, true), W.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12, 0, 0, 0));
        card.addView(head);

        if (detail != null) {
            card.addView(W.text(this, detail, 13, W.SUB, false), W.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        }
        int shown = Math.min(files.size(), 3);
        for (int i = 0; i < shown; i++) {
            Saved s = files.get(i);
            TextView n = W.text(this, "📎 " + s.name + "  (" + W.size(s.size) + ")", 13, W.TEXT, false);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            card.addView(n, W.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        }
        if (files.size() > shown) {
            card.addView(W.text(this, "+ " + (files.size() - shown) + " more", 12, W.SUB, false),
                W.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        }
        LinearLayout where = W.column(this);
        where.setBackground(W.rect(this, W.alpha(W.BLUE, 0x0F), 10));
        int wp = W.dp(this, 12);
        where.setPadding(wp, W.dp(this, 10), wp, W.dp(this, 10));
        where.addView(W.text(this, "Saved to", 11.5f, W.SUB, false));
        TextView link = W.text(this, "📁  Phone storage › Download › " + SAVE_FOLDER, 13.5f, W.BLUE, true);
        link.setPaintFlags(link.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        link.setPadding(0, W.dp(this, 4), 0, W.dp(this, 2));
        where.addView(link);
        where.addView(W.text(this, "Tap to open this folder", 11, W.SUB, false));
        where.setClickable(true);
        where.setOnClickListener(v -> openFolder());
        card.addView(where, W.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));

        LinearLayout btns = W.row(this);
        boolean viewable = ToolViewer.canView(files.get(0));
        TextView view = W.softBtn(this, viewable ? "👁 View" : "Open", W.BLUE,
            v -> { if (viewable) viewSaved(files, 0); else openSaved(files.get(0)); });
        btns.addView(view, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (viewable) {
            TextView ow = W.softBtn(this, "Open with", W.PURPLE, v -> openSaved(files.get(0)));
            btns.addView(ow, W.lp(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
            ((LinearLayout.LayoutParams) ow.getLayoutParams()).weight = 1f;
        }
        TextView sh = W.softBtn(this, "Share", W.GREEN, v -> shareSaved(files));
        btns.addView(sh, W.lp(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
        ((LinearLayout.LayoutParams) sh.getLayoutParams()).weight = 1f;
        card.addView(btns, W.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
        return card;
    }

    // ═════════════════════════ helpers ═════════════════════════
    static String safeName(String n) {
        if (n == null) n = "";
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty()) n = "file";
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    static String baseName(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    static String guessMime(String name) {
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }
}

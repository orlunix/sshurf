package dev.sshbrowser;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Recent visits: tap = open (via Index so auto-connect applies), ⋮ = add to bookmarks / delete. */
public final class RecentActivity extends Activity {

    private List<RecentStore.Entry> entries;
    private ListView lv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recent);
        lv = findViewById(R.id.lv_recents);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        entries = RecentStore.list(this);
        ArrayAdapter<RecentStore.Entry> adapter = new ArrayAdapter<RecentStore.Entry>(
                this, R.layout.item_bookmark, entries) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.item_bookmark, parent, false);
                RecentStore.Entry e = getItem(position);
                TextView initial = view.findViewById(R.id.tv_initial);
                initial.setText(e.title.isEmpty() ? "?" : e.title.substring(0, 1));
                initial.setBackground(IndexActivity.circle(IndexActivity.colorFor(e.title)));
                ((TextView) view.findViewById(R.id.tv_name)).setText(e.title);
                ((TextView) view.findViewById(R.id.tv_url)).setText(e.url);
                view.findViewById(R.id.tv_more).setOnClickListener(v -> showMenu(v, position));
                return view;
            }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, position, id) -> open(entries.get(position).url));
    }

    private void open(String url) {
        // Hand off to Index so the auto-connect flow applies.
        startActivity(new Intent(this, IndexActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(IndexActivity.EXTRA_OPEN_URL, url));
    }

    private void showMenu(View anchor, int position) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "加入书签");
        menu.getMenu().add(0, 2, 1, "删除");
        menu.setOnMenuItemClickListener(item -> {
            RecentStore.Entry e = entries.get(position);
            switch (item.getItemId()) {
                case 1:
                    BookmarkStore.add(this, e.title, e.url);
                    Toast.makeText(this, "已加入书签", Toast.LENGTH_SHORT).show();
                    return true;
                case 2:
                    RecentStore.removeAt(this, position);
                    refresh();
                    return true;
            }
            return false;
        });
        menu.show();
    }
}

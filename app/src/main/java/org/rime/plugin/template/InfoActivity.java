package org.rime.plugin.template;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Discovery entry point for frontends that support external Rime backends.
 *
 * The manifest declares the neutral {@code org.rime.plugin.MANIFEST} action and
 * the metadata describing this app's librime.so payload. The Activity is only a
 * discovery marker and an info screen; the frontend does not launch it.
 */
public final class InfoActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setGravity(Gravity.CENTER);
        text.setPadding(48, 48, 48, 48);
        text.setText(getString(R.string.plugin_title) + "\n\n"
                + getString(R.string.plugin_description));
        setContentView(text);
    }
}

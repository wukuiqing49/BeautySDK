package dev.beauty.sizeprobe;

/** 三个测量变体共享完全相同的最小宿主，不依赖Demo、相机或WKQ。 */
public final class MainActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView text = new android.widget.TextView(this);
        text.setText("SDK size probe");
        setContentView(text);
    }
}

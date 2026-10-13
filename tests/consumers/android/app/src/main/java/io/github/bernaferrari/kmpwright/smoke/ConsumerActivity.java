package io.github.bernaferrari.kmpwright.smoke;

import android.app.Activity;
import android.os.Bundle;
import com.bernaferrari.kmpwright.core.KmpwrightConfig;

public final class ConsumerActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // A Java consumer still verifies the Android AAR and its Kotlin ABI
        // resolve from Maven, without coupling this fixture to the root build.
        Class<?> publishedType = KmpwrightConfig.class;
        if (publishedType == null) throw new AssertionError("unreachable");
    }
}

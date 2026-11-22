package com.termux.app;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.termux.shared.activity.ActivityUtils;

/**
 * Standalone Termux terminal entry point.
 *
 * The reusable terminal host lives in {@link TermuxTerminalHostActivity} so that the default
 * MainActivity can embed the same root view in its Compose terminal tab.
 */
public final class TermuxActivity extends TermuxTerminalHostActivity {

    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
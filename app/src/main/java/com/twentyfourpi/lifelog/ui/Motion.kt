package com.twentyfourpi.lifelog.ui

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** The app preference is kept outside the archive backup and applies immediately. */
class MotionPreference internal constructor(
    val reduced: androidx.compose.runtime.MutableState<Boolean>,
    private val systemEnabledState: State<Boolean>,
    private val save: (Boolean) -> Unit,
) {
    val systemEnabled: Boolean get() = systemEnabledState.value
    val enabled: Boolean get() = !reduced.value && systemEnabled
    fun setReduced(value: Boolean) {
        reduced.value = value
        save(value)
    }

    fun <T> spec(durationMs: Int): FiniteAnimationSpec<T> =
        if (enabled) tween(durationMs) else snap()
}

val LocalMotionPreference = compositionLocalOf<MotionPreference> {
    MotionPreference(mutableStateOf(false), mutableStateOf(true)) {}
}

@Composable
fun rememberMotionPreference(): MotionPreference {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { context.getSharedPreferences("lifelog_motion", 0) }
    val reduced = remember { mutableStateOf(preferences.getBoolean("reduced", false)) }
    fun readSystemMotion() =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f &&
            Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f
    val systemEnabled = remember(context) { mutableStateOf(readSystemMotion()) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) systemEnabled.value = readSystemMotion()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return remember(preferences, systemEnabled) {
        MotionPreference(reduced, systemEnabled) { preferences.edit().putBoolean("reduced", it).apply() }
    }
}

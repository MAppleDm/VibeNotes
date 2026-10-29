package io.github.mappledm.vibenotes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.runtime.DisposableEffect
import io.github.mappledm.vibenotes.AppContainer
import io.github.mappledm.vibenotes.VibeNotesApp

/** Factory that hands the app container to a ViewModel constructor. */
inline fun <reified VM : ViewModel> containerFactory(crossinline create: (AppContainer) -> VM): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as VibeNotesApp
            create(app.container)
        }
    }

/** Runs [onResume] every time the screen returns to the foreground (e.g. after editing on a PC). */
/** Runs [block] each time [key] changes, but not on the first composition. */
@Composable
fun OnChange(key: Any?, block: () -> Unit) {
    val seen = remember { mutableStateOf(key) }
    LaunchedEffect(key) {
        if (key != seen.value) {
            seen.value = key
            block()
        }
    }
}

@Composable
fun OnResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val callback = rememberUpdatedState(onResume)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) callback.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
fun OnPause(onPause: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val callback = rememberUpdatedState(onPause)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) callback.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

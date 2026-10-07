package dev.streamer.app.ui.components

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.streamer.app.AppContainer
import dev.streamer.app.LocalAppContainer

/** ViewModel scoped to the current navigation entry, built from the app container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel(key = key) { create(container) }
}

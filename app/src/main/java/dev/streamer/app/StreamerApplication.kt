package dev.streamer.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.streamer.app.data.account.SessionState
import dev.streamer.app.data.images.CoverArtMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StreamerApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createAppContainer(this)
        clearImageCachesOnSignOut()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(CoverArtMapper(container.accounts, container.subsonic, container.artworkStore))
                add(OkHttpNetworkFetcherFactory(callFactory = { container.http }))
            }
            .build()

    /** Cover images belong to the account; drop them with the rest of its data. */
    private fun clearImageCachesOnSignOut() {
        container.scope.launch {
            var wasSignedIn = false
            container.accounts.state.collect { state ->
                when (state) {
                    is SessionState.Active -> wasSignedIn = true
                    SessionState.SignedOut -> if (wasSignedIn) {
                        wasSignedIn = false
                        val loader = SingletonImageLoader.get(this@StreamerApplication)
                        loader.memoryCache?.clear()
                        withContext(Dispatchers.IO) { loader.diskCache?.clear() }
                    }
                    SessionState.Loading -> Unit
                }
            }
        }
    }
}

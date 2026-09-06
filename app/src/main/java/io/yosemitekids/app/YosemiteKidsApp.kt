package io.yosemitekids.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class YosemiteKidsApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        io.yosemitekids.app.data.Extractor.init()
        // Thumbnail/playback quality targets from connection + device type.
        io.yosemitekids.app.data.NetworkQuality.configureTargets(this)
    }

    /** Coil singleton: aggressive thumbnail caching — YouTube thumb URLs are immutable. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(io.yosemitekids.app.data.Http.client)
            .crossfade(true)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this).maxSizePercent(0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnails"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .apply { if (BuildConfig.DEBUG) logger(coil.util.DebugLogger()) }
            .build()
}

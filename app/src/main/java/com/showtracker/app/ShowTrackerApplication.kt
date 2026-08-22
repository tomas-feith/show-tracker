package com.showtracker.app

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.showtracker.app.data.BackupFolder
import com.showtracker.app.data.LibraryRepository
import com.showtracker.app.data.Settings
import com.showtracker.app.data.ShowDatabase
import com.showtracker.app.network.TmdbClient
import com.showtracker.app.notify.BackupSchedule
import com.showtracker.app.notify.RefreshWorker
import com.showtracker.app.notify.ensureChannel
import okhttp3.OkHttpClient

/**
 * Manual dependency wiring.
 *
 * Constructed lazily and held by the Application, because everything here has to be
 * reachable from a background worker as well as from an activity, and a worker can start
 * the process with no activity ever existing.
 */
class AppContainer(
    context: Context,
) {
    /**
     * Application context, kept for the pieces that schedule work rather than talk to a
     * database. Held as `applicationContext` explicitly so nothing can pin an activity.
     */
    val appContext: Context = context.applicationContext

    val http: OkHttpClient by lazy { TmdbClient.defaultClient() }

    val tmdb: TmdbClient by lazy { TmdbClient(http) }

    val settings: Settings by lazy { Settings(context) }

    val library: LibraryRepository by lazy {
        LibraryRepository(ShowDatabase.get(context).showDao())
    }

    val backups: BackupFolder by lazy { BackupFolder(context) }

    val backupSchedule: BackupSchedule by lazy { BackupSchedule(appContext) }
}

class ShowTrackerApplication :
    Application(),
    SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // The channel has to exist before anything posts to it, and creating it here rather
        // than at post time means it appears in system settings straight away, so the user
        // can silence it before the first notification rather than after.
        ensureChannel(this)
        RefreshWorker.schedule(this)
    }

    /**
     * Poster loading shares the app's OkHttp client, so there is one connection pool and
     * one set of timeouts rather than two competing ones.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.http }))
            }.build()
}

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
     * The context everything here is built from.
     *
     * Held as `applicationContext` explicitly so nothing can pin an activity, and used by
     * every member below rather than by only the ones that schedule work: the container
     * outlives any activity, so a member that captured the constructor argument instead
     * would defeat the field whose whole purpose is to prevent that.
     */
    val appContext: Context = context.applicationContext

    val http: OkHttpClient by lazy { TmdbClient.defaultClient() }

    val tmdb: TmdbClient by lazy { TmdbClient(http) }

    val settings: Settings by lazy { Settings(appContext) }

    val library: LibraryRepository by lazy {
        LibraryRepository(ShowDatabase.get(appContext).showDao())
    }

    val backups: BackupFolder by lazy { BackupFolder(appContext) }

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

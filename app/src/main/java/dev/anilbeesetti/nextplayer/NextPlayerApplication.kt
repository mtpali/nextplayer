package dev.anilbeesetti.nextplayer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.ApplicationScope
import dev.anilbeesetti.nextplayer.core.data.repository.LegacyHiddenVideoRecovery
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.crash.CrashActivity
import dev.anilbeesetti.nextplayer.crash.GlobalExceptionHandler
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class NextPlayerApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var legacyHiddenVideoRecovery: LegacyHiddenVideoRecovery

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(applicationContext, CrashActivity::class.java))
        applicationScope.launch {
            try {
                legacyHiddenVideoRecovery.restore()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logger.logError("LegacyHiddenVideoRecovery", "Unable to start legacy recovery: $error")
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}

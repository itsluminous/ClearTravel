package com.itsluminous.cleartravel.feature.applock

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.security.lock.AppLockController
import com.itsluminous.cleartravel.core.security.lock.LockTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-locks the UI after the configured background time (ADR-031). Observes the
 * PROCESS lifecycle (not the activity's) so rotations and in-app activity switches
 * never count as "background"; the current [LockTiming] is mirrored from settings so
 * the ON_START decision needs no suspend call. Install once from the shell.
 */
@Singleton
class AppLockLifecycleObserver
    @Inject
    constructor(
        private val lockController: AppLockController,
        private val settingsRepository: SettingsRepository,
    ) : DefaultLifecycleObserver {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        @Volatile
        private var timing: LockTiming = LockTiming.DEFAULT
        private var installed = false

        fun install() {
            if (installed) return
            installed = true
            scope.launch { settingsRepository.lockTiming.collect { timing = it } }
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        override fun onStop(owner: LifecycleOwner) {
            lockController.onBackground()
        }

        override fun onStart(owner: LifecycleOwner) {
            lockController.onForeground(timing)
        }
    }

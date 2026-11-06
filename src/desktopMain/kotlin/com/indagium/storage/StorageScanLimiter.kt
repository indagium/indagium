package com.indagium.storage

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Process-wide bound on retained state from top-level scans. Acquire this before any page permit.
 * Orchestrators must call top-level scans sequentially, never from inside another limited scan;
 * page reads and validation must not acquire this permit.
 */
internal object StorageScanLimiter {
    private val permits = Semaphore(2)

    suspend fun <T> withScan(block: suspend () -> T): T = permits.withPermit { block() }
}

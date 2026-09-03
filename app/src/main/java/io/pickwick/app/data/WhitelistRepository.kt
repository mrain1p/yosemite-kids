package io.pickwick.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The form-managed local config is the single source of truth. */
class WhitelistRepository(private val configStore: ConfigStore) {
    suspend fun load(): Whitelist = withContext(Dispatchers.IO) { configStore.load() }
}

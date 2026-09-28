package com.pupsikcall.app

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal fun createSupabaseRealtimeHttpEngine(): HttpClientEngine = OkHttp.create()

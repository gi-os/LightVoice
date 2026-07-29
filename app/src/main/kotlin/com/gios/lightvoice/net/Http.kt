package com.gios.lightvoice.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** One client for all three APIs. Read timeouts are generous because the phone is
 *  usually on a weak connection and a re-ask costs the user another sentence. */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
}

class VoiceError(message: String) : Exception(message)

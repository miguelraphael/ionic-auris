package com.auris.app.data.remote.music

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicCountryTest {
    @Test
    fun sendsPhilippinesEvenWhenDefaultSerializationOmitsOtherDefaults() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{}"))
            val source = InnerTubeClient(OkHttpClient(), Json, baseUrl = server.url("/").toString().trimEnd('/'))
            assertTrue(source.search("Swim").isSuccess)
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val client = request.getValue("context").jsonObject.getValue("client").jsonObject
            assertEquals("PH", client.getValue("gl").jsonPrimitive.content)
            assertEquals("Swim", request.getValue("query").jsonPrimitive.content)
        }
    }
}

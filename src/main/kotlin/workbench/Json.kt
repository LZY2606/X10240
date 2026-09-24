package workbench

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
val JsonFmt = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

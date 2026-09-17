package com.engage.ads.core

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.putMap(values: Map<String, Any?>) {
    values.forEach { (key, value) -> put(key, value.toJsonValue()) }
}

internal fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { out -> forEach { (k, v) -> if (k is String) out.put(k, v.toJsonValue()) } }
    is Iterable<*> -> JSONArray().also { out -> forEach { out.put(it.toJsonValue()) } }
    is Array<*> -> JSONArray().also { out -> forEach { out.put(it.toJsonValue()) } }
    is Number, is Boolean, is String, is JSONObject, is JSONArray -> this
    else -> toString()
}

internal fun JSONObject.optionalString(name: String): String? =
    (opt(name) as? String)?.takeIf { it.isNotBlank() }

internal fun JSONArray.stringList(): List<String> = buildList {
    for (index in 0 until length()) optString(index).takeIf { it.isNotBlank() }?.let(::add)
}

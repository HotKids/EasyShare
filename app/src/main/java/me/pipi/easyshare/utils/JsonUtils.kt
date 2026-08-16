package me.pipi.easyshare.utils

import kotlinx.serialization.json.Json

val JsonWithUnknownKeys = Json { ignoreUnknownKeys = true }
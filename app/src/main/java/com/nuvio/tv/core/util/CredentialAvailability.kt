package com.nuvio.tv.core.util

// Release builds ship this in place of credentials the maintainer cannot redistribute.
private const val UNLICENSED_PLACEHOLDER = "unlicensed"

fun String.isUsableCredential(): Boolean = isNotBlank() && this != UNLICENSED_PLACEHOLDER

package com.nuvio.tv.core.diagnostics

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Lite-edition stub: without the Sentry SDK there are no breadcrumbs to record,
 * so requests pass through untouched.
 */
class SentryNetworkBreadcrumbInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}

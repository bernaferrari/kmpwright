package com.bernaferrari.kmpwright.core

fun Page.route(urlPattern: String, handler: suspend (Route) -> Unit) {
    route(urlPattern) { route ->
        handler(route)
        RouteHandler.Fallthrough
    }
}

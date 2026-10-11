# Browser farm reuse

Kmpwright provides the lifecycle primitives for a small browser farm, while
the application remains responsible for process lifetime, request admission,
and exporting metrics. `BrowserFarmScheduler` is a library-level scheduler;
it is not a hosted queue or service.

## Safe leases

Build slots with `browserFarmSlot`. Releasing a lease runs the browser reset
workflow (`about:blank`, cookies, storage, headers, routes, and extra pages)
before another caller can acquire that slot.

```kotlin
val farm = BrowserFarmScheduler(
    listOf(
        browserFarmSlot(
            id = "local-1",
            metadata = mapOf("platform" to "linux", "costUsdPerHour" to "0.00"),
            connect = { Browser(context, config) }
        ),
        browserFarmSlot(
            id = "remote-1",
            metadata = mapOf("platform" to "linux", "provider" to "browserbase", "costUsdPerHour" to "0.12"),
            connect = { Browser(context, config) }
        )
    )
)

try {
    farm.withLease(
        selector = BrowserFarmSelector(requiredMetadata = mapOf("platform" to "linux"))
    ) { lease ->
        lease.resource.newPage().goto("https://example.com")
    }
} finally {
    farm.close()
}
```

Use `lease.markBroken("reason")` when a browser or provider session is no
longer safe to reuse. The scheduler closes and quarantines that slot; future
leases skip it. For an idle slot, `farm.quarantine(id, reason)` provides the
same operation without taking a lease.

## Health and cost-aware selection

Run a caller-owned health probe against a particular slot. A failed probe
marks the slot broken and emits health and quarantine events:

```kotlin
if (!farm.healthCheck("remote-1") { browser -> browser.isConnected.value }) {
    // Keep serving from healthy slots, or decide when to provision a replacement.
}

val inexpensive = farm.acquire(
    selector = BrowserFarmSelector(maxCostUsdPerHour = 0.05)
)
inexpensive.release()
```

Capability predicates can combine metadata with dynamic policy:

```kotlin
val mobile = farm.acquire(
    selector = BrowserFarmSelector(
        requiredMetadata = mapOf("platform" to "ios"),
        predicate = { snapshot -> snapshot.metadata["region"] == "eu" }
    )
)
mobile.release()
```

## Observability

`farm.events` is a `SharedFlow<BrowserFarmEvent>` containing slot IDs and
reasons, never browser instances. `farm.metrics()` returns capacity,
availability, busy/quarantined counts, acquisition/release totals, failures,
and cumulative wait time. Applications can collect the flow and periodically
export the snapshot to their existing metrics system.

The scheduler does not create a background supervisor or automatically
replace quarantined slots. That policy belongs at the application boundary,
where provider credentials, budgets, and retry limits are available.

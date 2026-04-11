# Fix Model Download — Implementation Plan

## Problem
Clicking "Download" on a model does nothing. No progress bar, no error, no notification.

## Root Cause Analysis

### Bug 1 (Critical): `ModelRegistry.getById()` does not exist
**File:** `ModelDownloadWorker.kt:54`
```kotlin
val descriptor = ModelRegistry.getById(modelId) ?: return Result.failure()
```
`ModelRegistry` only has `getByRawId(raw: String)`. The worker silently returns `Result.failure()` on every download attempt.

### Bug 2 (Critical): Missing foreground service type for WorkManager
**File:** `AndroidManifest.xml`
The manifest declares `FOREGROUND_SERVICE_DATA_SYNC` permission but there is **no `<service>` declaration for WorkManager's foreground service** with `android:foregroundServiceType="dataSync"`. On Android 14+ this crashes or silently fails when `setForeground()` is called.

### Bug 3 (Medium): `ForegroundInfo` missing service type parameter
**File:** `ModelDownloadWorker.kt:126`
```kotlin
return ForegroundInfo(NOTIFICATION_ID, notification)
```
On API 34+ (Android 14), `ForegroundInfo` constructor requires a third parameter: `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`.

### Bug 4 (Medium): No HTTP timeouts or User-Agent
**File:** `AppModule.kt`
The Ktor `HttpClient` has no timeouts and no User-Agent header. HuggingFace CDN may reject or throttle requests without a proper User-Agent, and connections can hang indefinitely.

### Bug 5 (Low): No user-visible error feedback
Download failures are only logged via Timber. The UI shows no error snackbar or toast — the user just sees nothing happen.

---

## Fix Plan

### Step 1: Fix `ModelRegistry.getById` → `getByRawId`
**File:** `ModelDownloadWorker.kt`
```
Line 54: ModelRegistry.getById(modelId)  →  ModelRegistry.getByRawId(modelId)
```

### Step 2: Add WorkManager foreground service to manifest
**File:** `AndroidManifest.xml`
Add inside `<application>`:
```xml
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

### Step 3: Pass service type to `ForegroundInfo`
**File:** `ModelDownloadWorker.kt`
```kotlin
import android.content.pm.ServiceInfo

return ForegroundInfo(
    NOTIFICATION_ID,
    notification,
    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
)
```

### Step 4: Add HTTP timeouts and User-Agent
**File:** `AppModule.kt`
```kotlin
fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            followRedirects(true)
            retryOnConnectionFailure(true)
            connectTimeout(30, TimeUnit.SECONDS)
            readTimeout(60, TimeUnit.SECONDS)
        }
    }
    install(io.ktor.client.plugins.HttpTimeout) {
        requestTimeoutMillis = 600_000  // 10 min for large models
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
    }
    install(io.ktor.client.plugins.DefaultRequest) {
        header("User-Agent", "Neuron/0.1.0 (Android)")
    }
}
```

### Step 5: Surface download errors to UI
**File:** `ModelManagerViewModel.kt` — Add error state:
```kotlin
data class ModelManagerUiState(
    ...
    val downloadError: String? = null
)
```
In the `downloadModel()` flow collector, handle `WorkInfo.State.FAILED`:
```kotlin
WorkInfo.State.FAILED -> {
    _uiState.update {
        it.copy(
            downloads = it.downloads - modelId,
            downloadError = "Download failed: ${modelId}"
        )
    }
}
```
**File:** `ModelManagerScreen.kt` — Show error snackbar when `downloadError` is set.

### Step 6: Add `ModelRepository.getDescriptorById` fallback to `getByRawId`
**File:** `ModelRepository.kt`
This was already changed to `getByRawId` in a previous commit but the download worker still uses `ModelRegistry` directly. Align it.

---

## Files to Change

| # | File | Change |
|---|------|--------|
| 1 | `ModelDownloadWorker.kt` | Fix `getById` → `getByRawId`, add `ServiceInfo` type to `ForegroundInfo` |
| 2 | `AndroidManifest.xml` | Add WorkManager foreground service declaration |
| 3 | `AppModule.kt` | Add HTTP timeouts and User-Agent header |
| 4 | `ModelManagerViewModel.kt` | Add `downloadError` state, handle `FAILED` state |
| 5 | `ModelManagerScreen.kt` | Show error snackbar for download failures |

## Testing
1. Click Download on any registry model → should show progress bar + notification
2. Toggle airplane mode mid-download → should show error, not hang
3. Download a large model (4GB+) → should not timeout
4. Cancel download → should stop and clean up

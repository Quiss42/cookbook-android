package de.ex_parrot.cookbook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.data.HttpException
import java.io.IOException
import javax.net.ssl.SSLException

/** Simple state holder for data loaded from the server. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T, val refreshing: Boolean = false) : Loadable<T>
    data class Failed(val error: Throwable) : Loadable<Nothing>
}

@Composable
fun errorMessage(error: Throwable): String = when {
    error is HttpException && error.code == 401 -> stringResource(R.string.error_unauthorized)
    error is HttpException && error.code == 404 -> stringResource(R.string.error_not_found)
    error is HttpException -> stringResource(R.string.error_http, error.code)
    error is SSLException -> stringResource(R.string.error_certificate)
    error is IOException -> stringResource(R.string.error_network)
    else -> error.message ?: error.toString()
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorView(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Text(errorMessage(error), textAlign = TextAlign.Center)
        ErrorDetails(error)
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
fun EmptyView(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Recipe photo loaded with the session's authenticated image loader. */
@Composable
fun RecipeImage(
    url: String?,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** Shown if [url] fails, e.g. the stored thumbnail when offline. */
    fallbackUrl: String? = null,
) {
    val placeholder = @Composable {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Restaurant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
            )
        }
    }
    if (url == null) {
        Box(modifier) { placeholder() }
        return
    }
    SubcomposeAsyncImage(
        model = url,
        imageLoader = imageLoader,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
        loading = { placeholder() },
        error = {
            if (fallbackUrl != null) {
                SubcomposeAsyncImage(
                    model = fallbackUrl,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                    loading = { placeholder() },
                    error = { placeholder() },
                )
            } else {
                placeholder()
            }
        },
    )
}

/** Shown while the data comes from the phone because the server is unreachable. */
@Composable
fun OfflineBanner(session: de.ex_parrot.cookbook.data.Session, modifier: Modifier = Modifier) {
    val offline by session.offlineStore.offline.collectAsStateWithLifecycle()
    val status by session.offlineStore.status.collectAsStateWithLifecycle()
    val pendingChanges by session.offlineStore.pending.changes.collectAsStateWithLifecycle()
    val pendingCount = pendingChanges.size
    if (!offline && pendingCount == 0) return
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (offline) Icons.Outlined.CloudOff else Icons.Outlined.CloudUpload,
            null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        val parts = buildList {
            if (offline) {
                add(
                    status.lastSync?.let { stringResource(R.string.offline_banner_since, formatDateTime(it)) }
                        ?: stringResource(R.string.offline_banner),
                )
            }
            if (pendingCount > 0) {
                add(
                    if (!offline && status.syncing) {
                        androidx.compose.ui.res.pluralStringResource(R.plurals.pending_uploading, pendingCount, pendingCount)
                    } else {
                        androidx.compose.ui.res.pluralStringResource(R.plurals.pending_waiting, pendingCount, pendingCount)
                    },
                )
            }
        }
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

fun formatDateTime(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(millis))

/** Technical details, shown small, to help with bug reports. */
@Composable
fun ErrorDetails(error: Throwable) {
    val cause = generateSequence(error) { it.cause }.last()
    val text = buildString {
        append(error.javaClass.simpleName)
        error.message?.let { append(": ").append(it.take(200)) }
        if (cause !== error) {
            append("\n").append(cause.javaClass.simpleName)
            cause.message?.let { append(": ").append(it.take(200)) }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package de.ex_parrot.cookbook.ui.edit

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.data.CloudEntry
import de.ex_parrot.cookbook.data.ImageTools
import de.ex_parrot.cookbook.data.NextcloudFiles
import de.ex_parrot.cookbook.data.NoImageException
import de.ex_parrot.cookbook.ui.components.ErrorDetails
import de.ex_parrot.cookbook.ui.components.ErrorView
import de.ex_parrot.cookbook.ui.components.Loadable
import de.ex_parrot.cookbook.ui.components.LoadingView
import de.ex_parrot.cookbook.ui.components.RecipeImage
import de.ex_parrot.cookbook.ui.components.errorMessage
import de.ex_parrot.cookbook.ui.importer.extractUrl
import de.ex_parrot.cookbook.ui.importer.normalizeWebUrl
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CancellationException

/** Image preview with the four ways to choose a picture. */
@Composable
fun ImageSection(vm: EditRecipeViewModel, imageLoader: ImageLoader) {
    val context = LocalContext.current
    var showCloud by remember { mutableStateOf(false) }
    var showWeb by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::useLocalImage)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        if (success && uri != null) vm.useLocalImage(uri)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when (val selection = vm.imageSelection) {
                ImageSelection.Keep -> RecipeImage(vm.originalImageUrl(), imageLoader, Modifier.fillMaxSize())
                ImageSelection.Removed -> RecipeImage(null, imageLoader, Modifier.fillMaxSize())
                is ImageSelection.Cloud -> RecipeImage(
                    selection.fileId?.let { vm.files.previewUrl(it, 1024) },
                    imageLoader,
                    Modifier.fillMaxSize(),
                )
                is ImageSelection.Web -> RecipeImage(null, imageLoader, Modifier.fillMaxSize())
                is ImageSelection.Local -> AsyncImage(
                    model = selection.file,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (vm.imageBusy) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageChip(Icons.Outlined.Cloud, stringResource(R.string.image_from_nextcloud)) { showCloud = true }
            ImageChip(Icons.Outlined.Link, stringResource(R.string.image_from_web)) { showWeb = true }
            ImageChip(Icons.Outlined.PhotoCamera, stringResource(R.string.image_from_camera)) {
                val file = ImageTools.newCameraFile(context)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                cameraUri = uri
                camera.launch(uri)
            }
            ImageChip(Icons.Outlined.PhotoLibrary, stringResource(R.string.image_from_gallery)) {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (vm.imageSelection != ImageSelection.Keep) {
                ImageChip(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.image_undo)) { vm.resetImage() }
            }
            if (vm.imageSelection != ImageSelection.Removed && (vm.hasOriginalImage || vm.imageSelection != ImageSelection.Keep)) {
                ImageChip(Icons.Outlined.Delete, stringResource(R.string.image_remove)) { vm.removeImage() }
            }
        }

        (vm.imageSelection as? ImageSelection.Web)?.let { web ->
            Text(
                stringResource(R.string.image_web_later, web.url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        vm.imageError?.let { error ->
            Text(
                if (error is NoImageException) stringResource(R.string.image_none_found) else errorMessage(error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            if (error !is NoImageException) ErrorDetails(error)
        }
    }

    if (showCloud) {
        CloudImagePicker(
            files = vm.files,
            imageLoader = imageLoader,
            onPick = { vm.selectCloudImage(it); showCloud = false },
            onDismiss = { showCloud = false },
        )
    }
    if (showWeb) {
        WebImageDialog(
            // The recipe's source page usually has a fitting preview image.
            initialUrl = vm.url.trim(),
            onConfirm = { vm.useWebImage(it); showWeb = false },
            onDismiss = { showWeb = false },
        )
    }
}

@Composable
private fun ImageChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
}

@Composable
private fun WebImageDialog(initialUrl: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.image_from_web)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.image_web_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.import_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        IconButton(onClick = {
                        clipboardScope.launch {
                            clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)?.text?.toString()?.let { url = extractUrl(it) ?: it }
                        }
                    }) {
                            Icon(Icons.Outlined.ContentPaste, stringResource(R.string.paste))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(normalizeWebUrl(url)) }, enabled = url.isNotBlank()) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Full-screen browser for folders and images in the user's Nextcloud. */
@Composable
private fun CloudImagePicker(
    files: NextcloudFiles,
    imageLoader: ImageLoader,
    onPick: (CloudEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val stack = remember { mutableStateListOf("/") }
    val path = stack.last()
    var state by remember { mutableStateOf<Loadable<List<CloudEntry>>>(Loadable.Loading) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(path, reload) {
        state = Loadable.Loading
        state = try {
            Loadable.Ready(files.list(path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Loadable.Failed(e)
        }
    }

    Dialog(
        onDismissRequest = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (path == "/") stringResource(R.string.image_nextcloud_files) else path.substringAfterLast('/'),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (stack.size > 1) {
                            IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                            }
                        } else {
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) }
                        }
                    },
                )
            },
        ) { padding ->
            when (val s = state) {
                Loadable.Loading -> LoadingView(Modifier.padding(padding))
                is Loadable.Failed -> ErrorView(s.error, { reload++ }, Modifier.padding(padding))
                is Loadable.Ready -> if (s.value.isEmpty()) {
                    Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.image_folder_empty), textAlign = TextAlign.Center)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    ) {
                        items(s.value, key = { it.path }) { entry ->
                            CloudTile(entry, files, imageLoader) {
                                if (entry.isFolder) stack.add(entry.path) else onPick(entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudTile(entry: CloudEntry, files: NextcloudFiles, imageLoader: ImageLoader, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.isFolder) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                RecipeImage(entry.fileId?.let { files.previewUrl(it) }, imageLoader, Modifier.fillMaxSize())
            }
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

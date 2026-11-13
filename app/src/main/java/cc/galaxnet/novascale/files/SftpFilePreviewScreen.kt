/*
 * NovaScale for Android
 * Copyright (C) 2026 NovaScale contributors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package cc.galaxnet.novascale.files

import cc.galaxnet.novascale.ui.NovaPrimaryButton

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import cc.galaxnet.novascale.BuildConfig
import cc.galaxnet.novascale.R
import cc.galaxnet.novascale.ui.NovaGroupedCard
import cc.galaxnet.novascale.ui.NovaSpacing
import cc.galaxnet.novascale.ui.NovaTheme
import cc.galaxnet.novascale.ui.UiText
import cc.galaxnet.novascale.ui.asString
import cc.galaxnet.novascale.ui.uiText
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun SftpFilePreviewScreen(
    preview: SftpFilePreview,
    transfer: SftpTransfer?,
    onClose: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    var externalOpenError by remember(preview.localPath) { mutableStateOf<UiText?>(null) }
    val localFile = preview.localPath?.let(::File)
    val externalChooserTitle = stringResource(
        R.string.open_named_file,
        preview.entry.name,
    )

    fun openExternally(file: File) {
        externalOpenError = runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.files",
                file,
            )
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, previewMimeType(preview.entry.name, preview.kind))
                clipData = ClipData.newRawUri(preview.entry.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(
                    viewIntent,
                    externalChooserTitle,
                ),
            )
        }.exceptionOrNull()?.let { error ->
            if (error is ActivityNotFoundException) {
                uiText(R.string.external_open_unavailable)
            } else {
                uiText(R.string.external_open_failed)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = NovaTheme.colors.backgroundSecondary, tonalElevation = 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = NovaSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back_to_files),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        preview.entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        preview.entry.size?.let(::formatBytes)
                            ?: stringResource(R.string.remote_file_preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = NovaTheme.colors.textSecondary,
                    )
                }
                if (localFile?.isFile == true) {
                    IconButton(onClick = { openExternally(localFile) }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = stringResource(R.string.cd_open_external),
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = NovaTheme.colors.borderSubtle)

        when {
            preview.error != null -> PreviewFailure(
                preview = preview,
            message = preview.error,
                onRetry = onRetry,
            )
            localFile == null || !localFile.isFile -> PreviewDownload(
                preview = preview,
                transfer = transfer,
                onCancel = onCancel,
            )
            else -> when (preview.kind) {
                SftpPreviewKind.IMAGE -> ImageFilePreview(
                    file = localFile,
                    onOpenExternally = { openExternally(localFile) },
                )
                SftpPreviewKind.VIDEO -> MediaFilePreview(
                    file = localFile,
                    video = true,
                    onOpenExternally = { openExternally(localFile) },
                )
                SftpPreviewKind.AUDIO -> MediaFilePreview(
                    file = localFile,
                    video = false,
                    onOpenExternally = { openExternally(localFile) },
                )
                SftpPreviewKind.PDF -> PdfFilePreview(
                    file = localFile,
                    onOpenExternally = { openExternally(localFile) },
                )
                SftpPreviewKind.EXTERNAL -> ExternalFilePreview(
                    preview = preview,
                    error = externalOpenError,
                    onOpenExternally = { openExternally(localFile) },
                )
            }
        }
    }
}

@Composable
private fun PreviewDownload(
    preview: SftpFilePreview,
    transfer: SftpTransfer?,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(NovaSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = preview.kind.previewIcon(),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            preview.entry.name,
            modifier = Modifier.padding(top = NovaSpacing.lg),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(
                if (transfer?.canceling == true) R.string.preview_canceling
                else R.string.preview_downloading,
            ),
            modifier = Modifier.padding(top = NovaSpacing.sm),
            style = MaterialTheme.typography.bodyMedium,
            color = NovaTheme.colors.textSecondary,
        )
        transfer?.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NovaSpacing.lg),
            )
            Text(
                stringResource(
                    R.string.preview_progress,
                    (progress * 100).roundToInt(),
                    formatBytes(transfer.transferredBytes),
                ),
                modifier = Modifier.padding(top = NovaSpacing.sm),
                style = MaterialTheme.typography.bodySmall,
                color = NovaTheme.colors.textSecondary,
            )
        } ?: CircularProgressIndicator(modifier = Modifier.padding(top = NovaSpacing.lg))
        TextButton(
            onClick = onCancel,
            enabled = transfer?.canceling != true,
            modifier = Modifier.padding(top = NovaSpacing.md),
        ) {
            Text(
                stringResource(
                    if (transfer?.canceling == true) R.string.transfer_canceling
                    else R.string.action_cancel,
                ),
            )
        }
    }
}

@Composable
private fun PreviewFailure(
    preview: SftpFilePreview,
    message: UiText,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(NovaSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.BrokenImage,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(R.string.preview_unavailable),
            modifier = Modifier.padding(top = NovaSpacing.md),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            message.asString(),
            modifier = Modifier.padding(top = NovaSpacing.sm),
            style = MaterialTheme.typography.bodyMedium,
            color = NovaTheme.colors.textSecondary,
        )
        NovaPrimaryButton(onClick = onRetry, modifier = Modifier.padding(top = NovaSpacing.lg)) {
            Text(stringResource(R.string.try_again))
        }
    }
}

private sealed interface LoadedResource<out T> {
    data object Loading : LoadedResource<Nothing>
    data class Ready<T>(val value: T) : LoadedResource<T>
    data class Failed(val message: UiText) : LoadedResource<Nothing>
}

@Composable
private fun ImageFilePreview(file: File, onOpenExternally: () -> Unit) {
    val resource by produceState<LoadedResource<Drawable>>(
        initialValue = LoadedResource.Loading,
        key1 = file.absolutePath,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val size = info.size
                    val longest = maxOf(size.width, size.height)
                    if (longest > MAX_IMAGE_DIMENSION) {
                        val factor = MAX_IMAGE_DIMENSION.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (size.width * factor).roundToInt().coerceAtLeast(1),
                            (size.height * factor).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            }.fold(
                onSuccess = { LoadedResource.Ready(it) },
                onFailure = { LoadedResource.Failed(uiText(R.string.image_decode_failed)) },
            )
        }
    }
    when (val current = resource) {
        LoadedResource.Loading -> PreviewRendererLoading(uiText(R.string.image_decoding))
        is LoadedResource.Failed -> RendererFailure(current.message, onOpenExternally)
        is LoadedResource.Ready -> ZoomableImage(current.value)
    }
}

@Composable
private fun ZoomableImage(drawable: Drawable) {
    var scale by remember(drawable) { mutableFloatStateOf(1f) }
    var translationX by remember(drawable) { mutableFloatStateOf(0f) }
    var translationY by remember(drawable) { mutableFloatStateOf(0f) }
    DisposableEffect(drawable) {
        (drawable as? Animatable)?.start()
        onDispose { (drawable as? Animatable)?.stop() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .pointerInput(drawable) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 6f)
                    scale = nextScale
                    if (nextScale == 1f) {
                        translationX = 0f
                        translationY = 0f
                    } else {
                        translationX += pan.x
                        translationY += pan.y
                    }
                }
            },
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(AndroidColor.BLACK)
                }
            },
            update = { it.setImageDrawable(drawable) },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = translationX,
                    translationY = translationY,
                ),
        )
    }
}

@Composable
private fun MediaFilePreview(
    file: File,
    video: Boolean,
    onOpenExternally: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(file.absolutePath) { ExoPlayer.Builder(context).build() }
    var playbackError by remember(file.absolutePath) { mutableStateOf<UiText?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = uiText(R.string.media_play_failed)
            }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    playbackError?.let {
        RendererFailure(it, onOpenExternally)
        return
    }
    if (video) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    keepScreenOn = true
                    this.player = player
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(NovaSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AudioFile,
                contentDescription = null,
                modifier = Modifier.size(88.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                file.name,
                modifier = Modifier.padding(top = NovaSpacing.lg),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            AudioPlayerControls(
                player = player,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NovaSpacing.lg),
            )
        }
    }
}

@Composable
private fun AudioPlayerControls(player: ExoPlayer, modifier: Modifier = Modifier) {
    var positionMillis by remember(player) { mutableStateOf(0L) }
    var durationMillis by remember(player) { mutableStateOf(0L) }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var isSeeking by remember(player) { mutableStateOf(false) }
    var seekMillis by remember(player) { mutableFloatStateOf(0f) }

    LaunchedEffect(player) {
        while (isActive) {
            if (!isSeeking) positionMillis = player.currentPosition.coerceAtLeast(0)
            durationMillis = player.duration.takeIf { it > 0 } ?: 0
            isPlaying = player.isPlaying
            delay(200)
        }
    }

    NovaGroupedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(NovaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
        ) {
            Slider(
                value = if (isSeeking) seekMillis else positionMillis.toFloat(),
                onValueChange = {
                    isSeeking = true
                    seekMillis = it
                },
                onValueChangeFinished = {
                    player.seekTo(seekMillis.toLong())
                    positionMillis = seekMillis.toLong()
                    isSeeking = false
                },
                valueRange = 0f..durationMillis.coerceAtLeast(1).toFloat(),
                enabled = durationMillis > 0,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatMediaTime(if (isSeeking) seekMillis.toLong() else positionMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = NovaTheme.colors.textSecondary,
                )
                Text(
                    formatMediaTime(durationMillis),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = NovaTheme.colors.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                    },
                ) {
                    Icon(
                        Icons.Outlined.Replay10,
                        contentDescription = stringResource(R.string.cd_back_ten_seconds),
                    )
                }
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    IconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.cd_pause else R.string.cd_play,
                            ),
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        val end = durationMillis.takeIf { it > 0 } ?: Long.MAX_VALUE
                        player.seekTo((player.currentPosition + 10_000).coerceAtMost(end))
                    },
                ) {
                    Icon(
                        Icons.Outlined.Forward10,
                        contentDescription = stringResource(R.string.cd_forward_ten_seconds),
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfFilePreview(file: File, onOpenExternally: () -> Unit) {
    val documentResource by produceState<LoadedResource<PdfPreviewDocument>>(
        initialValue = LoadedResource.Loading,
        key1 = file.absolutePath,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { PdfPreviewDocument(file) }.fold(
                onSuccess = { LoadedResource.Ready(it) },
                onFailure = { LoadedResource.Failed(uiText(R.string.pdf_open_failed)) },
            )
        }
    }
    when (val current = documentResource) {
        LoadedResource.Loading -> PreviewRendererLoading(uiText(R.string.pdf_opening))
        is LoadedResource.Failed -> RendererFailure(current.message, onOpenExternally)
        is LoadedResource.Ready -> {
            val document = current.value
            DisposableEffect(document) {
                onDispose { document.close() }
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF46474C)),
            ) {
                val density = LocalDensity.current
                val targetWidth = with(density) {
                    maxWidth.toPx().roundToInt().coerceIn(1, MAX_PDF_DIMENSION)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(NovaSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(NovaSpacing.sm),
                ) {
                    items((0 until document.pageCount).toList(), key = { it }) { pageIndex ->
                        PdfPage(document, pageIndex, targetWidth)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(document: PdfPreviewDocument, pageIndex: Int, targetWidth: Int) {
    val pageResource by produceState<LoadedResource<Bitmap>>(
        initialValue = LoadedResource.Loading,
        key1 = document,
        key2 = pageIndex,
        key3 = targetWidth,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { document.render(pageIndex, targetWidth) }.fold(
                onSuccess = { LoadedResource.Ready(it) },
                onFailure = {
                    LoadedResource.Failed(
                        uiText(R.string.pdf_page_render_failed, pageIndex + 1),
                    )
                },
            )
        }
    }
    when (val current = pageResource) {
        LoadedResource.Loading -> Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp),
            color = Color.White,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is LoadedResource.Failed -> NovaGroupedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                current.message.asString(),
                modifier = Modifier.padding(NovaSpacing.lg),
                color = MaterialTheme.colorScheme.error,
            )
        }
        is LoadedResource.Ready -> {
            val bitmap = current.value
            DisposableEffect(bitmap) {
                onDispose { if (!bitmap.isRecycled) bitmap.recycle() }
            }
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_pdf_page, pageIndex + 1),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

private class PdfPreviewDocument(file: File) : Closeable {
    private val lock = Any()
    private val renderer = PdfRenderer(
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
    )
    val pageCount: Int = renderer.pageCount

    fun render(pageIndex: Int, requestedWidth: Int): Bitmap = synchronized(lock) {
        renderer.openPage(pageIndex).use { page ->
            var width = requestedWidth.coerceIn(1, MAX_PDF_DIMENSION)
            var height = (page.height.toDouble() * width / page.width.toDouble())
                .roundToInt()
                .coerceAtLeast(1)
            if (height > MAX_PDF_DIMENSION) {
                val factor = MAX_PDF_DIMENSION.toDouble() / height.toDouble()
                width = (width * factor).roundToInt().coerceAtLeast(1)
                height = MAX_PDF_DIMENSION
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    override fun close() {
        synchronized(lock) { runCatching { renderer.close() } }
    }
}

@Composable
private fun ExternalFilePreview(
    preview: SftpFilePreview,
    error: UiText?,
    onOpenExternally: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(NovaSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.FileOpen,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            preview.entry.name,
            modifier = Modifier.padding(top = NovaSpacing.lg),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.external_preview_description),
            modifier = Modifier.padding(top = NovaSpacing.sm),
            style = MaterialTheme.typography.bodyMedium,
            color = NovaTheme.colors.textSecondary,
        )
        error?.let {
            Text(
                it.asString(),
                modifier = Modifier.padding(top = NovaSpacing.md),
                color = MaterialTheme.colorScheme.error,
            )
        }
        NovaPrimaryButton(onClick = onOpenExternally, modifier = Modifier.padding(top = NovaSpacing.lg)) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
            Text(
                stringResource(R.string.open_with_another_app),
                modifier = Modifier.padding(start = NovaSpacing.sm),
            )
        }
    }
}

@Composable
private fun PreviewRendererLoading(label: UiText) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                label.asString(),
                modifier = Modifier.padding(top = NovaSpacing.md),
                color = NovaTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun RendererFailure(message: UiText, onOpenExternally: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(NovaSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.BrokenImage,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            message.asString(),
            modifier = Modifier.padding(top = NovaSpacing.md),
            style = MaterialTheme.typography.bodyLarge,
        )
        NovaPrimaryButton(onClick = onOpenExternally, modifier = Modifier.padding(top = NovaSpacing.lg)) {
            Text(stringResource(R.string.try_another_app))
        }
    }
}

private fun SftpPreviewKind.previewIcon() = when (this) {
    SftpPreviewKind.IMAGE -> Icons.Outlined.Image
    SftpPreviewKind.VIDEO -> Icons.Outlined.Movie
    SftpPreviewKind.AUDIO -> Icons.Outlined.AudioFile
    SftpPreviewKind.PDF -> Icons.Outlined.Description
    SftpPreviewKind.EXTERNAL -> Icons.Outlined.FileOpen
}

private const val MAX_IMAGE_DIMENSION = 4_096
private const val MAX_PDF_DIMENSION = 2_048

private fun formatMediaTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

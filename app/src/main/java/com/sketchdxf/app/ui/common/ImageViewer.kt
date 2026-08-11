package com.sketchdxf.app.ui.common

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * Full-screen image viewer. Swipe left/right when [paths] has several images; Share sends the
 * current one and does NOT close (the user closes with the X). Dismiss returns to the caller.
 */
@Composable
fun ImageViewerDialog(paths: List<String>, onDismiss: () -> Unit, startIndex: Int = 0) {
    if (paths.isEmpty()) { onDismiss(); return }
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, paths.size - 1), pageCount = { paths.size })

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF000000))) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                Text(if (paths.size > 1) "${pager.currentPage + 1} / ${paths.size}" else "", color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val path = paths.getOrNull(pager.currentPage) ?: return@IconButton
                    val f = File(path); if (!f.exists()) return@IconButton
                    val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.provider", f) }.getOrNull() ?: return@IconButton
                    val intent = Intent(Intent.ACTION_SEND).apply { type = "image/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    runCatching { context.startActivity(Intent.createChooser(intent, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Icon(Icons.Filled.Share, "Share", tint = Color.White) }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                val bmp: ImageBitmap? = remember(paths[page]) {
                    runCatching {
                        val bytes = File(paths[page]).readBytes()
                        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                        var s = 1; while (opt.outWidth / s > 2000 || opt.outHeight / s > 2000) s *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
                    }.getOrNull()
                }
                var scale by remember(paths[page]) { mutableStateOf(1f) }
                var offX by remember(paths[page]) { mutableStateOf(0f) }
                var offY by remember(paths[page]) { mutableStateOf(0f) }
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(paths[page]) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                if (scale > 1f) {
                                    val limit = size.width * (scale - 1f) / 2f
                                    offX = (offX + pan.x).coerceIn(-limit, limit)
                                    offY = (offY + pan.y).coerceIn(-limit, limit)
                                } else { offX = 0f; offY = 0f }
                            }
                        }
                        .pointerInput(paths[page]) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1f) { scale = 1f; offX = 0f; offY = 0f } else scale = 2.5f
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (bmp != null) Image(
                        bmp, contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer(
                            scaleX = scale, scaleY = scale, translationX = offX, translationY = offY
                        ),
                        contentScale = ContentScale.Fit
                    )
                    else Text("Could not open image", color = Color.White)
                }
            }
        }
    }
}

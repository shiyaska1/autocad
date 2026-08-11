package com.sketchdxf.app.ink

import androidx.compose.ui.geometry.Offset
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around ML Kit Digital Ink, fixed to English — dimensions/labels on the sketch
 * canvas are numbers and short words, so there is no language picker here.
 *
 * Works fully offline once the model has been downloaded once (~20 MB, needs internet that one time).
 */
class InkRecognizer {
    private val model: DigitalInkRecognitionModel =
        DigitalInkRecognitionModel.builder(
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")!!
        ).build()

    private val recognizer: DigitalInkRecognizer =
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())

    private val remote = RemoteModelManager.getInstance()

    /** Downloads the recognition model if needed. Returns true when ready to recognize. */
    suspend fun ensureReady(): Boolean = withContext(Dispatchers.IO) {
        if (runCatching { Tasks.await(remote.isModelDownloaded(model)) }.getOrDefault(false)) {
            return@withContext true
        }
        runCatching {
            Tasks.await(remote.download(model, DownloadConditions.Builder().build()))
        }.isSuccess
    }

    /** Recognizes the best text candidate for the given strokes (empty on failure). */
    suspend fun recognize(strokes: List<List<Offset>>): String = withContext(Dispatchers.IO) {
        val usable = strokes.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return@withContext ""
        val ink = Ink.builder().apply {
            usable.forEach { pts ->
                val stroke = Ink.Stroke.builder()
                pts.forEach { p -> stroke.addPoint(Ink.Point.create(p.x, p.y)) }
                addStroke(stroke.build())
            }
        }.build()
        runCatching {
            val result = Tasks.await(recognizer.recognize(ink))
            result.candidates.firstOrNull()?.text ?: ""
        }.getOrDefault("")
    }

    fun close() { runCatching { recognizer.close() } }
}

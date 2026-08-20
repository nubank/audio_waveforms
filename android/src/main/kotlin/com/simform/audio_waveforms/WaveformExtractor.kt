package com.simform.audio_waveforms

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import androidx.core.net.toUri

/**
 * WaveformExtractor handles the process of extracting amplitude data from audio files
 * to generate waveform visualizations.
 * 
 * This class uses the Android MediaCodec API to decode audio files and extract
 * RMS (Root Mean Square) values at regular intervals, which represent the amplitude
 * of the audio signal. These values can then be used to draw waveform visualizations.
 * 
 * The extractor supports various audio formats and bit depths (8-bit, 16-bit, and 32-bit)
 * and handles both mono and stereo audio channels.
 */
class WaveformExtractor(
    /** Path to the audio file to analyze */
    private val path: String,
    /** Number of waveform data points to generate */
    private val expectedPoints: Int,
    /** Unique identifier for this extraction process */
    private val key: String,
    /** Method channel for sending progress updates to Flutter */
    private val methodChannel: MethodChannel,
    /** Result callback for sending the final result back to Flutter */
    private val result: MethodChannel.Result,
    /** Application context for accessing content URIs */
    private val context: Context,
) {
    /** MediaCodec for decoding audio data */
    private var decoder: MediaCodec? = null
    /** MediaExtractor for reading audio tracks from the file */
    private var extractor: MediaExtractor? = null
    /** Duration of the audio file in milliseconds */
    private var durationMillis = 0L
    /** Current extraction progress (0.0 to 1.0) */
    private var progress = 0F
    /** Number of processed chunks */
    private var currentProgress = 0F

    /** Latch for synchronizing completion of the extraction process */
    private val finishCount = CountDownLatch(1)
    /** Flag indicating end of input data */
    private var inputEof = false
    /** Sample rate of the audio in Hz */
    private var sampleRate = 0
    /** Number of audio channels (1=mono, 2=stereo) */
    private var channels = 1
    /** Bit depth of the audio (8, 16, or 32) */
    private var pcmEncodingBit = 16
    /** Total number of audio samples */
    private var totalSamples = 0L
    /** Number of audio samples per waveform data point */
    private var perSamplePoints = 0L
    /** Flag to prevent submitting multiple results */
    private val isReplySubmitted = AtomicBoolean(false)
    /** Flag to keep the teardown idempotent */
    private val isStopped = AtomicBoolean(false)

    /**
     * The codec delivers its callbacks on its own looper thread, while
     * [stop] can also be reached from the platform thread through
     * `stopExtraction`. A `MethodChannel.Result` has to be answered on the
     * platform thread, so every reply is posted here.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Retrieves the audio format from the given media file
     *
     * This method:
     * 1. Creates a MediaExtractor to read the file
     * 2. Finds the first audio track in the file
     * 3. Retrieves and selects that track
     * 4. Extracts the audio duration
     *
     * @param path Path to the audio file (content URI format)
     * @return MediaFormat of the audio track, or null if no audio track is found
     */
    private fun getFormat(path: String): MediaFormat? {
        if (path.isEmpty()) {
            return null
        }
        val mediaExtractor = MediaExtractor()
        this.extractor = mediaExtractor
        val uri = path.toUri()
        mediaExtractor.setDataSource(context, uri, null)
        val trackCount = mediaExtractor.trackCount
        repeat(trackCount) {
            val format = mediaExtractor.getTrackFormat(it)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.contains("audio")) {
                durationMillis = format.getLong(MediaFormat.KEY_DURATION) / 1000
                mediaExtractor.selectTrack(it)
                return format
            }
        }
        return null
    }

    /**
     * Starts the decoding and waveform extraction process
     * 
     * This method initializes the MediaCodec decoder with appropriate
     * callbacks to process audio frames. It handles:
     * 1. Setting up the decoder with the proper format
     * 2. Processing input buffers from the MediaExtractor
     * 3. Processing decoded PCM audio data in output buffers
     * 4. Calculating RMS values for waveform visualization
     * 5. Reporting progress over the method channel
     */
    fun startDecode() {
        try {
            val format = getFormat(path) ?: error("No audio format found")
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("No MIME type found")
            decoder = MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        if (inputEof || index < 0) return
                        val extractor = extractor ?: return
                        codec.getInputBuffer(index)?.let { buf ->
                            val size = extractor.readSampleData(buf, 0)
                            val sampleTime = extractor.sampleTime
                            if (size > 0 && sampleTime >= 0) {
                                try {
                                    codec.queueInputBuffer(index, 0, size, sampleTime, 0)
                                    extractor.advance()
                                } catch (e: Exception) {
                                    inputEof = true
                                    submitError(e.message, "Invalid input buffer.")
                                }
                            } else {
                                codec.queueInputBuffer(
                                    index,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEof = true
                            }
                        }
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncodingBit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                                    AudioFormat.ENCODING_PCM_16BIT -> 16
                                    AudioFormat.ENCODING_PCM_8BIT -> 8
                                    AudioFormat.ENCODING_PCM_FLOAT -> 32
                                    else -> 16
                                }
                            } else {
                                16
                            }
                        } else {
                            16
                        }
                        totalSamples = (sampleRate.toLong() * durationMillis) / 1000
                        // A bucket of zero samples would divide by zero on
                        // every point when more points are asked for than the
                        // clip has samples.
                        perSamplePoints = max(1L, totalSamples / expectedPoints)
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        submitError(
                            e.message,
                            "An error is thrown while decoding the audio file"
                        )
                        stop()
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        if (index < 0 || decoder == null) return
                        
                        try {
                            if (info.size > 0) {
                                codec.getOutputBuffer(index)?.let { buf ->
                                    try {
                                        val size = info.size
                                        // Set both position and limit to ensure buffer is accessible
                                        buf.position(info.offset)
                                        buf.limit(info.offset + info.size)
                                        
                                        when (pcmEncodingBit) {
                                            8 -> {
                                                handle8bit(size, buf)
                                            }
                                            16 -> {
                                                handle16bit(size, buf)
                                            }
                                            32 -> {
                                                handle32bit(size, buf)
                                            }
                                            else -> {
                                                Log.e(Constants.LOG_TAG, "Unsupported PCM encoding bit: $pcmEncodingBit")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(Constants.LOG_TAG, "Error processing output buffer: ${e.message}")
                                    }
                                }
                            }
                        } finally {
                            // Always release the buffer, even if processing failed
                            try {
                                codec.releaseOutputBuffer(index, false)
                            } catch (e: IllegalStateException) {
                                Log.e(Constants.LOG_TAG, "Error releasing output buffer: ${e.message}")
                            }
                        }

                        if (info.isEof()) {
                            // The trailing bucket holds fewer samples than a
                            // full one, so its RMS is averaged over what it
                            // actually got.
                            if (currentProgress < expectedPoints && sampleCount > 0) {
                                updateProgress()
                                sendProgress(sqrt(sampleSum / sampleCount).toFloat())
                            }
                            stop()
                        }
                    }

                })
                it.start()
            }

        } catch (e: Exception) {
            submitError(e.message, "An error is thrown before decoding the audio file")
            stop()
        }


    }

    /** Collected waveform amplitude data points */
    var sampleData = ArrayList<Float>()
    /** Count of samples processed for the current data point */
    private var sampleCount = 0L
    /** Sum of squared sample values for RMS calculation */
    private var sampleSum = 0.0

    /**
     * Processes each audio sample and accumulates data for RMS calculation
     *
     * This method:
     * 1. Accumulates squared sample values
     * 2. When enough samples are collected for a data point, calculates the RMS
     * 3. Updates progress and sends the new data point to Flutter
     * 
     * @param value The normalized audio sample value (-1.0 to 1.0)
     */
    private fun handleBufferDivision(value: Float) {
        if (sampleCount == perSamplePoints) {
            // A decoder emits more frames than the container's declared
            // duration implies — an AAC encoder delay alone is 2048 frames —
            // so the buckets run out before the stream does. Those trailing
            // frames are padding: drop them instead of emitting a point that
            // was never asked for. Tearing the codec down here, as this used
            // to do, killed the app: it runs inside the codec's own output
            // callback, and the end-of-stream callback queued behind it then
            // stopped an already released codec.
            if (currentProgress >= expectedPoints) {
                sampleCount = 0
                sampleSum = 0.0
                return
            }
            updateProgress()
            val rms = sqrt(sampleSum / perSamplePoints).toFloat()
            sendProgress(rms)
        }

        sampleCount++
        sampleSum += value.toDouble().pow(2.0)
    }

    /**
     * Processes 8-bit PCM audio data
     *
     * Reads 8-bit samples from the buffer, normalizes them to the range [-1.0, 1.0],
     * and passes them to handleBufferDivision for RMS calculation.
     * 
     * @param size Size of the buffer in bytes
     * @param buf ByteBuffer containing the audio data
     */
    private fun handle8bit(size: Int, buf: ByteBuffer) {
        repeat(size / if (channels == 2) 2 else 1) {
            // ENCODING_PCM_8BIT is unsigned, with 128 as silence, but a Kotlin
            // Byte is signed: it has to be widened before being centered.
            val sample = (buf.get().toInt() and 0xFF) - 128
            val result = sample / Constants.EIGHT_BITS
            if (channels == 2) {
                buf.get()
            }
            handleBufferDivision(result)
        }
    }

    /**
     * Processes 16-bit PCM audio data
     *
     * Reads 16-bit samples from the buffer, normalizes them to the range [-1.0, 1.0],
     * and passes them to handleBufferDivision for RMS calculation.
     * 
     * @param size Size of the buffer in bytes
     * @param buf ByteBuffer containing the audio data
     */
    private fun handle16bit(size: Int, buf: ByteBuffer) {
        repeat(size / if (channels == 2) 4 else 2) {
            // Little endian: low byte first. It has to be masked, or a Kotlin
            // Byte over 0x7F widens to a negative Int and its sign bits
            // swallow the high byte, turning the sample into noise. The high
            // byte is left signed on purpose — that is the sample's own sign.
            val low = buf.get().toInt() and 0xFF
            val high = buf.get().toInt() shl 8
            val value = (low or high) / Constants.SIXTEEN_BITS
            if (channels == 2) {
                buf.get()
                buf.get()
            }
            handleBufferDivision(value)
        }
    }

    /**
     * Processes 32-bit PCM audio data
     *
     * A 32-bit bit depth is only ever set for `ENCODING_PCM_FLOAT`, so the
     * buffer holds IEEE-754 floats already normalized to [-1.0, 1.0]. Reading
     * them as a 32-bit integer, as this used to do, reinterprets the exponent
     * bits as magnitude and yields noise.
     *
     * @param size Size of the buffer in bytes
     * @param buf ByteBuffer containing the audio data
     */
    private fun handle32bit(size: Int, buf: ByteBuffer) {
        val floats = buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        repeat(size / if (channels == 2) 8 else 4) {
            val value = floats.get()
            if (channels == 2) {
                floats.get()
            }
            handleBufferDivision(value)
        }
    }

    /**
     * Updates the extraction progress
     * 
     * Increments the progress counter and calculates the overall
     * extraction progress as a ratio of current to expected data points.
     */
    private fun updateProgress() {
        currentProgress++
        progress = currentProgress / expectedPoints
    }

    /**
     * Sends a new waveform data point and current progress to Flutter
     *
     * This method:
     * 1. Adds the new RMS value to the waveform data
     * 2. Resets the sample counters for the next data point
     * 3. Sends the current waveform data and progress to Flutter via the method channel
     * 
     * @param rms The calculated RMS value for this data point
     */
    private fun sendProgress(rms: Float) {
        sampleData.add(rms)
        sampleCount = 0
        sampleSum = 0.0

        val args: MutableMap<String, Any?> = HashMap()
        args[Constants.waveformData] = sampleData
        args[Constants.progress] = progress
        args[Constants.playerKey] = key
        methodChannel.invokeMethod(
            Constants.onCurrentExtractedWaveformData,
            args
        )
    }

    /**
     * Stops the extraction process, releases resources and answers Flutter
     * with whatever was collected.
     *
     * Reachable both from the codec's end-of-stream callback and from
     * `stopExtraction` on the platform thread, so it is idempotent: a second
     * call would otherwise stop an already released `MediaCodec` and take the
     * app down with an `IllegalStateException`. Answering here as well is what
     * keeps a cancelled — or short — extraction from leaving its caller
     * waiting forever.
     */
    fun stop() {
        if (!isStopped.compareAndSet(false, true)) return
        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: IllegalStateException) {
            Log.e(Constants.LOG_TAG, "Error releasing the decoder: ${e.message}")
        }
        decoder = null
        extractor?.release()
        extractor = null
        submitSuccess()
        finishCount.countDown()
    }

    /** Answers Flutter with the collected points, once. */
    private fun submitSuccess() {
        if (!isReplySubmitted.compareAndSet(false, true)) return
        val data = ArrayList(sampleData)
        mainHandler.post { result.success(data) }
    }

    /** Answers Flutter with an error, once. */
    private fun submitError(message: String?, details: String) {
        if (!isReplySubmitted.compareAndSet(false, true)) return
        mainHandler.post { result.error(Constants.LOG_TAG, message, details) }
    }
}

/**
 * Extension function to check if a buffer contains the end-of-stream flag
 * 
 * @return true if this buffer marks the end of the stream, false otherwise
 */
fun MediaCodec.BufferInfo.isEof() = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
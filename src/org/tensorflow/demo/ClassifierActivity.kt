package org.tensorflow.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.os.Vibrator
import android.util.Size
import android.util.TypedValue
import java.util.Vector
import org.tensorflow.demo.OverlayView.DrawCallback
import org.tensorflow.demo.env.BorderedText
import org.tensorflow.demo.env.ImageUtils
import org.tensorflow.demo.env.Logger


class ClassifierActivity: CameraActivity(), OnImageAvailableListener {
    override val desiredPreviewFrameSize: Size
        get() =DESIRED_PREVIEW_SIZE //To change initializer of created properties use File | Settings | File Templates.
    override val layoutId: Int
        get() = R.layout.camera_connection_fragment //To change initializer of created properties use File | Settings | File Templates.

    private var classifier: Classifier? = null

    private var sensorOrientation: Int? = null

    private var previewWidth = 0
    private var previewHeight = 0
    private var yuvBytes:  Array<ByteArray>? = null
    private var rgbBytes: IntArray? = null
    private var rgbFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null

    private var cropCopyBitmap: Bitmap? = null

    private var computing = false

    private var frameToCropTransform: Matrix? = null
    private var cropToFrameTransform: Matrix? = null

    private var resultsView: ResultsView? = null

    private var borderedText: BorderedText? = null

    private var lastProcessingTimeMs: Long = 0

    public override fun onPreviewSizeChosen(size: Size, rotation: Int) {
        val textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics)
        borderedText = BorderedText(textSizePx)
        borderedText!!.setTypeface(Typeface.MONOSPACE)

        classifier = TensorFlowImageClassifier.create(
                assets,
                MODEL_FILE,
                LABEL_FILE,
                INPUT_SIZE,
                IMAGE_MEAN,
                IMAGE_STD,
                INPUT_NAME,
                OUTPUT_NAME)

        resultsView = findViewById(R.id.results) as ResultsView
        previewWidth = size.width
        previewHeight = size.height

        val display = windowManager.defaultDisplay
        val screenOrientation = display.rotation

        LOGGER.i("Sensor orientation: %d, Screen orientation: %d", rotation, screenOrientation)

        sensorOrientation = rotation + screenOrientation

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight)
        rgbBytes = IntArray(previewWidth * previewHeight)
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888)
        croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Config.ARGB_8888)

        frameToCropTransform = ImageUtils.getTransformationMatrix(
                previewWidth, previewHeight,
                INPUT_SIZE, INPUT_SIZE,
                sensorOrientation!!, MAINTAIN_ASPECT)

        cropToFrameTransform = Matrix()
        frameToCropTransform!!.invert(cropToFrameTransform)

        yuvBytes = Array<ByteArray>(3,{ByteArray(3)})

        addCallback(
                object : DrawCallback {
                    override fun drawCallback(canvas: Canvas) {
                        renderDebug(canvas)
                    }
                })
    }

    override fun onImageAvailable(reader: ImageReader) {
        var image: Image? = null

        try {
            image = reader.acquireLatestImage()

            if (image == null) {
                return
            }

            if (computing) {
                image!!.close()
                return
            }
            computing = true

            Trace.beginSection("imageAvailable")

            val planes = image!!.planes
            fillBytes(planes, yuvBytes!!)

            val yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride
            ImageUtils.convertYUV420ToARGB8888(
                    yuvBytes!![0],
                    yuvBytes!![1],
                    yuvBytes!![2],
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    rgbBytes)

            image!!.close()
        } catch (e: Exception) {
            if (image != null) {
                image!!.close()
            }
            LOGGER.e(e, "Exception!")
            Trace.endSection()
            return
        }

        rgbFrameBitmap!!.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight)
        val canvas = Canvas(croppedBitmap!!)
        canvas.drawBitmap(rgbFrameBitmap!!, frameToCropTransform!!, null)

        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap)
        }

        runInBackground {
            val startTime = SystemClock.uptimeMillis()
            val results = classifier!!.recognizeImage(croppedBitmap!!)
            val result = 0f
            var str: Char
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime

            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap!!)
            resultsView!!.setResults(results)
            for (recog in results) {
                str = recog.title!![0]
                if (str == 'b') {
                    if (recog.confidence!!.toFloat() >= 0.5 && VibrateFlag == 0) {
                        vibrator!!.vibrate(100)
                        VibrateFlag = 1
                    } else if (recog.confidence!!.toFloat() < 0.5 && VibrateFlag == 1) {
                        vibrator!!.vibrate(1000)
                        VibrateFlag = 0
                    }
                }
            }
            requestRender()
            computing = false
        }

        Trace.endSection()
    }

    override fun onSetDebug(debug: Boolean) {
        classifier!!.enableStatLogging(debug)
    }

    private fun renderDebug(canvas: Canvas) {
        if (!isDebug) {
            return
        }
        val copy = cropCopyBitmap
        if (copy != null) {
            val matrix = Matrix()
            val scaleFactor = 2f
            matrix.postScale(scaleFactor, scaleFactor)
            matrix.postTranslate(
                    canvas.width - copy!!.width * scaleFactor,
                    canvas.height - copy!!.height * scaleFactor)
            canvas.drawBitmap(copy!!, matrix, Paint())

            val lines = Vector<String>()
            if (classifier != null) {
                val statString = classifier!!.statString
                val statLines = statString.split(("\n").toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                for (line in statLines) {
                    lines.add(line)
                }
            }

            lines.add("Frame: " + previewWidth + "x" + previewHeight)
            lines.add("Crop: " + copy!!.width + "x" + copy!!.height)
            lines.add("View: " + canvas.width + "x" + canvas.height)
            lines.add("Rotation: " + sensorOrientation!!)
            lines.add("Inference time: " + lastProcessingTimeMs + "ms")

            borderedText!!.drawLines(canvas, 10f, (canvas.height - 10).toFloat(), lines)
        }
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {

    }

    override fun onProviderEnabled(provider: String) {

    }

    override fun onProviderDisabled(provider: String) {

    }

    companion object {
        private val LOGGER = Logger()

        // These are the settings for the original v1 Inception model. If you want to
        // use a model that's been produced from the TensorFlow for Poets codelab,
        // you'll need to set IMAGE_SIZE = 299, IMAGE_MEAN = 128, IMAGE_STD = 128,
        // INPUT_NAME = "Mul", and OUTPUT_NAME = "final_result".
        // You'll also need to update the MODEL_FILE and LABEL_FILE paths to point to
        // the ones you produced.
        //
        // To use v3 Inception model, strip the DecodeJpeg Op from your retrained
        // model first:
        //
        // python strip_unused.py \
        // --input_graph=<retrained-pb-file> \
        // --output_graph=<your-stripped-pb-file> \
        // --input_node_names="Mul" \
        // --output_node_names="final_result" \
        // --input_binary=true

        /* Inception V3
        private static final int INPUT_SIZE = 299;
        private static final int IMAGE_MEAN = 128;
        private static final float IMAGE_STD = 128.0f;
        private static final String INPUT_NAME = "Mul:0";
        private static final String OUTPUT_NAME = "final_result";
        */

        private val INPUT_SIZE = 299
        private val IMAGE_MEAN = 128
        private val IMAGE_STD = 128.0f
        private val INPUT_NAME = "Mul:0"
        private val OUTPUT_NAME = "final_result"

        private val MODEL_FILE = "file:///android_asset/stripped_graph.pb"
        private val LABEL_FILE = "file:///android_asset/retrained_labels.txt"

        private val SAVE_PREVIEW_BITMAP = false

        private val MAINTAIN_ASPECT = true

        private val DESIRED_PREVIEW_SIZE = Size(640, 480)

        private var VibrateFlag = 0 //보도블럭 알림용

        private val TEXT_SIZE_DIP = 10f
    }
}

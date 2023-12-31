/* Copyright 2021 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================
*/

package org.tensorflow.lite.examples.poseestimation.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tensorflow.lite.examples.poseestimation.VisualizationUtils
import org.tensorflow.lite.examples.poseestimation.YuvToRgbConverter
import org.tensorflow.lite.examples.poseestimation.data.BodyPart
import org.tensorflow.lite.examples.poseestimation.data.KeyPoint
import org.tensorflow.lite.examples.poseestimation.data.Person
import org.tensorflow.lite.examples.poseestimation.ml.MoveNetMultiPose
import org.tensorflow.lite.examples.poseestimation.ml.PoseClassifier
import org.tensorflow.lite.examples.poseestimation.ml.PoseDetector
import org.tensorflow.lite.examples.poseestimation.ml.TrackerType
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2


class CameraSource(
    private val surfaceView: SurfaceView,
    private val listener: CameraSourceListener? = null
) {

    companion object {
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_HEIGHT = 480

        /** Threshold for confidence score. */
        private const val MIN_CONFIDENCE = .2f
        private const val TAG = "Camera Source"
    }


    private lateinit var leftWrist: KeyPoint
    private lateinit var leftShoulder: KeyPoint
    private lateinit var leftElbow: KeyPoint
    private lateinit var rightWrist: KeyPoint
    private lateinit var rightShoulder: KeyPoint
    private lateinit var rightElbow: KeyPoint
    private lateinit var leftHip: KeyPoint
    private lateinit var leftKnee: KeyPoint
    private lateinit var nose: KeyPoint
    private var tts: TextToSpeech? = null
    // Определение переменных
    var elbowAngle: Double = 0.0
    var backAngle: Double = 0.0
    var upPosition = false
    var downPosition = false
    var highlightBack = false
    var backWarningGiven = false
    var reps = 0

    private val lock = Any()
    private var detector: PoseDetector? = null
    private var classifier: PoseClassifier? = null
    private var isTrackerEnabled = false
    private var yuvConverter: YuvToRgbConverter = YuvToRgbConverter(surfaceView.context)
    private lateinit var imageBitmap: Bitmap

    /** Frame count that have been processed so far in an one second interval to calculate FPS. */
    private var fpsTimer: Timer? = null
    private var frameProcessedInOneSecondInterval = 0
    private var framesPerSecond = 0

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = surfaceView.context
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** Readers used as buffers for camera still shots */
    private var imageReader: ImageReader? = null

    /** The [CameraDevice] that will be opened in this fragment */
    private var camera: CameraDevice? = null

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private var session: CameraCaptureSession? = null

    /** [HandlerThread] where all buffer reading operations run */
    private var imageReaderThread: HandlerThread? = null

    /** [Handler] corresponding to [imageReaderThread] */
    private var imageReaderHandler: Handler? = null
    private var cameraId: String =  "0"
    private fun getFrontFacingCameraId(cManager: CameraManager): String? {
        try {
            var cameraId: String?
            var cameraOrientation: Int
            var characteristics: CameraCharacteristics
            for (i in cManager.cameraIdList.indices) {
                cameraId = cManager.cameraIdList[i]
                characteristics = cManager.getCameraCharacteristics(cameraId)
                cameraOrientation = characteristics.get(CameraCharacteristics.LENS_FACING)!!
                if (cameraOrientation == CameraCharacteristics.LENS_FACING_FRONT) {
                //if (cameraOrientation == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        return null
    }

    suspend fun initCamera() {
        camera = openCamera(cameraManager, getFrontFacingCameraId(cameraManager)!!)
        imageReader =
            ImageReader.newInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, ImageFormat.YUV_420_888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                if (!::imageBitmap.isInitialized) {
                    imageBitmap =
                        Bitmap.createBitmap(
                            PREVIEW_WIDTH,
                            PREVIEW_HEIGHT,
                            Bitmap.Config.ARGB_8888
                        )
                }
                yuvConverter.yuvToRgb(image, imageBitmap)
                // Create rotated version for portrait display
                val rotateMatrix = Matrix()
                rotateMatrix.postRotate(90.0f)
                rotateMatrix.preScale(-1.0f, 1.0f)

                val rotatedBitmap = Bitmap.createBitmap(
                    imageBitmap, 0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    rotateMatrix, false
                )
                processImage(rotatedBitmap)
                image.close()
            }
        }, imageReaderHandler)

        imageReader?.surface?.let { surface ->
            session = createSession(listOf(surface))
            val cameraRequest = camera?.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )?.apply {
                addTarget(surface)
            }
            cameraRequest?.build()?.let {
                session?.setRepeatingRequest(it, null, null)
            }
        }

    }

    private suspend fun createSession(targets: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            camera?.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) =
                    cont.resume(captureSession)

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    cont.resumeWithException(Exception("Session error"))
                }
            }, null)
        }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(manager: CameraManager, cameraId: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = cont.resume(camera)

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (cont.isActive) cont.resumeWithException(Exception("Camera error"))
                }
            }, imageReaderHandler)
        }

    fun prepareCamera() {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            // We don't use a front facing camera in this sample.
            val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (cameraDirection != null &&
                cameraDirection == CameraCharacteristics.LENS_FACING_FRONT
            ) {
                continue
            }
            this.cameraId = cameraId
        }
    }

    fun setDetector(detector: PoseDetector) {
        synchronized(lock) {
            if (this.detector != null) {
                this.detector?.close()
                this.detector = null
            }
            this.detector = detector
        }
    }

    fun setClassifier(classifier: PoseClassifier?) {
        synchronized(lock) {
            if (this.classifier != null) {
                this.classifier?.close()
                this.classifier = null
            }
            this.classifier = classifier
        }
    }

    /**
     * Set Tracker for Movenet MuiltiPose model.
     */
    fun setTracker(trackerType: TrackerType) {
        isTrackerEnabled = trackerType != TrackerType.OFF
        (this.detector as? MoveNetMultiPose)?.setTracker(trackerType)
    }

    fun resume() {
        imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
        imageReaderHandler = Handler(imageReaderThread!!.looper)
        fpsTimer = Timer()
        fpsTimer?.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    framesPerSecond = frameProcessedInOneSecondInterval
                    frameProcessedInOneSecondInterval = 0
                }
            },
            0,
            1000
        )
    }

    fun close() {
        session?.close()
        session = null
        camera?.close()
        camera = null
        imageReader?.close()
        imageReader = null
        stopImageReaderThread()
        detector?.close()
        detector = null
        classifier?.close()
        classifier = null
        fpsTimer?.cancel()
        fpsTimer = null
        frameProcessedInOneSecondInterval = 0
        framesPerSecond = 0
        // Shutdown TTS when
        // activity is destroyed
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
    }

    // process image
    private fun processImage(bitmap: Bitmap) {
        val persons = mutableListOf<Person>()
        var classificationResult: List<Pair<String, Float>>? = null

        synchronized(lock) {
            detector?.estimatePoses(bitmap)?.let {
                persons.addAll(it)

                // if the model only returns one item, allow running the Pose classifier.
                if (persons.isNotEmpty()) {
                    classifier?.run {
                        classificationResult = classify(persons[0])
                    }
                }
            }
        }
        frameProcessedInOneSecondInterval++
        if (frameProcessedInOneSecondInterval == 1) {
            // send fps to view
            listener?.onFPSListener(framesPerSecond)
        }

        // if the model returns only one item, show that item's score.
        if (persons.isNotEmpty()) {
            listener?.onDetectedInfo(persons[0].score, classificationResult)
        }

        visualize(persons, bitmap)
    }

    private fun visualize(persons: List<Person>, bitmap: Bitmap) {

        val outputBitmap = VisualizationUtils.drawBodyKeypoints(
            bitmap,
            persons.filter { it.score > MIN_CONFIDENCE }, isTrackerEnabled
        )
        persons.forEach { person ->

            person.keyPoints.forEach { point ->
                if (point.bodyPart == BodyPart.LEFT_WRIST)
                    leftWrist = point
                if (point.bodyPart == BodyPart.LEFT_SHOULDER)
                    leftShoulder = point
                if (point.bodyPart == BodyPart.LEFT_ELBOW)
                    leftElbow = point
                if (point.bodyPart == BodyPart.RIGHT_WRIST)
                    rightWrist = point
                if (point.bodyPart == BodyPart.RIGHT_SHOULDER)
                    rightShoulder = point
                if (point.bodyPart == BodyPart.RIGHT_ELBOW)
                    rightElbow = point
                if  (point.bodyPart == BodyPart.LEFT_HIP)
                    leftHip = point
                if (point.bodyPart == BodyPart.LEFT_KNEE)
                    leftKnee = point
                if (point.bodyPart == BodyPart.NOSE)
                    nose = point
            }
        }
        if(::leftShoulder.isInitialized) {
            updateArmAngle();
            //updateBackAngle();
            inUpPosition();
            inDownPosition();
        }
        val holder = surfaceView.holder
        val surfaceCanvas = holder.lockCanvas()
        surfaceCanvas?.let { canvas ->
            val screenWidth: Int
            val screenHeight: Int
            val left: Int
            val top: Int

            if (canvas.height > canvas.width) {
                val ratio = outputBitmap.height.toFloat() / outputBitmap.width
                screenWidth = canvas.width
                left = 0
                screenHeight = (canvas.width * ratio).toInt()
                top = (canvas.height - screenHeight) / 2
            } else {
                val ratio = outputBitmap.width.toFloat() / outputBitmap.height
                screenHeight = canvas.height
                top = 0
                screenWidth = (canvas.height * ratio).toInt()
                left = (canvas.width - screenWidth) / 2
            }
            val right: Int = left + screenWidth
            val bottom: Int = top + screenHeight

            canvas.drawBitmap(
                outputBitmap, Rect(0, 0, outputBitmap.width, outputBitmap.height),
                Rect(left, top, right, bottom), null
            )
            surfaceView.holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun stopImageReaderThread() {
        imageReaderThread?.quitSafely()
        try {
            imageReaderThread?.join()
            imageReaderThread = null
            imageReaderHandler = null
        } catch (e: InterruptedException) {
            Log.d(TAG, e.message.toString())
        }
    }

    interface CameraSourceListener {
        fun onFPSListener(fps: Int)

        fun onTTS(spechText:String)

        fun onDetectedInfo(personScore: Float?, poseLabels: List<Pair<String, Float>>?)
    }

    // Функция для обновления угла локтя
    private fun updateArmAngle() {
        if (leftWrist.score > 0.3 && leftElbow.score > 0.3 && leftShoulder.score > 0.3) {
            val angle = (
                    atan2(
                        leftWrist.coordinate.y - leftElbow.coordinate.y,
                        leftWrist.coordinate.x - leftElbow.coordinate.x
                    ) - atan2(
                        leftShoulder.coordinate.y - leftElbow.coordinate.y,
                        leftShoulder.coordinate.x - leftElbow.coordinate.x
                    )
                    ) * (180 / PI)
            elbowAngle = angle
        } else {
            if (rightWrist.score > 0.3 && rightElbow.score > 0.3 && rightShoulder.score > 0.3) {
                val angle = (
                        atan2(
                            rightWrist.coordinate.y - rightElbow.coordinate.y,
                            rightWrist.coordinate.x - rightElbow.coordinate.x
                        ) - atan2(
                            rightShoulder.coordinate.y - rightElbow.coordinate.y,
                            rightShoulder.coordinate.x - rightElbow.coordinate.x
                        )
                        ) * (180 / PI)
                elbowAngle = angle
            }
        }
    }

    // Функция для обновления угла спины
    private fun updateBackAngle() {

        val angle = (
                atan2(
                    leftKnee.coordinate.y - leftHip.coordinate.y,
                    leftKnee.coordinate.x - leftHip.coordinate.x
                ) - atan2(
                    leftShoulder.coordinate.y - leftHip.coordinate.y,
                    leftShoulder.coordinate.x - leftHip.coordinate.x
                )
                ) * (180 / PI)
        backAngle = angle % 180
        if (leftKnee.score > 0.3 && leftHip.score > 0.3 && leftShoulder.score > 0.3) {
            backAngle = angle
        }

        highlightBack = if (backAngle < 20 || backAngle > 160) {
            false
        } else {
            if (!backWarningGiven) {
                Log.d(ContentValues.TAG, "Keep your back straight")
                // замените эту строку кодом для произнесения фразы "Keep your back straight"
                backWarningGiven = true
            }
            true
        }
    }

    // Функция для определения находится ли человек в верхней позиции
    private fun inUpPosition() {
        if (abs(elbowAngle) > 160 && abs(elbowAngle) < 210) {
            if (downPosition) {
                reps += 1
                Log.d(ContentValues.TAG, reps.toString())
                listener?.onTTS(reps.toString())
                // замените эту строку кодом для произнесения количества повторений
            }
            upPosition = true
            downPosition = false
        }
    }

    // Функция для определения находится ли человек в нижней позиции
    fun inDownPosition() {
/*        var elbowAboveNose = false
        if (nose.coordinate.y > leftElbow.coordinate.y) {
            elbowAboveNose = true
        } else {

            //println("Elbow is not above nose")
        }*/

        if (//!highlightBack //&& elbowAboveNose
            //&&
                abs(elbowAngle) > 70 && abs(
                elbowAngle
            ) < 110) {
            if (upPosition) {
                listener?.onTTS("Вверх")
                // замените эту строку кодом для произнесения слова "Up"
                Log.d(ContentValues.TAG, "Up")
            }
            downPosition = true
            upPosition = false
        }
    }
}

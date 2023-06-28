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

package org.tensorflow.lite.examples.poseestimation

import android.R.attr.text
import android.content.ContentValues.TAG
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import org.tensorflow.lite.examples.poseestimation.data.BodyPart
import org.tensorflow.lite.examples.poseestimation.data.KeyPoint
import org.tensorflow.lite.examples.poseestimation.data.Person
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max


object VisualizationUtils {



    // Определение переменных
    var elbowAngle: Double = 0.0
    var backAngle: Double = 0.0
    var upPosition = false
    var downPosition = false
    var highlightBack = false
    var backWarningGiven = false
    var reps = 0
    private lateinit var leftWrist: KeyPoint
    private lateinit var leftShoulder: KeyPoint
    private lateinit var leftElbow: KeyPoint
    private lateinit var leftHip: KeyPoint
    private lateinit var leftKnee: KeyPoint
    private lateinit var nose: KeyPoint
    private var tts: TextToSpeech? = null

    /** Radius of circle used to draw keypoints.  */
    private const val CIRCLE_RADIUS = 6f

    /** Width of line used to connected two keypoints.  */
    private const val LINE_WIDTH = 4f

    /** The text size of the person id that will be displayed when the tracker is available.  */
    private const val PERSON_ID_TEXT_SIZE = 30f

    /** Distance from person id to the nose keypoint.  */
    private const val PERSON_ID_MARGIN = 6f

    /** Pair of keypoints to draw lines between.  */
    private val bodyJoints = listOf(
        Pair(BodyPart.NOSE, BodyPart.LEFT_EYE),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_EYE),
        Pair(BodyPart.LEFT_EYE, BodyPart.LEFT_EAR),
        Pair(BodyPart.RIGHT_EYE, BodyPart.RIGHT_EAR),
        Pair(BodyPart.NOSE, BodyPart.LEFT_SHOULDER),
        Pair(BodyPart.NOSE, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_ELBOW),
        Pair(BodyPart.LEFT_ELBOW, BodyPart.LEFT_WRIST),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_ELBOW),
        Pair(BodyPart.RIGHT_ELBOW, BodyPart.RIGHT_WRIST),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.RIGHT_SHOULDER),
        Pair(BodyPart.LEFT_SHOULDER, BodyPart.LEFT_HIP),
        Pair(BodyPart.RIGHT_SHOULDER, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.RIGHT_HIP),
        Pair(BodyPart.LEFT_HIP, BodyPart.LEFT_KNEE),
        Pair(BodyPart.LEFT_KNEE, BodyPart.LEFT_ANKLE),
        Pair(BodyPart.RIGHT_HIP, BodyPart.RIGHT_KNEE),
        Pair(BodyPart.RIGHT_KNEE, BodyPart.RIGHT_ANKLE)
    )

    // Draw line and point indicate body pose
    fun drawBodyKeypoints(
        input: Bitmap,
        persons: List<Person>,
        isTrackerEnabled: Boolean = false
    ): Bitmap {
        val paintCircle = Paint().apply {
            strokeWidth = CIRCLE_RADIUS
            color = Color.RED
            style = Paint.Style.FILL
        }
        val paintLine = Paint().apply {
            strokeWidth = LINE_WIDTH
            color = Color.RED
            style = Paint.Style.STROKE
        }

        val paintText = Paint().apply {
            textSize = PERSON_ID_TEXT_SIZE
            color = Color.BLUE
            textAlign = Paint.Align.LEFT
        }

        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val originalSizeCanvas = Canvas(output)
        persons.forEach { person ->
            // draw person id if tracker is enable
            if (isTrackerEnabled) {
                person.boundingBox?.let {
                    val personIdX = max(0f, it.left)
                    val personIdY = max(0f, it.top)

                    originalSizeCanvas.drawText(
                        person.id.toString(),
                        personIdX,
                        personIdY - PERSON_ID_MARGIN,
                        paintText
                    )
                    originalSizeCanvas.drawRect(it, paintLine)
                }
            }
            bodyJoints.forEach {
                val pointA = person.keyPoints[it.first.position].coordinate
                val pointB = person.keyPoints[it.second.position].coordinate
                originalSizeCanvas.drawLine(pointA.x, pointA.y, pointB.x, pointB.y, paintLine)
            }

            person.keyPoints.forEach { point ->
                originalSizeCanvas.drawCircle(
                    point.coordinate.x,
                    point.coordinate.y,
                    CIRCLE_RADIUS,
                    paintCircle
                )
                if (point.bodyPart == BodyPart.LEFT_WRIST)
                     leftWrist = point
                if (point.bodyPart == BodyPart.LEFT_SHOULDER)
                    leftShoulder = point
                if (point.bodyPart == BodyPart.LEFT_ELBOW)
                    leftElbow = point
                if  (point.bodyPart == BodyPart.LEFT_HIP)
                    leftHip = point
                if (point.bodyPart == BodyPart.LEFT_KNEE)
                    leftKnee = point
                if (point.bodyPart == BodyPart.NOSE)
                    nose = point
            }
        }
/*        if(::leftShoulder.isInitialized) {
            updateArmAngle();
            updateBackAngle();
            inUpPosition();
            inDownPosition();
        }*/
        return output
    }
    // Функция для обновления угла локтя
    private fun updateArmAngle() {

        val angle = (
                atan2(
                    leftWrist.coordinate.y - leftElbow.coordinate.y,
                    leftWrist.coordinate.x - leftElbow.coordinate.x
                ) - atan2(
                    leftShoulder.coordinate.y - leftElbow.coordinate.y,
                    leftShoulder.coordinate.x - leftElbow.coordinate.x
                )
                ) * (180 / PI)

        if (leftWrist.score > 0.3 && leftElbow.score > 0.3 && leftShoulder.score > 0.3) {
            elbowAngle = angle
        } else {
            //Log.d(TAG, "Cannot see elbow")
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
                Log.d(TAG, "Keep your back straight")
                // замените эту строку кодом для произнесения фразы "Keep your back straight"
                backWarningGiven = true
            }
            true
        }
    }

    // Функция для определения находится ли человек в верхней позиции
    private fun inUpPosition() {
        if (elbowAngle > 170 && elbowAngle < 200) {
            if (downPosition) {
                reps += 1
                Log.d(TAG, reps.toString())
                // замените эту строку кодом для произнесения количества повторений
            }
            upPosition = true
            downPosition = false
        }
    }

    // Функция для определения находится ли человек в нижней позиции
    fun inDownPosition() {
        var elbowAboveNose = false
        if (nose.coordinate.y > leftElbow.coordinate.y) {
            elbowAboveNose = true
        } else {

            //println("Elbow is not above nose")
        }

        if (!highlightBack && elbowAboveNose && abs(elbowAngle) > 70 && abs(elbowAngle) < 100) {
            if (upPosition) {
                // замените эту строку кодом для произнесения слова "Up"
                Log.d(TAG, "Up")
            }
            downPosition = true
            upPosition = false
        }
    }

}

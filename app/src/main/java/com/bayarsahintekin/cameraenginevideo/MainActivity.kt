package com.bayarsahintekin.cameraenginevideo

import android.app.AlertDialog
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.Image
import android.os.Bundle
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.widget.*
import android.widget.AdapterView.OnItemSelectedListener
import androidx.appcompat.app.AppCompatActivity
import com.huawei.camera.camerakit.*
import com.huawei.camera.camerakit.ActionStateCallback.TakePictureResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.regex.PatternSyntaxException
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val TAG = CameraKit::class.java.simpleName

    private val PREVIEW_SURFACE_READY_TIMEOUT = 5000L

    private val mPreviewSurfaceChangedDone = ConditionVariable()

    private val DEFAULT_RETURN_VALUE = -1

    private val PIVOT = " "

    /**
     * View for preview
     */
    private var mTextureView: AutoFitTextureView? = null

    /**
     * Button for capture
     */
    private var mButtonCaptureImage: Button? = null

    private var mButtonStopPicture: Button? = null

    /**
     * Preview size
     */
    private var mPreviewSize: Size? = null

    /**
     * Capture size
     */
    private var mCaptureSize: Size? = null

    /**
     * Capture jpeg file
     */
    private var mFile: File? = null

    /**
     * CameraKit instance
     */
    private var mCameraKit: CameraKit? = null

    /**
     * Current mode type
     */
    @Mode.Type
    private val mCurrentModeType = Mode.Type.SUPER_NIGHT_MODE

    /**
     * Current mode object
     */
    private var mMode: Mode? = null

    /**
     * Mode characteristics
     */
    private var mModeCharacteristics: ModeCharacteristics? = null

    /**
     * Mode config builder
     */
    private var modeConfigBuilder: ModeConfig.Builder? = null

    /**
     * Work thread for time consumed task
     */
    private var mCameraKitThread: HandlerThread? = null

    /**
     * Handler correspond to mCameraKitThread
     */
    private var mCameraKitHandler: Handler? = null

    /**
     * Lock for camera device
     */
    private val mCameraOpenCloseLock =
        Semaphore(1)

    private val mSurfaceTextureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            texture: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            mCameraKitHandler!!.post { createMode() }
        }

        override fun onSurfaceTextureSizeChanged(
            texture: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            mPreviewSurfaceChangedDone.open()
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    private val actionDataCallback: ActionDataCallback = object : ActionDataCallback() {
        override fun onImageAvailable(
            mode: Mode,
            @Type type: Int,
            image: Image
        ) {
            Log.d(TAG, "onImageAvailable: save img")
            when (type) {
                Type.TAKE_PICTURE -> {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer[bytes]
                    var output: FileOutputStream? = null
                    try {
                        output = FileOutputStream(mFile)
                        output.write(bytes)
                    } catch (e: IOException) {
                        Log.e(TAG, "IOException when write in run")
                    } finally {
                        image.close()
                        if (output != null) {
                            try {
                                output.close()
                            } catch (e: IOException) {
                                Log.e(TAG, "IOException when close in run")
                            }
                        }
                    }
                }
                else -> {
                }
            }
        }
    }

    private val actionStateCallback: ActionStateCallback = object : ActionStateCallback() {
        override fun onPreview(
            mode: Mode,
            state: Int,
            result: PreviewResult?
        ) {
            if (state == PreviewResult.State.PREVIEW_STARTED) {
                Log.i(TAG, "onPreview Started")
            }
        }

        override fun onTakePicture(
            mode: Mode,
            state: Int,
            result: TakePictureResult?
        ) {
            when (state) {
                TakePictureResult.State.CAPTURE_STARTED -> Log.d(
                    TAG,
                    "onState: STATE_CAPTURE_STARTED"
                )
                TakePictureResult.State.CAPTURE_EXPOSURE_BEGIN ->                     // exposure begin
                    processExposureBegein(result)
                TakePictureResult.State.CAPTURE_EXPOSURE_END ->                     // exposure end
                    processExposureEnd()
                TakePictureResult.State.CAPTURE_COMPLETED -> {
                    Log.d(TAG, "onState: STATE_CAPTURE_COMPLETED")
                    showToast("take picture success! file=$mFile")
                }
                else -> {
                }
            }
        }
    }

    private fun processExposureEnd() {
        runOnUiThread {
            Toast.makeText(this, "exposure end, capturing", Toast.LENGTH_SHORT).show()
            mButtonStopPicture!!.visibility = View.INVISIBLE
        }
    }

    private fun processExposureBegein(result: TakePictureResult?) {
        if (result == null || mCurrentModeType != Mode.Type.SUPER_NIGHT_MODE) {
            return
        }
        // Get the exposure time of this shooting. After that time, the exposure will be completed. Then you can get the picture through actiondatacallback.onimageavailable.
        val exposureTime = result.exposureTime
        if (exposureTime != DEFAULT_RETURN_VALUE) {
            runOnUiThread {
                Toast.makeText(this, "exposureTime $exposureTime", Toast.LENGTH_SHORT).show()
                // after the exposure starts, it is allowed to finish the exposure by stoppicture before the end of the exposure countdown.
                mButtonStopPicture!!.visibility = View.VISIBLE
            }
        }
    }

    private val mModeStateCallback: ModeStateCallback = object : ModeStateCallback() {
        override fun onCreated(mode: Mode) {
            Log.d(TAG, "mModeStateCallback onModeOpened: ")
            mCameraOpenCloseLock.release()
            mMode = mode
            mModeCharacteristics = mode.modeCharacteristics
            modeConfigBuilder = mMode!!.modeConfigBuilder
            configMode()
        }

        override fun onCreateFailed(
            cameraId: String,
            modeType: Int,
            errorCode: Int
        ) {
            Log.d(
                TAG,
                "mModeStateCallback onCreateFailed with errorCode: $errorCode and with cameraId: $cameraId"
            )
            mCameraOpenCloseLock.release()
        }

        override fun onConfigured(mode: Mode) {
            Log.d(TAG, "mModeStateCallback onModeActivated : ")
            mMode!!.startPreview()
            runOnUiThread {
                initManualIsoSpinner()
                initManualExposureSpinner()
            }
            runOnUiThread { mButtonCaptureImage!!.isEnabled = true }
        }

        override fun onConfigureFailed(
            mode: Mode,
            errorCode: Int
        ) {
            Log.d(
                TAG,
                "mModeStateCallback onConfigureFailed with cameraId: " + mode.cameraId
            )
            mCameraOpenCloseLock.release()
        }

        override fun onFatalError(mode: Mode, errorCode: Int) {
            Log.d(
                TAG,
                "mModeStateCallback onFatalError with errorCode: " + errorCode + " and with cameraId: "
                        + mode.cameraId
            )
            mCameraOpenCloseLock.release()
            finish()
        }

        override fun onReleased(mode: Mode) {
            Log.d(TAG, "mModeStateCallback onModeReleased: ")
            mCameraOpenCloseLock.release()
        }
    }

    private fun initManualIsoSpinner() {
        var ranges = arrayOfNulls<Long>(0)
        val parameters =
            mModeCharacteristics!!.supportedParameters
        if (parameters != null && parameters.contains(RequestKey.HW_SUPER_NIGHT_ISO)) {
            val lists =
                mModeCharacteristics!!.getParameterRange(RequestKey.HW_SUPER_NIGHT_ISO)
            ranges = arrayOfNulls(lists.size)
            lists.toLongArray()
        }
        initSpinner(
            R.id.manualIso,
            longToList(ranges, R.string.manualIso),
            object : SpinnerOperation {
                override fun doOperation(text: String?) {
                    try {
                        mMode!!.setParameter(
                            RequestKey.HW_SUPER_NIGHT_ISO,
                            text?.split(PIVOT.toRegex())?.toTypedArray()?.get(1)?.toLong()
                        )
                    } catch (e: PatternSyntaxException) {
                        Log.e(
                            TAG,
                            "patternSyntaxException NumberFormatException text: $text"
                        )
                    } catch (e: NumberFormatException) {
                        Log.e(
                            TAG,
                            "patternSyntaxException NumberFormatException text: $text"
                        )
                    }
                }
            })
    }

    private fun initManualExposureSpinner() {
        var ranges = arrayOfNulls<Long>(0)
        val parameters =
            mModeCharacteristics!!.supportedParameters
        if (parameters != null && parameters.contains(RequestKey.HW_SUPER_NIGHT_EXPOSURE)) {
            val lists =
                mModeCharacteristics!!.getParameterRange(RequestKey.HW_SUPER_NIGHT_EXPOSURE)
            ranges = arrayOfNulls(lists.size)
            lists.toLongArray()
        }
        initSpinner(
            R.id.manualExposure,
            longToList(ranges, R.string.manualExposure),
            object : SpinnerOperation {
                override fun doOperation(text: String?) {
                    try {
                        mMode!!.setParameter(
                            RequestKey.HW_SUPER_NIGHT_EXPOSURE,
                            text?.split(PIVOT.toRegex())?.toTypedArray()?.get(1)?.toLong()
                        )
                    } catch (e: PatternSyntaxException) {
                        Log.e(
                            TAG,
                            "patternSyntaxException NumberFormatException text: $text"
                        )
                    } catch (e: NumberFormatException) {
                        Log.e(
                            TAG,
                            "patternSyntaxException NumberFormatException text: $text"
                        )
                    }
                }
            })
    }

    private fun initSpinner(
        resId: Int,
        list: List<String?>,
        operation: SpinnerOperation
    ) {
        val spinner: Spinner = findViewById(resId)
        spinner.visibility = View.VISIBLE
        if (list.size == 0) {
            spinner.visibility = View.GONE
            return
        }
        val adapter: ArrayAdapter<String?> =
            ArrayAdapter<String?>(this, R.layout.item, R.id.itemText, list)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View,
                position: Int,
                id: Long
            ) {
                val text = spinner.getItemAtPosition(position).toString()
                mCameraKitHandler!!.post {
                    if (mMode != null) {
                        operation.doOperation(text)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun longToList(
        values: Array<Long?>,
        id: Int
    ): List<String?> {
        val lists: MutableList<String?> =
            ArrayList(0)
        if (values == null || values.size == 0) {
            Log.d(TAG, "getLongList, values is null")
            return lists
        }
        for (mode in values) {
            lists.add(getString(id) + PIVOT + mode)
        }
        return lists
    }

    private fun createMode() {
        Log.i(TAG, "createMode begin")
        mCameraKit = CameraKit.getInstance(applicationContext)
        if (mCameraKit == null) {
            Log.e(TAG, "This device does not support CameraKit！")
            showToast("CameraKit not exist or version not compatible")
            return
        }
        // Query camera id list
        val cameraLists = mCameraKit!!.cameraIdList
        if (cameraLists != null && cameraLists.size > 0) {
            Log.i(TAG, "Try to use camera with id " + cameraLists[0])
            // Query supported modes of this device
            val modes = mCameraKit!!.getSupportedModes(cameraLists[0])
            if (!Arrays.stream(modes)
                    .anyMatch { i: Int -> i == mCurrentModeType }
            ) {
                Log.w(TAG, "Current mode is not supported in this device!")
                return
            }
            try {
                if (!mCameraOpenCloseLock.tryAcquire(
                        2000,
                        TimeUnit.MILLISECONDS
                    )
                ) {
                    throw RuntimeException("Time out waiting to lock camera opening.")
                }
                mCameraKit!!.createMode(
                    cameraLists[0],
                    mCurrentModeType,
                    mModeStateCallback,
                    mCameraKitHandler!!
                )
            } catch (e: InterruptedException) {
                throw RuntimeException(
                    "Interrupted while trying to lock camera opening.",
                    e
                )
            }
        }
        Log.i(TAG, "createMode end")
    }

    private fun configMode() {
        Log.i(TAG, "configMode begin")
        // Query supported preview size
        val previewSizes =
            mModeCharacteristics!!.getSupportedPreviewSizes(
                SurfaceTexture::class.java
            )
        // Query supported capture size
        val captureSizes =
            mModeCharacteristics!!.getSupportedCaptureSizes(ImageFormat.JPEG)
        Log.d(
            TAG,
            "configMode: captureSizes = " + captureSizes.size + ";previewSizes=" + previewSizes.size
        )
        // Use the first one or default 4000x3000
        mCaptureSize = captureSizes.stream().findFirst().orElse(Size(4000, 3000))
        // Use the same ratio with preview
        val tmpPreviewSize = previewSizes.stream()
            .filter { size: Size ->
                abs(1.0f * size.height / size.width - 1.0f * mCaptureSize!!.height / mCaptureSize!!.width) < 0.01
            }.findFirst().get()
        Log.i(
            TAG,
            "configMode: mCaptureSize = $mCaptureSize;mPreviewSize=$mPreviewSize"
        )
        // Update view
        runOnUiThread {
            mTextureView?.setAspectRatio(
                tmpPreviewSize.height,
                tmpPreviewSize.width
            )
        }
        waitTextureViewSizeUpdate(tmpPreviewSize)
        val texture: SurfaceTexture = mTextureView?.surfaceTexture!!
        if (texture == null) {
            Log.e(TAG, "configMode: texture=null!")
            return
        }
        // Set buffer size of view
        texture.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
        // Get surface of texture
        val surface = Surface(texture)
        // Add preview and capture parameters to config builder
        modeConfigBuilder!!.addPreviewSurface(surface)
            .addCaptureImage(mCaptureSize!!, ImageFormat.JPEG)
        // Set callback for config builder
        modeConfigBuilder!!.setDataCallback(actionDataCallback, mCameraKitHandler)
        modeConfigBuilder!!.setStateCallback(actionStateCallback, mCameraKitHandler)
        // Configure mode
        mMode!!.configure()
        Log.i(TAG, "configMode end")
    }

    private fun waitTextureViewSizeUpdate(targetPreviewSize: Size) {
        if (mPreviewSize == null) {
            mPreviewSize = targetPreviewSize
            mPreviewSurfaceChangedDone.close()
            mPreviewSurfaceChangedDone.block(PREVIEW_SURFACE_READY_TIMEOUT)
        } else {
            if (targetPreviewSize.height * mPreviewSize!!.width
                - targetPreviewSize.width * mPreviewSize!!.height == 0
            ) {
                mPreviewSize = targetPreviewSize
            } else {
                mPreviewSize = targetPreviewSize
                mPreviewSurfaceChangedDone.close()
                mPreviewSurfaceChangedDone.block(PREVIEW_SURFACE_READY_TIMEOUT)
            }
        }
    }

    private fun captureImage() {
        Log.i(TAG, "captureImage begin")
        if (mMode != null) {
            mMode!!.setImageRotation(90)
            // Default jpeg file path
            mFile = File(
                getExternalFilesDir(null),
                System.currentTimeMillis().toString() + "pic.jpg"
            )
            // Take picture
            mMode!!.takePicture()
        }
        Log.i(TAG, "captureImage end")
    }

    private fun stopPicture() {
        /** In the super night view mode, call takepicture to enter the long exposure stage.
         * You can call stoppicture to end the exposure in advance after receive TakePictureResult.State.CAPTURE_EXPOSURE_BEGIN, and get the photo   */
        if (mMode != null) {
            mButtonStopPicture!!.visibility = View.INVISIBLE
            mMode!!.stopPicture()
        }
    }

    private fun showToast(text: String) {
        runOnUiThread { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate: ")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mButtonCaptureImage = findViewById(R.id.capture_image)
        mButtonCaptureImage?.setOnClickListener(View.OnClickListener { v: View? -> captureImage() })
        mButtonStopPicture = findViewById(R.id.stopPicture)
        mButtonStopPicture?.setOnClickListener(View.OnClickListener { v: View? -> stopPicture() })
        mButtonStopPicture?.setVisibility(View.INVISIBLE)
        mTextureView = findViewById(R.id.texture)
    }

    override fun onStart() {
        Log.d(TAG, "onStart: ")
        super.onStart()
    }

    override fun onResume() {
        Log.d(TAG, "onResume: ")
        super.onResume()
        if (!PermissionHelper.hasPermission(this)) {
            PermissionHelper.requestPermission(this)
            return
        } else {
            if (!initCameraKit()) {
                showAlertWarning(getString(R.string.warning_str))
                return
            }
        }
        startBackgroundThread()
        if (mTextureView != null) {
            if (mTextureView!!.isAvailable) {
                mTextureView?.surfaceTextureListener = mSurfaceTextureListener
                mCameraKitHandler!!.post { createMode() }
            } else {
                mTextureView?.surfaceTextureListener = mSurfaceTextureListener
            }
        }
    }

    private fun showAlertWarning(msg: String) {
        AlertDialog.Builder(this).setMessage(msg)
            .setTitle("warning:")
            .setCancelable(false)
            .setPositiveButton(
                "OK"
            ) { dialog, which -> finish() }
            .show()
    }

    override fun onPause() {
        Log.d(TAG, "onPause: ")
        if (mMode != null) {
            mCameraKitHandler!!.post {
                mMode = try {
                    mCameraOpenCloseLock.acquire()
                    mMode!!.release()
                    null
                } catch (e: InterruptedException) {
                    throw RuntimeException(
                        "Interrupted while trying to lock camera closing.",
                        e
                    )
                } finally {
                    Log.d(TAG, "closeMode:")
                    mCameraOpenCloseLock.release()
                }
            }
        }
        super.onPause()
    }

    /**
     * Camera Kit Initialize
     */
    private fun initCameraKit(): Boolean {
        mCameraKit = CameraKit.getInstance(applicationContext)
        if (mCameraKit == null) {
            Log.e(
                TAG,
                "initCamerakit: this devices not support camerakit or not installed!"
            )
            return false
        }
        return true
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: ")
        super.onDestroy()
        stopBackgroundThread()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        Log.d(TAG, "onRequestPermissionsResult: ")
        if (!PermissionHelper.hasPermission(this)) {
            Toast.makeText(this, "This application needs camera permission.", Toast.LENGTH_LONG)
                .show()
            finish()
        }
    }

    private fun startBackgroundThread() {
        Log.d(TAG, "startBackgroundThread")
        if (mCameraKitThread == null) {
            mCameraKitThread = HandlerThread("CameraBackground")
            mCameraKitThread!!.start()
            mCameraKitHandler = Handler(mCameraKitThread!!.looper)
            Log.d(
                TAG,
                "startBackgroundTThread: mCameraKitThread.getThreadId()=" + mCameraKitThread!!.threadId
            )
        }
    }

    private fun stopBackgroundThread() {
        Log.d(TAG, "stopBackgroundThread")
        if (mCameraKitThread != null) {
            mCameraKitThread!!.quitSafely()
            try {
                mCameraKitThread!!.join()
                mCameraKitThread = null
                mCameraKitHandler = null
            } catch (e: InterruptedException) {
                Log.e(TAG, "InterruptedException in stopBackgroundThread " + e.message)
            }
        }
    }
}
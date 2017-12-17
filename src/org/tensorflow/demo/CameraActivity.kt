package org.tensorflow.demo

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.Image.Plane
import android.media.ImageReader.OnImageAvailableListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread

import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast

import java.io.IOException

import org.tensorflow.demo.env.Logger

import android.content.Intent
import android.speech.RecognizerIntent

import java.util.Locale

import android.view.MotionEvent

abstract class CameraActivity : Activity(), OnImageAvailableListener, LocationListener, TextToSpeech.OnInitListener {

    internal var currentLocationAddress: String = ""
    internal var myTTS: TextToSpeech? = null

    var isDebug = false
        private set
    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null
    protected var i: Intent? = null

    internal var locationManager: LocationManager? = null
    private var latitude = 0.0
    private var longitude = 0.0
    protected abstract val layoutId: Int
    protected abstract val desiredPreviewFrameSize: Size

    override fun onCreate(savedInstanceState: Bundle?) {

        LOGGER.d("onCreate " + this)
        super.onCreate(savedInstanceState)
        myTTS = TextToSpeech(this, this)

        setContentView(R.layout.activity_camera)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (hasPermission()) {
            setFragment()

        } else {
            requestPermission()
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    Activity#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for Activity#requestPermissions for more details.
            return
        }
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // GPS 프로바이더 사용가능여부
        val isGPSEnabled = locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)
        // 네트워크 프로바이더 사용가능여부
        val isNetworkEnabled = locationManager!!.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (isGPSEnabled)
            locationManager!!.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, this)
        if (isNetworkEnabled)
            locationManager!!.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0f, this)


    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        val action = event.action
        when (action) {
            MotionEvent.ACTION_DOWN    //화면을 터치했을때
            -> {

                i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                i!!.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                i!!.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR") //인식할 언어
                i!!.putExtra(RecognizerIntent.EXTRA_PROMPT, "말해주세요")
                //Toast.makeText(CameraActivity.this, "start speak", Toast.LENGTH_SHORT).show();
                try {
                    startActivityForResult(i, RESULT_SPEECH)
                } catch (e: ActivityNotFoundException) {
                    //Toast.makeText(getApplicationContext(),"STT X", Toast.LENGTH_SHORT).show();
                    //Test
                    findAddress()
                }

                val sstResult = i!!.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            }
            MotionEvent.ACTION_UP    //화면을 터치했다 땠을때
            -> {
            }
            MotionEvent.ACTION_MOVE    //화면을 터치하고 이동할때
            -> {
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        LOGGER.d("onActivity " + this)
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && requestCode == RESULT_SPEECH) {
            val sstResult = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            Toast.makeText(applicationContext, sstResult[0], Toast.LENGTH_LONG).show()
            if (sstResult[0] == "현재 위치" || sstResult[0] == "현재위치") {
                findAddress()
            }
        }
    }

    @Synchronized public override fun onStart() {
        LOGGER.d("onStart " + this)
        super.onStart()
    }

    @Synchronized public override fun onResume() {
        LOGGER.d("onResume " + this)
        super.onResume()

        handlerThread = HandlerThread("inference")
        handlerThread!!.start()
        handler = Handler(handlerThread!!.looper)
    }

    @Synchronized public override fun onPause() { //다른 엑티비티 멈춤
        LOGGER.d("onPause " + this)

        handlerThread!!.quitSafely()
        try {
            handlerThread!!.join()
            handlerThread = null
            handler = null
        } catch (e: InterruptedException) {
            LOGGER.e(e, "Exception!")
        }

        super.onPause()
    }

    @Synchronized public override fun onStop() {
        LOGGER.d("onStop " + this)
        super.onStop()
    }

    @Synchronized public override fun onDestroy() {
        LOGGER.d("onDestroy " + this)
        super.onDestroy()
    }

    @Synchronized protected fun runInBackground(r: () -> Unit) {
        if (handler != null) {
            handler!!.post(r)
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST -> {
                if (grantResults.size > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED
                        && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    setFragment()
                } else {
                    requestPermission()
                }
            }
        }
    }

    //위치변경시 호출
    override fun onLocationChanged(location: Location) {
        Toast.makeText(applicationContext, location.latitude.toString() + ", " + location.longitude, Toast.LENGTH_SHORT).show()
        latitude = location.latitude
        longitude = location.longitude
    }

    private fun findAddress() {
        val bf = StringBuffer()
        val geocoder = Geocoder(this, Locale.KOREA)
        val address: List<Address>?
        try {
            if (geocoder != null) {
                // 세번째 인수는 최대결과값인데 하나만 리턴받도록 설정했다
                address = geocoder.getFromLocation(latitude, longitude, 1)
                // 설정한 데이터로 주소가 리턴된 데이터가 있으면
                if (address != null && address.size > 0) {
                    // 주소
                    currentLocationAddress = address[0].getAddressLine(0).toString()
                    Log.e("Hong", "current Address : " + currentLocationAddress)
                    myTTS!!.speak("현재 위치는 $currentLocationAddress 입니다.", TextToSpeech.QUEUE_ADD, null)
                    for (i in 0..2) {
                        if (Math.abs(subway_XY[i][0] - latitude) <= 0.0001 && Math.abs(subway_XY[i][1] - longitude) <= 0.0001) {
                            myTTS!!.speak(subway[i] + " 부근입니다.", TextToSpeech.QUEUE_ADD, null)
                        }
                    }
                    // 전송할 주소 데이터 (위도/경도 포함 편집)
                    //bf.append(currentLocationAddress).append("#");
                    //bf.append(lat).append("#");
                    //bf.append(lng);
                }
            }

        } catch (e: IOException) {
            Toast.makeText(applicationContext, "주소취득 실패", Toast.LENGTH_LONG).show()

            e.printStackTrace()
        }

        //return bf.toString();
    }

    override fun onInit(status: Int) {

    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) || shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
                Toast.makeText(this@CameraActivity, "Camera AND storage permission are required for this demo", Toast.LENGTH_LONG).show()
            }
            requestPermissions(arrayOf(PERMISSION_CAMERA, PERMISSION_STORAGE, PERMISSION_GPS, PERMISSION_INTERNET, PERMISSION_RECORD, PERMISSION_VIBRATE), PERMISSIONS_REQUEST)
        }
    }

    protected fun setFragment() {
        val fragment = CameraConnectionFragment.newInstance(
                object : CameraConnectionFragment.ConnectionCallback {
                    override fun onPreviewSizeChosen(size: Size, rotation: Int) {
                        this@CameraActivity.onPreviewSizeChosen(size, rotation)
                    }
                },
                this,
                layoutId,
                desiredPreviewFrameSize)

        fragmentManager
                .beginTransaction()
                .replace(R.id.container, fragment)
                .commit()
    }

    protected fun fillBytes(planes: Array<Plane>, yuvBytes: Array<ByteArray>) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity())
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i])
        }
    }

    fun requestRender() {
        val overlay = findViewById(R.id.debug_overlay) as OverlayView
        overlay?.postInvalidate()
    }

    fun addCallback(callback: OverlayView.DrawCallback) {
        val overlay = findViewById(R.id.debug_overlay) as OverlayView
        overlay?.addCallback(callback)
    }

    open fun onSetDebug(debug: Boolean) {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            isDebug = !isDebug
            requestRender()
            onSetDebug(isDebug)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    protected abstract fun onPreviewSizeChosen(size: Size, rotation: Int)

    companion object {
        private val LOGGER = Logger()

        private val PERMISSIONS_REQUEST = 1

        private val PERMISSION_CAMERA = Manifest.permission.CAMERA
        private val PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
        private val PERMISSION_GPS = Manifest.permission.ACCESS_FINE_LOCATION
        private val PERMISSION_INTERNET = Manifest.permission.INTERNET
        private val PERMISSION_RECORD = Manifest.permission.RECORD_AUDIO
        private val PERMISSION_VIBRATE = Manifest.permission.VIBRATE
        private val subway = arrayOf("상록수역", "죽전역", "우리집")
        private val subway_XY = arrayOf(doubleArrayOf(37.302786, 126.866409), doubleArrayOf(37.324862, 127.107416), doubleArrayOf(37.300605, 126.857266))

        private val RESULT_SPEECH = 1
    }
}

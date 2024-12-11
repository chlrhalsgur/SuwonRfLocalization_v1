package com.example.SuwonRfLocalization

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.example.coexlibrary.ExIndoorLocalization
import com.example.suwonRfLocalization.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.kircherelectronics.fsensor.observer.SensorSubject
import com.kircherelectronics.fsensor.sensor.FSensor
import com.kircherelectronics.fsensor.sensor.gyroscope.GyroscopeSensor
import kotlinx.coroutines.*


class MainActivity : AppCompatActivity(), SensorEventListener {
    private var curFloor = -2.0
    private val mSensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val vibrator by lazy {
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var arrow_update_time: Long = 300

    /* 자이로 센서 관련 변수 */
    private lateinit var fSensor: FSensor
    private var fusedOrientation = FloatArray(3)

    /* 자이로스코프 관련 함수 */
    private val sensorObserver = SensorSubject.SensorObserver { values -> updateValues(values!!) }
    private fun updateValues(values: FloatArray) {
        fusedOrientation = values
    }

    /* RF 엔진 관련 변수 */
    var wifiRange = arrayListOf(-1.0f, -1.0f, -1.0f, -1.0f)

    val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            exIndoorLocalization.wifipermitted = success
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    /* 측위 관련 변수 */
    private lateinit var exIndoorLocalization: ExIndoorLocalization
    private var resultPosition : ArrayList<Double> = arrayListOf(0.0, 0.0, 0.0)
    private var preResultPosition : ArrayList<Double> = arrayListOf(1.0, 1.0, 1.0)
    private var lastStep_pdr = 0

    /* 웹뷰 관련 변수 */
    private val mHandler: Handler = Handler(Looper.myLooper()!!)
    private lateinit var webView : WebView
    private val HTML_FILE = "http://163.152.52.60:8001/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermission()
        webView = findViewById(R.id.webView)
        webViewSetting(HTML_FILE)

        // 자이로스코프 센서 초기화
        fSensor = GyroscopeSensor(this)
        (fSensor as GyroscopeSensor).register(sensorObserver)
        (fSensor as GyroscopeSensor).start()

        exIndoorLocalization = ExIndoorLocalization(this)

        // 현재 방향 표시 (0.x 초마다 방향 업데이트)
        CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                printArrowInWebView(exIndoorLocalization.userDir)
                delay(arrow_update_time)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null) {
            resultPosition = exIndoorLocalization.sensorChanged(event, fusedOrientation)
            Log.d("adaiosdja", resultPosition.toString())
            if (resultPosition != preResultPosition){
                checkFloor(resultPosition[2])
                try {
                    printDotInWebView(resultPosition[0], resultPosition[1], true)  // 마지막 인자에 true가 들어가야합니다.
                }catch (e:Exception){}
                preResultPosition = resultPosition
            }

            if (lastStep_pdr != exIndoorLocalization.stepCount) { // 걸음 인식 되면,
                vibrator.vibrate(30)
                lastStep_pdr = exIndoorLocalization.stepCount
            }
        }
    }

    private fun checkFloor(floor: Double) {
        if (curFloor != floor){
            val fileName = when (floor){
                -1.0 -> "COEX_B1F.png"
                1.0 -> "COEX_1F.png"
                2.0 -> "COEX_2F.png"
                3.0 -> "COEX_3F.png"
                4.0 -> "COEX_4F.png"
                5.0 -> "COEX_5F.png"
                6.0 -> "COEX_6F.png"
                else -> "COEX_B1F.png"
            }
            mHandler.postDelayed(Runnable {
                webView.loadUrl("javascript:changeMapImage('${fileName}')")
            }, 100)
            curFloor = floor
        }
    }
    fun printDotInWebView(x: Double, y: Double, remove_range: Boolean = true) {
        if (remove_range)
            removeRangeInWebView()
        mHandler.postDelayed(Runnable {
            webView.loadUrl("javascript:show_my_position($x, $y)")
        }, 100)
    }

    fun printRangeInWebView(wifiRange: ArrayList<Float>, floor: Int) {
        mHandler.postDelayed(kotlinx.coroutines.Runnable {
            webView.loadUrl("javascript:showArea(${wifiRange[2]},${wifiRange[3]},${wifiRange[0]},${wifiRange[1]},$floor)")
            if ((wifiRange[3]-wifiRange[2]) > 10)
                webView.loadUrl("javascript:show_my_position(${(wifiRange[2]+wifiRange[3])/2}, ${(wifiRange[0]+wifiRange[1])/2}, ${floor})")
        }, 100)
    }


    fun removeRangeInWebView() {
        mHandler.postDelayed(kotlinx.coroutines.Runnable {
            webView.loadUrl("javascript:showArea(0,0,0,0)")
        }, 100)
    }

    fun printArrowInWebView(gyro_from_map: Float) {
        mHandler.postDelayed(kotlinx.coroutines.Runnable {
            webView.loadUrl("javascript:rotateArrow($gyro_from_map)")
        }, 100)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    private fun checkPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
                && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ), 101)
            } else {
                ActivityCompat.requestPermissions(this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ), 101)
            }
        }
    }

    private fun webViewSetting(html_file_name: String) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }

        webView.addJavascriptInterface(WebviewInterface(this), "NaviEvent")
        webView.loadUrl(html_file_name)
        webView.scrollTo(1690, 480)
        webView.isScrollbarFadingEnabled = true
        webView.setInitialScale(160)

        val webSettings = webView.settings
        webSettings.useWideViewPort = true
        webSettings.builtInZoomControls = true
        webSettings.javaScriptEnabled = true
        webSettings.javaScriptCanOpenWindowsAutomatically = false
        webSettings.setSupportMultipleWindows(false)
        webSettings.setSupportZoom(true)
        webSettings.domStorageEnabled = true
    }

    override fun onResume() {
        super.onResume()
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT), SensorManager.SENSOR_DELAY_GAME)
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_GAME)
        /* RF 엔진 관련 */
        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    override fun onPause() {
        super.onPause()
        mSensorManager.unregisterListener(this)
        unregisterReceiver(wifiScanReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

class WebviewInterface(private val mContext: Context) {
    @JavascriptInterface
    fun showAndroidToast(toast: String) {
        Toast.makeText(mContext, toast, Toast.LENGTH_SHORT).show()
    }
}
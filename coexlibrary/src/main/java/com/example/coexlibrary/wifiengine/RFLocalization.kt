package com.example.mylibrary

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.kircherelectronics.fsensor.filter.gyroscope.OrientationGyroscope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt


val RSSITHRES = -75
class RFLocalization(uppercontext: Context, TESTBED: String) {
    private var isNetworkFailed = false
    private val straightParm = 15.0
    private var gyroErrCaliValue = 0.0
    private var mapDataTemp: MutableMap<String, MutableMap<String, java.util.ArrayList<java.util.ArrayList<Int>>>> = mutableMapOf()
    private var mapData: MutableMap<String, MutableMap<String, ArrayList<ArrayList<Int>>>> = mutableMapOf()
    private var testbed = TESTBED
    private val wifiScanResults: MutableList<ScanResult> = mutableListOf()
    private var wifiScanData: MutableMap<String, Int> = mutableMapOf("initData" to 0)
    private var prewifiScanData: MutableMap<String, Int> = mutableMapOf("initPreData" to 0)
    var wifipermitted = true
    lateinit var wifiManager: WifiManager
    private var firstScan = true
    private var isFresh = true
    private var firstScaned = false
    private var context = uppercontext
    var compassDirection = 0.0
    private var model = ""
    var isGetFloor = false
    var isGetRange = false
    private var statusFloor = 0
    private var statusRange = 0
    private var floor = ""
    private var range = arrayListOf(0.0, 0.0, 0.0, 0.0)
    var coorCandidate = arrayListOf(arrayListOf(0,0))
//    var coorsWithWeight = arrayListOf(arrayListOf(arrayListOf(0,0),0))
    val coorsWithWeight = mutableListOf<CoordinateData>()

    private var dirOffset = -1.0
    var rfCalliedGyro = 0.0f
    var rfGyro = 0.0f
    var rfGyroPre = 0.0f
    var rfGyroDiff = 0.0f
    var gyroOffsetAvgCount = 1
    var gyroOffsetAvgSum = 1.0
    var convergenceDirection = 0.0
    var errRateParm = 0.0
    var errRate = 0.5
    private var dirConvergence = false
    private var gyroDiffArray: ArrayList<Double> = arrayListOf()

    private var statusCode = 0.0
    private var queueForDirection = ArrayList<ArrayList<Double>>()
    private var queueForDirectionSize = 5
    var coor = arrayOf(0.0,0.0)
    var coorAvg = arrayOf(0.0,0.0)
    var coorMedian = arrayOf(0.0,0.0)
    var rfLocalizationCoor = arrayListOf(0.0, 0.0, 0.0)
    var weightAverage = arrayListOf(0.0,0.0)

    //gyro
    // gyro properties
    private val orientationGyroscope by lazy {
        OrientationGyroscope()
    }
    private var rotation = FloatArray(3)
//    private var fusedOrientation = FloatArray(3)
    private var dir : Double = 0.0
    private var rawGyroX : Double = 0.0
    private var rawGyroY : Double = 0.0
    private var rawGyroZ : Double = 0.0


    var rfLocalizationCoorArray = arrayListOf(arrayListOf(0.0,0.0))
    val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            wifipermitted = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
        }
    }

    var wifiThread = WifiThread(context)
    private var rfApiClient = RfApiClient()

    init {
        rfModuleInit()
    }
    fun rfModuleInit() {
        model = Build.MODEL.toString()

        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        firstScan = true

        wifiThreadStart()

    }


    private fun getWifiInfo(context: Context){
        if (wifipermitted) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            // Wi-Fi 스캔 결과 초기화
            wifiScanResults.clear()

            // Wi-Fi 스캔 시작
            wifiManager.startScan()

            val scanResultList: List<ScanResult> = wifiManager.scanResults.filter { it.level >= RSSITHRES }

            val wifiDataMap = scanResultList.associate {
                it.BSSID to it.level
            }
            wifiScanData = wifiDataMap.toMutableMap()
//            jsonData = Json.encodeToString(wifiData)
//            val jsonData1 = Json { encodeDefaults = false }.encodeToString(wifiData)
//            Log.d("wifi", jsonData.toString())

            if (wifiScanData != prewifiScanData){
                sendSsidRssi()
                prewifiScanData = wifiScanData
                dirOffset = getDirection(rfLocalizationCoor)
                isFresh = true
            }else{
                statusCode = 100.0
            }
        }
    }

    inner class WifiThread(private val context: Context) : Thread() {
        override fun run() {
            while (!isInterrupted) {
                try {
                    sleep(500)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                if((wifipermitted)) {
                    getWifiInfo(context)
                }
            }
        }
    }

    private fun wifiThreadStart() {
        wifiThread.isDaemon = true
        wifiThread.start()
    }


    fun calculateOrientation(accelerometerValues: FloatArray?, magnetometerValues: FloatArray?): Double {
        if (accelerometerValues != null && magnetometerValues != null) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues,
                magnetometerValues
            )

            val orientationValues = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationValues)

            val azimuthInRadians = orientationValues[0]

            compassDirection = (Math.toDegrees(azimuthInRadians.toDouble()) + 360)%360
            return compassDirection
        }
        else{
            compassDirection = 0.0
            return compassDirection
        }
    }

    fun sendFloorData(){
        runBlocking {
            launch(Dispatchers.IO) {
                val jsonData = Json.encodeToString(wifiScanData)
                val data = Json.encodeToString(mapOf("wifi_data" to jsonData, "testbed" to testbed, "model" to model))
                val responseData = rfApiClient.serverRequestFloor(data, "get_floor/")
                statusFloor = responseData["status"] as Int
                floor = responseData["floor"] as String
                isGetFloor = true
            }
        }
    }

    fun sendRangeData(){
        runBlocking {
            launch(Dispatchers.IO) {
                val jsonData = Json.encodeToString(wifiScanData)
                val data = Json.encodeToString(mapOf("wifi_data" to jsonData, "gyro" to compassDirection.toString()))
                val responseData = rfApiClient.serverRequestRange(data, "get_range/")
                statusRange = responseData["status"].toString().toInt()
                range = responseData["range"] as ArrayList<Double>

                isGetRange = true
            }
        }
    }

    // ssid로 데이터 일부만 받기 위함
    fun sendSSIDDataJson(){
        runBlocking {
            launch(Dispatchers.IO) {
                val ssidList = wifiScanData.keys.toList()
                val data = Json.encodeToString(mapOf("ssid_list" to ssidList))

                val deferredResponse = GlobalScope.async {
                    rfApiClient.serverRequestPartialDataSSID(data, "get_partial_data/")
                }

                GlobalScope.launch {
                    val responseData = deferredResponse.await()
                    statusRange = responseData["status"].toString().toInt()
                    if (responseData["mapData"] == ""){
                        mapDataTemp = mutableMapOf()
                    }
                    else{
                        mapDataTemp = responseData["mapData"] as MutableMap<String, MutableMap<String, ArrayList<ArrayList<Int>>>>
                    }
                }
                isGetRange = true
            }
        }
    }

    fun sendSsidRssi(){
        runBlocking {
            launch(Dispatchers.IO) {
                val data = Json.encodeToString(mapOf("rf_scan_data" to wifiScanData, "gyro" to mutableMapOf("gyro" to 90 )))
                val deferredResponse = GlobalScope.async {
                    rfApiClient.serverRequestRfSimilarity(data, "get_rf_similarity/")
                }

                GlobalScope.launch {
                    val responseData = deferredResponse.await()
                    statusRange = responseData["status"].toString().toInt()
                    if (statusRange.toDouble() == 400.0){
                        isNetworkFailed = true
                    }
                    rfLocalizationCoor = if (responseData["rf_localization_result"] == ""){
                        isNetworkFailed = true
                        rfLocalizationCoor
                    } else{
                        statusCode = 200.0
                        responseData["rf_localization_result"] as ArrayList<Double>
                    }
                    weightAverage = if (responseData["weighted_average"] == ""){
                        weightAverage
                    } else{
                        responseData["weighted_average"] as ArrayList<Double>
                    }
                }
                isGetRange = true
            }
        }
    }

    fun getParticles(wifiScanData: MutableMap<String, Int>, mapData: MutableMap<String, MutableMap<String, ArrayList<ArrayList<Int>>>>): Array<Double> {
        var xSig = 0.0
        var ySig = 0.0
        var wSum = 0.0
        var newWeight = 0.0
        var currentWeight = 0.0
        var timesApplied = 0
        var found = false
        coorsWithWeight.clear()
        for ((bssid, rssiList) in mapData){
            if ( bssid in wifiScanData){
                for ((rssi, coors) in rssiList){
                    var w = (5 - (rssi.toDouble() - wifiScanData[bssid]!!))
                    w = max(w, 0.0)
                    w = w.pow(2)

//                    val diff = abs(rssi.toDouble() - wifiScanData[bssid]!!)
//                    var w = 1 / (1 + diff)
//                    val additionalWeight = newWeight * newWeight // 비선형 누적
//                    val bonus = timesApplied * 0.5 // 가중치 적용 횟수 고려
//                    w = (currentWeight + additionalWeight + bonus)
//                    Log.d("asdjoasjdasdasd", w.toString())

                    // 겹치는 가중치 합산
                    for ((x, y) in coors){
                        found = false
                        for ((index, coord) in coorsWithWeight.withIndex()){
                            if (coord.coord == listOf(x, y)){
                                coorsWithWeight[index].intensity += w
                                found = true
                                break
                            }
                        }
                        if (!found){
                            coorsWithWeight.add(CoordinateData(listOf(x.toDouble(), y.toDouble()), w))
                        }
                    }

                    for ((x, y) in coors){
                        xSig += (w * x)
                        ySig += (w * y)
                        wSum += w
                    }
//                    for ((x, y) in coors) {
//                        wTotInc += w
//                        xAvgInc += (w / wTotInc) * (x - xAvgInc)
//                        yAvgInc += (w / wTotInc) * (y - yAvgInc)
//                    }
                }
                wifiScanData[bssid] //scan wifi rssi
            }
        }
        if (wSum == 0.0){
            return arrayOf(0.0,0.0)
        }
        return arrayOf(ceil(xSig/wSum), ceil(ySig/wSum))
//        return arrayOf(ceil(xAvgInc), ceil(yAvgInc))
    }
    private var walkingThres = 0.89
    private fun getDirection(rfLocalizationCoor: ArrayList<Double>): Double {
        var pathAngle = -1.0
        queueForDirection.add(rfLocalizationCoor)
        if (queueForDirection.size > queueForDirectionSize){
            var vectors = ArrayList<ArrayList<Double>>()

            // 방향이 계속 변하면 어떤 판단도 하기 힘듦
            rfGyroDiff = rfCalliedGyro - rfGyroPre
            if (rfGyroDiff > 180){
                rfGyroDiff -= 360.0f
            }else if (rfGyroDiff < -180.0){
                rfGyroDiff += 360.0f
            }
            rfGyroDiff = (rfGyroDiff%360.0).toFloat()
            rfGyroPre = rfCalliedGyro
            Log.d("asdasdada y", rfGyroDiff.toString())

            // gyroscope 값이 틀어졌다면(직선으로 걷지 않으면) 큐 비우고 실행 중단
            if (abs(rfGyroDiff) > straightParm){
//                queueForDirection.removeAt(0)
                queueForDirection.clear()
                return dirOffset
            }


            for (i in 0 until queueForDirection.size-1){
                vectors.add(arrayListOf(queueForDirection[i][1] - queueForDirection[0][1], queueForDirection[i][0] - queueForDirection[0][0]))
            }

            val vectorSimilarity = calculateVectorSimilarity(vectors)

            if (vectorSimilarity > walkingThres ){
                val dirRadian = atan2(queueForDirection[queueForDirection.size-1][0] - queueForDirection[0][0], queueForDirection[queueForDirection.size-1][1] - queueForDirection[0][1])
                pathAngle = (dirRadian * (180 / PI))
                pathAngle = 360 - (pathAngle + 360)%360  // x, y 반대라서가 아니라 시계방향으로 각도 추정해서 360에서 빼는 것임


                Log.d("asdasdada 에러", abs((pathAngle - rfCalliedGyro) - dirOffset).toString())

                if (!dirConvergence){ //방향 수렴 전
                    var errorRate = 0.0
                    if (dirOffset == -1.0){ // 처음 방향 수렴 시도 시 offset에 평균을 취할 값이 없음
                        dirOffset = pathAngle - rfCalliedGyro  // pathAngle만 있어서 rfGyro 뺐음
                        gyroOffsetAvgSum += dirOffset
                    }else{
                        val dirOffset2 = pathAngle - rfCalliedGyro
                        gyroOffsetAvgSum += dirOffset2

                        errorRate = dirOffset - (gyroOffsetAvgSum/(gyroOffsetAvgCount+1))
                        dirOffset = gyroOffsetAvgSum/(++gyroOffsetAvgCount)
                    }
                    Log.d("asdasdada 추정 각도와 차이", abs((pathAngle - rfCalliedGyro) - dirOffset).toString())
                    Log.d("asdasdada 추정 calligyro", rfCalliedGyro.toString())
                    Log.d("asdasdada 추정 error rate", errorRate.toString())
                    Log.d("asdasdada 추정 fsetAvgSum", gyroOffsetAvgSum.toString())
                    Log.d("asdasdada 추정 AvgCounte", gyroOffsetAvgCount.toString())

                    if (errorRate < errRateParm){
                        convergenceDirection = dirOffset + rfCalliedGyro
                        dirConvergence = true
                    }
                }else{ //방향 수렴 이후
                    if (abs((pathAngle - rfCalliedGyro) - dirOffset) > 30){ // 수렴 이후인데 현재 이동 경로 각도에 gyro 만큼 더한 예측 각도와
                                                                               // 수렴 시 각도가 10도 이상 벌어지면 재탐색 진행
                        gyroErrCaliValue = rfGyro*(-1.0) // 현재 방향을 0도로 맞추고

                        dirOffset = pathAngle  // 현재 이동 경로 각도를 offset으로 설정
                        gyroOffsetAvgSum = 1.0  //
                        gyroOffsetAvgSum += dirOffset
                        gyroOffsetAvgCount = 1
                        dirConvergence = false
                        Log.d("asdasdada 수렴 이후 재탐색", dirOffset.toString())
                        Log.d("asdasdada gyroCalliVal", gyroErrCaliValue.toString())
                        Log.d("asdasdada gyroOffsetAv", gyroOffsetAvgSum.toString())
                        Log.d("asdasdada gyroOff", gyroOffsetAvgCount.toString())
                        Log.d("asdasdada dirConverge", dirConvergence.toString())
                        Log.d("asdasdada Callrfgyro", rfCalliedGyro.toString())

//                        dirOffset = convergenceDirection
//
//                        //gyro 보정
//                        gyroDiffArray.add((convergenceDirection - pathAngle) - rfGyro)
//                        if (gyroDiffArray.size > 10){ // 차이 값이 10보다 크다-> 파라미터 없애야하는데 일단 둠
//                            val gyroDiffAvg = gyroDiffArray.average()
//                            convergenceDirection -= gyroDiffAvg
//                            dirOffset = convergenceDirection
//                        }
                    }
                }
            }
        }
        return dirOffset
    }

    private fun calculateVectorSimilarity(vectors: List<ArrayList<Double>>): Double {
        val numVectors = vectors.size
        if (numVectors < 2) {
            return 0.0 // 하나 이하의 벡터는 비교할 필요가 없음
        }

        val normalizedVectors = vectors.map { vector ->
            val magnitude = sqrt(vector[0] * vector[0] + vector[1] * vector[1])
            if (magnitude == 0.0) {
                vector // 크기가 0인 경우, 그대로 반환
            } else {
                arrayListOf(vector[0] / magnitude, vector[1] / magnitude)
            }
        }

        var sumVector = arrayListOf(0.0, 0.0)
        for (vector in normalizedVectors) {
            sumVector = arrayListOf(sumVector[0] + vector[0], sumVector[1] + vector[1])
        }
        val magnitude = sqrt(sumVector[0] * sumVector[0] + sumVector[1] * sumVector[1])

        return magnitude / numVectors
    }

    fun calculateAngleBetweenVectors(vector1: ArrayList<Double>, vector2: ArrayList<Double>): Double {
        val dotProduct = vector1[0] * vector2[0] + vector1[1] * vector2[1]
        val cosTheta = dotProduct
        val angleRadians = acos(cosTheta)
        val angleDegrees = Math.toDegrees(angleRadians)

        return angleDegrees
    }
    // getter setter
    fun getRfLocalization(): Map<String, Serializable> {
        if (isFresh){
            statusCode = 200.0
            isNetworkFailed = false
            isFresh = false
        }
        return mapOf("rfStatusCode" to statusCode, "rfCoor" to rfLocalizationCoor)
    }
    fun getterWifiData(): MutableMap<String, Int> { isGetRange = false; return wifiScanData }
    fun getRfFloor(): Map<String, Any> { isGetFloor = false; return mapOf("status" to statusFloor, "floor" to floor) }
    fun getRfRange(): Map<String, Serializable> { isGetRange = false; return mapOf("status" to statusRange, "range" to range) }
    fun getRfMap(): Map<String, Any?> { return mapOf("status" to statusRange, "mapData" to mapDataTemp) }
    fun getDirOffset(gyroCaliValue: Float ): Double {
        return if (dirOffset == -1.0){
            gyroCaliValue.toDouble()
        }else{
            dirOffset
        }
    }

    fun getRfDirection(fusedOrientation: FloatArray, gyroMagCaliValue: Float, gyro: Float): Float {
        rfCalliedGyro = ((gyro + gyroErrCaliValue + 360)%360).toFloat()
        rfGyro = gyro
        val dirOffset = getDirOffset(gyroMagCaliValue).toFloat()

        rfCalliedGyro + dirOffset
//        return (((Math.toDegrees(fusedOrientation[0].toDouble()).toFloat() + 360) % 360 - gyroMagCaliValue + dirOffset) + 360) % 360
        return (dirOffset + rfCalliedGyro) % 360
//        return ((((rfCalliedGyro - gyroErrCaliValue + 360)%360 - gyroMagCaliValue + dirOffset) + 360) % 360).toFloat()
    }

}
data class CoordinateData(val coord: List<Double>, var intensity: Double)
data class Point(val x: Double, val y: Double)

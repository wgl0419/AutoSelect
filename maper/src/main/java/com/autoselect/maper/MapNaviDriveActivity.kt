package com.autoselect.maper

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import com.amap.api.navi.enums.AimLessMode
import com.amap.api.navi.model.*
import com.amap.api.navi.view.RouteOverLay
import com.autoselect.helper.ToastHelper.showLong
import kotlinx.android.synthetic.main.activity_navi_basic.*

class MapNaviDriveActivity : MapNaviActivity() {
    var isTurn: Boolean = false
    var isDirection: Boolean = false
    var isTrafficButton: Boolean = false
    var isTrafficProgress: Boolean = false
    var isTrafficBar: Boolean = false
    var isWay: Boolean = false
    var isZoomIn: Boolean = false
    var isZoom: Boolean = false
    var isOverview: Boolean = false
    var isMenu: Boolean = false
    var isRoute: Boolean = false
    var isTexture: Boolean = false
    var isCar: Boolean = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navi_basic)
        when {
            isHub -> {
                aMapHudView = findViewById(R.id.hudview)
                aMapHudView?.onCreate(savedInstanceState)
                aMapHudView?.setHudViewListener(this)
            }
            else -> {
                aMapNaviView = findViewById(R.id.navi_view)
                aMapNaviView?.onCreate(savedInstanceState)
                aMapNaviView?.setAMapNaviViewListener(this)
            }
        }
        aMapNaviView?.apply {
            if (isTurn) lazyNextTurnTipView = nextTurnTipView
            if (isDirection) lazyDirectionView = directionView
            if (isTrafficButton) lazyTrafficButtonView = trafficButtonView
            if (isTrafficBar) lazyTrafficBarView = trafficBarView.apply {
                setTmcBarHeightWhenLandscape(0.5)//横屏<0.1用0.1，值>1用1：为原始图片一半
                setTmcBarHeightWhenPortrait(1.0)//竖屏<0.1用0.1，值>1用1：和原始图片一致
                unknownTrafficColor = Color.WHITE
                smoothTrafficColor = Color.GREEN
                slowTrafficColor = Color.YELLOW
                jamTrafficColor = Color.DKGRAY
                veryJamTrafficColor = Color.BLACK
            }
            if (isTrafficProgress) trafficProgressBar.apply {
                setUnknownTrafficColor(Color.WHITE)
                setSmoothTrafficColor(Color.GREEN)
                setSlowTrafficColor(Color.YELLOW)
                setJamTrafficColor(Color.DKGRAY)
                setVeryJamTrafficColor(Color.BLACK)
            }
            if (isWay) lazyDriveWayView = driveWayView
            if (isZoomIn) lazyZoomInIntersectionView = zoomInIntersectionView
            if (isZoom) setLazyZoomButtonView(zoomButtonView)
            if (isOverview) setLazyOverviewButtonView(overviewButtonView)
        }
        aMapNaviView?.viewOptions?.apply {
            if (isMenu) isSettingMenuEnabled = true
            if (isRoute || isTexture) isAutoDrawRoute = false
            if (isTexture) routeOverlayOptions = RouteOverlayOptions().apply {
                arrowOnTrafficRoute =
                    BitmapFactory.decodeResource(resources, R.mipmap.custtexture_aolr)
                normalRoute = BitmapFactory.decodeResource(resources, R.mipmap.custtexture)
                unknownTraffic = BitmapFactory.decodeResource(resources, R.mipmap.custtexture_no)
                smoothTraffic = BitmapFactory.decodeResource(resources, R.mipmap.custtexture_green)
                slowTraffic = BitmapFactory.decodeResource(resources, R.mipmap.custtexture_slow)
                jamTraffic = BitmapFactory.decodeResource(resources, R.mipmap.custtexture_bad)
                veryJamTraffic =
                    BitmapFactory.decodeResource(resources, R.mipmap.custtexture_grayred)
            }
            if (isTrafficProgress) isTrafficBarEnabled = isTrafficProgress
            if (isTrafficBar) {
                isTrafficBarEnabled = isTrafficBar
                tilt = 0//倾斜[0，60]，0为2D模式
                setPointToCenter(1.0 / 2, 1.0 / 2)//自车位置锁定在屏幕中的位置，一参宽度，二参高度
            }
            if (isCar) {
                carBitmap = BitmapFactory.decodeResource(resources, R.mipmap.car)
                fourCornersBitmap = BitmapFactory.decodeResource(resources, R.mipmap.lane00)
                setStartPointBitmap(BitmapFactory.decodeResource(resources, R.mipmap.navi_start))
                setWayPointBitmap(BitmapFactory.decodeResource(resources, R.mipmap.navi_way))
                setEndPointBitmap(BitmapFactory.decodeResource(resources, R.mipmap.navi_end))
            }
            if (isAimless) mapView = map
        }
    }

    private val isOrientationLandscape: Boolean =
        requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        trafficBarView?.onConfigurationChanged(isOrientationLandscape)
    }

    override fun onGpsSignalWeak(boolean: Boolean) {}
    fun setCarNumber(number: String) =
        aMapNavi?.setCarInfo(AMapCarInfo().apply { carNumber = number })

    val calculateDriveRoute = try {
        aMapNavi?.run {
            calculateDriveRoute(
                startPoints, endPoints, wayPoints, strategyConvert(true, false, false, false, false)
            )//躲避拥堵、不走高速A、避免收费B、高速优先AB、不多路径
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    override fun onInitNaviSuccess() {
        super.onInitNaviSuccess()
        when {
            isAimless -> aMapNavi?.startAimlessMode(AimLessMode.CAMERA_AND_SPECIALROAD_DETECTED)
            else -> calculateDriveRoute
        }
    }

    override fun onTrafficStatusUpdate() {
        super.onTrafficStatusUpdate()
        if (isTrafficBar) {
            val end: Int = aMapNaviPath?.allLength ?: 0
            val start: Int = end - remainingDistance
            trafficBarView?.update(aMapNavi?.getTrafficStatuses(start, end), remainingDistance)
        }
    }

    private var remainingDistance: Int = 0
    override fun onNaviInfoUpdate(naviInfo: NaviInfo?) {
        super.onNaviInfoUpdate(naviInfo)
        when {
            isTrafficBar -> remainingDistance = naviInfo?.pathRetainDistance ?: 0
            isTrafficProgress -> naviInfo?.let {
                aMapNavi?.run {
                    trafficProgressBar?.update(
                        naviPath.allLength, it.pathRetainDistance, getTrafficStatuses(0, 0)
                    )
                }
            }
        }
    }

    private var array: Array<String?> = arrayOf(
        "直行车道",
        "左转车道",
        "左转或直行车道",
        "右转车道",
        "右转或这行车道",
        "左掉头车道",
        "左转或者右转车道",
        " 左转或右转或直行车道",
        "右转掉头车道",
        "直行或左转掉头车道",
        "直行或右转掉头车道",
        "左转或左掉头车道",
        "右转或右掉头车道",
        "无",
        "无",
        "不可以选择该车道"
    )
    private var actions: Array<String?> = arrayOf(
        "直行",
        "左转",
        "左转或直行",
        "右转",
        "右转或这行",
        "左掉头",
        "左转或者右转",
        " 左转或右转或直行",
        "右转掉头",
        "直行或左转掉头",
        "直行或右转掉头",
        "左转或左掉头",
        "右转或右掉头",
        "无",
        "无",
        "不可以选择"
    )
    private val Char.toHex: Int
        get() = when (this) {
            in '0'..'9' -> (this - '0')
            in 'a'..'f' -> (this - 'a' + 10)
            in 'A'..'F' -> (this - 'A' + 10)
            else -> throw Exception("error param")
        }

    override fun showLaneInfo(
        laneInfos: Array<AMapLaneInfo?>?,
        laneBackgroundInfo: ByteArray?, laneRecommendedInfo: ByteArray?
    ) {
        super.showLaneInfo(laneInfos, laneBackgroundInfo, laneRecommendedInfo)
        if (isWay) StringBuffer().apply {
            laneInfos?.let {
                append("共${laneInfos.size}车道")
                for ((index, value) in laneInfos.withIndex()) {
                    try {
                        value?.run { append("，第${index + 1}车道为${array[laneTypeIdHexString[0].toHex]}，该车道可执行动作为${actions[laneTypeIdHexString[1].toHex]}") }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }.run { showLong(toString()) }
    }

    private var aMapNaviPath: AMapNaviPath? = null
    override fun onCalculateRouteSuccess(aMapCalcRouteResult: AMapCalcRouteResult?) {
        if (isRoute) IntArray(10).apply {
            this[0] = Color.BLACK
            this[1] = Color.RED
            this[2] = Color.BLUE
            this[3] = Color.YELLOW
            this[4] = Color.GRAY
        }.let {
            RouteOverLay(aMapNaviView?.map, aMapNavi?.naviPath, this).apply {
                setStartPointBitmap(BitmapFactory.decodeResource(resources, R.mipmap.r1))
                setEndPointBitmap(BitmapFactory.decodeResource(resources, R.mipmap.b1))
                setWayPointBitmap(BitmapFactory.decodeResource(resources, R.mipmap.b2))
                isTrafficLine = false
                width = 30f
            }.addToMap(it, aMapNavi?.naviPath?.getWayPointIndex())
        }
        if (isTrafficBar) {
            aMapNaviPath = aMapNavi?.naviPath
            val end: Int = aMapNaviPath?.allLength ?: 0
            trafficBarView?.update(aMapNavi?.getTrafficStatuses(0, end), end)
        }
        super.onCalculateRouteSuccess(aMapCalcRouteResult)
    }
}
package com.autoselect.payer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.alipay.sdk.app.PayTask
import com.android.volley.AuthFailureError
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.autoselect.helper.ThreadHelper.poolSingle
import com.autoselect.helper.connectivityManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.tencent.mm.opensdk.modelpay.PayReq
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.unionpay.UPPayAssistEx
import okhttp3.*
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object PayHelper {
    enum class HttpType { Get, Post }
    enum class NetworkClientType { HttpUrlConnection, Volley, OkHttp, Retrofit }
    enum class PayWay(var payway: Int) {
        WeChatPay(0), ALiPay(1), UPPay(2);

        override fun toString(): String = payway.toString()
    }

    data class PayParams(
        var activity: Activity? = null,
        var weChatAppID: String? = null,
        var payWay: PayWay? = null,
        var goodsPrice: Float = 0f,
        var goodsName: String? = null,
        var goodsIntroduction: String? = null,
        var httpType: HttpType? = HttpType.Post,
        var networkClientType: NetworkClientType? = NetworkClientType.OkHttp,
        var apiUrl: String? = null
    )

    interface NetworkClientInter {
        interface CallBack {
            fun onSuccess(result: Any?)
            fun onFailure()
        }

        fun get(payParams: PayParams?, callBack: CallBack?)
        fun post(payParams: PayParams?, callBack: CallBack?)
    }

    interface OnPayInfoRequestListener {
        fun onPayInfoRequestStart()
        fun onPayInfoRequestSuccess()
        fun onPayInfoRequestFailure()
    }

    interface OnPayResultListener {
        fun onPaySuccess(payWay: PayWay?)
        fun onPayCancel(payWay: PayWay?)
        fun onPayFailure(payWay: PayWay?, errCode: Int)
    }

    val Runnable.execute
        get() = poolSingle?.execute(this)
    val shutdown = poolSingle?.run { if (!isShutdown) shutdownNow() }

    class HttpUrlConnectionClient : NetworkClientInter {
        override fun get(payParams: PayParams?, callBack: NetworkClientInter.CallBack?) {
            object : Thread() {
                override fun run() {
                    super.run()
                    val connection: HttpURLConnection? = null
                    try {
                        (URL(payParams?.run { "$apiUrl?pay_way=$payWay&price=$goodsPrice&goods_name=$goodsName&goods_introduction=$goodsIntroduction" }).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 20 * 1000
                            readTimeout = 10 * 1000
                            connect()
                            when (responseCode) {
                                HttpURLConnection.HTTP_OK -> inputStream?.use {
                                    ByteArray(512).let { bytes ->
                                        StringBuffer().apply {
                                            while (it.read(bytes) > 0) {
                                                append(String(bytes))
                                            }
                                        }.toString().run { callBack?.onSuccess(this) }
                                    }
                                }
                                else -> callBack?.onFailure()
                            }
                        }
                    } catch (e: Exception) {
                        callBack?.onFailure()
                    } finally {
                        connection?.disconnect()
                    }
                }
            }.execute
        }//服务器为微信、支付宝、银联等预支付信息走一个接口，通过pay_way或者其他字段进行区分，除商品详情介绍(goods_introduction)外，均为必须上传字段，key值协商定义

        override fun post(payParams: PayParams?, callBack: NetworkClientInter.CallBack?) {
            object : Thread() {
                override fun run() {
                    super.run()
                    var connection: HttpURLConnection? = null
                    try {
                        connection =
                            (URL(payParams?.apiUrl).openConnection() as HttpURLConnection).apply {
                                requestMethod = "POST"
                                connectTimeout = 20 * 1000
                                readTimeout = 10 * 1000
                                doOutput = true
                                outputStream.use {
                                    it.write((payParams?.run { "pay_way=$payWay&price=$goodsPrice&goods_name=$goodsName&goods_introduction=$goodsIntroduction" }
                                        ?: "").toByteArray())
                                    it.flush()
                                }
                                connect()
                                when (responseCode) {
                                    HttpURLConnection.HTTP_OK -> inputStream.use {
                                        ByteArray(512).let { bytes ->
                                            StringBuffer().apply {
                                                while (it.read(bytes) > 0) {
                                                    append(String(bytes))
                                                }
                                            }.toString().run { callBack?.onSuccess(this) }
                                        }
                                    }
                                    else -> callBack?.onFailure()
                                }
                            }
                    } catch (e: Exception) {
                        callBack?.onFailure()
                    } finally {
                        connection?.disconnect()
                    }
                }
            }.execute
        }//服务器为微信、支付宝、银联等预支付信息走一个接口，通过pay_way或者其他字段进行区分，除商品详情介绍(goods_introduction)外，均为必须上传字段，key值协商定义
    }

    class VolleyClient : NetworkClientInter {
        override fun get(payParams: PayParams?, callBack: NetworkClientInter.CallBack?) {
            payParams?.run { "$apiUrl?pay_way=$payWay&price=$goodsPrice&goods_name=$goodsName&goods_introduction=$goodsIntroduction" }
                .let {
                    StringRequest(Request.Method.GET, it,
                        Response.Listener { response -> callBack?.onSuccess(response) },
                        Response.ErrorListener { callBack?.onFailure() }
                    ).run { Volley.newRequestQueue(payParams?.activity).add(this) }
                }
        }

        override fun post(payParams: PayParams?, callBack: NetworkClientInter.CallBack?) {
            object : StringRequest(
                Method.POST, payParams?.apiUrl,
                Response.Listener { response -> callBack?.onSuccess(response) },
                Response.ErrorListener { callBack?.onFailure() }
            ) {
                @Throws(AuthFailureError::class)
                override fun getParams(): MutableMap<String?, String?>? = mutableMapOf(
                    Pair("pay_way", payParams?.payWay.toString()),
                    Pair("price", payParams?.goodsPrice.toString()),
                    Pair("goods_name", payParams?.goodsName),
                    Pair("goods_introduction", payParams?.goodsIntroduction)
                )
            }.run { Volley.newRequestQueue(payParams?.activity).add(this) }
        }
    }

    class OkHttpClientImpl : NetworkClientInter {
        override fun get(payParams: PayParams?, callBack: NetworkClientInter.CallBack?) {
            payParams?.run { "$apiUrl?pay_way=$payWay&price=$goodsPrice&goods_name=$goodsName&goods_introduction=$goodsIntroduction" }
                ?.let {
                    OkHttpClient().newCall(okhttp3.Request.Builder().url(it).build())
                        .enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                callBack?.onFailure()
                            }

                            @Throws(IOException::class)
                            override fun onResponse(call: Call, response: okhttp3.Response) {
                                when {
                                    response.isSuccessful -> callBack?.onSuccess(response.body?.string())
                                    else -> callBack?.onFailure()
                                }
                            }
                        })
                }
        }

        override fun post(payParams: PayParams?, callBack: NetworkClientInter.CallBack?) {
            payParams?.run {
                OkHttpClient().newCall(
                    okhttp3.Request.Builder().url(apiUrl ?: "").post(
                        FormBody.Builder()
                            .add("pay_way", payWay.toString())
                            .add("price", goodsPrice.toString())
                            .add("goods_name", goodsName ?: "")
                            .add("goods_introduction", goodsIntroduction ?: "").build()
                    ).build()
                ).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        callBack?.onFailure()
                    }

                    @Throws(IOException::class)
                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        when {
                            response.isSuccessful -> callBack?.onSuccess(response.body?.string())
                            else -> callBack?.onFailure()
                        }
                    }
                })
            }
        }
    }

    interface PrePayInfoService {
        @GET("?")
        fun getPrePayInfo(
            @Query("pay_way") payWay: String?,
            @Query("price") GoodsPrice: String?,
            @Query("goods_name") goodsName: String?,
            @Query("goods_introduction") goodsIntroduce: String?
        ): retrofit2.Call<ResponseBody?>?

        @POST("?")
        fun postPrePayInfo(
            @Query("pay_way") payWay: String?,
            @Query("price") GoodsPrice: String?,
            @Query("goods_name") goodsName: String?,
            @Query("goods_introduction") goodsIntroduce: String?
        ): retrofit2.Call<ResponseBody?>?
    }

    class RetrofitClient : NetworkClientInter {
        override fun get(payParams: PayParams?, callBack: NetworkClientInter.CallBack?) {
            payParams?.run {
                Retrofit.Builder().baseUrl(apiUrl).build().create(PrePayInfoService::class.java)
                    .getPrePayInfo(
                        payWay.toString(), goodsPrice.toString(), goodsName, goodsIntroduction
                    )?.enqueue(object : retrofit2.Callback<ResponseBody?> {
                        override fun onResponse(
                            call: retrofit2.Call<ResponseBody?>?,
                            response: retrofit2.Response<ResponseBody?>?
                        ) {
                            when (response?.isSuccessful) {
                                true -> callBack?.onSuccess(response.body().toString())
                                else -> callBack?.onFailure()
                            }
                        }

                        override fun onFailure(
                            call: retrofit2.Call<ResponseBody?>?,
                            t: Throwable?
                        ) {
                            callBack?.onFailure()
                        }
                    })
            }
        }

        override fun post(payParams: PayParams?, callBack: NetworkClientInter.CallBack?) {
            payParams?.run {
                Retrofit.Builder().baseUrl(apiUrl).build().create(PrePayInfoService::class.java)
                    .postPrePayInfo(
                        payWay.toString(), goodsPrice.toString(), goodsName, goodsIntroduction
                    )?.enqueue(object : retrofit2.Callback<ResponseBody?> {
                        override fun onResponse(
                            call: retrofit2.Call<ResponseBody?>?,
                            response: retrofit2.Response<ResponseBody?>?
                        ) {
                            when (response?.isSuccessful) {
                                true -> callBack?.onSuccess(response.body().toString())
                                else -> callBack?.onFailure()
                            }
                        }

                        override fun onFailure(
                            call: retrofit2.Call<ResponseBody?>?,
                            t: Throwable?
                        ) {
                            callBack?.onFailure()
                        }
                    })
            }
        }
    }

    fun newClient(networkClientType: NetworkClientType?): NetworkClientInter? =
        when (networkClientType) {
            NetworkClientType.HttpUrlConnection -> HttpUrlConnectionClient()
            NetworkClientType.Volley -> VolleyClient()
            NetworkClientType.OkHttp -> OkHttpClientImpl()
            NetworkClientType.Retrofit -> RetrofitClient()
            else -> HttpUrlConnectionClient()
        }

    interface PayStrategyInter {
        fun doPay()
    }

    class PayContext(private val mStrategy: PayStrategyInter?) {
        fun pay() {
            mStrategy?.doPay()
        }
    }

    class AutoPay private constructor(private var payParams: PayParams?) {
        val weChatAppID: String?
            get() = payParams?.weChatAppID
        private var mOnPayInfoRequestListener: OnPayInfoRequestListener? = null
        fun requestPayInfo(onPayInfoRequestListener: OnPayInfoRequestListener?): AutoPay = apply {
            payParams?.payWay?.let {
                mOnPayInfoRequestListener = onPayInfoRequestListener
                mOnPayInfoRequestListener?.onPayInfoRequestStart()
                newClient(payParams?.networkClientType)?.run {
                    object : NetworkClientInter.CallBack {
                        override fun onSuccess(result: Any?) {
                            mOnPayInfoRequestListener?.onPayInfoRequestSuccess()
                            doPay(result as String?)
                        }

                        override fun onFailure() {
                            mOnPayInfoRequestListener?.onPayInfoRequestFailure()
                            sendPayResult(COMMON_ERR_REQUEST_TIME_OUT)
                        }
                    }.let {
                        when (payParams?.httpType) {
                            HttpType.Get -> get(payParams, it)
                            HttpType.Post -> post(payParams, it)
                            else -> post(payParams, it)
                        }
                    }
                }
            } ?: throw NullPointerException("请设置支付方式")
        }

        interface PayCallBack {
            fun onPayCallBack(code: Int)
        }

        private fun doPay(prePayInfo: String?) = object : PayCallBack {
            override fun onPayCallBack(code: Int) = sendPayResult(code)
        }.let {
            when (payParams?.payWay) {
                PayWay.WeChatPay -> PayContext(WeChatPayStrategy(payParams, prePayInfo, it))
                PayWay.ALiPay -> PayContext(ALiPayStrategy(payParams, prePayInfo, it))
                PayWay.UPPay -> PayContext(UPPayStrategy(payParams, prePayInfo, it))
                else -> null
            }?.pay()
        }

        private var mOnPayResultListener: OnPayResultListener? = null
        private val releaseMemory = payParams?.run {
            activity = null
            payParams = null
            instance = null
        }

        private fun sendPayResult(code: Int) = mOnPayResultListener?.run {
            payParams?.payWay?.let {
                when (code) {
                    COMMON_OK_PAY -> onPaySuccess(it)
                    COMMON_ERR_USER_CANCELED -> onPayCancel(it)
                    else -> onPayFailure(it, code)
                }
                releaseMemory
            }
        } ?: Unit

        fun toPay(onPayResultListener: OnPayResultListener) {
            mOnPayResultListener = onPayResultListener
            if (!isNetworkAvailable(payParams?.activity?.applicationContext))
                sendPayResult(COMMON_ERR_NETWORK_NOT_AVAILABLE)
        }

        private fun isNetworkAvailable(context: Context?): Boolean =
            context?.connectivityManager?.allNetworkInfo?.run {
                for (networkInfo in this) {
                    if (networkInfo.state == NetworkInfo.State.CONNECTED) return true
                }
                return false
            } ?: false

        companion object {
            const val COMMON_OK_PAY = 0//支付正常
            const val COMMON_ERR_PAY = -1//支付错误
            const val COMMON_ERR_USER_CANCELED = -2//用户取消错误
            const val COMMON_ERR_NETWORK_NOT_AVAILABLE = 1//网络不可用错误
            const val COMMON_ERR_REQUEST_TIME_OUT = 2//请求超时错误
            const val WECHAT_ERR_SENT_FAILED = -3//微信发送失败错误
            const val WECHAT_ERR_AUTH_DENIED = -4//微信作者否认错误
            const val WECHAT_ERR_UNSUPPORT = -5//微信不支持错误
            const val WECHAT_ERR_BAN = -6//微信禁止错误
            const val WECHAT_ERR_NOT_INSTALLED = -7//微信未安装错误
            const val ALI_PAY_ERR_WAIT_CONFIRM = 8000//支付宝等待确认错误
            const val ALI_PAY_ERR_NET = 6002//支付宝网络错误
            const val ALI_PAY_ERR_UNKNOWN = 6004//支付宝未知错误
            const val ALI_PAY_ERR_OTHER = 6005//支付宝其他错误
            const val UPPAY_PLUGIN_NOT_INSTALLED = -10//插件未安装
            const val UPPAY_PLUGIN_NEED_UPGRADE = -11//插件需更新
            private var instance: AutoPay? = null

            @JvmStatic
            @JvmOverloads
            fun newInstance(payParams: PayParams? = null): AutoPay? {
                payParams?.let { instance = AutoPay(it) }
                return instance
            }
        }
    }

    abstract class PayStrategyBase(
        protected var mPayParams: PayParams?, protected var mPrePayInfo: String?,
        protected var mOnPayResultListener: AutoPay.PayCallBack?
    ) : PayStrategyInter {
        abstract override fun doPay()
    }

    data class PrePayInfo(
        var preAppId: String? = null,//应用ID
        var prePartnerId: String? = null,//合作者ID
        var prePayId: String? = null,//预支付ID
        @SerializedName("package")//此属性序列化成JSON时，将名字序列化成注解value属性指定值
        var prePackageValue: String? = null,//包值
        var preNonceStr: String? = null,//目前
        var preTimeStamp: String? = null,//时间戳
        var preSign: String? = null//签名
    )

    class WeChatPayStrategy(
        params: PayParams?, prePayInfo: String?, resultListener: AutoPay.PayCallBack?
    ) : PayStrategyBase(params, prePayInfo, resultListener) {
        companion object {
            const val WECHAT_PAY_RESULT_ACTION: String =
                "com.tencent.mm.opensdk.WECHAT_PAY_RESULT_ACTION"
            const val WECHAT_PAY_RESULT_EXTRA: String =
                "com.tencent.mm.opensdk.WECHAT_PAY_RESULT_EXTRA"
        }

        private var localBroadcastManager: LocalBroadcastManager? = null
        private val context: Context? = params?.activity
        private val unRegisterPayResultBroadcast = {
            broadcastReceiver?.let { localBroadcastManager?.unregisterReceiver(it) }
            localBroadcastManager = null
            broadcastReceiver = null
        }
        private var broadcastReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.getIntExtra(WECHAT_PAY_RESULT_EXTRA, -100)
                    ?.let { mOnPayResultListener?.onPayCallBack(it) }
                unRegisterPayResultBroadcast
            }
        }
        private val registerPayResultBroadcast = {
            localBroadcastManager =
                context?.applicationContext?.let { LocalBroadcastManager.getInstance(it) }
            broadcastReceiver?.let {
                localBroadcastManager?.registerReceiver(it, IntentFilter(WECHAT_PAY_RESULT_ACTION))
            }
        }

        override fun doPay() {
            WXAPIFactory.createWXAPI(context?.applicationContext, mPayParams?.weChatAppID, true)
                .run {
                    if (!isWXAppInstalled) {
                        super.mOnPayResultListener?.onPayCallBack(AutoPay.WECHAT_ERR_NOT_INSTALLED)
                        return
                    }
                    if (wxAppSupportAPI == 0) {
                        super.mOnPayResultListener?.onPayCallBack(AutoPay.WECHAT_ERR_UNSUPPORT)
                        return
                    }
                    registerApp(mPayParams?.weChatAppID)
                    registerPayResultBroadcast
                    Gson().fromJson(mPrePayInfo, PrePayInfo::class.java).run {
                        sendReq(PayReq().apply {
                            appId = preAppId
                            partnerId = prePartnerId
                            prepayId = prePayId
                            packageValue = prePackageValue
                            nonceStr = preNonceStr
                            timeStamp = preTimeStamp
                            sign = preSign
                        })
                    }//TODO 需要做正式解析，修改PrePayInfo，并解开此处注释
                }
        }
    }

    class ALiPayResult(rawResult: MutableMap<String?, String?>?) {
        var resultStatus: String? = rawResult?.get("resultStatus")
        private var result: String? = rawResult?.get("result")
        private var memo: String? = rawResult?.get("memo")
        override fun toString(): String =
            "resultStatus={$resultStatus};memo={${memo}};result={$result}"

        companion object {
            const val PAY_STATUS_OK: String = "9000"//成功
            const val PAY_STATUS_WAIT_CONFIRM: String = "8000"//等待确认
            const val PAY_STATUS_ERR_UNKNOWN: String = "6004"//未知错误
            const val PAY_STATUS_ERR_NET: String = "6002"//网络错误
            const val PAY_STATUS_CANCEL: String = "6001"//取消
            const val PAY_STATUS_FAILED: String = "4000"//失败
        }
    }

    class ALiPayStrategy(
        params: PayParams?, prePayInfo: String?, resultListener: AutoPay.PayCallBack?
    ) : PayStrategyBase(params, prePayInfo, resultListener) {
        companion object {
            private const val PAY_RESULT_MSG = 0
        }

        private val handler: Handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                super.handleMessage(msg)
                if (msg.what == PAY_RESULT_MSG) {
                    shutdown
                    mOnPayResultListener?.run {
                        when (ALiPayResult(msg.obj as MutableMap<String?, String?>).resultStatus) {
                            ALiPayResult.PAY_STATUS_OK -> onPayCallBack(AutoPay.COMMON_OK_PAY)
                            ALiPayResult.PAY_STATUS_WAIT_CONFIRM -> onPayCallBack(AutoPay.ALI_PAY_ERR_WAIT_CONFIRM)
                            ALiPayResult.PAY_STATUS_ERR_UNKNOWN -> onPayCallBack(AutoPay.ALI_PAY_ERR_UNKNOWN)
                            ALiPayResult.PAY_STATUS_ERR_NET -> onPayCallBack(AutoPay.ALI_PAY_ERR_NET)
                            ALiPayResult.PAY_STATUS_CANCEL -> onPayCallBack(AutoPay.COMMON_ERR_USER_CANCELED)
                            ALiPayResult.PAY_STATUS_FAILED -> onPayCallBack(AutoPay.COMMON_ERR_PAY)
                            else -> onPayCallBack(AutoPay.ALI_PAY_ERR_OTHER)
                        }
                    }
                    removeCallbacksAndMessages(null)
                }
            }
        }

        override fun doPay() {
            Runnable {
                handler.run {
                    obtainMessage().apply {
                        what = PAY_RESULT_MSG
                        obj = PayTask(mPayParams?.activity).payV2(mPrePayInfo, true)
                    }.let { sendMessage(it) }
                }//TODO 根据自身需求解析mPrePayInfo，最终字符串值应为一连串key=value形式
            }.execute
        }
    }

    class UPPayStrategy(
        params: PayParams?, prePayInfo: String?, resultListener: AutoPay.PayCallBack?
    ) : PayStrategyBase(params, prePayInfo, resultListener) {
        private val modeOfficial: String = "00"//官方模式，启动银联正式环境
        private val modeDev: String = "01"//开发模式，连接银联测试环境
        private val mode: String = modeOfficial

        companion object {
            private const val PLUGIN_VALID = 0//插件有效响应码
            private const val PLUGIN_NOT_INSTALLED = -1//插件未安装
            private const val PLUGIN_NEED_UPGRADE = 2//插件需更新
        }//TODO 根据实际情况修改上述环境mode

        override fun doPay() {
            mOnPayResultListener?.run {
                when (UPPayAssistEx
                    .startPay(mPayParams?.activity, null, null, getTn(mPrePayInfo), mode)) {
                    PLUGIN_VALID -> onPayCallBack(AutoPay.COMMON_OK_PAY)
                    PLUGIN_NOT_INSTALLED -> onPayCallBack(AutoPay.UPPAY_PLUGIN_NOT_INSTALLED)
                    PLUGIN_NEED_UPGRADE -> onPayCallBack(AutoPay.UPPAY_PLUGIN_NEED_UPGRADE)
                    else -> onPayCallBack(AutoPay.COMMON_ERR_PAY)
                }
            }
        }

        private fun getTn(prePayInfo: String?): String? = ""//TODO 根据自身需求解析prePayInfo得到预支付订单号tn
    }
}
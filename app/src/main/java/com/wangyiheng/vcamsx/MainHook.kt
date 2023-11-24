package com.wangyiheng.vcamsx

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraDevice
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.data.models.VideoStatues
import com.wangyiheng.vcamsx.utils.InfoManager
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import java.util.*


class MainHook : IXposedHookLoadPackage {
    private var isplaying: Boolean = false
    private var videoStatus: VideoStatues? = null
    private var infoManager : InfoManager?= null
    private var player_exoplayer: ExoPlayer? = null
    private var player_media:MediaPlayer? = null
    private var context: Context? = null
    private var original_c2_preview_Surface: Surface? = null

    private var c2_virtual_surface: Surface? = null

    private var c2_state_callback_class: Class<*>? = null
    private var c2_state_callback: CameraDevice.StateCallback? = null
    var playtestcount = 0
    // Xposed模块中
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if(lpparam.packageName == "com.wangyiheng.vcamsx"){
            return
        }

        //获取context
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate",
            Application::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    if (param!!.args[0] is Application) {

                        val application = param.args[0] as? Application ?: return
                        val applicationContext = application.applicationContext
                        if (context == applicationContext) return
                        try {
                            context = applicationContext
                            initStatus()
                        } catch (ee: Exception) {
                            HLog.d("VCAM", "$ee")
                        }
                    }
                }
            })

        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
            String::class.java,
            CameraDevice.StateCallback::class.java,
            Handler::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if(param.args[1] == null){
                            return
                        }
                        if(param.args[1] == c2_state_callback){
                            return
                        }
                        c2_state_callback = param.args[1] as CameraDevice.StateCallback
                        c2_state_callback_class = param.args[1]?.javaClass
                        process_camera2_init(c2_state_callback_class as Class<Any>?,lpparam)
                    }catch (e:Exception){
                        HLog.d("android.hardware.camera2.CameraManager报错了", "openCamera")
                    }
                }
            })
    }


    fun initStatus(){
        infoManager = InfoManager(context!!)
        videoStatus = infoManager!!.getVideoStatus()
    }

    private fun process_camera2_init(c2StateCallbackClass: Class<Any>?, lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(c2StateCallbackClass, "onOpened", CameraDevice::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                if(c2_virtual_surface!=null){
                    player_exoplayer!!.stop()
                    c2_virtual_surface!!.release()
                    c2_virtual_surface = null
                }
                original_c2_preview_Surface = null
            }
        })


        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "addTarget",
            android.view.Surface::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] != null) {
                        if(param.args[0] == c2_virtual_surface)return
                        val surfaceInfo = param.args[0].toString()
                        if (!surfaceInfo.contains("Surface(name=null)")) {
                            if(original_c2_preview_Surface == null ){
                                original_c2_preview_Surface = param.args[0] as Surface
                                if(original_c2_preview_Surface?.isValid == true) {
                                    Log.d("vcamsx","开始播放")
                                    Log.d("surfaceInfo",surfaceInfo)
                                    playtestcount = 0
                                    process_camera_play()
                                }
                            }
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam?) {
                    super.afterHookedMethod(param)
                    if (param != null) {
                        if (param.args[0] != null) {
                            if(param.args[0] == c2_virtual_surface)return
                            val surfaceInfo = param.args[0].toString()
                            if (!surfaceInfo.contains("Surface(name=null)")) {
                                if(!isplaying){
                                    process_camera_play()
                                }
                            }
                        }
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "build",object :XC_MethodHook(){
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(msg = "build构建成功")
            }
        })

        XposedHelpers.findAndHookMethod(c2StateCallbackClass, "onDisconnected",CameraDevice::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(msg="APP断开")
                player_exoplayer!!.stop()
                original_c2_preview_Surface = null
            }
        })
    }

    fun initPlayer(){
        player_exoplayer = ExoPlayer.Builder(context!!).build()
        player_exoplayer!!.repeatMode = Player.REPEAT_MODE_ALL
        if(videoStatus != null && videoStatus!!.volume){
            player_exoplayer!!.volume = 1f
        }else{
            player_exoplayer!!.volume = 0f
        }
        player_exoplayer!!.shuffleModeEnabled = true
        player_exoplayer!!.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                isplaying = true
            }
            override fun onPlayerError(error: PlaybackException) {
                player_exoplayer!!.release()
                player_exoplayer = null
                if(original_c2_preview_Surface!!.isValid && playtestcount < 5){
                    process_camera_play()
                    playtestcount++
                }
            }
        })


        val mediaItem = MediaItem.fromUri("content://com.wangyiheng.vcamsx.videoprovider")

        player_exoplayer!!.setMediaItem(mediaItem)
        player_exoplayer!!.prepare()
    }

    fun process_camera_play() {
        if(videoStatus != null && videoStatus!!.videoPlayer == 1){
            exoplay_play()
        }else{
            media_play()
        }
    }

    fun exoplay_play(){
        if (original_c2_preview_Surface != null) {
            initPlayer()
            if(videoStatus != null && videoStatus!!.isVideoEnable){
                player_exoplayer!!.setVideoSurface(original_c2_preview_Surface)
                player_exoplayer!!.prepare()
                player_exoplayer!!.play()
            }
        }
    }

    private fun media_play() {
        if (original_c2_preview_Surface != null) {
            player_media = MediaPlayer()
            player_media!!.isLooping = true
            player_media!!.setSurface(original_c2_preview_Surface)

            player_media!!.reset()
            val videoPathUri = Uri.parse("content://com.wangyiheng.vcamsx.videoprovider")
            player_media!!.setVolume(0f, 0f)
            player_media!!.setDataSource(context!!, videoPathUri)

            player_media!!.prepare()

            // 设置视频准备好的监听器
            player_media!!.setOnPreparedListener {
                player_media!!.start()
                isplaying = true
            }
        }
    }
}
package com.blenderar.client

import android.app.Application
import com.blenderar.client.core.net.AssetCache
import com.blenderar.client.core.net.LanClient
import com.blenderar.client.core.repo.EffectRepository
import com.blenderar.client.core.state.EffectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

/**
 * 应用入口:持有跨页面共享的单例组件(无 DI 框架,手动装配)。
 */
class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * 手动依赖容器。所有组件在此创建并共享应用生命周期。
 */
class AppContainer(private val app: Application) {

    /** 应用级协程作用域(SupervisorJob:一个子任务失败不影响其它) */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 设备唯一 ID(协议 origin / 能力协商用) */
    val deviceId: String = UUID.randomUUID().toString()

    val lanClient: LanClient = LanClient(appScope, deviceId)

    val assetCache: AssetCache = AssetCache(app)

    val effectStore: EffectStore = EffectStore(deviceId)

    val effectRepository: EffectRepository = EffectRepository(
        scope = appScope,
        appContext = app,
        store = effectStore,
        cache = assetCache,
        lan = lanClient,
    ).also { it.start() }
}

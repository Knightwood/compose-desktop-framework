package androidx.compose.desktop.runtime.core

import androidx.jvm.system.di.InstanceContext
import androidx.jvm.system.di.startUp
import org.koin.core.error.KoinApplicationAlreadyStartedException
import org.koin.core.logger.Level
import org.koin.core.logger.Level.ERROR
import org.koin.core.logger.Level.INFO
import org.koin.core.logger.Level.NONE
import org.koin.core.logger.Level.WARNING
import org.koin.core.logger.MESSAGE
import org.koin.core.parameter.ParametersHolder
import org.koin.core.qualifier.Qualifier
import org.slf4j.Logger
import kotlin.reflect.KClass

object ServiceBooter {

    /**
     * 启动koin加载所有内部模块、加载ModuleProvider接口实例提供的模块
     */
    fun bootstrap(logger: Logger) {
        //启动koin
        try {
            InstanceContext.startUp {
                logger(object : org.koin.core.logger.Logger() {
                    override fun display(level: Level, msg: MESSAGE) {
                        when (level) {
                            Level.DEBUG -> logger.debug(msg)
                            INFO -> logger.info(msg)
                            ERROR -> logger.error(msg)
                            NONE -> logger.info(msg)
                            WARNING -> logger.warn(msg)
                        }
                    }
                })
            }
        } catch (e: KoinApplicationAlreadyStartedException) {
            logger.warn(e.message, e)
        }
    }

    /**
     * 获取内部koin实例
     */
    fun getKoin() = InstanceContext.get()

    /**
     * 获取一个已注册到koin的框架服务实例
     *
     * @param cls 服务类
     * @param qualifier  如果要获取的服务使用接口形式定义, 在不同操作系统上有不同的实现，则需要使用[androidx.jvm.system.di.currentPlatformKoinNamed]作为qualifier参数
     * @param parameters 服务所需参数, 大多数情况下服务会在初始化阶段生成实例,参数也由那时被传递,因此当你在使用时一般不需要传递参数
     *
     * 例如：
     * ```
     * // application结束时将状态保存到磁盘
     * // 这里只是为了演示，只打印了一下
     * getService(ApplicationSaveStateSaver::class)
     *     .export()
     *     .also {
     *         logger.info("Application save state saver changed to $it")
     *     }
     * ```
     */
    fun <T : Any> getService(
        cls: KClass<T>,
        qualifier: Qualifier? = null,
        parameters: (() -> ParametersHolder)? = null,
    ): T {
        return getKoin().get(cls, qualifier, parameters)
    }

    fun <T : Any> getService(
        cls: Class<T>,
        qualifier: Qualifier? = null,
        parameters: (() -> ParametersHolder)? = null,
    ): T {
        return getKoin().get(cls.kotlin, qualifier, parameters)
    }
}

/**
 * 获取某个服务的实例
 * ```
 * //公共：
 * getServiceInstance<ApplicationSaveStateSaver>()
 * getServiceInstance<IntentProcessor>()
 *
 * //compose:
 * getServiceInstance<ActivityManager>()
 * getServiceInstance<ActivityLauncher>()
 * getServiceInstance<WindowManager>()
 *
 * //swing:
 * getServiceInstance<JFrameManager>()
 * getServiceInstance<JFrameLauncher>()
 * ```
 * @param qualifier  如果要获取的服务使用接口形式定义, 在不同操作系统上有不同的实现，则需要使用[androidx.jvm.system.di.currentPlatformKoinNamed]作为qualifier参数
 * @param parameters 服务所需参数, 大多数情况下服务会在初始化阶段生成实例,参数也由那时被传递,因此当你在使用时一般不需要传递参数
 *
 */
inline fun <reified T : Any> getServiceInstance(
    qualifier: Qualifier? = null,
    noinline parameters: (() -> ParametersHolder)? = null,
): T {
    return ServiceBooter.getService(T::class, qualifier, parameters)
}

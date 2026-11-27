package androidx.compose.desktop.runtime.core

import androidx.compose.desktop.runtime.activity.Activity
import androidx.compose.desktop.runtime.activity.ActivityManager
import androidx.compose.desktop.runtime.core.intent.Intent
import androidx.compose.desktop.runtime.core.context.ContextImpl
import androidx.compose.desktop.runtime.window.ApplicationWindowContainer
import androidx.compose.desktop.runtime.window.WindowManager
import androidx.jvm.system.core.PathService
import androidx.jvm.system.di.InstanceContext
import com.github.knightwood.slf4j.kotlin.logFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.parameter.parametersOf
import kotlin.system.exitProcess

/**
 * 一切的起源
 * 通过[Singularity.boot] 方法启动框架
 */
object Singularity {
    private val logger = logFor("Singularity")

    /**
     * 从这里可以得到全局的application引用，用于上下文操作
     */
    internal lateinit var applicationInternal: Application
    private val lock = Any()
    fun isApplicationExist() = this::applicationInternal.isInitialized

    /**
     * 一切的开端；一切的终结；
     *
     * @param awares 如果不想在application的onCreate函数中写太多逻辑，可以放到这里的初始化块
     * @param applicationWindowContainer 自定义窗口容器实现
     * @param intentBuilder 启动主界面的参数
     */
    inline fun <reified T : Activity, reified R : Application> boot(
        awares: Array<Aware> = arrayOf(),
        applicationWindowContainer: ApplicationWindowContainer? = null,
        noinline intentBuilder: (Intent.() -> Unit)? = null,
    ) {
        boot(
            mainActivity = T::class.java,
            applicationClass = R::class.java,
            awares = awares,
            applicationWindowContainer = applicationWindowContainer,
            intentBuilder = intentBuilder
        )
    }

    /**
     * 一切的开端；一切的终结；
     *
     * @param mainActivity 主界面
     * @param applicationClass 应用程序类，默认为Application
     * @param awares 如果不想再application的onCreate函数中写太多逻辑，可以放到这里的初始化块
     * @param applicationWindowContainer 自定义窗口容器实现
     * @param intentBuilder 启动主界面的参数
     */
    fun boot(
        mainActivity: Class<out Activity>,
        applicationClass: Class<out Application> = Application::class.java,
        awares: Array<Aware> = arrayOf(),
        applicationWindowContainer: ApplicationWindowContainer? = null,
        intentBuilder: (Intent.() -> Unit)? = null,
    ) {
        synchronized(lock) {
            if (isApplicationExist()) {
                throw IllegalStateException("Application already exists")
            }
            //用于获取程序安装目录
            PathService.anyClass = mainActivity
            ServiceBooter.bootstrap(logger)
            //启动所有的服务，比如窗口管理、activity管理
            InstanceContext.get().run {
                get<ActivityManager>().prepare()
                get<WindowManager>(parameters = { parametersOf(applicationWindowContainer) })
            }
            // 创建Application实例
            val instance: Application = applicationClass.getDeclaredConstructor().newInstance()
            //生成第一个context
            val firstContext = ContextImpl.createBaseContextForApplication(instance)
            instance.also { instance: Application ->
                this.applicationInternal = instance
                instance.attach(firstContext)
                instance.prepare(awares)
            }
            firstContext.startMainActivity(mainActivity, intentBuilder)
            //启动完成，陷入阻塞
            getServiceInstance<WindowManager>().prepare()
        }
    }

    /**
     * 启动MainActivity，第一个显示出来的窗口
     */
    private fun ContextImpl.startMainActivity(
        mainActivity: Class<out Activity>,
        intentBuilder: (Intent.() -> Unit)?,
    ) {
        val intent = Intent(this@Singularity, mainActivity)
        intentBuilder?.invoke(intent)
        startActivity(intent)//这里会运行在协程，注意调用时机
    }

    /**
     * 结束进程
     */
    fun exitAppOrProcess(err: Boolean) {
        if (::applicationInternal.isInitialized) {
            CoroutineScope(Dispatchers.Unconfined).launch {
                applicationInternal.release()
            }
        } else {
            exitProcess(if (err) 1 else 0)
        }
    }


}


//<editor-fold desc="启动">
/**
 * 一切的开端；一切的终结；
 * 示例
 * ```
 * startApplication<SplashActivity, MainApplication>(
 *      applicationWindowContainer = ApplicationWindowContainer { windows ->
 *          application(exitProcessOnExit = false) {
 *              UncaughtExceptionContent {
 *                  windows()
 *                  SystemTray()
 *              }
 *          }
 *      },
 *      intentBuilder = {
 *          launchMode = LaunchMode.STANDARD
 *          data {
 *              putInt("targetId", 1)
 *          }
 *      }
 * )
 *
 * ```
 *
 *
 * @param awares 如果不想在application的onCreate函数中写太多逻辑，可以放到这里的初始化块
 * @param applicationWindowContainer 自定义applicationScope内容显示
 * @param intentBuilder 启动主界面的参数
 */
inline fun <reified T : Activity, reified R : Application> startApplication(
    awares: Array<Aware> = arrayOf(),
    applicationWindowContainer: ApplicationWindowContainer? = null,
    noinline intentBuilder: (Intent.() -> Unit)? = null,
) = Singularity.boot<T, R>(awares = awares, applicationWindowContainer = applicationWindowContainer, intentBuilder = intentBuilder)

/**
 * 一切的开端；一切的终结；
 *
 * @param mainActivity 主界面
 * @param applicationClass 应用程序类，默认为Application
 * @param awares 如果不想在application的onCreate函数中写太多逻辑，可以放到这里的初始化块
 * @param applicationWindowContainer 自定义applicationScope内容显示
 * @param intentBuilder 启动主界面的参数
 */
fun startApplication(
    mainActivity: Class<out Activity>,
    applicationClass: Class<out Application> = Application::class.java,
    awares: Array<Aware> = arrayOf(),
    applicationWindowContainer: ApplicationWindowContainer? = null,
    intentBuilder: (Intent.() -> Unit)? = null,
) = Singularity.boot(
    mainActivity = mainActivity,
    applicationClass = applicationClass,
    awares = awares,
    applicationWindowContainer = applicationWindowContainer,
    intentBuilder = intentBuilder,
)

//</editor-fold>

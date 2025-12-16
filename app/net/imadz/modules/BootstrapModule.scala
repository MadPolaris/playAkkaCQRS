package net.imadz.modules // [Fix] 修正为与目录结构一致

import com.google.inject.AbstractModule
import net.imadz.infrastructure.bootstrap.ApplicationBootstrap

class BootstrapModule extends AbstractModule {
  override def configure(): Unit = {
    // Eager Singleton 保证应用启动即初始化
    bind(classOf[ApplicationBootstrap]).asEagerSingleton()
  }
}
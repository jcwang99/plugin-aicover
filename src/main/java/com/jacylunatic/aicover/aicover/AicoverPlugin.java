package com.jacylunatic.aicover.aicover;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 插件主入口。
 * @ComponentScan 注解确保 Spring 会扫描插件的所有包。
 */
@Component
public class AicoverPlugin extends BasePlugin {

    public AicoverPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        System.out.println("插件启动成功！");
    }

    @Override
    public void stop() {
        System.out.println("插件停止！");
    }
}


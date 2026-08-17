package com.skillforge.studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
/**
 * SkillForge Studio 后端启动入口，自动扫描当前包下的控制器、服务、Mapper 和配置类。
 */
public class SkillForgeStudioApplication {
    public static void main(String[] args) {
        SpringApplication.run(SkillForgeStudioApplication.class, args);
    }
}

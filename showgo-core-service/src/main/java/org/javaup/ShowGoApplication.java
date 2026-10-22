package org.javaup;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 星幕票务 ShowGo 启动类。
 *
 * <p>exposeProxy 为 true：订单落库等需要「Redis 补偿 + 数据库事务」成对出现的地方，
 * 通过 AopContext.currentProxy() 调用事务方法，保证事务注解生效。</p>
 */
@EnableScheduling
@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("org.javaup.mapper")
@SpringBootApplication
public class ShowGoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShowGoApplication.class, args);
    }
}

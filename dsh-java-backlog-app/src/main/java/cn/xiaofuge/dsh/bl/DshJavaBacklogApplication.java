package cn.xiaofuge.dsh.bl;

import cn.xiaofuge.dsh.bl.config.BacklogProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BacklogProperties.class)
public class DshJavaBacklogApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshJavaBacklogApplication.class, args);
    }
}

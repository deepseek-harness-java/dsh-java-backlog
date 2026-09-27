package cn.xiaofuge.dsh.bl.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BacklogProperties.class)
public class ApplicationConfiguration {
}

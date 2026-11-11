package com.gjzg;

import com.hundsun.jrescloud.common.boot.CloudApplication;
import com.hundsun.jrescloud.common.boot.CloudBootstrap;
import org.springframework.scheduling.annotation.EnableScheduling;

@CloudApplication
@EnableScheduling
public class ApplicationServerStarter {
    public static void main(String[] args) {
        CloudBootstrap.run(ApplicationServerStarter.class, args);
    }
}

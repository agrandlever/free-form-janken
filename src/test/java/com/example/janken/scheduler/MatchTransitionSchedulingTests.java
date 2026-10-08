package com.example.janken.scheduler;

import com.example.janken.JankenApplication;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import static org.junit.jupiter.api.Assertions.*;

// 既存テスト用の無効化設定をこの空Storeのコンテキストだけで上書きし、Springへの登録を検証する。
@SpringBootTest(properties={"janken.match-transition.enabled=true", "janken.disconnect-monitor.enabled=true"})
class MatchTransitionSchedulingTests {
    @Autowired MatchTransitionScheduler scheduler;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduling;
    @Autowired Clock clock;
    @Autowired DisconnectMonitor monitor;

    @Test void springRegistersFiveHundredMillisecondTask() throws Exception {
        assertNotNull(scheduler); assertNotNull(clock);
        assertTrue(JankenApplication.class.isAnnotationPresent(EnableScheduling.class));
        var annotation=MatchTransitionScheduler.class.getMethod("runTransitions").getAnnotation(Scheduled.class);
        assertEquals(500,annotation.fixedRate());
        assertNotNull(monitor);
        assertEquals(1000, DisconnectMonitor.class.getMethod("runChecks").getAnnotation(Scheduled.class).fixedRate());
        assertEquals(2,scheduling.getScheduledTasks().size());
    }
}

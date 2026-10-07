package com.example.janken.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class GameStateLockTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private GameStateLock gameStateLock;

    @Test
    void applicationProvidesOneSharedLockInstance() {
        // 実際のアプリケーション設定でBeanが1つだけ登録され、同じ実体が注入される。
        assertEquals(1, context.getBeansOfType(GameStateLock.class).size());
        assertTrue(context.isSingleton("gameStateLock"));
        assertSame(gameStateLock, context.getBean(GameStateLock.class));
        assertSame(gameStateLock, context.getBean("gameStateLock"));
        assertSame(gameStateLock, context.getBean(GameStateLock.class));
    }
}

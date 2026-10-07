package com.example.janken.store;

import org.springframework.stereotype.Component;

/**
 * 全Service・Schedulerに注入して共有する、アプリケーション全体のロック。
 * Springの標準スコープはsingletonなので、同じコンテキストでは同じインスタンスになる。
 * 後続の処理はこのインスタンス自体をsynchronizedの対象にする。
 * 独自にnewせず、Springから受け取ったインスタンスを利用する。
 */
@Component
public final class GameStateLock {
}

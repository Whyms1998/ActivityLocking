package com.activitylock.app.core

/**
 * Watcher 版本单一来源。
 * WatcherMain（Java）与 AmsController 都从这里取值——
 * 此前两处各自维护常量，多次升级时漏改一处导致 Watcher 永不换代。
 */
object WatcherVersion {
    const val VERSION = 19
}

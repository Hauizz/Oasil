package com.example.ai.service;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;

/**
 * SQLite 连接的公共初始化。
 *
 * 打包成 Spring Boot 可执行 jar 后，驱动在 BOOT-INF/lib 的嵌套类加载器里，
 * DriverManager 的自动注册依赖初始化时机，偶发会出现
 * 「No suitable driver found for jdbc:sqlite:…」，导致文件仓库 / 聊天日志 /
 * 考试记录全部写不进去。这里在建立连接前显式注册一次，避免这个问题。
 */
final class SqliteSupport {

    private SqliteSupport() {
    }

    private static volatile boolean ready;

    /** 确保 org.sqlite.JDBC 已注册，可在每次取连接前调用（幂等） */
    static void ensureDriver() {
        if (ready) {
            return;
        }
        synchronized (SqliteSupport.class) {
            if (ready) {
                return;
            }
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (Throwable ignore) {
                // 类路径里没有驱动时忽略，交给 DriverManager 报错
            }
            if (!registered()) {
                try {
                    Driver d = (Driver) Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
                    DriverManager.registerDriver(new DriverShim(d));
                } catch (Throwable ignore) {
                    // 注册失败也继续，后面会给出明确报错
                }
            }
            ready = true;
        }
    }

    private static boolean registered() {
        Enumeration<Driver> it = DriverManager.getDrivers();
        while (it.hasMoreElements()) {
            if (it.nextElement().getClass().getName().startsWith("org.sqlite")) {
                return true;
            }
        }
        return false;
    }

    /** 包一层，避免类加载器隔离导致 registerDriver 被拒 */
    private static final class DriverShim implements Driver {
        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.sql.Connection connect(String url, java.util.Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info)
                throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            try {
                return delegate.getParentLogger();
            } catch (Exception e) {
                throw new java.sql.SQLFeatureNotSupportedException();
            }
        }
    }
}

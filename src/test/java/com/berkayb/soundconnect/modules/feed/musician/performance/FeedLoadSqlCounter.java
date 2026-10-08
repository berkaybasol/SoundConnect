package com.berkayb.soundconnect.modules.feed.musician.performance;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.LongAdder;

/** Counts JDBC execute invocations, including JPA viewer checks and provider threads. */
final class FeedLoadSqlCounter extends DelegatingDataSource {
    static final LongAdder EXECUTIONS = new LongAdder();

    FeedLoadSqlCounter(DataSource delegate) { super(delegate); }

    @Override public Connection getConnection() throws SQLException { return counted(super.getConnection()); }
    @Override public Connection getConnection(String username, String password) throws SQLException {
        return counted(super.getConnection(username, password));
    }

    private Connection counted(Connection connection) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(connection, args);
                        if (result instanceof Statement statement && (method.getName().equals("prepareStatement")
                                || method.getName().equals("createStatement") || method.getName().equals("prepareCall"))) {
                            return Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{method.getReturnType()}, (statementProxy, operation, parameters) -> {
                                        if (operation.getName().startsWith("execute")) EXECUTIONS.increment();
                                        try { return operation.invoke(statement, parameters); }
                                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                                    });
                        }
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
}

/*
  Copyright (c) 2020, 2021, Oracle and/or its affiliates.

  This software is dual-licensed to you under the Universal Permissive License
  (UPL) 1.0 as shown at https://oss.oracle.com/licenses/upl or Apache License
  2.0 as shown at http://www.apache.org/licenses/LICENSE-2.0. You may choose
  either license.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package oracle.r2dbc.impl;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import io.r2dbc.spi.R2dbcTimeoutException;
import oracle.jdbc.OracleConnection;
import oracle.jdbc.datasource.OracleDataSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.r2dbc.spi.ConnectionFactoryOptions.CONNECT_TIMEOUT;
import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static oracle.r2dbc.DatabaseConfig.connectTimeout;
import static oracle.r2dbc.DatabaseConfig.host;
import static oracle.r2dbc.DatabaseConfig.password;
import static oracle.r2dbc.DatabaseConfig.port;
import static oracle.r2dbc.DatabaseConfig.serviceName;
import static oracle.r2dbc.DatabaseConfig.user;
import static oracle.r2dbc.util.Awaits.awaitError;
import static oracle.r2dbc.util.Awaits.awaitNone;
import static oracle.r2dbc.util.Awaits.awaitOne;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that
 * {@link OracleColumnMetadataImpl} implements behavior that is specified in
 * it's class and method level javadocs.
 */
public class OracleReactiveJdbcAdapterTest {

  /**
   * Verifies the implementation of
   * {@link OracleReactiveJdbcAdapter#createDataSource(ConnectionFactoryOptions)}
   */
  @Test
  public void testCreateDataSource() throws SQLException {

    // Verify extended options that configure Oracle JDBC Driver connection
    // properties. The defaultProperties variable contains properties that
    // are set to default values by OracleReactiveJdbcAdapter and the Oracle
    // JDBC Driver
    Properties defaultProperties = new Properties();
    defaultProperties.setProperty(
      OracleConnection.CONNECTION_PROPERTY_J2EE13_COMPLIANT, "true");
    defaultProperties.setProperty(
      OracleConnection.CONNECTION_PROPERTY_ENABLE_AC_SUPPORT, "false");
    defaultProperties.setProperty(
      OracleConnection.CONNECTION_PROPERTY_IMPLICIT_STATEMENT_CACHE_SIZE, "25");

    // Expect only default connection properties when no extended
    // options are supplied
    assertEquals(defaultProperties,
      OracleReactiveJdbcAdapter.getInstance()
        .createDataSource(ConnectionFactoryOptions.builder()
          .option(HOST, host())
          .option(PORT, port())
          .option(DATABASE, serviceName())
          .build())
        .unwrap(OracleDataSource.class)
        .getConnectionProperties());

    // Expect only default connection properties when no supported options are
    // supplied
    assertEquals(defaultProperties,
      OracleReactiveJdbcAdapter.getInstance()
        .createDataSource(ConnectionFactoryOptions.builder()
          .option(HOST, host())
          .option(PORT, port())
          .option(DATABASE, serviceName())
          .option(Option.valueOf(
            OracleConnection.CONNECTION_PROPERTY_RETAIN_V9_BIND_BEHAVIOR),
            "true")
          .option(Option.valueOf("This is not supported"), "true")
          .build())
        .unwrap(OracleDataSource.class)
        .getConnectionProperties());

    // Expect additional connection properties when supported options are
    // supplied
    Properties expected = (Properties)defaultProperties.clone();
    expected.setProperty(
      OracleConnection.CONNECTION_PROPERTY_THIN_JAVAX_NET_SSL_KEYSTORETYPE,
      "PKCS12");
    expected.setProperty(
      OracleConnection.CONNECTION_PROPERTY_THIN_SSL_VERSION,
      "TLSv1.3");
    expected.setProperty(
      OracleConnection.CONNECTION_PROPERTY_TNS_ADMIN,
      "/home/oracle/admin");

    assertEquals(expected,
      OracleReactiveJdbcAdapter.getInstance()
        .createDataSource(ConnectionFactoryOptions.builder()
          .option(HOST, host())
          .option(PORT, port())
          .option(DATABASE, serviceName())
          .option(Option.valueOf(
            OracleConnection
              .CONNECTION_PROPERTY_THIN_JAVAX_NET_SSL_KEYSTORETYPE),
            "PKCS12")
          .option(Option.valueOf(
            OracleConnection.CONNECTION_PROPERTY_THIN_SSL_VERSION),
            "TLSv1.3")
          .option(Option.valueOf(
            OracleConnection.CONNECTION_PROPERTY_TNS_ADMIN),
            "/home/oracle/admin")
          .build())
        .unwrap(OracleDataSource.class)
        .getConnectionProperties());

    // Expect the TNS_ADMIN property to be set when an option specifies
    // it's short form name
    assertEquals(expected,
      OracleReactiveJdbcAdapter.getInstance()
        .createDataSource(ConnectionFactoryOptions.builder()
          .option(HOST, host())
          .option(PORT, port())
          .option(DATABASE, serviceName())
          .option(Option.valueOf(
            OracleConnection
              .CONNECTION_PROPERTY_THIN_JAVAX_NET_SSL_KEYSTORETYPE),
            "PKCS12")
          .option(Option.valueOf(
            OracleConnection.CONNECTION_PROPERTY_THIN_SSL_VERSION),
            "TLSv1.3")
          .option(Option.valueOf("TNS_ADMIN"), "/home/oracle/admin")
          .build())
        .unwrap(OracleDataSource.class)
        .getConnectionProperties());
  }

  /**
   * Verifies that
   * {@link OracleReactiveJdbcAdapter#createDataSource(ConnectionFactoryOptions)}
   * handles the {@link OracleConnection#CONNECTION_PROPERTY_TNS_ADMIN} property
   * correctly. This property configures a file system path to a
   * directory containing tnsnames.ora and ojdbc.properties files. If the TNS
   * admin property is handled correctly, then TNS descriptors should be read
   * from tnsnames.ora, and connection properties should be read from
   * ojdbc.properties.
   */
  @Test
  public void testTnsAdmin() throws IOException {

    // Create a tnsnames.ora file with a the TNS descriptor
    Files.writeString(Path.of("tnsnames.ora"),
      String.format("test_alias=" +
          "(DESCRIPTION=(ADDRESS=(HOST=%s)(PORT=%d)(PROTOCOL=tcp))" +
          "(CONNECT_DATA=(SERVICE_NAME=%s)))",
          host(), port(), serviceName()),
      StandardOpenOption.CREATE_NEW);
    try {
      // Expect to connect with the tnsnames.ora file, when a URL specifies
      // it's path and an alias, along with a user and password.
      awaitNone(awaitOne(
        ConnectionFactories.get(String.format(
          "r2dbc:oracle://%s:%s@%s?TNS_ADMIN=%s",
          user(), password(), "test_alias", System.getProperty("user.dir")))
          .create())
          .close());

      // Create an ojdbc.properties file containing the user name
      Files.writeString(Path.of("ojdbc.properties"),
        String.format("user=%s", user()),
        StandardOpenOption.CREATE_NEW);
      try {
        // Expect to connect with the tnsnames.ora and ojdbc.propeties files,
        // when a URL specifies their path and an alias, the properties file
        // specifies a user, and a standard option specifies the password.
        awaitNone(awaitOne(
          ConnectionFactories.get(ConnectionFactoryOptions.parse(String.format(
            "r2dbc:oracle://%s?TNS_ADMIN=%s",
            "test_alias", System.getProperty("user.dir")))
            .mutate()
            .option(PASSWORD, password())
            .build())
            .create())
            .close());
      }
      finally {
        Files.delete(Path.of("ojdbc.properties"));
      }
    }
    finally {
      Files.delete(Path.of("tnsnames.ora"));
    }
  }

  /**
   * Verifies that the {@link ConnectionFactoryOptions#CONNECT_TIMEOUT} option
   * is applied, as specified in the javadoc of
   * {@link OracleReactiveJdbcAdapter#createDataSource(ConnectionFactoryOptions)}
   */
  @Test
  public void testConnectTimeout()
    throws InterruptedException, ExecutionException, TimeoutException,
      IOException {

    // Create a server that doesn't allow the driver to complete connection
    // establishment. This will ensure that the timeout expires.
    try (ServerSocketChannel listeningChannel = ServerSocketChannel.open()) {
      listeningChannel.configureBlocking(true);
      listeningChannel.bind(null);

      verifyConnectTimeout(listeningChannel, ConnectionFactoryOptions.builder()
        .option(DRIVER, "oracle")
        .option(HOST, "localhost")
        .option(PORT, listeningChannel.socket().getLocalPort())
        .option(DATABASE, serviceName())
        .option(CONNECT_TIMEOUT, Duration.ofMillis(500L))
        .build());

      verifyConnectTimeout(listeningChannel, ConnectionFactoryOptions.parse(
        "r2dbc:oracle://localhost:" + listeningChannel.socket().getLocalPort()
          + "?connectTimeout=PT0.5S")); // The value is parsed as a Duration
    }
  }

  /**
   * Verifies that an attempt to connect with a {@code listeningChannel}
   * results in an {@link R2dbcTimeoutException}.
   * @param listeningChannel Listens for an R2DBC connection
   */
  private void verifyConnectTimeout(
    ServerSocketChannel listeningChannel, ConnectionFactoryOptions options)
    throws InterruptedException, ExecutionException, TimeoutException,
    IOException {

    // Accept the R2DBC driver's connection, and then leave it hanging. The
    // timeout should expire.
    CompletableFuture<SocketChannel> r2dbcConnectFuture =
      CompletableFuture.supplyAsync(() -> {
        try {
          return listeningChannel.accept();
        }
        catch (IOException ioException) {
          throw new CompletionException(ioException);
        }
      }, runnable -> new Thread(runnable).start());

    // Try to connect
    try {
      awaitError(R2dbcTimeoutException.class,
        ConnectionFactories.get(options).create());
    }
    catch (Throwable unexpected) {
      unexpected.printStackTrace();
    }
    finally {
      // Close the connection that was accepted
      r2dbcConnectFuture
        .get(connectTimeout().toSeconds(), TimeUnit.SECONDS)
        .close();
    }
  }
}
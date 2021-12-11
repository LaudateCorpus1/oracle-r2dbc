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

import io.r2dbc.spi.Blob;
import io.r2dbc.spi.Clob;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import oracle.sql.json.OracleJsonFactory;
import oracle.sql.json.OracleJsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.sql.RowId;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static oracle.r2dbc.test.DatabaseConfig.connectTimeout;
import static oracle.r2dbc.test.DatabaseConfig.databaseVersion;
import static oracle.r2dbc.test.DatabaseConfig.sharedConnection;
import static oracle.r2dbc.test.DatabaseConfig.sqlTimeout;
import static oracle.r2dbc.util.Awaits.awaitExecution;
import static oracle.r2dbc.util.Awaits.awaitOne;
import static oracle.r2dbc.util.Awaits.awaitUpdate;
import static oracle.r2dbc.util.Awaits.tryAwaitNone;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <p>
 * This test class verifies the Oracle R2DBC Driver's implementation of SQL
 * to Java and Java to SQL type mappings. The type mapping implementation is
 * exposed through the {@link Row} and {@link Statement} SPIs. The
 * {@link Row#get(int)} method is expected to implement SQL to Java type
 * conversion, and the {@link Statement#bind(int, Object)} method is expected
 * to implement Java to SQL type conversion.
 * </p><p>
 * For SQL types which the Oracle Database supports, the Oracle R2DBC Driver is
 * expected to implement all type conversions listed in
 * <a href="https://r2dbc.io/spec/0.8.2.RELEASE/spec/html/#datatypes.mapping">
 * Section 12 of the R2DBC 0.8.2 Specification.
 * </a>
 * </p><p>
 * SQL types which the Oracle Database does not support, such as BOOLEAN, are
 * not verified by this test class. Non-standard types which the Oracle
 * Database does support, like JSON, are verified by this test class.
 * </p>
 */
public class TypeMappingTest {

  /**
   * <p>
   * Verifies the implementation of Java to SQL and SQL to Java type mappings
   * for character data types. The Oracle R2DBC Driver is expected to
   * implement the type mappings listed in
   * <a href="https://r2dbc.io/spec/0.8.3.RELEASE/spec/html/#datatypes.mapping">
   * Table 4 of Section 12 of the R2DBC 0.8.3 Specification.
   * </a>
   * </p>
   */
  @Test
  public void testCharacterTypeMappings() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {

      // Expect CHAR and String to map
      verifyTypeMapping(connection,
        String.format("%100s", "Hello, Oracle"), "CHAR(100)");

      // Expect VARCHAR and String to map
      verifyTypeMapping(connection, "Bonjour, Oracle", "VARCHAR(100)");

      // Expect NCHAR and String to map
      verifyTypeMapping(connection,
        String.format("%100s", "你好, Oracle"), "NCHAR(100)");

      // Expect NVARCHAR and String to map. The Oracle type named "NVARCHAR2" is
      // equivalent to the standard type named "NVARCHAR"
      verifyTypeMapping(connection, "नमस्कार, Oracle", "NVARCHAR2(100)");

      // Expect CLOB and String to map
      verifyTypeMapping(connection, "Hola, Oracle", "CLOB");

      // Expect CLOB and io.r2dbc.spi.Clob to map
      verifyTypeMapping(connection,
        Clob.from(Mono.just("Hola, Oracle")), "CLOB",
        row -> row.get(0, Clob.class),
        (expected, actual) ->
          assertEquals("Hola, Oracle", clobToString(actual)));

      // Expect NCLOB and String to map for bind values, but not for row values.
      // For row values, expect Oracle CLOB to be mapped to io.r2dbc.spi.Clob
      verifyTypeMapping(connection, "こんにちは, Oracle", "NCLOB");

      // Expect NCLOB and io.r2dbc.spi.Clob to map
      verifyTypeMapping(connection,
        Clob.from(Mono.just("こんにちは, Oracle")), "NCLOB",
        row -> row.get(0, Clob.class),
        (expected, actual) ->
          assertEquals("こんにちは, Oracle", clobToString(actual)));

      // Expect LONG and String to map. LONG is variable length character
      // data, equivalent to VARCHAR
      verifyTypeMapping(connection,
        Stream.generate(() -> "Aloha, Oracle")
          .limit(1_000)
          .collect(Collectors.joining()),
        "LONG");
    }
    finally {
      tryAwaitNone(connection.close());
    }
  }

  /**
   * <p>
   * Verifies the implementation of Java to SQL and SQL to Java type mappings
   * for binary data types. The Oracle R2DBC Driver is expected to implement
   * the type mappings listed in
   * <a href="https://r2dbc.io/spec/0.8.3.RELEASE/spec/html/#datatypes.mapping">
   * Table 6 of Section 12 of the R2DBC 0.8.3 Specification.
   * </a>
   * </p><p>
   * The Oracle Database does not support a fixed length binary type, like
   * BINARY, so the Oracle R2DBC Driver is not expected to support this
   * mapping.
   *</p>
   */
  @Test
  public void testBinaryTypeMappings() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {
      // Buffer holds the set of all 8 bit values. Use allocateDirect to
      // catch any code that assumes ByteBuffer.hasArray().
      ByteBuffer byteBuffer = ByteBuffer.allocateDirect(0x100);
      for (int value = 0; value <= 0xFF; value++)
        byteBuffer.put((byte)value);

      // Expect Oracle R2DBC to never mutate the buffer's position, limit,
      // or mark.
      byteBuffer.clear();

      // Expect VARBINARY and ByteBuffer to map. The Oracle type named
      // "RAW" is equivalent to the standard type named "VARBINARY".
      verifyTypeMapping(connection, byteBuffer, "RAW(1000)");

      // Expect LONG RAW and ByteBuffer to map. The Oracle type named
      // "LONG RAW" is equivalent to the standard type named "VARBINARY".
      verifyTypeMapping(connection, byteBuffer, "LONG RAW");

      // Expect BLOB and ByteBuffer to map for bind values, but not for row
      // values. For row values, expect Oracle BLOB to be mapped to
      // io.r2dbc.spi.Blob
      verifyTypeMapping(connection, byteBuffer, "BLOB");

      // Expect BLOB and io.r2dbc.spi.Blob to map
      verifyTypeMapping(connection,
        Blob.from(Mono.just(byteBuffer)), "BLOB",
        row -> row.get(0, Blob.class),
        (expected, actual) ->
          assertEquals(byteBuffer, blobToByteBuffer(actual)));

    }
    finally {
      tryAwaitNone(connection.close());
    }
  }

  /**
   * <p>
   * Verifies the implementation of Java to SQL and SQL to Java type mappings
   * for numeric data types. The Oracle R2DBC Driver is expected to implement
   * the type mappings listed in
   * <a href="https://r2dbc.io/spec/0.8.3.RELEASE/spec/html/#datatypes.mapping">
   * Table 7 of Section 12 of the R2DBC 0.8.3 Specification.
   * </a>
   * </p><p>
   * The Oracle Database does not support INTEGER, TINYINT, SMALLINT, BIGINT,
   * REAL, or DOUBLE PRECISION so the Oracle R2DBC Driver is not expected to
   * support these mappings.
   *</p>
   */
  @Test
  public void testNumericTypeMappings() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {
      // This value has 39 decimal digits, which is the maximum precision of
      // Oracle's NUMBER type.
      BigDecimal pi =
        new BigDecimal("3.14159265358979323846264338327950288419");

      // Expect DECIMAL/NUMERIC and BigDecimal to map. The Oracle type named
      // "NUMBER" is equivalent to the standard types named "DECIMAL" and
      // "NUMERIC".
      verifyTypeMapping(connection, pi, "NUMBER");

      // Expect FLOAT and Double to map.
      verifyTypeMapping(connection, pi.doubleValue(), "FLOAT");

      // Expect BINARY_FLOAT and Float to map.
      verifyTypeMapping(connection, pi.floatValue(), "BINARY_FLOAT");

      // Expect BINARY_DOUBLE and Double to map.
      verifyTypeMapping(connection, pi.doubleValue(), "BINARY_DOUBLE");

    }
    finally {
      tryAwaitNone(connection.close());
    }
  }

  /**
   * <p>
   * Verifies the implementation of Java to SQL and SQL to Java type mappings
   * for datetime data types. The Oracle R2DBC Driver is expected to implement
   * the type mappings listed in
   * <a href="https://r2dbc.io/spec/0.8.3.RELEASE/spec/html/#datatypes.mapping">
   * Table 8 of Section 12 of the R2DBC 0.8.3 Specification.
   * </a>
   * </p><p>
   * The Oracle Database does not support the standard SQL types named DATE,
   * TIME, or TIME WITH TIME ZONE so the Oracle R2DBC Driver is not expected
   * to support mappings for the standard SQL types with these names.
   * </p><p>
   * Note that Oracle Database does support a type which is named "DATE", but
   * this type is not equivalent to the standard SQL type of the same name. An
   * Oracle DATE stores a value of years, months, days, hours, minutes, and
   * seconds. The standard SQL DATE does not store hours, minutes, or
   * seconds, so the standard type is not equivalent to the Oracle type. The
   * Oracle R2DBC Driver is expected to map Oracle DATE with LocalDateTime,
   * which is the expected mapping for the TIMESTAMP type, which is
   * equivalent to the Oracle DATE type.
   *</p>
   */
  @Test
  public void testDatetimeTypeMappings() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {
      OffsetDateTime dateTimeValue =
        OffsetDateTime.of(2038, 10, 23, 9, 42, 1, 1, ZoneOffset.ofHours(-5));

      // Expect Oracle's "DATE" and LocalDateTime to map.
      verifyTypeMapping(connection,
        dateTimeValue.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS),
        "DATE");

      // Expect TIMESTAMP and LocalDateTime to map.
      verifyTypeMapping(connection,
        dateTimeValue.toLocalDateTime(), "TIMESTAMP(9)");

      // Expect TIMESTAMP WITH TIME ZONE and OffsetDateTime to map.
      verifyTypeMapping(connection,
        dateTimeValue, "TIMESTAMP(9) WITH TIME ZONE");

      // Expect TIMESTAMP WITH LOCAL TIME ZONE and LocalDateTime to map.
      verifyTypeMapping(connection,
        dateTimeValue.toLocalDateTime(),
        "TIMESTAMP(9) WITH LOCAL TIME ZONE");

      // Expect INTERVAL YEAR TO MONTH and Period to map. Note that Period
      // binds must have a zero number of days
      verifyTypeMapping(connection, Period.between(
        LocalDate.of(1977, Month.JUNE, 16), dateTimeValue.toLocalDate())
        .withDays(0),
        "INTERVAL YEAR(4) TO MONTH");

      // Expect INTERVAL DAY TO SECOND and Duration to map.
      verifyTypeMapping(connection,
        Duration.ofDays(1).withSeconds(9),
        "INTERVAL DAY TO SECOND");

    }
    finally {
      tryAwaitNone(connection.close());
    }
  }

  /**
   * <p>
   * Verifies the implementation of Java to SQL and SQL to Java type mappings
   * for row ID data types. The R2DBC 0.8.3 Specification does not contain
   * mapping guidelines for row ID data types. The Oracle R2DBC Driver is
   * expected to map these types to {@link java.sql.RowId}.
   *</p>
   */
  @Test
  public void testRowIdMappings() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {
      // The "dual" table is queried to retrieve a valid row ID.
      RowId rowId = awaitOne(Mono.from(connection.createStatement(
        "SELECT rowid FROM dual")
        .execute())
        .flatMap(result ->
          Mono.from(result.map((row, metadata) ->
            row.get(0, RowId.class)))
        ));

      // Expect ROWID and RowId to map.
      verifyTypeMapping(connection, rowId, "ROWID");

      // Expect UROWID and RowId to map.
      verifyTypeMapping(connection, rowId, "UROWID");
    }
    finally {
      tryAwaitNone(connection.close());
    }
  }

  /**
   * <p>
   * Verifies the implementation of Java to SQL and SQL to Java type mappings
   * for the JSON data type. The R2DBC 0.8.3 Specification does not contain
   * mapping guidelines for the JSON data type. The Oracle R2DBC Driver is
   * expected to map JSON to a {@link oracle.sql.json.OracleJsonObject} value.
   *</p>
   */
  @Test
  public void testJsonMapping() {

    // The JSON data type was introduced in Oracle Database version 21c, so this
    // test is skipped if the version is older than 21c.
    assumeTrue(databaseVersion() >= 21,
      "JSON columns are not supported by database versions older than 21");

    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {
      OracleJsonObject oracleJson = new OracleJsonFactory().createObject();
      oracleJson.put("species", "cat");
      oracleJson.put("birthday", OffsetDateTime.of(
        LocalDate.of(2009, 7, 12), LocalTime.NOON, ZoneOffset.UTC));
      oracleJson.put("weight", 9.2);

      // Expect JSON and OracleJsonObject to map.
      verifyTypeMapping(connection, oracleJson, "JSON");
    }
    finally {
      tryAwaitNone(connection.close());
    }
  }

  /**
   * <p>
   * Verifies the implementation of Java to SQL and SQL to Java type mappings
   * where the Java type is {@link Boolean} and the SQL type is a numeric type.
   * The R2DBC 0.9.0 Specification only requires that Java {@code Boolean} be
   * mapped to the SQL BOOLEAN type, however Oracle Database does not support a
   * BOOLEAN column type. To allow the use of the {@code Boolean} bind
   * values, Oracle JDBC supports binding the Boolean as a NUMBER. Oracle
   * R2DBC is expected to expose this functionality as well.
   *</p>
   */
  @Test
  public void testBooleanNumericMapping() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {
      // Expect NUMBER and Boolean to map, with Row.get(...) mapping the
      // NUMBER column value to BigDecimal
      verifyTypeMapping(connection, true, "NUMBER",
        (expected, actual) -> assertEquals(BigDecimal.ONE, actual));
      verifyTypeMapping(connection, false, "NUMBER",
        (expected, actual) -> assertEquals(BigDecimal.ZERO, actual));

      // Expect NUMBER and Boolean to map, with Row.get(..., Boolean.class)
      // mapping the NUMBER column value to Boolean
      verifyTypeMapping(connection, true, "NUMBER",
        row -> row.get(0, Boolean.class),
        (expected, actual) -> assertTrue(actual));
      verifyTypeMapping(connection, false, "NUMBER",
        row -> row.get(0, Boolean.class),
        (expected, actual) -> assertFalse(actual));
    }
    finally {
      tryAwaitNone(connection.close());
    }
  }

  /**
   * <p>
   * Verifies the implementation of Java to SQL and SQL to Java type mappings
   * where the Java type is a {@code byte} array and the SQL type is RAW or
   * BLOB. The R2DBC 0.9.0 Specification does not require drivers to
   * support byte array mapping, however this mapping is required by
   * the JDBC 4.3 specification. Oracle R2DBC is expected to expose mappings
   * supported by Oracle JDBC.
   *</p>
   */
  @Test
  public void testByteArrayMapping() {
    Connection connection =
      Mono.from(sharedConnection()).block(connectTimeout());
    try {
      byte[] byteArray = new byte[100];

      for (int i = 0; i < byteArray.length; i++)
        byteArray[i] = (byte) i;

      // Expect RAW and byte[] to map, with Row.get(...) mapping the
      // RAW column value to ByteBuffer
      verifyTypeMapping(connection, byteArray, "RAW(100)",
        (expected, actual) -> assertEquals(ByteBuffer.wrap(byteArray), actual));

      // Expect RAW and byte[] to map, with Row.get(..., byte[].class)
      // mapping the RAW column value to byte[]
      verifyTypeMapping(connection, byteArray, "RAW(100)",
        row -> row.get(0, byte[].class),
        (expected, actual) -> assertArrayEquals(byteArray, actual));
    }
    finally {
      tryAwaitNone(connection.close());
    }
  }

  // TODO: More tests for JDBC 4.3 mappings like BigInteger to BIGINT,
  //  java.sql.Date to DATE, java.sql.Blob to BLOB? Oracle R2DBC exposes all
  //  type mappings supported by Oracle JDBC.

  /**
   * <p>
   * Verifies the conversion between a Java Language type and a SQL Language
   * type. The Java Language {@code javaValue} is converted to a SQL Language
   * value of a type specified by {@code sqlTypeDdl}. The SQL Language value is
   * then converted back to a Java Language type and checked for equality
   * with the original {@code javaValue}.
   * </p>
   * @param connection Connection to a database
   * @param javaValue Value to convert. Maybe null.
   * @param sqlTypeDdl SQL Language DDL for a column type, such as
   *                   "NUMBER" or "VARCHAR(100)". Not null.
   */
  private static void verifyTypeMapping(
    Connection connection, Object javaValue, String sqlTypeDdl) {
    verifyTypeMapping(connection, javaValue, sqlTypeDdl,
      Assertions::assertEquals);
  }

  /**
   * <p>
   * Verifies the conversion between a Java Language type and a SQL Language
   * type. The Java Language {@code javaValue} is converted to a SQL Language
   * value of a type specified by {@code sqlTypeDdl}. The SQL Language value is
   * then converted back to a Java Language type and checked for equality
   * with the original {@code javaValue}.
   * </p><p>
   * In case the expected type mapping for {@link Row#get(int)} is different
   * from the {@code javaValue's} type, the {@code verifyEquals} function is
   * specified to convert the {@code Row's} expected mapping into the same type
   * as {@code javaValue}. This function is used when a bind type mapping
   * isn't supported as a row type mapping.
   * </p><p>
   * This method will also INSERT a Java {@code null} bind value, and expect
   * to get a Java {@code null} value from a SELECT of the SQL NULL value.
   * </p>
   * @param connection Connection to a database
   * @param javaValue Value to insert. Maybe null.
   * @param sqlTypeDdl SQL Language DDL for a column type, such as
   *                   "NUMBER" or "VARCHAR(100)". Not null.
   * @param verifyEquals Verifies the expected Java type mapping for
   * {@link Row#get(int)} has a value that is equal to {@code javaValue}.
   */
  private static <T> void verifyTypeMapping(
    Connection connection, T javaValue, String sqlTypeDdl,
    BiConsumer<T, Object> verifyEquals) {
    verifyTypeMapping(connection, javaValue, sqlTypeDdl,
      row -> row.get("javaValue"), verifyEquals);
  }
  
  /**
   * <p>
   * Verifies the conversion between a Java Language type and a SQL Language
   * type. The Java Language {@code javaValue} is converted to a SQL Language
   * value of a type specified by {@code sqlTypeDdl}, and inserted into a table.
   * </p><p>
   * The inserted value is then queried back and the resulting {@code Row} is
   * input to each function the {@code rowVerifiers} array. These functions
   * should verify that the row returns an expected value.
   * </p><p>
   * This method will also INSERT a Java {@code null} bind value, and expect
   * to get a Java {@code null} value from a SELECT of the SQL NULL value.
   * </p>
   * @param connection Connection to a database
   * @param javaValue Value to insert. Maybe null.
   * @param sqlTypeDdl SQL Language DDL for a column type, such as
   *                   "NUMBER" or "VARCHAR(100)". Not null.
   * @param rowMapper Outputs a Java value for an input Row
   * @param verifyEquals Verifies the {@code rowMapper} output is equal to
   * {@code javaValue}.
   */
  private static <T, U> void verifyTypeMapping(
    Connection connection, T javaValue, String sqlTypeDdl,
    Function<Row, U> rowMapper, BiConsumer<T, U> verifyEquals) {
    String table = "verify_" + sqlTypeDdl.replaceAll("[^\\p{Alnum}]", "_");
    try {
      awaitExecution(connection.createStatement(String.format(
        "CREATE TABLE "+table+" (javaValue %s)", sqlTypeDdl)));

      awaitUpdate(asList(1,1), connection.createStatement(
        "INSERT INTO "+table+"(javaValue) VALUES(:javaValue)")
        .bind("javaValue", javaValue).add()
        .bindNull("javaValue", javaValue.getClass()));

      verifyEquals.accept(javaValue,
        awaitOne(Flux.from(connection.createStatement(
          "SELECT javaValue FROM "+table+" WHERE javaValue IS NOT NULL")
          .execute())
          .flatMap(result ->
            result.map((row, metadata) -> rowMapper.apply(row))
          )));

      awaitOne(true,
        Flux.from(connection.createStatement(
          "SELECT javaValue FROM "+table+" WHERE javaValue IS NULL")
          .execute())
          .flatMap(result ->
            result.map((row, metadata) -> null == row.get("javaValue"))
          ));
    }
    finally {
      try {
        awaitExecution(connection.createStatement("DROP TABLE "+table));
      }
      catch (RuntimeException error) {
        error.printStackTrace();
      }
    }
  }

  /**
   * Returns a {@code String} that stores the character data emitted by a
   * {@code clobValue's} {@link Clob#stream()} publisher.
   * @param clobValue A {@link Clob}
   * @return The {@code clobValue's} content.
   * @throws ClassCastException If {@code clobValue} is not a {@link Clob}
   */
  private static String clobToString(Object clobValue) {
    return Flux.from(((Clob)clobValue).stream())
      .map(CharSequence::toString)
      .reduce(CharBuffer.allocate(1024), (previous, current) ->
        previous.remaining() >= current.length()
          ? previous.put(current)
          : CharBuffer.allocate((int) (
          1.5d * (previous.capacity() + current.length())))
          .put(previous.flip())
          .put(current))
      .block(sqlTimeout())
      .flip()
      .toString();
  }

  /**
   * Returns a {@code ByteBuffer} that stores the binary data emitted by a
   * {@code blobValue's} {@link Blob#stream()} publisher.
   * @param blobValue A {@link Blob}
   * @return The {@code blobValue's} content.
   * @throws ClassCastException If {@code blobValue} is not a {@link Blob}
   */
  private static ByteBuffer blobToByteBuffer(Object blobValue) {
    return Flux.from(((Blob)blobValue).stream())
      .reduce(ByteBuffer.allocate(1024), (previous, current) ->
        previous.remaining() >= current.remaining()
          ? previous.put(current)
          : ByteBuffer.allocate((int) (
              1.5d * (previous.capacity() + current.capacity())))
              .put(previous.flip())
              .put(current))
      .block(sqlTimeout())
      .flip();
  }

}

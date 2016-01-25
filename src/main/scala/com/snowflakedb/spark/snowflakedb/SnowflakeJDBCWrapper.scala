/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.snowflakedb.spark.snowflakedb

import java.net.URI
import java.sql.{Connection, Driver, DriverManager, ResultSetMetaData, SQLException}
import java.util.Properties

import com.snowflakedb.spark.snowflakedb.Parameters.MergedParameters

import scala.util.Try

import org.apache.spark.SPARK_VERSION
import org.apache.spark.sql.types._
import org.slf4j.LoggerFactory

/**
 * Shim which exposes some JDBC helper functions. Most of this code is copied from Spark SQL, with
 * minor modifications for Snowflake-specific features and limitations.
 */
private[snowflakedb] class JDBCWrapper {

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Given a JDBC subprotocol, returns the appropriate driver class so that it can be registered
   * with Spark. If the user has explicitly specified a driver class in their configuration then
   * that class will be used. Otherwise, we will attempt to load the correct driver class based on
   * the JDBC subprotocol.
   *
   * Snowflake-todo Probably not needed
   *
   * @param jdbcSubprotocol 'redshift' or 'postgres' or 'snowflake'
   * @param userProvidedDriverClass an optional user-provided explicit driver class name
   * @return the driver class
   */
  private def getDriverClass(
      jdbcSubprotocol: String,
      userProvidedDriverClass: Option[String]): Class[Driver] = {
    userProvidedDriverClass.map(Utils.classForName).getOrElse {
      jdbcSubprotocol match {
        case "snowflake" =>
          try {
            Utils.classForName("com.snowflake.client.jdbc.SnowflakeDriver")
          } catch {
              case e: ClassNotFoundException =>
                throw new ClassNotFoundException(
                  "Could not load a Snowflake JDBC driver", e)
          }
        case "redshift" =>
          try {
            Utils.classForName("com.amazon.redshift.jdbc41.Driver")
          } catch {
            case _: ClassNotFoundException =>
              try {
                Utils.classForName("com.amazon.redshift.jdbc4.Driver")
              } catch {
                case e: ClassNotFoundException =>
                  throw new ClassNotFoundException(
                    "Could not load an Amazon Redshift JDBC driver; see the README for " +
                      "instructions on downloading and configuring the official Amazon driver.", e)
              }
          }
        case "postgres" => Utils.classForName("org.postgresql.Driver")
        case other => throw new IllegalArgumentException(s"Unsupported JDBC protocol: '$other'")
      }
    }.asInstanceOf[Class[Driver]]
  }

  private def registerDriver(driverClass: String): Unit = {
    // DriverRegistry.register() is one of the few pieces of private Spark functionality which
    // we need to rely on. This class was relocated in Spark 1.5.0, so we need to use reflection
    // in order to support both Spark 1.4.x and 1.5.x.
    if (SPARK_VERSION.startsWith("1.4")) {
      val className = "org.apache.spark.sql.jdbc.package$DriverRegistry$"
      val driverRegistryClass = Utils.classForName(className)
      val registerMethod = driverRegistryClass.getDeclaredMethod("register", classOf[String])
      val companionObject = driverRegistryClass.getDeclaredField("MODULE$").get(null)
      registerMethod.invoke(companionObject, driverClass)
    } else { // Spark 1.5.0+
      val className = "org.apache.spark.sql.execution.datasources.jdbc.DriverRegistry"
      val driverRegistryClass = Utils.classForName(className)
      val registerMethod = driverRegistryClass.getDeclaredMethod("register", classOf[String])
      registerMethod.invoke(null, driverClass)
    }
  }

  /**
   * Takes a (schema, table) specification and returns the table's Catalyst
   * schema.
   *
   * @param conn A JDBC connection to the database.
   * @param table The table name of the desired table.  This may also be a
   *   SQL query wrapped in parentheses.
   *
   * @return A StructType giving the table's Catalyst schema.
   * @throws SQLException if the table specification is garbage.
   * @throws SQLException if the table contains an unsupported type.
   */
  def resolveTable(conn: Connection, table: String): StructType = {
    val rs = conn.prepareStatement(s"SELECT * FROM $table WHERE 1=0").executeQuery()
    try {
      val rsmd = rs.getMetaData
      val ncols = rsmd.getColumnCount
      val fields = new Array[StructField](ncols)
      var i = 0
      while (i < ncols) {
        val columnName = rsmd.getColumnLabel(i + 1)
        val dataType = rsmd.getColumnType(i + 1)
        val typeName = rsmd.getColumnTypeName(i + 1)
        val fieldSize = rsmd.getPrecision(i + 1)
        val fieldScale = rsmd.getScale(i + 1)
        val isSigned = rsmd.isSigned(i + 1)
        val nullable = rsmd.isNullable(i + 1) != ResultSetMetaData.columnNoNulls
        val columnType = getCatalystType(dataType, fieldSize, fieldScale, isSigned)
        fields(i) = StructField(columnName, columnType, nullable)
        i = i + 1
      }
      new StructType(fields)
    } finally {
      rs.close()
    }
  }

  /**
   * Given a driver string and a JDBC url, load the specified driver and return a DB connection.
   *
   * Snowflake-todo Probably not needed
   *
   * @param userProvidedDriverClass the class name of the JDBC driver for the given url. If this
   *                                is None then `spark-redshift` will attempt to automatically
   *                                discover the appropriate driver class.
   * @param url the JDBC url to connect to.
   */
  def getConnector(userProvidedDriverClass: Option[String], url: String): Connection = {
    val subprotocol = new URI(url.stripPrefix("jdbc:")).getScheme
    val driverClass: Class[Driver] = getDriverClass(subprotocol, userProvidedDriverClass)
    println(driverClass)
    registerDriver(driverClass.getCanonicalName)
    DriverManager.getConnection(url, new Properties())
  }

  /**
   *  Get a connection based on the provided parameters
   */
  def getConnector(params: MergedParameters): Connection = {
    // Derive class name
    val driverClassName = params.jdbcDriver.
      getOrElse("com.snowflake.client.jdbc.SnowflakeDriver")
    // Load class
    try {
      val driverClass = Utils.classForName(driverClassName)
      registerDriver(driverClass.getCanonicalName)
    } catch {
      case e: ClassNotFoundException =>
        throw new ClassNotFoundException(
          "Could not load a Snowflake JDBC driver", e)
    }

    val sfURL = params.sfURL
    val jdbcURL = s"""jdbc:snowflake://$sfURL"""

    val jdbcProperties = new Properties()
    // Obligatory properties
    jdbcProperties.put("db", params.sfDatabase)
    jdbcProperties.put("schema", params.sfSchema)  // Has a default
    jdbcProperties.put("user", params.sfUser)
    jdbcProperties.put("password", params.sfPassword)
    jdbcProperties.put("ssl", params.sfSSL)  // Has a default
    // Optional properties
    if (params.sfAccount.isDefined) {
      jdbcProperties.put("account", params.sfAccount.get)
    }
    if (params.sfWarehouse.isDefined) {
      jdbcProperties.put("warehouse", params.sfWarehouse.get)
    }
    if (params.sfRole.isDefined) {
      jdbcProperties.put("role", params.sfRole.get)
    }

    DriverManager.getConnection(jdbcURL, jdbcProperties)
  }


  /**
   * Compute the SQL schema string for the given Spark SQL Schema.
   */
  def schemaString(schema: StructType): String = {
    val sb = new StringBuilder()
    schema.fields.foreach { field => {
      val name = field.name
      val typ: String = field.dataType match {
        case IntegerType => "INTEGER"
        case LongType => "INTEGER"
        case DoubleType => "DOUBLE"
        case FloatType => "FLOAT"
        case ShortType => "INTEGER"
        case ByteType => "INTEGER" // Snowflake does not support the BYTE type.
//        case BooleanType => "BOOLEAN"
        case StringType =>
          if (field.metadata.contains("maxlength")) {
            s"VARCHAR(${field.metadata.getLong("maxlength")})"
          } else {
            "STRING"
          }
//        case BinaryType => "BLOB"
        case TimestampType => "TIMESTAMP"
        case DateType => "DATE"
        case t: DecimalType => s"DECIMAL(${t.precision},${t.scale})"
        case _ => throw new IllegalArgumentException(s"Don't know how to save $field of type ${field.name} to Snowflake")
      }
      val nullable = if (field.nullable) "" else "NOT NULL"
      sb.append(s""", "${name.replace("\"", "\\\"")}" $typ $nullable""".trim)
    }}
    if (sb.length < 2) "" else sb.substring(2)
  }

  /**
   * Returns true if the table already exists in the JDBC database.
   */
  def tableExists(conn: Connection, table: String): Boolean = {
    // Somewhat hacky, but there isn't a good way to identify whether a table exists for all
    // SQL database systems, considering "table" could also include the database name.
    Try(conn.prepareStatement(s"SELECT 1 FROM $table LIMIT 1").executeQuery().next()).isSuccess
  }

  /**
   * Maps a JDBC type to a Catalyst type.
   *
   * @param sqlType - A field of java.sql.Types
   * @return The Catalyst type corresponding to sqlType.
   */
  private def getCatalystType(
      sqlType: Int,
      precision: Int,
      scale: Int,
      signed: Boolean): DataType = {
    // TODO: cleanup types which are irrelevant for Snowflake.
    // Snowflake-todo: Add support for some types like ARRAY.
    // Snowflake-todo: Verify all types.
    val answer = sqlType match {
      // scalastyle:off
      case java.sql.Types.ARRAY         => null
      case java.sql.Types.BIGINT        => if (signed) { LongType } else { DecimalType(20,0) }
//      case java.sql.Types.BINARY        => BinaryType
//      case java.sql.Types.BIT           => BooleanType // @see JdbcDialect for quirks
//      case java.sql.Types.BLOB          => BinaryType
      case java.sql.Types.BOOLEAN       => BooleanType
      case java.sql.Types.CHAR          => StringType
      case java.sql.Types.CLOB          => StringType
      case java.sql.Types.DATALINK      => null
      case java.sql.Types.DATE          => DateType
      case java.sql.Types.DECIMAL
        if precision != 0 || scale != 0 => DecimalType(precision, scale)
      case java.sql.Types.DECIMAL       => DecimalType(38, 18) // Spark 1.5.0 default
      case java.sql.Types.DISTINCT      => null
      case java.sql.Types.DOUBLE        => DoubleType
      case java.sql.Types.FLOAT         => FloatType
      case java.sql.Types.INTEGER       => if (signed) { IntegerType } else { LongType }
      case java.sql.Types.JAVA_OBJECT   => null
      case java.sql.Types.LONGNVARCHAR  => StringType
//      case java.sql.Types.LONGVARBINARY => BinaryType
      case java.sql.Types.LONGVARCHAR   => StringType
      case java.sql.Types.NCHAR         => StringType
      case java.sql.Types.NCLOB         => StringType
      case java.sql.Types.NULL          => null
      case java.sql.Types.NUMERIC
        if precision != 0 || scale != 0 => DecimalType(precision, scale)
      case java.sql.Types.NUMERIC       => DecimalType(38, 18) // Spark 1.5.0 default
      case java.sql.Types.NVARCHAR      => StringType
      case java.sql.Types.OTHER         => null
      case java.sql.Types.REAL          => DoubleType
      case java.sql.Types.REF           => StringType
      case java.sql.Types.ROWID         => LongType
      case java.sql.Types.SMALLINT      => IntegerType
      case java.sql.Types.SQLXML        => StringType // Snowflake-todo: ?
      case java.sql.Types.STRUCT        => StringType // Snowflake-todo: ?
//      case java.sql.Types.TIME          => TimestampType
      case java.sql.Types.TIMESTAMP     => TimestampType
      case java.sql.Types.TINYINT       => IntegerType
//      case java.sql.Types.VARBINARY     => BinaryType
      case java.sql.Types.VARCHAR       => StringType
      case _                            => null
      // scalastyle:on
    }

    if (answer == null) throw new SQLException("Unsupported type " + sqlType)
    answer
  }
}

private[snowflakedb] object DefaultJDBCWrapper extends JDBCWrapper
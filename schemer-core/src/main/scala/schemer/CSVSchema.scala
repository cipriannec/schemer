package schemer

import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SparkSession}
import schemer.utils.JSONUtil

case class CSVOptions(
    header: Boolean = true,
    headerBasedParser: Boolean = false,
    separator: String = ",",
    quoteChar: String = "\"",
    escapeChar: String = "\\"
)

case class CSVSchemaBase(csvOptions: CSVOptions) extends SchemaLikeBase[CSVSchema] {
  override def infer(paths: String*)(implicit @transient spark: SparkSession) = {
    val schema = spark.read
      .option("header", csvOptions.header.toString)
      .option("delimiter", csvOptions.separator)
      .option("quote", csvOptions.quoteChar)
      .option("escape", csvOptions.escapeChar)
      .option("nullValue", null)
      .option("inferSchema", "true")
      .csv(paths: _*)
      .schema

    CSVSchema(schema, csvOptions)
  }
}

case class CSVSchema(
    @JsonProperty(required = true) fields: List[CSVField],
    options: CSVOptions = CSVOptions()
) extends SchemaLike {

  override def validate: List[String] =
    validateFields ++ validateMetaFields

  override def sparkSchema() = {
    val structFields = this.fields.map(field => StructField(field.name, getDataType(field.`type`), field.nullable))
    StructType(structFields)
  }

  def toDf(paths: String*)(implicit @transient spark: SparkSession) = {
    val csvDF = spark.read
      .option("delimiter", options.separator)
      .option("quote", options.quoteChar)
      .option("escape", options.escapeChar)
      .option("nullValue", null)
      .csv(paths: _*)
    val orderedSchema = reconcileSchemaFieldOrder(sparkSchema(), csvDF)

    spark.read
      .option("header", options.header.toString)
      .option("delimiter", options.separator)
      .option("quote", options.quoteChar)
      .option("escape", options.escapeChar)
      .option("nullValue", null)
      .schema(orderedSchema)
      .csv(paths: _*)
  }

  private def reconcileSchemaFieldOrder(sparkSchema: StructType, csvDF: DataFrame) =
    if (options.headerBasedParser && options.header) {
      val actualHeaders = csvDF
        .first()
        .toSeq
        .map(_.toString)
      StructType(actualHeaders.map(field => sparkSchema(sparkSchema.fieldIndex(field))))
    } else {
      sparkSchema
    }

  private def getDataType(csvFieldType: String) =
    csvFieldType.toLowerCase match {
      case "int" | "integer" => IntegerType
      case "long"            => LongType
      case "double"          => DoubleType
      case "float"           => FloatType
      case "string"          => StringType
      case "datetime"        => DateType
      case "boolean"         => BooleanType
      case _                 => StringType
    }

  private def validateFields =
    if (fields.nonEmpty) {
      List.empty
    } else {
      List("fields can't be empty in a CSVSchema")
    }

  private def validateMetaFields =
    if (options.header && fields.exists(_.position.isEmpty)) {
      List("CSVSchema with hasHeader=false should have valid position numbers on all fields")
    } else {
      List.empty
    }

  override def schema() =
    JSONUtil.toJson(this)
}

object CSVSchema {
  def apply(schema: String): CSVSchema =
    JSONUtil.fromJson[CSVSchema](schema)

  def apply(options: CSVOptions): CSVSchemaBase =
    CSVSchemaBase(options)

  def apply(): CSVSchemaBase =
    CSVSchemaBase(CSVOptions())
  def apply(
      schema: StructType,
      options: CSVOptions
  ): CSVSchema = {
    val fields = schema.fields.zipWithIndex.map {
      case (f: StructField, i: Int) => CSVField(f.name, f.nullable, getCsvType(f.dataType), Some(i))
    }.toList

    new CSVSchema(fields, options)
  }

  def apply(
      schema: StructType,
      options: Map[String, String]
  ): CSVSchema = {
    val fields = schema.fields.zipWithIndex.map {
      case (f: StructField, i: Int) => CSVField(f.name, f.nullable, getCsvType(f.dataType), Some(i))
    }.toList

    val csvOptions = CSVOptions(
      options.getOrElse("header", "true").toBoolean,
      options.getOrElse("headerBasedParser", "true").toBoolean,
      options.getOrElse("separator", ","),
      options.getOrElse("quoteChar", "\""),
      options.getOrElse("escapeChar", "\\")
    )

    new CSVSchema(fields, csvOptions)
  }

  private def getCsvType(sparkType: DataType) = sparkType match {
    case IntegerType => "int"
    case LongType    => "long"
    case DoubleType  => "double"
    case FloatType   => "float"
    case StringType  => "string"
    case DateType    => "datetime"
    case BooleanType => "boolean"
    case _           => "string"
  }
}

case class CSVField(
    name: String,
    nullable: Boolean,
    `type`: String,
    position: Option[Int]
)

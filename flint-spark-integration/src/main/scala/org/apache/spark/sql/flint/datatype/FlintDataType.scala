/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.apache.spark.sql.flint.datatype

import org.json4s.{Formats, JField, JValue, NoTypeHints}
import org.json4s.JsonAST.{JNothing, JObject, JString}
import org.json4s.JsonAST.JBool.True
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.opensearch.flint.spark.udt.GeoPointUDT
import org.opensearch.flint.spark.udt.IPAddressUDT

import org.apache.spark.sql.catalyst.util.DateFormatter
import org.apache.spark.sql.flint.datatype.FlintMetadataExtensions.{MetadataBuilderExtension, MetadataExtension, OS_TYPE_KEY}
import org.apache.spark.sql.types._

/**
 * Mapping between Flint data type and Spark data type
 */
object FlintDataType {

  implicit val formats: Formats = Serialization.formats(NoTypeHints)

  val DEFAULT_DATE_FORMAT = "strict_date_optional_time || epoch_millis"

  val STRICT_DATE_OPTIONAL_TIME_FORMATTER_WITH_NANOS =
    s"${DateFormatter.defaultPattern}'T'HH:mm:ss.SSSSSSZ"

  val DATE_FORMAT_PARAMETERS: Map[String, String] = Map(
    "dateFormat" -> DateFormatter.defaultPattern,
    "timestampFormat" -> STRICT_DATE_OPTIONAL_TIME_FORMATTER_WITH_NANOS)

  val METADATA_ALIAS_PATH_NAME = "aliasPath"

  val UNSUPPORTED_OPENSEARCH_FIELD_TYPE = Set.empty[String]

  /**
   * parse Flint metadata and extract properties to StructType.
   */
  def deserialize(metadata: String): StructType = {
    deserializeJValue(JsonMethods.parse(metadata))
  }

  def deserializeJValue(json: JValue): StructType = {
    val properties = (json \ "properties").extract[Map[String, JValue]]
    val (aliasProps, normalProps) = properties.partition { case (_, fieldProperties) =>
      (fieldProperties \ "type") match {
        case JString("alias") => true
        case _ => false
      }
    }

    val fields: Seq[StructField] = normalProps
      .filter { case (_, fp) => isSupported(fp) }
      .map { case (fieldName, fieldProperties) =>
        deserializeField(fieldName, fieldProperties)
      }
      .toSeq

    val normalFieldMap: Map[String, StructField] = fields.map(f => f.name -> f).toMap

    // Process alias fields: only include alias fields if the referenced field exists.
    val aliasFields: Seq[StructField] = aliasProps.flatMap { case (fieldName, fieldProperties) =>
      val aliasPath = (fieldProperties \ "path").extract[String]
      normalFieldMap.get(aliasPath).map { referencedField =>
        val metadataBuilder = new MetadataBuilder()
        metadataBuilder.putString(METADATA_ALIAS_PATH_NAME, aliasPath)
        DataTypes
          .createStructField(fieldName, referencedField.dataType, true, metadataBuilder.build())
      }
    }.toSeq

    StructType(fields ++ aliasFields)
  }

  def deserializeField(fieldName: String, fieldProperties: JValue): StructField = {
    val metadataBuilder = new MetadataBuilder()
    val dataType = fieldProperties \ "type" match {
      // boolean
      case JString("boolean") => BooleanType

      // Keywords
      case JString("keyword") => StringType

      // Numbers
      case JString("long") => LongType
      case JString("integer") => IntegerType
      case JString("short") => ShortType
      case JString("byte") => ByteType
      case JString("double") => DoubleType
      case JString("float") => FloatType
      case JString("half_float") =>
        metadataBuilder.withHalfFloat()
        FloatType

      // Date
      case JString("date") =>
        parseFormat(
          (fieldProperties \ "format")
            .extractOrElse(DEFAULT_DATE_FORMAT))

      // Text with possible multi-fields
      case JString("text") =>
        metadataBuilder.withTextField()
        (fieldProperties \ "fields") match {
          case fields: JObject =>
            metadataBuilder.withMultiFields(fields.obj.map { case (name, props) =>
              (s"$fieldName.$name", (props \ "type").extract[String])
            }.toMap)
            StringType
          case _ => StringType
        }

      // object types
      case JString("object") | JNothing => deserializeJValue(fieldProperties)

      // binary types
      case JString("binary") => BinaryType

      // ip type
      case JString("ip") => IPAddressUDT

      // geo_point type
      case JString("geo_point") => GeoPointUDT

      // not supported
      case unknown => throw new IllegalStateException(s"unsupported data type: $unknown")
    }
    DataTypes.createStructField(fieldName, dataType, true, metadataBuilder.build())
  }

  def isSupported(fieldProperties: JValue): Boolean = {
    (fieldProperties \ "type") match {
      case JString(fieldType) => !UNSUPPORTED_OPENSEARCH_FIELD_TYPE.contains(fieldType)
      case _ => true
    }
  }

  /**
   * parse format in flint metadata
   * @return
   *   (DateTimeFormatter, epoch_millis | epoch_second)
   */
  private def parseFormat(format: String): DataType = {
    val formats = format.split("\\|\\|").map(_.trim)
    val (formatter, epoch_formatter) =
      formats.partition(str => str != "epoch_millis" && str != "epoch_second")

    (formatter.headOption, epoch_formatter.headOption) match {
      case (Some("date"), None) | (Some("strict_date"), None) => DateType
      case (Some("strict_date_optional_time_nanos"), None) |
          (Some("strict_date_optional_time"), None) | (None, Some("epoch_millis")) |
          (Some("strict_date_optional_time"), Some("epoch_millis")) =>
        TimestampType
      case _ => throw new IllegalStateException(s"unsupported date type format: $format")
    }
  }

  /**
   * construct Flint metadata properties section from spark data type.
   */
  def serialize(structType: StructType): String = {
    val jValue = serializeJValue(structType)
    JsonMethods.compact(JsonMethods.render(jValue))
  }

  private def serializeJValue(structType: StructType): JValue = {
    JObject(
      "properties" -> JObject(
        structType.fields
          .map(field => JField(field.name, serializeField(field.dataType, field.metadata)))
          .toList))
  }

  def serializeField(dataType: DataType, metadata: Metadata): JValue = {
    dataType match {
      // boolean
      case BooleanType => JObject("type" -> JString("boolean"))

      // string
      case StringType | _: VarcharType | _: CharType =>
        if (metadata.isTextField) {
          JObject("type" -> JString("text"))
        } else {
          JObject("type" -> JString("keyword"))
        }

      // Numbers
      case LongType => JObject("type" -> JString("long"))
      case IntegerType => JObject("type" -> JString("integer"))
      case ShortType => JObject("type" -> JString("short"))
      case ByteType => JObject("type" -> JString("byte"))
      case DoubleType => JObject("type" -> JString("double"))
      case FloatType =>
        if (metadata.isHalfFloatField) {
          JObject("type" -> JString("half_float"))
        } else {
          JObject("type" -> JString("float"))
        }
      case DecimalType() => JObject("type" -> JString("double"))

      // Date
      case TimestampType | _: TimestampNTZType =>
        JObject(
          "type" -> JString("date"),
          "format" -> JString("strict_date_optional_time_nanos"));
      case DateType => JObject("type" -> JString("date"), "format" -> JString("strict_date"));

      // objects
      case st: StructType => serializeJValue(st)

      // Serialize maps as empty objects and let the map entries automap
      case mt: MapType => serializeJValue(new StructType())

      // array
      case ArrayType(elementType, _) => serializeField(elementType, Metadata.empty)

      // binary
      case BinaryType =>
        JObject(
          "type" -> JString("binary"),
          "doc_values" -> True // enable doc value required by painless script filtering
        )

      // ip
      case IPAddressUDT =>
        JObject("type" -> JString("ip"))

      // geo_point
      case GeoPointUDT => JObject("type" -> JString("geo_point"))

      case unknown => throw new IllegalStateException(s"unsupported data type: ${unknown.sql}")
    }
  }
}

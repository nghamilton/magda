package au.csiro.data61.magda.model

import java.time.OffsetDateTime

import akka.http.scaladsl.model.{MediaType, MediaTypes}
import au.csiro.data61.magda.model.GeoJsonFormats._
import au.csiro.data61.magda.model.Temporal._
import au.csiro.data61.magda.spatial.GeoJsonValidator
import com.monsanto.labs.mwundo.GeoJson._
import spray.json._

import scala.runtime.ScalaRunTime
import scala.util.{Failure, Success, Try}

package misc {

  import scala.util.matching.Regex

  sealed trait FacetType {
    def id: String
  }

  object FacetType {
    val all: Seq[FacetType] = Seq(Publisher, Format)

    private val idToFacet = all.groupBy(_.id.toLowerCase).mapValues(_.head)

    def fromId(id: String): Option[FacetType] = idToFacet get id.toLowerCase
  }

  case object Publisher extends FacetType {
    override def id = "Publisher"
  }

  case object Format extends FacetType {
    override def id = "Format"
  }

  case class FacetSearchResult(
    hitCount: Long,
    options: Seq[FacetOption])

  case class Facet(
    id: String,
    options: Seq[FacetOption])

  case class FacetOption(
    identifier: Option[String], // = None
    value: String,
    hitCount: Long,
    upperBound: Option[Int] = None,
    lowerBound: Option[Int] = None,
    matched: Boolean = false,
    countErrorUpperBound: Long = 0)


  final case class ReadyStatus(ready: Boolean = false)

  case class DataSouce(id: String, name: Option[String], extras: Option[Map[String, JsValue]] = None)

  case class DcatCreation(
     isInternallyProduced: Option[Boolean] = None,
     mechanism: Option[String] = None,
     sourceSystem: Option[String] = None,
     likelihoodOfRelease: Option[String] = None,
     isOpenData: Option[Boolean] = None,
     affiliatedOrganisation: Option[Seq[String]] = None)

  case class AccessControl(
     ownerId: Option[String] = None,
     orgUnitOwnerId: Option[String] = None,
     preAuthorisedPermissionIds: Option[Seq[String]] = None)

  case class DataSet(
      identifier: String,
      tenantId: BigInt,
      title: Option[String] = None,
      catalog: Option[String],
      description: Option[String] = None,
      issued: Option[OffsetDateTime] = None,
      modified: Option[OffsetDateTime] = None,
      languages: Set[String] = Set(),
      publisher: Option[Agent] = None,
      accrualPeriodicity: Option[Periodicity] = None,
      spatial: Option[Location] = None,
      temporal: Option[PeriodOfTime] = None,
      themes: Seq[String] = List(),
      keywords: Seq[String] = List(),
      contactPoint: Option[Agent] = None,
      distributions: Seq[Distribution] = Seq(),
      landingPage: Option[String] = None,
      years: Option[String] = None,
      indexed: Option[OffsetDateTime] = None,
      quality: Double,
      hasQuality: Boolean = false,
      source: Option[DataSouce] = None,
      creation: Option[DcatCreation] = None,
      score: Option[Float],
      publishingState: Option[String] = None,
      accessControl: Option[AccessControl] = None,
      accrualPeriodicityRecurrenceRule: Option[String] = None) {

    override def toString: String = s"Dataset(identifier = $identifier, tenantId = $tenantId, title=$title)"

    def normalToString: String = ScalaRunTime._toString(this)
  }

  object DataSet {
    // A dataset identifier is the record id in the registry. It is not unique among all tenants.
    // In the previous Magda version (single tenant only), an indexed dataset has ES document ID
    // being set to UTF-8 encoded record id, which is guaranteed to be unique. However, in a multi-tenant
    // case, a record id is not unique, which should not be used as document ID in the ES database.
    // With this new version, regardless the deployment mode, all document IDs will consist of record identifier
    // and tenant id. The ES document IDs will be unique.
    def uniqueEsDocumentId(registryId: String, tenantId: BigInt): String = {
      val rawIdentifier = registryId + "---" + tenantId
      java.net.URLEncoder.encode(rawIdentifier, "UTF-8")
    }
  }

  case class Agent(
    identifier: Option[String] = None,
    name: Option[String] = None,
    description: Option[String] = None,
    acronym: Option[String] = None,
    jurisdiction: Option[String] = None,
    aggKeywords: Option[String] = None,
    email: Option[String] = None,
    imageUrl: Option[String] = None,
    phone: Option[String] = None,
    addrStreet: Option[String] = None,
    addrSuburb: Option[String] = None,
    addrState: Option[String] = None,
    addrPostCode: Option[String] = None,
    addrCountry: Option[String] = None,
    website: Option[String] = None,
    source: Option[DataSouce] = None,
    datasetCount: Option[Long] = None)

  case class Location(
    text: Option[String] = None,
    geoJson: Option[Geometry] = None)

  object Location {
    val geoJsonPattern: Regex = "\\{\"type\":\\s*\".+\",.*\\}".r
    val emptyPolygonPattern: Regex = "POLYGON \\(\\(0 0, 0 0, 0 0, 0 0\\)\\)".r
    val polygonPattern: Regex = "POLYGON\\s*\\(\\(((-?[\\d\\.]+ -?[\\d\\.]+\\,?\\s?)+)\\)\\)".r
    val csvPattern: Regex = "^(-?\\d+\\.?\\d*\\,){3}-?\\d+\\.?\\d*$".r

    def applySanitised(text: String, geoJson: Option[Geometry] = None): Location = {
      val processedGeoJson: Option[Geometry] = geoJson match {
        case Some(Polygon(Seq(coords: Seq[Coordinate]))) =>
          coords.distinct match {
            case Seq(_)          => Some(Point(coords.head))
            case Seq(coord1, coord2) => Some(MultiPoint(Seq(coord1, coord2)))
            case _                   => Some(Polygon(Seq(coords)))
          }
        case x => x
      }

      processedGeoJson.foreach(geoJson => GeoJsonValidator.validate(geoJson))

      new Location(Some(text), processedGeoJson)
    }

    def apply(stringWithNewLines: String): Location = {
      val string = stringWithNewLines.replaceAll("[\\n\\r]", " ")
      Location.applySanitised(string, string match {
        case geoJsonPattern() =>
          val theJson = Try(string.parseJson) match {
            case Success(json) => json
            case Failure(_) =>
              CoordinateFormat.quoteNumbersInJson(string).parseJson
          }
          Some(Protocols.GeometryFormat.read(theJson))
        case emptyPolygonPattern() => None
        case csvPattern(_) =>
          val latLongs = string.split(",").map(str => CoordinateFormat.convertStringToBigDecimal(str))
          fromBoundingBox(Seq(BoundingBox(latLongs(0), latLongs(1), latLongs(2), latLongs(3))))
        case polygonPattern(polygonCoords, _) =>
          val coords = polygonCoords.split(",")
            .map { stringCoords =>
              val Array(x, y) = stringCoords.trim.split("\\s").map { str =>
                // --- remove possible redundant dot in number string
                val parts = str.split("\\.")
                if (parts.length > 2) {
                  // --- drop all dots except the first one
                  parts.take(2).mkString(".") + parts.drop(2).mkString
                } else {
                  str
                }
              }.map(_.toDouble)
              Coordinate(x, y)
            }.toSeq

          Some(Polygon(Seq(coords)))
        case _ => None
      })
    }

    def fromBoundingBox(boundingBoxList: Seq[BoundingBox]): Option[Geometry] = {
      val bBoxPoints = boundingBoxList
        .map { boundingBox =>
          val BoundingBox(north, east, south, west) = boundingBox
          val northEast = Coordinate(east, north)
          val northWest = Coordinate(west, north)
          val southWest = Coordinate(west, south)
          val southEast = Coordinate(east, south)

          Seq(
            northEast,
            northWest,
            southWest,
            southEast)
        }
        .map { seq => seq.distinct }
        .distinct

      if (bBoxPoints.isEmpty) {
        None
      } else if (bBoxPoints.size == 1) {
        val coords = bBoxPoints.head
        Some(coords.size match {
          case 1     => Point(coords.head)
          case 2 | 3 => LineString(coords)
          case _     => Polygon(Seq(coords.toList :+ coords.head))
        })
      } else if (!bBoxPoints.exists(_.size != 2)) {
        Some(MultiLineString(bBoxPoints))
      } else if (!bBoxPoints.exists(_.size < 3))
        Some(MultiPolygon(Seq(bBoxPoints)))
      else
        Some(MultiPoint(bBoxPoints.flatten))

    }
  }
  case class BoundingBox(north: BigDecimal, east: BigDecimal, south: BigDecimal, west: BigDecimal)

  case class QueryRegion(
      regionType: String,
      regionId: String) {

    override def toString = s"$regionType:$regionId"
  }

  case class Region(
    queryRegion: QueryRegion,
    regionName: Option[String] = None,
    boundingBox: Option[BoundingBox] = None,
    regionShortName: Option[String] = None)

  case class Distribution(
    identifier: Option[String] = None,
    title: String,
    description: Option[String] = None,
    issued: Option[OffsetDateTime] = None,
    modified: Option[OffsetDateTime] = None,
    license: Option[License] = None,
    rights: Option[String] = None,
    accessURL: Option[String] = None,
    downloadURL: Option[String] = None,
    byteSize: Option[Int] = None,
    mediaType: Option[MediaType] = None,
    source: Option[DataSouce] = None,
    format: Option[String] = None)

  object Distribution {
    private val extensionRegex = new scala.util.matching.Regex("\\.([^./]+)$", "extension")
    // TODO: Push into config
    private val formatToMimeType: Map[String, MediaType] = Map(
      "GeoJSON" -> MediaTypes.`application/json`,
      "KML" -> MediaTypes.`application/vnd.google-earth.kml+xml`,

      "CSV" -> MediaTypes.`text/csv`,
      "JSON" -> MediaTypes.`application/json`,
      "SHP" -> MediaTypes.`application/octet-stream`)

    private val caseInsensitiveFormatToMimeType = formatToMimeType.map {
      case (key: String, mediaType: MediaType) =>
        Map(
          key.toUpperCase -> mediaType,
          key.toLowerCase -> mediaType,
          key -> mediaType)
    }.reduce(_ ++ _)

    private val urlToFormat = Map(
      ".*\\.geojson$".r -> "GeoJSON",
      ".*?.*service=wms.*".r -> "WMS",
      ".*?.*service=wfs.*".r -> "WFS",
      ".*\\.(shp|shz|dbf)(\\.zip)?$".r -> "SHP",
      ".*\\.(pdf)(\\.zip)?$".r -> "PDF",
      ".*\\.(xls|xlsx)(\\.zip)?$".r -> "Excel",
      ".*\\.(json)(\\.zip)?$".r -> "JSON",
      ".*\\.(xml)(\\.zip)?$".r -> "XML",
      ".*\\.(tif)(\\.zip)?$".r -> "TIFF",
      ".*\\.(zip)$".r -> "ZIP",
      ".*\\.(html|xhtml|php|asp|aspx|jsp)(\\?.*)?".r -> "HTML")

    private def mediaTypeFromMimeType(mimeType: String): Option[MediaType] = MediaType.parse(mimeType) match {
      case Right(mediaType) => Some(mediaType)
      case Left(_)          => None
    }

    def mediaTypeFromFormat(format: String): Option[MediaType] = caseInsensitiveFormatToMimeType.get(format)
      .orElse(MediaTypes.forExtensionOption(format.toLowerCase()))

    private def mediaTypeFromExtension(url: String): Option[MediaType] = extensionRegex
      .findFirstIn(url)
      .flatMap { case extensionRegex(extension) => MediaTypes.forExtensionOption(extension) }

    def parseMediaType(mimeType: Option[String], rawFormat: Option[String], url: Option[String]): Option[MediaType] = mimeType
      .flatMap(mediaTypeFromMimeType)
      .orElse(rawFormat flatMap mediaTypeFromFormat)
      .orElse(url flatMap mediaTypeFromExtension)

    def formatFromUrl(url: String): Option[String] = {
      urlToFormat
        .view
        .filter {
          case (regex, _) =>
            regex.findFirstIn(url).isDefined
        }
        .map { case (_, format) => format }
        .headOption
    }

    def parseFormat(rawFormat: Option[String], url: Option[String], parsedMediaType: Option[MediaType], description: Option[String]): Option[String] = {
      rawFormat
        .orElse(url.flatMap(Distribution.formatFromUrl))
        .orElse(parsedMediaType.map(_.subType))
        .orElse(description.flatMap(desc =>
          urlToFormat.values.find(format =>
            desc.toLowerCase.contains(format.toLowerCase()))))
    }

    def isDownloadUrl(url: String, title: String, description: Option[String], format: Option[String]): Boolean = {
      title.toLowerCase.contains("download") ||
        description.exists(_.toLowerCase.contains("download")) ||
        format.exists(_.equals("HTML"))
    }
  }

  case class License(name: Option[String] = None, url: Option[String] = None)


  trait Protocols extends DefaultJsonProtocol with Temporal.Protocols {

    implicit val dataSouceFormat: RootJsonFormat[DataSouce] = jsonFormat3(DataSouce.apply)
    implicit val dcatCreationFormat: RootJsonFormat[DcatCreation] = jsonFormat6(DcatCreation.apply)

    implicit val licenseFormat: RootJsonFormat[License] = jsonFormat2(License.apply)

    implicit object FacetTypeFormat extends JsonFormat[FacetType] {
      override def write(facetType: FacetType): JsString = JsString.apply(facetType.id)

      override def read(json: JsValue): FacetType = FacetType.fromId(json.convertTo[String]).get
    }

    implicit object MediaTypeFormat extends JsonFormat[MediaType] {
      override def write(mediaType: MediaType): JsString = JsString.apply(mediaType.value)

      override def read(json: JsValue): MediaType = MediaType.parse(json.convertTo[String]).right.get
    }

    implicit object GeometryFormat extends JsonFormat[Geometry] {
      override def write(geometry: Geometry): JsValue = geometry match {
        case point: Point           => GeoJsonFormats.PointFormat.write(point)
        case point: MultiPoint      => MultiPointFormat.write(point)
        case point: LineString      => LineStringFormat.write(point)
        case point: MultiLineString => MultiLineStringFormat.write(point)
        case point: Polygon         => PolygonFormat.write(point)
        case point: MultiPolygon    => MultiPolygonFormat.write(point)
      }

      override def read(json: JsValue): Geometry = json match {
        case JsObject(jsObj) => jsObj.get("type") match {
          case Some(JsString("Point"))           => PointFormat.read(json)
          case Some(JsString("MultiPoint"))      => MultiPointFormat.read(json)
          case Some(JsString("LineString"))      => LineStringFormat.read(json)
          case Some(JsString("MultiLineString")) => MultiLineStringFormat.read(json)
          case Some(JsString("Polygon"))         => PolygonFormat.read(json)
          case Some(JsString("MultiPolygon"))    =>
            val multiPolygon = MultiPolygonFormat.read(json)
            val coordinates = multiPolygon.coordinates
            val firstPoint = coordinates(0)(0)(0)
            val lastPoint = coordinates(0)(0)(coordinates(0)(0).size - 1)
            if (firstPoint.x != lastPoint.x || firstPoint.y != lastPoint.y) {
              multiPolygon.copy(
                coordinates = Seq(Seq(coordinates(0)(0) :+ firstPoint))
              )
            } else {
              multiPolygon
            }
          case _                                 => deserializationError(s"'$json' is not a valid geojson shape")
        }
        case _ => deserializationError(s"'$json' is not a valid geojson shape")
      }
    }

    implicit object CoordinateFormat extends JsonFormat[Coordinate] {
      def write(obj: Coordinate): JsValue = JsArray(
        JsNumber(obj.x),
        JsNumber(obj.y))

      def read(json: JsValue): Coordinate = json match {
        case JsArray(is) =>
          Coordinate(is(0).convertTo[BigDecimal], is(1).convertTo[BigDecimal])
      }
    }

    object EsBoundingBoxFormat extends JsonFormat[BoundingBox] {
      override def write(region: BoundingBox): JsValue = {
        JsObject(
          "type" -> JsString("envelope"),
          "coordinates" -> JsArray(Vector(
            JsArray(Vector(JsNumber(region.west), JsNumber(region.north))),
            JsArray(Vector(JsNumber(region.east), JsNumber(region.south))))))
      }

      override def read(jsonRaw: JsValue): BoundingBox = {
        jsonRaw.asJsObject match {
          case JsObject(fields) => (fields("type"), fields("coordinates")) match {
            case (JsString("envelope"), JsArray(Vector(
              JsArray(Vector(JsNumber(west), JsNumber(north))),
              JsArray(Vector(JsNumber(east), JsNumber(south)))
              ))) => BoundingBox(north, east, south, west)
          }
        }
      }
    }

    val apiBoundingBoxFormat: RootJsonFormat[BoundingBox] = jsonFormat4(BoundingBox)

    implicit val queryRegionFormat: RootJsonFormat[QueryRegion] = jsonFormat2(QueryRegion.apply)

    class RegionFormat(bbFormat: JsonFormat[BoundingBox]) extends JsonFormat[Region] {
      override def write(region: Region): JsValue = JsObject(Map(
        "regionId" -> region.queryRegion.regionId.toJson,
        "regionType" -> region.queryRegion.regionType.toJson,
        "regionName" -> region.regionName.toJson,
        "regionShortName" -> region.regionShortName.toJson,
        "boundingBox" -> region.boundingBox.map(_.toJson(bbFormat)).toJson).filter(x => !x._2.equals(JsNull)))

      override def read(jsonRaw: JsValue): Region = {
        val json = jsonRaw.asJsObject

        Region(
          QueryRegion(
            regionId = json.getFields("regionId").head.convertTo[String],
            regionType = json.getFields("regionType").head.convertTo[String]),
            regionName = json.getFields("regionName").headOption.map(_.convertTo[String]),
            boundingBox = json.getFields("boundingBox").headOption.map(_.convertTo[BoundingBox](bbFormat)),
            regionShortName = json.getFields("regionShortName").headOption.flatMap { shortName => shortName match {
              case JsNull => None
              case _ => Some(shortName.convertTo[String])
            }})
      }
    }

    val apiRegionFormat = new RegionFormat(apiBoundingBoxFormat)
    val esRegionFormat = new RegionFormat(EsBoundingBoxFormat)

    implicit val distributionFormat: RootJsonFormat[Distribution] = jsonFormat13(Distribution.apply)
    implicit val locationFormat: RootJsonFormat[Location] = jsonFormat2(Location.apply)
    implicit val agentFormat: RootJsonFormat[Agent] = jsonFormat17(Agent.apply)
    implicit val facetOptionFormat: RootJsonFormat[FacetOption] = jsonFormat7(FacetOption.apply)
    implicit val facetFormat: RootJsonFormat[Facet] = jsonFormat2(Facet.apply)
    implicit val facetSearchResultFormat: RootJsonFormat[FacetSearchResult] = jsonFormat2(FacetSearchResult.apply)

    implicit val readyStatus: RootJsonFormat[ReadyStatus] = jsonFormat1(ReadyStatus.apply)

    implicit val accessControlFormat: RootJsonFormat[AccessControl] = jsonFormat3(AccessControl.apply)

    /**
      * Manually implement RootJsonFormat to overcome the limit of 22 parameters
      */
    implicit object dataSetFormat extends RootJsonFormat[DataSet] {
      override def write(dataSet: DataSet):JsValue =
        JsObject(
          "identifier" -> dataSet.identifier.toJson,
          "tenantId" -> dataSet.tenantId.toJson,
          "title" -> dataSet.title.toJson,
          "catalog" -> dataSet.catalog.toJson,
          "description" -> dataSet.description.toJson,
          "issued" -> dataSet.issued.toJson,
          "modified" -> dataSet.modified.toJson,
          "languages" -> dataSet.languages.toJson,
          "publisher" -> dataSet.publisher.toJson,
          "accrualPeriodicity" -> dataSet.accrualPeriodicity.toJson,
          "accrualPeriodicityRecurrenceRule" -> dataSet.accrualPeriodicityRecurrenceRule.toJson,
          "spatial" -> dataSet.spatial.toJson,
          "temporal" -> dataSet.temporal.toJson,
          "themes" -> dataSet.themes.toJson,
          "keywords" -> dataSet.keywords.toJson,
          "contactPoint" -> dataSet.contactPoint.toJson,
          "distributions" -> dataSet.distributions.toJson,
          "landingPage" -> dataSet.landingPage.toJson,
          "years" -> dataSet.years.toJson,
          "indexed" -> dataSet.indexed.toJson,
          "quality" -> dataSet.quality.toJson,
          "hasQuality" -> dataSet.hasQuality.toJson,
          "creation" -> dataSet.creation.toJson,
          "source" -> dataSet.source.toJson,
          "score" -> dataSet.score.toJson,
          "publishingState" -> dataSet.publishingState.toJson,
          "accessControl" -> dataSet.accessControl.toJson
        )

      def convertOptionField[T:JsonReader](fieldName: String, jsData: JsValue): Option[T] = {
        val jsObject = jsData.asJsObject
        jsObject.getFields(fieldName).headOption.flatMap(fieldData => fieldData match {
          case JsNull => None
          case _ => Some(fieldData.convertTo[T])
        })
      }

      def convertField[T:JsonReader](fieldName: String, jsData: JsValue): T = jsData.asJsObject.getFields(fieldName).head.convertTo[T]

      def convertCollectionField[T:JsonReader](fieldName: String, json: JsValue): Seq[T] = json match {
        case JsObject(jsData) => jsData.get(fieldName) match {
          case Some(JsArray(items)) => items.map(_.convertTo[T])
          case _ => Seq()
        }
        case _ => Seq()
      }

      override def read(json: JsValue): DataSet= {

        DataSet(
          identifier = convertField[String]("identifier", json),
          tenantId = convertField[BigInt]("tenantId", json),
          title = convertOptionField[String]("title", json),
          catalog = convertOptionField[String]("catalog", json),
          description = convertOptionField[String]("description", json),
          issued = convertOptionField[OffsetDateTime]("issued", json),
          modified = convertOptionField[OffsetDateTime]("modified", json),
          languages = convertField[Set[String]]("languages", json),
          publisher = convertOptionField[Agent]("publisher", json),
          accrualPeriodicity = convertOptionField[Periodicity]("accrualPeriodicity", json),
          spatial = convertOptionField[Location]("spatial", json),
          temporal = convertOptionField[PeriodOfTime]("temporal", json),
          themes = convertCollectionField[String]("themes", json),
          keywords = convertCollectionField[String]("keywords", json),
          contactPoint = convertOptionField[Agent]("contactPoint", json),
          distributions = convertCollectionField[Distribution]("distributions", json),
          landingPage = convertOptionField[String]("landingPage", json),
          years = convertOptionField[String]("years", json),
          indexed = convertOptionField[OffsetDateTime]("indexed", json),
          quality = convertField[Double]("quality", json),
          hasQuality = convertField[Boolean]("hasQuality", json),
          source = convertOptionField[DataSouce]("source", json),
          creation = convertOptionField[DcatCreation]("creation", json),
          score = convertOptionField[Float]("score", json),
          publishingState = convertOptionField[String]("publishingState", json),
          accessControl = convertOptionField[AccessControl]("accessControl", json),
          accrualPeriodicityRecurrenceRule = convertOptionField[String]("accrualPeriodicityRecurrenceRule", json)
        )
      }
    }

  }

  object Protocols extends Protocols {
  }
}

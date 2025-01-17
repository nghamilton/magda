package au.csiro.data61.magda.registry

import java.sql.SQLException

import akka.NotUsed
import akka.event.LoggingAdapter
import akka.stream.scaladsl.Source
import au.csiro.data61.magda.model.Registry._
import gnieh.diffson._
import gnieh.diffson.sprayJson._
import scalikejdbc._
import spray.json._
import spray.json.lenses.JsonLenses._

import scala.util.{Failure, Success, Try}

// TODO: Not to filter results by tenant ID for magda internal use only functions.
// For example, getByIdsWithAspects() is used by WebHookProcessor that may send records to Indexer.
// In this case, those records should not be filtered by tenant ID.
trait RecordPersistence {
  def getAll(implicit session: DBSession, tenantId: BigInt, pageToken: Option[String], start: Option[Int], limit: Option[Int]): RecordsPage[RecordSummary]

  def getAllWithAspects(implicit session: DBSession,
                        tenantId: BigInt,
                        aspectIds: Iterable[String],
                        optionalAspectIds: Iterable[String],
                        pageToken: Option[Long] = None,
                        start: Option[Int] = None,
                        limit: Option[Int] = None,
                        dereference: Option[Boolean] = None,
                        aspectQueries: Iterable[AspectQuery] = Nil): RecordsPage[Record]

  def getCount(implicit session: DBSession,
               tenantId: BigInt,
               aspectIds: Iterable[String],
               aspectQueries: Iterable[AspectQuery] = Nil): Long

  def getById(implicit session: DBSession, tenantId: BigInt, id: String): Option[RecordSummary]

  def getByIdWithAspects(implicit session: DBSession,
                         id: String,
                         tenantId: BigInt,
                         aspectIds: Iterable[String] = Seq(),
                         optionalAspectIds: Iterable[String] = Seq(),
                         dereference: Option[Boolean] = None): Option[Record]

  def getByIdsWithAspects(implicit session: DBSession,
                          tenantId: BigInt,
                          ids: Iterable[String],
                          aspectIds: Iterable[String] = Seq(),
                          optionalAspectIds: Iterable[String] = Seq(),
                          dereference: Option[Boolean] = None): RecordsPage[Record]

  def getRecordsLinkingToRecordIds(implicit session: DBSession,
                                   tenantId: BigInt,
                                   ids: Iterable[String],
                                   idsToExclude: Iterable[String] = Seq(),
                                   aspectIds: Iterable[String] = Seq(),
                                   optionalAspectIds: Iterable[String] = Seq(),
                                   dereference: Option[Boolean] = None): RecordsPage[Record]

  def getRecordAspectById(implicit session: DBSession, recordId: String, tenantId: BigInt, aspectId: String): Option[JsObject]

  def getPageTokens(implicit session: DBSession,
                    tenantId: BigInt,
                    aspectIds: Iterable[String],
                    limit: Option[Int] = None,
                    recordSelector: Iterable[Option[SQLSyntax]] = Iterable()): List[String]

  def putRecordById(implicit session: DBSession, id: String, tenantId: BigInt, newRecord: Record): Try[Record]

  def patchRecordById(implicit session: DBSession, id: String, tenantId: BigInt, recordPatch: JsonPatch): Try[Record]

  def patchRecordAspectById(implicit session: DBSession, recordId: String, tenantId: BigInt, aspectId: String, aspectPatch: JsonPatch): Try[JsObject]

  def putRecordAspectById(implicit session: DBSession, recordId: String, tenantId: BigInt, aspectId: String, newAspect: JsObject): Try[JsObject]

  def createRecord(implicit session: DBSession, record: Record, tenantId: BigInt): Try[Record]

  def deleteRecord(implicit session: DBSession, recordId: String, tenantId: BigInt): Try[Boolean]

  def trimRecordsBySource(sourceTagToPreserve: String, sourceId: String, tenantId: BigInt, logger: Option[LoggingAdapter] = None)(implicit session: DBSession): Try[Long]

  def createRecordAspect(implicit session: DBSession, recordId: String, tenantId: BigInt, aspectId: String, aspect: JsObject): Try[JsObject]

  def deleteRecordAspect(implicit session: DBSession, recordId: String, tenantId: BigInt, aspectId: String): Try[Boolean]

  def reconstructRecordFromEvents(id: String, events: Source[RegistryEvent, NotUsed], aspects: Iterable[String], optionalAspects: Iterable[String]): Source[Option[Record], NotUsed]
}

object DefaultRecordPersistence extends Protocols with DiffsonProtocol with RecordPersistence {
  val maxResultCount = 1000
  val defaultResultCount = 100

  def getAll(implicit session: DBSession, tenantId: BigInt, pageToken: Option[String], start: Option[Int], limit: Option[Int]): RecordsPage[RecordSummary] = {
    this.getSummaries(session, tenantId, pageToken, start, limit)
  }

  def getAllWithAspects(implicit session: DBSession,
                        tenantId: BigInt,
                        aspectIds: Iterable[String],
                        optionalAspectIds: Iterable[String],
                        pageToken: Option[Long] = None,
                        start: Option[Int] = None,
                        limit: Option[Int] = None,
                        dereference: Option[Boolean] = None,
                        aspectQueries: Iterable[AspectQuery] = Nil): RecordsPage[Record] = {
    val selectors = aspectQueries.map(query => aspectQueryToWhereClause(tenantId, query)).map(Some.apply)

    this.getRecords(session, tenantId, aspectIds, optionalAspectIds, pageToken, start, limit, dereference, selectors)
  }

  def getCount(implicit session: DBSession,
               tenantId: BigInt,
               aspectIds: Iterable[String],
               aspectQueries: Iterable[AspectQuery] = Nil): Long = {
    val selectors: Iterable[Some[SQLSyntax]] = aspectQueries.map(query => aspectQueryToWhereClause(tenantId, query)).map(Some.apply)

    this.getCountInner(session, tenantId, aspectIds, selectors)
  }

  def getById(implicit session: DBSession, tenantId: BigInt, id: String): Option[RecordSummary] = {
    this.getSummaries(session, tenantId, None, None, None, Some(id)).records.headOption
  }

  def getByIdWithAspects(implicit session: DBSession,
                         id: String,
                         tenantId: BigInt,
                         aspectIds: Iterable[String] = Seq(),
                         optionalAspectIds: Iterable[String] = Seq(),
                         dereference: Option[Boolean] = None): Option[Record] = {
    this.getRecords(session, tenantId, aspectIds, optionalAspectIds, None, None, None, dereference, List(Some(sqls"recordId=$id"))).records.headOption
  }

  def getByIdsWithAspects(implicit session: DBSession,
                          tenantId: BigInt,
                          ids: Iterable[String],
                          aspectIds: Iterable[String] = Seq(),
                          optionalAspectIds: Iterable[String] = Seq(),
                          dereference: Option[Boolean] = None): RecordsPage[Record] = {
    if (ids.isEmpty)
      RecordsPage(hasMore = false, Some("0"), List())
    else {
      this.getRecords(session, tenantId, aspectIds, optionalAspectIds, None, None, None, dereference, List(Some(sqls"recordId in ($ids)")))
    }
  }

  def getRecordsLinkingToRecordIds(implicit session: DBSession,
                                   tenantId: BigInt,
                                   ids: Iterable[String],
                                   idsToExclude: Iterable[String] = Seq(),
                                   aspectIds: Iterable[String] = Seq(),
                                   optionalAspectIds: Iterable[String] = Seq(),
                                   dereference: Option[Boolean] = None): RecordsPage[Record] = {
    val linkAspects = buildDereferenceMap(session, List.concat(aspectIds, optionalAspectIds))
    if (linkAspects.isEmpty) {
      // There are no linking aspects, so there cannot be any records linking to these IDs.
      RecordsPage(hasMore = false, None, List())
    } else {
      val dereferenceSelectors = linkAspects.map {
        case (aspectId, propertyWithLink) =>
          sqls"""exists (select 1
                         from RecordAspects
                         where RecordAspects.recordId=Records.recordId
                         and aspectId=$aspectId
                         and tenantId=$tenantId
                         and data->${propertyWithLink.propertyName} ??| ARRAY[$ids])"""
      }

      val excludeSelector = if (idsToExclude.isEmpty) None else Some(sqls"recordId not in ($idsToExclude)")

      val selectors = Seq(Some(SQLSyntax.join(dereferenceSelectors.toSeq, SQLSyntax.or)), excludeSelector)

      this.getRecords(session, tenantId, aspectIds, optionalAspectIds, None, None, None, dereference, selectors)
    }
  }

  def getRecordAspectById(implicit session: DBSession, recordId: String, tenantId: BigInt, aspectId: String): Option[JsObject] = {
    sql"""select RecordAspects.aspectId as aspectId, name as aspectName, data from RecordAspects
          inner join Aspects using (aspectId, tenantId)
          where (RecordAspects.aspectId, RecordAspects.recordId, RecordAspects.tenantId)=($aspectId, $recordId, $tenantId)"""
      .map(rowToAspect)
      .single.apply()
  }

  def getPageTokens(implicit session: DBSession,
                    tenantId: BigInt,
                    aspectIds: Iterable[String],
                    limit: Option[Int] = None,
                    recordSelector: Iterable[Option[SQLSyntax]] = Iterable()): List[String] = {
    val recordsFilteredByTenantClause = filterRecordsByTenantClause(tenantId)
    val whereClauseParts = aspectIdsToWhereClause(tenantId, aspectIds) ++ recordSelector :+ Some(recordsFilteredByTenantClause)

    //    val whereClause = aspectIds.map(aspectId => s"recordaspects.aspectid = '$aspectId'").mkString(" AND ")

    sql"""SELECT sequence
        FROM
        (
            SELECT sequence, ROW_NUMBER() OVER (ORDER BY sequence) AS rownum
            FROM records
            ${makeWhereClause(whereClauseParts)}
        ) AS t
        WHERE t.rownum % ${limit.map(l => Math.min(l, maxResultCount)).getOrElse(defaultResultCount)} = 0
        ORDER BY t.sequence;""".map(rs => {
      rs.string("sequence")
    }).list.apply()
  }

  def putRecordById(implicit session: DBSession, id: String, tenantId: BigInt, newRecord: Record): Try[Record] = {
    val newRecordWithoutAspects = newRecord.copy(aspects = Map())

    for {
      _ <- if (id == newRecord.id) Success(newRecord) else Failure(new RuntimeException("The provided ID does not match the record's ID."))
      oldRecordWithoutAspects <- this.getByIdWithAspects(session, id, tenantId) match {
        case Some(record) => Success(record)
        // Possibility of a race condition here. The record doesn't exist, so we try to create it.
        // But someone else could have created it in the meantime. So if our create fails, try one
        // more time to get an existing one. We use a nested transaction so that, if the create fails,
        // we don't end up with an extraneous record creation event in the database.
        case None => DB.localTx { nested => createRecord(nested, newRecord, tenantId).map(_.copy(aspects = Map())) } match {
          case Success(record) => Success(record)
          case Failure(e) => this.getByIdWithAspects(session, id, tenantId) match {
            case Some(record) => Success(record)
            case None         => Failure(e)
          }
        }
      }

      recordPatch <- Try {
        // Diff the old record and the new one, ignoring aspects
        val oldRecordJson = oldRecordWithoutAspects.toJson
        val newRecordJson = newRecordWithoutAspects.toJson

        JsonDiff.diff(oldRecordJson, newRecordJson, remember = false)
      }
      result <- patchRecordById(session, id, tenantId, recordPatch)
      patchedAspects <- Try {
        newRecord.aspects.map {
          case (aspectId, data) =>
            (aspectId, this.putRecordAspectById(session, id, tenantId, aspectId, data))
        }
      }
      // Report the first failed aspect, if any
      _ <- patchedAspects.find(_._2.isFailure) match {
        case Some((_, Failure(failure))) => Failure(failure)
        case _                           => Success(result)
      }
      // No failed aspects, so unwrap the aspects from the Success Trys.
      resultAspects <- Try { patchedAspects.mapValues(_.get) }
    } yield result.copy(aspects = resultAspects, sourceTag = newRecord.sourceTag)
  }

  def patchRecordById(implicit session: DBSession, id: String, tenantId: BigInt, recordPatch: JsonPatch): Try[Record] = {
    for {
      record <- this.getByIdWithAspects(session, id, tenantId) match {
        case Some(record) => Success(record)
        case None         => Failure(new RuntimeException("No record exists with that ID."))
      }
      recordOnlyPatch <- Success(recordPatch.filter(op => op.path match {
        case "aspects" / _ => false
        case _             => true
      }))
      patchedRecord <- Try {
        recordOnlyPatch(record)
      }
      _ <- if (id == patchedRecord.id) Success(patchedRecord) else Failure(new RuntimeException("The patch must not change the record's ID."))
      _ <- Try {
        // Sourcetag should not generate an event so updating it is done separately
        if (record.sourceTag != patchedRecord.sourceTag) {
          sql"""update Records set sourcetag = ${patchedRecord.sourceTag} where (recordId, tenantId) = ($id, $tenantId)""".update.apply()
        }
      }
      _ <- Try {
        // Name is currently the only member of Record that should generate an event when changed.
        if (record.name != patchedRecord.name) {
          val event = PatchRecordEvent(id, tenantId, recordOnlyPatch).toJson.compactPrint
          val eventId = sql"insert into Events (eventTypeId, userId, tenantId, data) values (${PatchRecordEvent.Id}, 0, $tenantId, $event::json)".updateAndReturnGeneratedKey().apply()
          sql"""update Records set name = ${patchedRecord.name}, lastUpdate = $eventId where (recordId, tenantId) = ($id, $tenantId)""".update.apply()
          eventId
        } else {
          0
        }
      }
      aspectResults <- Try {
        recordPatch.ops.groupBy(op => op.path match {
          case "aspects" / (name / _) => Some(name)
          case _                      => None
        }).filterKeys(!_.isEmpty).map({
          // Create or patch each aspect.
          // We create if there's exactly one ADD operation and it's adding an entire aspect.
          case (Some(aspectId), List(Add("aspects" / (name / rest), value))) => {
            if (rest == Pointer.Empty)
              (aspectId, putRecordAspectById(session, id, tenantId, aspectId, value.asJsObject))
            else
              (aspectId, patchRecordAspectById(session, id, tenantId, aspectId, JsonPatch(Add(rest, value))))
          }
          // We delete if there's exactly one REMOVE operation and it's removing an entire aspect.
          case (Some(aspectId), List(Remove("aspects" / (name / rest), old))) => {
            if (rest == Pointer.Empty) {
              deleteRecordAspect(session, id, tenantId, aspectId)
              (aspectId, Success(JsNull))
            } else {
              (aspectId, patchRecordAspectById(session, id, tenantId, aspectId, JsonPatch(Remove(rest, old))))
            }
          }
          // We patch in all other scenarios.
          case (Some(aspectId), operations) => (aspectId, patchRecordAspectById(session, id, tenantId, aspectId, JsonPatch(operations.map({
            // Make paths in operations relative to the aspect instead of the record
            case Add("aspects" / (name / rest), value)          => Add(rest, value)
            case Remove("aspects" / (name / rest), old)         => Remove(rest, old)
            case Replace("aspects" / (name / rest), value, old) => Replace(rest, value, old)
            case Move("aspects" / (sourceName / sourceRest), "aspects" / (destName / destRest)) => {
              if (sourceName != destName)
                // We can relax this restriction, and the one on Copy below, by turning a cross-aspect
                // Move into a Remove on one and an Add on the other.  But it's probably not worth
                // the trouble.
                throw new RuntimeException("A patch may not move values between two different aspects.")
              else
                Move(sourceRest, destRest)
            }
            case Copy("aspects" / (sourceName / sourceRest), "aspects" / (destName / destRest)) => {
              if (sourceName != destName)
                throw new RuntimeException("A patch may not copy values between two different aspects.")
              else
                Copy(sourceRest, destRest)
            }
            case Test("aspects" / (name / rest), value) => Test(rest, value)
            case _                                      => throw new RuntimeException("The patch contains an unsupported operation for aspect " + aspectId)
          }))))
          case _ => throw new RuntimeException("Aspect ID is missing (this shouldn't be possible).")
        })
      }
      // Report the first failed aspect, if any
      _ <- aspectResults.find(_._2.isFailure) match {
        case Some((_, failure)) => failure
        case _                  => Success(record)
      }
      // No failed aspects, so unwrap the aspects from the Success Trys.
      aspects <- Success(aspectResults.filter({
        case (_, Success(JsNull)) => false // aspect was deleted
        case _                    => true
      }).map(aspect => (aspect._1, aspect._2.get.asJsObject)))
    } yield Record(patchedRecord.id, patchedRecord.name, aspects, tenantId=Some(tenantId))
  }

  def patchRecordAspectById(implicit session: DBSession, recordId: String, tenantId: BigInt, aspectId: String, aspectPatch: JsonPatch): Try[JsObject] = {
    for {
      aspect <- this.getRecordAspectById(session, recordId, tenantId, aspectId) match {
        case Some(aspect) => Success(aspect)
        case None         => createRecordAspect(session, recordId, tenantId, aspectId, JsObject())
      }

      patchedAspect <- Try {
        aspectPatch(aspect).asJsObject
      }

      testRecordAspectPatch <- Try {
        // Diff the old record aspect and the patched one to see whether an event should be created
        val oldAspectJson = aspect.toJson
        val newAspectJson = patchedAspect.toJson

        JsonDiff.diff(oldAspectJson, newAspectJson, false)
      }

      eventId <- Try {
        if (testRecordAspectPatch.ops.length > 0) {
          val event = PatchRecordAspectEvent(recordId, tenantId, aspectId, aspectPatch).toJson.compactPrint
          sql"insert into Events (eventTypeId, userId, tenantId, data) values (${PatchRecordAspectEvent.Id}, 0, $tenantId, $event::json)".updateAndReturnGeneratedKey().apply()
        } else {
          0
        }
      }

      _ <- Try {
        if (testRecordAspectPatch.ops.length > 0) {
          val jsonString = patchedAspect.compactPrint
          sql"""insert into RecordAspects (recordId, tenantId, aspectId, lastUpdate, data) values ($recordId, $tenantId, $aspectId, $eventId, $jsonString::json)
               on conflict (aspectId, recordId, tenantId) do update
               set lastUpdate = $eventId, data = $jsonString::json
               """.update.apply()
        } else {
          0
        }
      }
    } yield patchedAspect
  }

  def putRecordAspectById(implicit session: DBSession, recordId: String, tenantId: BigInt, aspectId: String, newAspect: JsObject): Try[JsObject] = {
    for {
      oldAspect <- this.getRecordAspectById(session, recordId, tenantId, aspectId) match {
        case Some(aspect) => Success(aspect)
        // Possibility of a race condition here. The aspect doesn't exist, so we try to create it.
        // But someone else could have created it in the meantime. So if our create fails, try one
        // more time to get an existing one. We use a nested transaction so that, if the create fails,
        // we don't end up with an extraneous record creation event in the database.
        case None => DB.localTx { nested => createRecordAspect(nested, recordId, tenantId, aspectId, newAspect) } match {
          case Success(aspect) => Success(aspect)
          case Failure(e) => this.getRecordAspectById(session, recordId, tenantId, aspectId) match {
            case Some(aspect) => Success(aspect)
            case None         => Failure(e)
          }
        }
      }
      recordAspectPatch <- Try {
        // Diff the old record aspect and the new one
        val oldAspectJson = oldAspect.toJson
        val newAspectJson = newAspect.toJson

        JsonDiff.diff(oldAspectJson, newAspectJson, false)
      }
      result <- patchRecordAspectById(session, recordId, tenantId, aspectId, recordAspectPatch)
    } yield result
  }

  def createRecord(implicit session: DBSession, record: Record, tenantId: BigInt): Try[Record] = {
    for {
      eventId <- Try {
        val eventJson = CreateRecordEvent(record.id, tenantId, record.name).toJson.compactPrint
        sql"insert into Events (eventTypeId, userId, tenantId, data) values (${CreateRecordEvent.Id}, 0, $tenantId, $eventJson::json)".updateAndReturnGeneratedKey.apply()
      }
      insertResult <- Try {
        sql"""insert into Records (recordId, tenantId, name, lastUpdate, sourcetag) values (${record.id}, $tenantId, ${record.name}, $eventId, ${record.sourceTag})""".update.apply()
      } match {
        case Failure(e: SQLException) if e.getSQLState().substring(0, 2) == "23" =>
          Failure(new RuntimeException(s"Cannot create record '${record.id}' because a record with that ID already exists."))
        case anythingElse => anythingElse
      }

      hasAspectFailure <- record.aspects.map(aspect => createRecordAspect(session, record.id, tenantId, aspect._1, aspect._2)).find(_.isFailure) match {
        case Some(Failure(e)) => Failure(e)
        case _                => Success(record.copy(tenantId = Some(tenantId)))
      }
    } yield hasAspectFailure
  }

  def deleteRecord(implicit session: DBSession, recordId: String, tenantId: BigInt): Try[Boolean] = {
    for {
      aspects <- Try {
        sql"select aspectId from RecordAspects where (recordId, tenantId)=($recordId, $tenantId)"
          .map(rs => rs.string("aspectId")).list.apply()
      }
      _ <- aspects.map(aspectId => deleteRecordAspect(session, recordId, tenantId, aspectId)).find(_.isFailure) match {
        case Some(Failure(e)) => Failure(e)
        case _                => Success(aspects)
      }
      _ <- Try {
        val eventJson = DeleteRecordEvent(recordId, tenantId).toJson.compactPrint
        sql"insert into Events (eventTypeId, userId, tenantId, data) values (${DeleteRecordEvent.Id}, 0, $tenantId, $eventJson::json)".updateAndReturnGeneratedKey.apply()
      }
      rowsDeleted <- Try {
        sql"""delete from Records where (recordId, tenantId)=($recordId, $tenantId)""".update.apply()
      }
    } yield rowsDeleted > 0
  }

  def trimRecordsBySource(sourceTagToPreserve: String, sourceId: String, tenantId: BigInt, logger: Option[LoggingAdapter] = None)(implicit session: DBSession): Try[Long] = {
    val recordIds = Try {
      sql"select distinct records.recordId, sourcetag from Records INNER JOIN recordaspects ON (records.recordid, records.tenantId) = (recordaspects.recordid, recordaspects.tenantId) where (sourcetag != $sourceTagToPreserve OR sourcetag IS NULL) and recordaspects.aspectid = 'source' and recordaspects.data->>'id' = $sourceId and Records.tenantId = $tenantId"
        .map(rs => rs.string("recordId")).list.apply()
    }

    val result = recordIds match {
      case Success(Nil) => Success(0l)
      case Success(ids) =>
        ids
          .map(recordId => deleteRecord(session, recordId, tenantId))
          .foldLeft[Try[Long]](Success(0l))((trySoFar: Try[Long], thisTry: Try[Boolean]) => (trySoFar, thisTry) match {
            case (Success(countSoFar), Success(bool)) => Success(countSoFar + (if (bool) 1 else 0))
            case (Failure(err), _)                    => Failure(err)
            case (_, Failure(err))                    => Failure(err)
          })
      case Failure(err) => Failure(err)
    }

    result match {
      case Success(count:Long) =>
        if(!logger.isEmpty) {
          logger.get.info(s"Trimmed ${count} records.")
        }
        Success(count)
      case Failure(err) =>
        if(!logger.isEmpty) {
          logger.get.error(err, "Error happened when trimming records.")
        }
        Failure(err)
    }

  }

  def createRecordAspect(implicit session: DBSession, recordId: String, tenantId: BigInt, aspectId: String, aspect: JsObject): Try[JsObject] = {
    for {
      eventId <- Try {
        val eventJson = CreateRecordAspectEvent(recordId, tenantId, aspectId, aspect).toJson.compactPrint
        sql"insert into Events (eventTypeId, userId, tenantId, data) values (${CreateRecordAspectEvent.Id}, 0, $tenantId, $eventJson::json)".updateAndReturnGeneratedKey.apply()
      }
      insertResult <- Try {
        val jsonData = aspect.compactPrint
        sql"""insert into RecordAspects (recordId, tenantId, aspectId, lastUpdate, data) values ($recordId, $tenantId, ${aspectId}, $eventId, $jsonData::json)""".update.apply()
        aspect
      } match {
        case Failure(e: SQLException) if e.getSQLState().substring(0, 2) == "23" =>
          Failure(new RuntimeException(s"Cannot create aspect '${aspectId}' for record '${recordId}' because the record or aspect does not exist, or because data already exists for that combination of record and aspect."))
        case anythingElse => anythingElse
      }
    } yield insertResult
  }

  def deleteRecordAspect(implicit session: DBSession, recordId: String, tenantId: BigInt, aspectId: String): Try[Boolean] = {
    for {
      _ <- Try {
        val eventJson = DeleteRecordAspectEvent(recordId, tenantId, aspectId).toJson.compactPrint
        sql"insert into Events (eventTypeId, userId, tenantId, data) values (${DeleteRecordAspectEvent.Id}, 0, $tenantId, $eventJson::json)".updateAndReturnGeneratedKey.apply()
      }
      rowsDeleted <- Try {
        sql"""delete from RecordAspects where (aspectId, recordId, tenantId)=($aspectId, $recordId, $tenantId)""".update.apply()
      }
    } yield rowsDeleted > 0
  }

  def reconstructRecordFromEvents(id: String, events: Source[RegistryEvent, NotUsed], aspects: Iterable[String], optionalAspects: Iterable[String]): Source[Option[Record], NotUsed] = {
    // TODO: can probably simplify some of this with lenses or whatever
    events.fold[JsValue](JsNull)((recordValue, event) => event.eventType match {
      case EventType.CreateRecord => JsObject("id" -> event.data.fields("recordId"), "name" -> event.data.fields("name"), "aspects" -> JsObject())
      case EventType.PatchRecord  => event.data.fields("patch").convertTo[JsonPatch].apply(recordValue)
      case EventType.DeleteRecord => JsNull
      case EventType.CreateRecordAspect => {
        val createAspectEvent = event.data.convertTo[CreateRecordAspectEvent]
        val record = recordValue.asJsObject
        val existingFields = record.fields
        val existingAspects = record.fields("aspects").asJsObject.fields
        val newAspects = existingAspects + (createAspectEvent.aspectId -> createAspectEvent.aspect)
        val newFields = existingFields + ("aspects" -> JsObject(newAspects))
        JsObject(newFields)
      }
      case EventType.PatchRecordAspect => {
        val patchRecordAspectEvent = event.data.convertTo[PatchRecordAspectEvent]
        val record = recordValue.asJsObject
        val existingFields = record.fields
        val existingAspects = record.fields("aspects").asJsObject.fields
        val existingAspect = existingAspects(patchRecordAspectEvent.aspectId)
        val newAspects = existingAspects + (patchRecordAspectEvent.aspectId -> patchRecordAspectEvent.patch.apply(existingAspect))
        val newFields = existingFields + ("aspects" -> JsObject(newAspects))
        JsObject(newFields)
      }
      case EventType.DeleteRecordAspect => {
        val deleteRecordAspectEvent = event.data.convertTo[DeleteRecordAspectEvent]
        val record = recordValue.asJsObject
        val existingFields = record.fields
        val existingAspects = record.fields("aspects").asJsObject.fields
        val newAspects = existingAspects - deleteRecordAspectEvent.aspectId
        val newFields = existingFields + ("aspects" -> JsObject(newAspects))
        JsObject(newFields)
      }
      case _ => recordValue
    }).map {
      case obj: JsObject => Some(obj.convertTo[Record])
      case _             => None
    }
  }

  private def getSummaries(implicit session: DBSession, tenantId: BigInt, pageToken: Option[String], start: Option[Int], rawLimit: Option[Int], recordId: Option[String] = None): RecordsPage[RecordSummary] = {
    val countWhereClauseParts = if (recordId.nonEmpty) Seq(recordId.map(id => sqls"recordId=$id and Records.tenantId=$tenantId")) else Seq(Some(sqls"Records.tenantId=$tenantId"))

    val whereClauseParts = countWhereClauseParts :+ pageToken.map(token => sqls"Records.sequence > ${token.toLong} and Records.tenantId=$tenantId")
    val limit = rawLimit.getOrElse(defaultResultCount)

    val result =
      sql"""select Records.sequence as sequence,
                   Records.recordId as recordId,
                   Records.name as recordName,
                   (select array_agg(aspectId) from RecordAspects where (recordId, tenantId)=(Records.recordId, $tenantId)) as aspects,
                   Records.tenantId as tenantId
            from Records
            ${makeWhereClause(whereClauseParts)}
            order by sequence
            offset ${start.getOrElse(0)}
            limit ${limit + 1}"""
        .map(rs => {
          (rs.long("sequence"),
            rowToRecordSummary(rs))
        })
        .list.apply()

    val hasMore = result.length > limit
    val trimmed = result.take(limit)
    val lastSequence = if (hasMore) Some(trimmed.last._1) else None
    val pageResults = trimmed.map(_._2)

    RecordsPage[RecordSummary](
      lastSequence.isDefined,
      lastSequence.map(_.toString),
      pageResults)
  }

  private def getRecords(implicit session: DBSession,
                         tenantId: BigInt,
                         aspectIds: Iterable[String],
                         optionalAspectIds: Iterable[String],
                         pageToken: Option[Long] = None,
                         start: Option[Int] = None,
                         rawLimit: Option[Int] = None,
                         dereference: Option[Boolean] = None,
                         recordSelector: Iterable[Option[SQLSyntax]] = Iterable()): RecordsPage[Record] = {

    // If we're dereferencing links, we'll need to determine which fields of the selected aspects are links.
    val dereferenceLinks = dereference.getOrElse(false)

    val dereferenceDetails = if (dereferenceLinks) {
      buildDereferenceMap(session, List.concat(aspectIds, optionalAspectIds))
    } else {
      Map[String, PropertyWithLink]()
    }

    val recordsFilteredByTenantClause: SQLSyntax = filterRecordsByTenantClause(tenantId)
    val theRecordSelector = recordSelector ++ Iterable(Some(recordsFilteredByTenantClause))
    val whereClauseParts = (aspectIdsToWhereClause(tenantId, aspectIds) ++ theRecordSelector) :+ pageToken.map(token => sqls"Records.sequence > $token")
    val aspectSelectors = aspectIdsToSelectClauses(List.concat(aspectIds, optionalAspectIds), dereferenceDetails)

    val limit = rawLimit.map(l => Math.min(l, maxResultCount)).getOrElse(defaultResultCount)
    val result =
      sql"""select Records.sequence as sequence,
                   Records.recordId as recordId,
                   Records.name as recordName,
                   Records.tenantId as tenantId
                   ${if (aspectSelectors.nonEmpty) sqls", $aspectSelectors" else SQLSyntax.empty},
                   Records.sourcetag as sourceTag
            from Records
            ${makeWhereClause(whereClauseParts)}
            order by Records.sequence
            offset ${start.getOrElse(0)}
            limit ${limit + 1}""".map(rs => {
        (rs.long("sequence"), rowToRecord(List.concat(aspectIds, optionalAspectIds))(rs))
      })
        .list.apply()

    val hasMore = result.length > limit
    val trimmed = result.take(limit)
    val lastSequence = if (hasMore) Some(trimmed.last._1) else None
    val pageResults = trimmed.map(_._2)

    RecordsPage(
      hasMore,
      lastSequence.map(_.toString),
      pageResults)
  }

  private def getCountInner(implicit session: DBSession,
                            tenantId: BigInt,
                            aspectIds: Iterable[String],
                            recordSelector: Iterable[Option[SQLSyntax]] = Iterable()): Long = {
    val recordsFilteredByTenantClause = filterRecordsByTenantClause(tenantId)
    val theRecordSelector = Iterable(Some(recordsFilteredByTenantClause)) ++ recordSelector
    val statement = if (aspectIds.size == 1) {
      // If there's only one aspect id, it's much much more efficient to query the recordaspects table rather than records.
      // Because a record cannot have more than one aspect for each type, counting the number of recordaspects with a certain aspect type
      // is equivalent to counting the records with that type
      val aspectIdsWhereClause = aspectIds.map(aspectId => sqls"RecordAspects.tenantId=$tenantId and RecordAspects.aspectId=$aspectId").toSeq
      val recordSelectorWhereClause = theRecordSelector.flatten.map(recordSelectorInner => sqls"EXISTS(SELECT 1 FROM Records WHERE Records.tenantId=$tenantId AND RecordAspects.recordId=Records.recordId AND $recordSelectorInner)").toSeq
      val clauses = (aspectIdsWhereClause ++ recordSelectorWhereClause).map(Some.apply)
      sql"select count(*) from RecordAspects ${makeWhereClause(clauses)}"
    } else {
      // If there's zero or > 1 aspect ids involved then there's no advantage to querying record aspects instead.
      sql"select count(*) from Records ${makeWhereClause(aspectIdsToWhereClause(tenantId, aspectIds) ++ theRecordSelector)}"
    }

    statement.map(_.long(1)).single.apply().getOrElse(0l)
  }

  private def makeWhereClause(andParts: Seq[Option[SQLSyntax]]) = {
    andParts.filter(!_.isEmpty) match {
      case Seq()    => SQLSyntax.empty
      case nonEmpty => SQLSyntax.where(SQLSyntax.joinWithAnd(nonEmpty.map(_.get): _*))
    }
  }

  private def rowToRecordSummary(rs: WrappedResultSet): RecordSummary = {
    RecordSummary(rs.string("recordId"), rs.string("recordName"), rs.arrayOpt("aspects").map(_.getArray().asInstanceOf[Array[String]].toList).getOrElse(List()), rs.bigInt("tenantId"))
  }

  private def rowToRecord(aspectIds: Iterable[String])(rs: WrappedResultSet): Record = {
    Record(rs.string("recordId"), rs.string("recordName"),
      aspectIds.zipWithIndex
        .filter {
          case (_, index) => rs.stringOpt(s"aspect${index}").isDefined
        }
        .map {
          case (aspectId, index) => (aspectId, JsonParser(rs.string(s"aspect${index}")).asJsObject)
        }
        .toMap, rs.stringOpt("sourceTag"),
      Option(rs.bigInt("tenantId")))
  }

  private def rowToAspect(rs: WrappedResultSet): JsObject = {
    JsonParser(rs.string("data")).asJsObject
  }

  def buildDereferenceMap(implicit session: DBSession, aspectIds: Iterable[String]): Map[String, PropertyWithLink] = {
    if (aspectIds.isEmpty) {
      Map()
    } else {
      val aspects =
        sql"""select aspectId, jsonSchema
            from Aspects
            where aspectId in (${aspectIds})"""
          .map(rs => (rs.string("aspectId"), JsonParser(rs.string("jsonSchema")).asJsObject))
          .list.apply()

      aspects.map {
        case (aspectId, jsonSchema) =>
          // This aspect can only have links if it uses hyper-schema
          if (jsonSchema.fields.getOrElse("$schema", JsString("")).toString().contains("hyper-schema")) {
            // TODO: support multiple linked properties in an aspect.

            val properties = jsonSchema.fields.get("properties").flatMap {
              case JsObject(properties) => Some(properties)
              case _                    => None
            }.getOrElse(Map())

            val propertyWithLinks = properties.map {
              case (propertyName, property) =>
                val linksInProperties = property.extract[JsValue]('links.? / filter { value =>
                  val relPredicate = 'rel.is[String](_ == "item")
                  val hrefPredicate = 'href.is[String](_ == "/api/v0/registry/records/{$}")
                  relPredicate(value) && hrefPredicate(value)
                })

                val linksInItems = property.extract[JsValue]('items.? / 'links.? / filter { value =>
                  val relPredicate = 'rel.is[String](_ == "item")
                  val hrefPredicate = 'href.is[String](_ == "/api/v0/registry/records/{$}")
                  relPredicate(value) && hrefPredicate(value)
                })

                if (!linksInProperties.isEmpty) {
                  Some(PropertyWithLink(propertyName, false))
                } else if (!linksInItems.isEmpty) {
                  Some(PropertyWithLink(propertyName, true))
                } else {
                  None
                }
            }.filter(!_.isEmpty).map(_.get)

            propertyWithLinks.map(property => (aspectId, property)).headOption
          } else {
            None
          }
      }.filter(!_.isEmpty).map(_.get).toMap
    }
  }

  private def aspectIdsToSelectClauses(aspectIds: Iterable[String], dereferenceDetails: Map[String, PropertyWithLink] = Map()) = {
    aspectIds.zipWithIndex.map {
      case (aspectId, index) =>
        // Use a simple numbered column name rather than trying to make the aspect name safe.
        val aspectColumnName = SQLSyntax.createUnsafely(s"aspect$index")
        val selection = dereferenceDetails.get(aspectId).map {
          case PropertyWithLink(propertyName, true) =>
            sqls"""(
            |                CASE WHEN
            |                        EXISTS (
            |                            SELECT FROM jsonb_array_elements_text(RecordAspects.data->$propertyName)
            |                        )
            |                    THEN(
            |                        select jsonb_set(
            |                                    RecordAspects.data,
            |                                    ${"{\"" + propertyName + "\"}"}::text[],
            |                                    jsonb_agg(
            |                                        jsonb_build_object(
            |                                            'id',
            |                                            Records.recordId,
            |                                            'name',
            |                                            Records.name,
            |                                            'aspects',
            |                                            (
            |                                                select jsonb_object_agg(aspectId, data)
            |                                                from RecordAspects
            |                                                where tenantId=Records.tenantId and recordId=Records.recordId
            |                                            )
            |                                        )
            |                                    )
            |                                )
            |                        from Records
            |                        inner join jsonb_array_elements_text(RecordAspects.data->$propertyName) as aggregatedId on aggregatedId=Records.recordId
            |                    )
            |                    ELSE(
            |                        select data
            |                        from RecordAspects
            |                        where (aspectId, recordid, tenantId) = ($aspectId, Records.recordId, Records.tenantId)
            |                    )
            |                END
            )""".stripMargin
          case PropertyWithLink(propertyName, false) =>
            sqls"""(select jsonb_set(RecordAspects.data, ${"{\"" + propertyName + "\"}"}::text[], jsonb_build_object('id', Records.recordId, 'name', Records.name, 'aspects',
                  (select jsonb_object_agg(aspectId, data) from RecordAspects where tenantId=Records.tenantId and recordId=Records.recordId)))
                   from Records where Records.tenantId=RecordAspects.tenantId and Records.recordId=RecordAspects.data->>$propertyName)"""
        }.getOrElse(sqls"data")
        sqls"""(select $selection from RecordAspects where (aspectId, recordid, tenantId)=($aspectId, Records.recordId, Records.tenantId)) as $aspectColumnName"""
    }
  }

  private def filterRecordsByTenantClause(tenantId: BigInt) = {
    if (tenantId == MAGDA_SYSTEM_ID) sqls"true" else sqls"Records.tenantId=$tenantId"
  }

  private def aspectIdsToWhereClause(tenantId: BigInt, aspectIds: Iterable[String]): Seq[Option[SQLSyntax]] = {
    aspectIds.map(aspectId => aspectIdToWhereClause(tenantId, aspectId)).toSeq
  }

  private def aspectIdToWhereClause(tenantId: BigInt, aspectId: String) = {
    Some(sqls"exists (select 1 from RecordAspects where (aspectId, recordid, tenantId)=($aspectId, Records.recordId, Records.tenantId))")
  }

  private def aspectQueryToWhereClause(tenantId: BigInt, query: AspectQuery) = {
    sqls"EXISTS (SELECT 1 FROM recordaspects WHERE (aspectId, recordid, tenantId)=(${query.aspectId}, Records.recordId, Records.tenantId) AND data #>> string_to_array(${query.path.mkString(",")}, ',') = ${query.value})"
  }
}

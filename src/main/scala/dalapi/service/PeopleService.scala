package dalapi.service

import dal.SlickPostgresDriver.simple._
import dal.Tables._
import dalapi.models._
import org.joda.time.LocalDateTime
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing._

import scala.util.{Failure, Success, Try}


// this trait defines our service behavior independently from the service actor
trait PeopleService extends HttpService with InboundService with EntityService {

  val routes = {
    pathPrefix("person") {
      createPerson ~
      createPersonRelationshipType ~
      linkPersonToPerson ~
      linkPersonToLocation ~
      linkPersonToOrganisation ~
      linkPersonToPropertyStatic ~
      linkPersonToPropertyDynamic ~
      addPersonType
    }
  }

  import JsonProtocol._

  def createPerson = path("") {
    post {
      entity(as[ApiPerson]) { person =>
        db.withSession { implicit session =>
          val personspersonRow = new PeoplePersonRow(0, LocalDateTime.now(), LocalDateTime.now(), person.name, person.personId)
          val result = Try((PeoplePerson returning PeoplePerson.map(_.id)) += personspersonRow)

          complete {
            result match {
              case Success(personId) =>
                person.copy(id = Some(personId))
              case Failure(e) =>
                (BadRequest, e.getMessage)
            }
          }
        }
      }
    }
  }

  def createPersonRelationshipType = path("relationshipType") {
    post {
        entity(as[ApiPersonRelationshipType]) { relationship =>
          db.withSession { implicit session =>
            val reltypeRow = new PeoplePersontopersonrelationshiptypeRow(0, LocalDateTime.now(), LocalDateTime.now(), relationship.name, relationship.description)
            val reltypeId = (PeoplePersontopersonrelationshiptype returning PeoplePersontopersonrelationshiptype.map(_.id)) += reltypeRow
            complete(Created, {
              relationship.copy(id = Some(reltypeId))
            })
          }

        }
    }
  }

  /*
   * Link two people together, e.g. as one person part of another person with a given relationship type
   */
  def linkPersonToPerson = path(IntNumber / "person" / IntNumber) { (personId: Int, person2Id: Int) =>
    post {
      entity(as[ApiPersonRelationshipType]) { relationship =>
        db.withSession { implicit session =>
          val recordId = createRelationshipRecord(s"person/$personId/person/$person2Id:${relationship.name}")

          val result = relationship.id match {
            case Some(relationshipTypeId) =>
              val crossref = new PeoplePersontopersoncrossrefRow(0, LocalDateTime.now(), LocalDateTime.now(),
                personId, person2Id, recordId, true, relationshipTypeId)
              Try((PeoplePersontopersoncrossref returning PeoplePersontopersoncrossref.map(_.id)) += crossref)
            case None =>
              Failure(new IllegalArgumentException("People can only be linked with an existing relationship type"))
          }


          // Return the created crossref
          complete {
            result match {
              case Success(crossrefId) =>
                (Created, ApiGenericId(crossrefId))
              case Failure(e) =>
                (BadRequest, e.getMessage)
            }
          }

        }
      }
    }
  }

  def linkPersonToLocation = path(IntNumber / "location" / IntNumber) { (personId: Int, locationId: Int) =>
    post {
      entity(as[ApiRelationship]) { relationship =>
        db.withSession { implicit session =>
          val recordId = createRelationshipRecord(s"person/$personId/location/$locationId:${relationship.relationshipType}")

          val crossref = new PeoplePersonlocationcrossrefRow(1, LocalDateTime.now(), LocalDateTime.now(),
            locationId, personId, relationship.relationshipType, true, recordId)

          val result = Try((PeoplePersonlocationcrossref returning PeoplePersonlocationcrossref.map(_.id)) += crossref)

          complete {
            result match {
              case Success(crossrefId) =>
                (Created, ApiGenericId(crossrefId))
              case Failure(e) =>
                (BadRequest, e.getMessage)
            }
          }

        }
      }
    }
  }

  def linkPersonToOrganisation = path(IntNumber / "organisation" / IntNumber) { (personId: Int, organisationId: Int) =>
    post {
      entity(as[ApiRelationship]) { relationship =>
        db.withSession { implicit session =>
          val recordId = createRelationshipRecord(s"person/$personId/organisation/$organisationId:${relationship.relationshipType}")

          val crossref = new PeoplePersonorganisationcrossrefRow(0, LocalDateTime.now(), LocalDateTime.now(),
            organisationId, personId, relationship.relationshipType, true, recordId)
          val result = Try((PeoplePersonorganisationcrossref returning PeoplePersonorganisationcrossref.map(_.id)) += crossref)

          complete {
            result match {
              case Success(crossrefId) =>
                (Created, ApiGenericId(crossrefId))
              case Failure(e) =>
                (BadRequest, e.getMessage)
            }
          }

        }
      }
    }
  }

  /*
   * Link person to a property statically (tying it in with a specific record ID)
   */
  def linkPersonToPropertyStatic = path(IntNumber / "property" / "static" / IntNumber) { (personId: Int, propertyId: Int) =>
    post {
      entity(as[ApiPropertyRelationshipStatic]) { relationship =>
        val result: Try[Int] = (relationship.field.id, relationship.record.id) match {
          case (Some(fieldId), Some(recordId)) =>
            val propertyRecordId = createPropertyRecord(
              s"person/$personId/property/$propertyId:${relationship.relationshipType}(${fieldId},${recordId},${relationship.relationshipType}")

            // Create the crossreference record and insert into db
            val crossref = new PeopleSystempropertystaticcrossrefRow(
              0, LocalDateTime.now(), LocalDateTime.now(),
              personId, propertyId,
              recordId, fieldId, relationship.relationshipType,
              true, propertyRecordId
            )

            db.withSession { implicit session =>
              Try((PeopleSystempropertystaticcrossref returning PeopleSystempropertystaticcrossref.map(_.id)) += crossref)
            }
          case (None, _) =>
            Failure(new IllegalArgumentException("Property relationship must have an existing Data Field with ID"))
          case (_, None) =>
            Failure(new IllegalArgumentException("Static Property relationship must have an existing Data Record with ID"))
        }

        complete {
          result match {
            case Success(crossrefId) =>
              (Created, ApiGenericId(crossrefId))
            case Failure(e) =>
              (BadRequest, e.getMessage)
          }
        }

      }
    }
  }

  /*
   * Link person to a property dynamically
   */
  def linkPersonToPropertyDynamic = path(IntNumber / "property" / "dynamic" / IntNumber) { (personId: Int, propertyId: Int) =>
    post {
      entity(as[ApiPropertyRelationshipDynamic]) { relationship =>
        val result: Try[Int] = relationship.field.id match {
          case Some(fieldId) =>
            val propertyRecordId = createPropertyRecord(
              s"""person/$personId/property/$propertyId:${relationship.relationshipType}
                  |(${fieldId},${relationship.relationshipType})""".stripMargin)

            // Create the crossreference record and insert into db
            val crossref = new PeopleSystempropertydynamiccrossrefRow(
              0, LocalDateTime.now(), LocalDateTime.now(),
              personId, propertyId,
              fieldId, relationship.relationshipType,
              true, propertyRecordId)

            db.withSession { implicit session =>
              Try((PeopleSystempropertydynamiccrossref returning PeopleSystempropertydynamiccrossref.map(_.id)) += crossref)
            }
          case None =>
            Failure(new IllegalArgumentException("Property relationship must have an existing Data Field with ID"))
        }

        complete {
          result match {
            case Success(crossrefId) =>
              (Created, ApiGenericId(crossrefId))
            case Failure(e) =>
              (BadRequest, e.getMessage)
          }
        }
      }
    }
  }

  /*
   * Tag person with a type
   */
  def addPersonType = path(IntNumber / "type" / IntNumber) { (personId: Int, typeId: Int) =>
    post {
      entity(as[ApiRelationship]) { relationship =>
        db.withSession { implicit session =>
          val personType = new PeopleSystemtypecrossrefRow(0, LocalDateTime.now(), LocalDateTime.now(),
            personId, typeId, relationship.relationshipType, true)
          val result = Try((PeopleSystemtypecrossref returning PeopleSystemtypecrossref.map(_.id)) += personType)

          complete {
            result match {
              case Success(crossrefId) =>
                (Created, ApiGenericId(crossrefId))
              case Failure(e) =>
                (BadRequest, e.getMessage)
            }
          }
        }
      }

    }
  }
}

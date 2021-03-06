/*
 * Copyright (C) 2016 Andrius Aucinas <andrius.aucinas@hatdex.org>
 * SPDX-License-Identifier: AGPL-3.0
 *
 * This file is part of the Hub of All Things project (HAT).
 *
 * HAT is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of
 * the License.
 *
 * HAT is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General
 * Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package hatdex.hat.api

import akka.event.LoggingAdapter
import hatdex.hat.api.actors.{EmailService, SmtpConfig}
import hatdex.hat.api.endpoints._
import hatdex.hat.api.endpoints.jsonExamples.DataExamples
import hatdex.hat.api.json.JsonProtocol
import hatdex.hat.api.models.ErrorMessage
import hatdex.hat.api.service._
import hatdex.hat.authentication.HatAuthTestHandler
import hatdex.hat.authentication.authenticators.{AccessTokenHandler, UserPassHandler}
import org.specs2.mutable.Specification
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpMethods._
import spray.http.StatusCodes._
import spray.http._
import spray.testkit.Specs2RouteTest
import spray.httpx.SprayJsonSupport._


class ApiSpec extends Specification with Specs2RouteTest with Api {
  def actorRefFactory = system // Connect the service API to the test ActorSystem

  val logger: LoggingAdapter = system.log

  import JsonProtocol._

  trait LoggingHttpService {
    def actorRefFactory = system

    val logger: LoggingAdapter = system.log
  }

  val smtpConfig = SmtpConfig(conf.getBoolean("mail.smtp.tls"),
    conf.getBoolean("mail.smtp.ssl"),
    conf.getInt("mail.smtp.port"),
    conf.getString("mail.smtp.host"),
    conf.getString("mail.smtp.username"),
    conf.getString("mail.smtp.password"))
  val apiEmailService = new EmailService(system, smtpConfig)

  val helloService = new Hello with LoggingHttpService {
    val emailService = apiEmailService
  }
  val apiDataService = new Data with LoggingHttpService {
    override def accessTokenHandler = AccessTokenHandler.AccessTokenAuthenticator(authenticator = HatAuthTestHandler.AccessTokenHandler.authenticator).apply()

    override def userPassHandler = UserPassHandler.UserPassAuthenticator(authenticator = HatAuthTestHandler.UserPassHandler.authenticator).apply()
  }
  val apiBundleService = new Bundles with LoggingHttpService

  val apiPropertyService = new Property with LoggingHttpService
  val eventsService = new Event with LoggingHttpService
  val locationsService = new Location with LoggingHttpService
  val peopleService = new Person with LoggingHttpService
  val thingsService = new Thing with LoggingHttpService
  val organisationsService = new Organisation with LoggingHttpService

  val apiBundlesContextService = new BundlesContext with LoggingHttpService {
    def eventsService: EventsService = ApiSpec.this.eventsService
    def peopleService: PeopleService = ApiSpec.this.peopleService
    def thingsService: ThingsService = ApiSpec.this.thingsService
    def locationsService: LocationsService = ApiSpec.this.locationsService
    def organisationsService: OrganisationsService = ApiSpec.this.organisationsService
  }

  val dataDebitService = new DataDebit with LoggingHttpService {
    val bundlesService: BundleService = apiBundleService
    val bundleContextService: BundleContextService = apiBundlesContextService
  }

  val userService = new Users with LoggingHttpService
  val typeService = new Type with LoggingHttpService

  val testRoute = handleRejections(jsonRejectionHandler) {
    apiDataService.routes
  }

  val ownerAuthToken = HatAuthTestHandler.validUsers.find(_.role == "owner").map(_.userId).flatMap { ownerId =>
    HatAuthTestHandler.validAccessTokens.find(_.userId == ownerId).map(_.accessToken)
  } getOrElse ("")
  val ownerAuthHeader = RawHeader("X-Auth-Token", ownerAuthToken)

  sequential

  "Api Service" should {
    "respond with correctly formatted error message for non-existent routes" in {
      Get("/data/randomRoute") ~> sealRoute(testRoute) ~> check {
        response.status should be equalTo NotFound
        responseAs[ErrorMessage].message must contain("could not be found")
      }
    }

    "respond with correctly formatted error message for unauthorised routes" in {
      Get("/data/sources") ~> sealRoute(testRoute) ~> check {
        response.status should be equalTo Unauthorized
        responseAs[ErrorMessage].message must contain("requires authentication")
      }
    }

    "respond with correctly formatted error message for bad request" in {
      HttpRequest(POST, "/data/table")
        .withHeaders(ownerAuthHeader)
        .withEntity(HttpEntity(MediaTypes.`application/json`, DataExamples.malformedTable)
      ) ~>
        sealRoute(testRoute) ~>
        check {
          response.status should be equalTo BadRequest
          responseAs[ErrorMessage].message must contain("request content was malformed")
        }

    }

  }
}
package org.thp.cortex.services

import javax.inject.{ Inject, Provider, Singleton }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

import play.api.cache.AsyncCacheApi
import play.api.mvc.RequestHeader

import akka.NotUsed
import akka.stream.scaladsl.Source
import org.thp.cortex.models.{ Roles, User, UserModel, UserStatus }

import org.elastic4play.controllers.Fields
import org.elastic4play.database.DBIndex
import org.elastic4play.services._
import org.elastic4play.utils.Instance
import org.elastic4play.{ AuthenticationError, AuthorizationError }

@Singleton
class UserSrv @Inject() (
    userModel: UserModel,
    createSrv: CreateSrv,
    getSrv: GetSrv,
    updateSrv: UpdateSrv,
    deleteSrv: DeleteSrv,
    findSrv: FindSrv,
    eventSrv: EventSrv,
    authSrv: Provider[AuthSrv],
    dbIndex: DBIndex,
    cache: AsyncCacheApi,
    implicit val ec: ExecutionContext) extends org.elastic4play.services.UserSrv {

  private case class AuthContextImpl(userId: String, userName: String, requestId: String, roles: Seq[Role]) extends AuthContext

  override def getFromId(request: RequestHeader, userId: String): Future[AuthContext] = {
    getSrv[UserModel, User](userModel, userId)
      .flatMap { user ⇒ getFromUser(request, user) }
  }

  override def getFromUser(request: RequestHeader, user: org.elastic4play.services.User): Future[AuthContext] = {
    user match {
      case u: User if u.status() == UserStatus.Ok ⇒ Future.successful(AuthContextImpl(user.id, user.getUserName, Instance.getRequestId(request), user.getRoles))
      case _                                      ⇒ Future.failed(AuthorizationError("Your account is locked"))
    }

  }

  override def getInitialUser(request: RequestHeader): Future[AuthContext] =
    dbIndex.getSize(userModel.modelName).map {
      case size if size > 0 ⇒ throw AuthenticationError(s"Use of initial user is forbidden because users exist in database")
      case _                ⇒ AuthContextImpl("init", "", Instance.getRequestId(request), Seq(Roles.admin, Roles.read))
    }

  override def inInitAuthContext[A](block: AuthContext ⇒ Future[A]): Future[A] = {
    val authContext = AuthContextImpl("init", "", Instance.getInternalId, Seq(Roles.admin, Roles.read))
    eventSrv.publish(StreamActor.Initialize(authContext.requestId))
    block(authContext).andThen {
      case _ ⇒ eventSrv.publish(StreamActor.Commit(authContext.requestId))
    }
  }

  def create(fields: Fields)(implicit authContext: AuthContext): Future[User] = {
    fields.getString("password") match {
      case None ⇒ createSrv[UserModel, User](userModel, fields)
      case Some(password) ⇒ createSrv[UserModel, User](userModel, fields.unset("password")).flatMap { user ⇒
        authSrv.get.setPassword(user.userId(), password).map(_ ⇒ user)
      }
    }
  }

  override def get(id: String): Future[User] = getSrv[UserModel, User](userModel, id)

  def getOrganizationId(userId: String): Future[String] = cache.getOrElseUpdate(s"user-org-$userId", 5.minutes) {
    get(userId).map(_.organization())
  }

  def update(id: String, fields: Fields)(implicit Context: AuthContext): Future[User] = {
    updateSrv[UserModel, User](userModel, id, fields)
  }

  def update(user: User, fields: Fields)(implicit Context: AuthContext): Future[User] = {
    updateSrv(user, fields)
  }

  def delete(id: String)(implicit Context: AuthContext): Future[User] =
    deleteSrv[UserModel, User](userModel, id)

  def find(queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[User, NotUsed], Future[Long]) = {
    findSrv[UserModel, User](userModel, queryDef, range, sortBy)
  }

  def findForOrganization(organizationId: String, queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[User, NotUsed], Future[Long]) = {
    import org.elastic4play.services.QueryDSL._
    find(and("organization" ~= organizationId, queryDef), range, sortBy)
  }

  def findForUser(userId: String, queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[User, NotUsed], Future[Long]) = {
    val users = for {
      user ← get(userId)
      organizationId = user.organization()
    } yield findForOrganization(organizationId, queryDef, range, sortBy)
    val userSource = Source.fromFutureSource(users.map(_._1)).mapMaterializedValue(_ ⇒ NotUsed)
    val userTotal = users.flatMap(_._2)
    userSource -> userTotal
  }
}

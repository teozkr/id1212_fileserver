package se.nullable.kth.id1212.fileserver.server.controller

import java.net.SocketAddress
import java.rmi.RemoteException
import java.rmi.server.UnicastRemoteObject
import javax.inject.Named

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import resource._

import javax.inject.Inject
import se.nullable.kth.id1212.fileserver.common.controller.{
  FileEventListener,
  FileServer,
  FileServerManager,
  TicketID
}
import se.nullable.kth.id1212.fileserver.server.model.FileManager
import se.nullable.kth.id1212.fileserver.server.model.{
  FSProfile,
  User,
  UserManager
}
import se.nullable.kth.id1212.fileserver.server.model.FSProfile.api._
import slick.basic.DatabaseConfig

object Utils {
  def logErrors[A](f: => A): A =
    try {
      f
    } catch {
      case ex: Exception =>
        ex.printStackTrace()
        throw new RemoteException("Internal server error")
    }
}

class ManagerController @Inject()(
    userManager: UserManager,
    db: Database,
    fileManager: FileManager,
    @Named("transferServer")
    override val transferServerAddr: SocketAddress)
    extends UnicastRemoteObject
    with FileServerManager {
  override def login(username: String, password: String): Option[FileServer] =
    Utils.logErrors {
      val user = for {
        user <- userManager.find(username)
        success = user
          .map(userManager.verifyPassword(_, password))
          .getOrElse(false)
      } yield user.filter(_ => success)
      val ctrl = user.map(_.map(new LoggedInController(_, fileManager, db)))
      Await.result(db.run(ctrl), 2.seconds)
    }

  override def register(username: String,
                        password: String): Either[String, Unit] =
    Utils.logErrors {
      val create = userManager
        .create(username, password)
        .asTry
        .map(_.toEither.left.map(_ => "That username is taken"))
      Await.result(db.run(create), 2.seconds)
    }
}

class LoggedInController(user: User, fileManager: FileManager, db: Database)
    extends UnicastRemoteObject
    with FileServer {
  override def uploadFile(name: String): Either[String, TicketID] =
    Utils.logErrors {
      val ticket = (for {
        file <- fileManager.findOrCreate(user, name)
        ticket <- fileManager.createTicket(user, file, upload = true).map(_.get)
      } yield ticket).transactionally.asTry.map(_.toEither.left.map(_ =>
        "You do not have access to that file"))
      Await.result(db.run(ticket), 2.seconds)
    }

  override def addEventListener(listener: FileEventListener): Unit = ???
  override def removeEventListener(listener: FileEventListener): Unit = ???
}
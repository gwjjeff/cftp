package com.shsz.young.cftp

import akka.actor.{ Actor, ActorRef, FSM }
import akka.event.EventHandler
import akka.util.duration._
import Actor._
import akka.routing.{ Routing, CyclicIterator }
import Routing._

sealed trait FtpClientState
case object UnAvailable extends FtpClientState
case object Disconnected extends FtpClientState
case object Connected extends FtpClientState
case object LoggedIn extends FtpClientState

sealed trait FtpMessage
case object Open extends FtpMessage
case object Login extends FtpMessage
case class UploadFile(local: String, remote: String) extends FtpMessage
case class UploadFileSucc(local: String, remote: String) extends FtpMessage
case class UploadFileFail(local: String, remote: String) extends FtpMessage
case class RetryAll() extends FtpMessage
case class Dump() extends FtpMessage

object CFtpFSM {
  // implicit lazy val statusLogger = actorOf[FileStatusLogger].start()
  private lazy val props = loadProps("secret.properties")
  lazy val DEFAULT = singleFromConf(props)
  lazy val DEFAULTROUTER = fsmRouter(props)

  private def loadProps(propsFile: String) = {
    // TODO: 处理获取资源是出现空指针的情况
    val is = classOf[CFtpFSM].getClassLoader.getResourceAsStream(propsFile)
    val p = new java.util.Properties
    p.load(is)
    p
  }

  private def fromProps(p: java.util.Properties) = {
    val host = p.getProperty("test.cftp.host")
    val port = p.getProperty("test.cftp.port").toInt
    val serverEncoding = p.getProperty("test.cftp.serverEncoding")
    val user = p.getProperty("test.cftp.user")
    val pass = p.getProperty("test.cftp.pass")
    val ddir = p.getProperty("test.cftp.ddir")
    actorOf(new CFtpFSM(host, port, serverEncoding, user, pass, ddir))
  }

  def singleFromConf(p: java.util.Properties) = {
    fromProps(p).start()
  }

  def multiFromConf(p: java.util.Properties) = {
    val fsmCnt = p.getProperty("test.cftpfsm.count").toInt
    Vector.fill(fsmCnt)(fromProps(p).start())
  }

  def fsmRouter(p: java.util.Properties) = {
    val multi = multiFromConf(p)
    val autoStart = p.getProperty("test.cftpfsm.autoStart").toBoolean
    val router = Routing.loadBalancerActor(CyclicIterator(multi)).start()
    if (autoStart) router ! Broadcast(Open)
    router
  }

}

class CFtpFSM(
  host: String,
  port: Int,
  serverEncoding: String,
  user: String,
  pass: String,
  ddir: String) extends Actor with FSM[FtpClientState, Unit] {
  import FSM._

  // defined else where
  val uploadManager = remote.actorFor("cftp:service", "localhost", 2552)

  private var cftpOpt: Option[CFtp] = None
  private var cftp: CFtp = _
  private val ACTIVE_TIMEOUT = 5 seconds
  private val DISCON_TIMEOUT = 10 seconds

  startWith(Disconnected, Unit)

  when(Disconnected) {
    case Ev(Open) =>
      cancelTimer("autoConn")
      cftpOpt = cftpOpt orElse Some(new CFtp(host, port, serverEncoding))
      cftp = cftpOpt.get
      cftp.open()
      if (cftp.isConnected()) {
        setTimer("autoLogin", Login, ACTIVE_TIMEOUT, false)
        goto(Connected)
      } else {
        // EventHandler.error(this, "无法连接 %s:%s".format(host, port))
        goto(UnAvailable)
      }
  }

  when(Connected) {
    case Ev(Login) =>
      cancelTimer("autoLogin")
      cftp.login(user, pass)
      if (cftp.isLoggedIn()) {
        cftp.cd(ddir) //TODO：处理异常
        goto(LoggedIn) forMax (ACTIVE_TIMEOUT)
      } else {
        // EventHandler.error(this, "登录失败 user:%s".format(user))
        goto(UnAvailable)
      }
  }

  when(LoggedIn) {
    case Ev(StateTimeout) =>
      if (cftp.activeTest())
        stay forMax (ACTIVE_TIMEOUT)
      else {
        //活动测试不成功时定时重连
        // EventHandler.error(this, "活动测试不成功")
        cftp.quit()
        goto(UnAvailable)
      }
    case Ev(UploadFile(local, remote)) =>
      if (cftp.upload(local, remote)) {
        uploadManager ! UploadFileSucc(local, remote)
        // EventHandler.info(this, "上传成功 local: %s, remote: %S".format(local, remote))
      } else {
        uploadManager ! UploadFileFail(local, remote)
        // EventHandler.info(this, "上传失败 local: %s, remote: %S".format(local, remote))
      }
      stay forMax (ACTIVE_TIMEOUT)
  }

  when(UnAvailable, ACTIVE_TIMEOUT) {
    case Ev(StateTimeout) =>
      setTimer("autoConn", Open, DISCON_TIMEOUT, false)
      goto(Disconnected)
  }

  onTransition {
    case a -> UnAvailable => EventHandler.info(this, "%s -> UnAvailable".format(a))
  }

  initialize
}

sealed trait FileMessage
/**
 * status: begin, success, failed
 */
case class FileUploadMsg(file: String, status: String) extends FileMessage

class FileStatusLogger extends Actor {
  def receive = {
    case FileUploadMsg(status, file) => status match {
      case CFtp.FU_BEGIN =>
        EventHandler.info(this, "%s upload %s".format(file, status))
      case CFtp.FU_SUCCESS =>
        EventHandler.info(this, "%s upload %s".format(file, status))
      case CFtp.FU_FAILED =>
        EventHandler.info(this, "%s upload %s".format(file, status))
    }
  }
}

import java.util.Date
import java.io.File
import scala.collection.mutable.HashMap
case class FileStatus(remote: String,
  start: Date, var lastSend: Date, var lastFail: Date, var retryReq: Int)

// TODO: 控制单线程dispatcher
class FileUploadManager(mvAfterSucc: Boolean = true, bakPath: String = "e:/temp1/b",
  router: ActorRef = CFtpFSM.DEFAULTROUTER) extends Actor {

  val bakDir = new File(bakPath)
  // TODO: 不满足requir时退出
  require(bakDir.isDirectory(), "目录 %s 不存在".format(bakPath))

  private var st = new HashMap[String, FileStatus]

  def receive = {
    case m @ UploadFile(local, remote) =>
      val now = new Date()
      st += (local -> FileStatus(remote, now, now, null, 0))
      router ! m
    case m @ UploadFileSucc(local, remote) =>
      st -= local
      // TODO: log retry times
      if (mvAfterSucc) {
        val oldFile = new File(local)
        val oldName = oldFile.getName()
        val newFile = new File("%s%s%s".format(
          bakDir.getCanonicalPath, File.separator, oldName))
        if (!oldFile.renameTo(newFile)) {
          // TODO: warn
        }
      }
    case m @ UploadFileFail(local, remote) =>
      val now = new Date()
      st(local).retryReq += 1
      st(local).lastFail = now
    case x: RetryAll =>
      st.foreach {
        case (local, status @ FileStatus(remote, start, lastSend,
          lastFail, retryReq)) if ((retryReq > 0) && (lastFail.getTime() > lastSend.getTime())) =>
          val now = new Date()
          router ! UploadFile(local, remote)
          status.lastSend = now
      }
    case x: Dump =>
      st.foreach {
        case (local, status) =>
          // TODO: 编写逻辑，Dump到文件
          println("%s: %s".format(local, status))
      }
  }

  //  override def postRestart(reason: Throwable) {
  //    st = new HashMap[String, FileStatus]
  //  }

  override def preStart() {
    remote.start("localhost", 2552)
    remote.register("cftp:service", self)
    akka.actor.Scheduler.schedule(self, RetryAll(), 30, 10, java.util.concurrent.TimeUnit.SECONDS)
  }
}

object FileUploadManager {
  lazy val DEFAULT = actorOf(new FileUploadManager()).start()

  def sendFile(local: String, remote: String) {
    DEFAULT ! UploadFile(local, remote)
  }

  def dumpStatus() {
    DEFAULT ! Dump()
  }
}

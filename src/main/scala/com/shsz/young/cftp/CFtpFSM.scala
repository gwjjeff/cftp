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
  import utils._
  def runDefault() {
    val manager = DEFAULTMANAGER
    val router = DEFAULTROUTER
  }
  // implicit lazy val statusLogger = actorOf[FileStatusLogger].start()
  private lazy val props = loadProps("secret.properties")
  lazy val DEFAULTFSM = singleFromConf(props)
  lazy val DEFAULTROUTER = fsmRouter(props)
  lazy val DEFAULTMANAGER = managerFromConf(props)

  private def fromProps(p: java.util.Properties) = {
    val host = p.getProperty("test.cftp.host")
    val port = p.getProperty("test.cftp.port").toInt
    val serverEncoding = p.getProperty("test.cftp.serverEncoding")
    val user = p.getProperty("test.cftp.user")
    val pass = p.getProperty("test.cftp.pass")
    val ddir = p.getProperty("test.cftp.ddir")
    val activeTimeout = p.getProperty("test.cftpfsm.activeTimeout").toInt
    val disconTimeout = p.getProperty("test.cftpfsm.disconTimeout").toInt
    val mgrHost = p.getProperty("test.cftpmgr.host")
    val mgrPort = p.getProperty("test.cftpmgr.port").toInt
    val mgrServiceId = p.getProperty("test.cftpmgr.serviceId")
    actorOf(new CFtpFSM(host, port, serverEncoding, user, pass, ddir,
      mgrServiceId, mgrHost, mgrPort,
      activeTimeout, disconTimeout))
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
    val interRouter = Routing.loadBalancerActor(CyclicIterator(multi)).start()
    val routerId = p.getProperty("test.cftpfsm.routerId")
    val router = actorOf(new ActorForwarder(routerId, interRouter)).start()

    if (autoStart) router ! Broadcast(Open)
    router
  }

  def managerFromConf(p: java.util.Properties) = {
    val mvAfterSucc = p.getProperty("test.cftpmgr.mvAfterSucc").toBoolean
    val bakPath = p.getProperty("test.cftpmgr.bakPath")
    val routerId = p.getProperty("test.cftpfsm.routerId")
    val initDelay = p.getProperty("test.cftpmgr.initDelay").toLong
    val delay = p.getProperty("test.cftpmgr.delay").toLong
    val host = p.getProperty("test.cftpmgr.host")
    val port = p.getProperty("test.cftpmgr.port").toInt
    val serviceId = p.getProperty("test.cftpmgr.serviceId")
    val mgr = actorOf(new FileUploadManager(mvAfterSucc, bakPath, routerId,
      host, port, serviceId, initDelay, delay)).start()
    mgr
  }

}

/*
 * akka 的 actor 的 id 似乎只能在创建的时候修改id，之后才能用 actorsFor 找到
 * 但是 router actor 似乎没有提供这样的方法，使用类名的话害怕会有冲突
 * 因此单独写一个actor用户包装router，可以启动的时候设置id，并且将所有消息forward给router
 */
class ActorForwarder(id: String, inter: ActorRef) extends Actor {
  self.id = id
  def receive = {
    case m => inter forward m
  }
}

class CFtpFSM(
  host: String,
  port: Int,
  serverEncoding: String,
  user: String,
  pass: String,
  ddir: String,
  mgrServiceId: String, mgrHost: String, mgrPort: Int,
  activeTimeout: Int, disconTimeout: Int) extends Actor with FSM[FtpClientState, Unit] {
  import FSM._

  // defined else where
  val uploadManager = remote.actorFor(mgrServiceId, mgrHost, mgrPort)

  private var cftpOpt: Option[CFtp] = None
  private var cftp: CFtp = _
  private val ACTIVE_TIMEOUT = activeTimeout seconds
  private val DISCON_TIMEOUT = disconTimeout seconds

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
class FileUploadManager(mvAfterSucc: Boolean, bakPath: String, routerId: String,
  host: String, port: Int, serviceId: String,
  initDelay: Long, delay: Long) extends Actor {

  lazy val router: ActorRef = registry.actorsFor(routerId).head

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
    remote.start(host, port)
    remote.register(serviceId, self)
    akka.actor.Scheduler.schedule(self, RetryAll(), initDelay, delay, java.util.concurrent.TimeUnit.SECONDS)
  }
}


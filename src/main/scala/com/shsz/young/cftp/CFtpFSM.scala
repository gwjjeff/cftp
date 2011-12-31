package com.shsz.young.cftp

import akka.actor.{ Actor, ActorRef, FSM }
import akka.event.EventHandler
import akka.util.duration._
import Actor._
import akka.routing.{ Routing, CyclicIterator }
import Routing._
import java.util.Date
import com.shsz.young.cftp.utils._

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
case object Ping extends FtpMessage
case class Pong(cftpId: Int, t: java.util.Date) extends FtpMessage
case object RetryAll extends FtpMessage
case object Dump extends FtpMessage
case object DumpToFile extends FtpMessage
case object Pure extends FtpMessage
case object Clear extends FtpMessage

object CFtpFSM {
  def runDefault() {
    val manager = DEFAULTMANAGER
    val router = DEFAULTROUTER
  }
  def shutdown() {
    registry.shutdownAll()
    remote.shutdown()
  }
  // implicit lazy val statusLogger = actorOf[FileStatusLogger].start()
  private lazy val props = loadProps("secret.properties")
  lazy val DEFAULTFSM = singleFromConf(props)
  lazy val DEFAULTROUTER = fsmRouter(props)
  lazy val DEFAULTMANAGER = managerFromConf(props)

  private def fromProps(p: java.util.Properties) = {
    val host = p.getProperty("cftp.host")
    val port = p.getProperty("cftp.port").toInt
    val serverEncoding = p.getProperty("cftp.serverEncoding")
    val user = p.getProperty("cftp.user")
    val pass = p.getProperty("cftp.pass")
    val ddir = p.getProperty("cftp.ddir")
    val activeTimeout = p.getProperty("cftpfsm.activeTimeout").toInt
    val disconTimeout = p.getProperty("cftpfsm.disconTimeout").toInt
    val mgrHost = p.getProperty("cftpmgr.host")
    val mgrPort = p.getProperty("cftpmgr.port").toInt
    val mgrServiceId = p.getProperty("cftpmgr.serviceId")
    actorOf(new CFtpFSM(host, port, serverEncoding, user, pass, ddir,
      mgrServiceId, mgrHost, mgrPort,
      activeTimeout, disconTimeout))
  }

  def singleFromConf(p: java.util.Properties) = {
    fromProps(p).start()
  }

  def multiFromConf(p: java.util.Properties) = {
    val fsmCnt = p.getProperty("cftpfsm.count").toInt
    Vector.fill(fsmCnt)(fromProps(p).start())
  }

  def fsmRouter(p: java.util.Properties) = {
    val multi = multiFromConf(p)
    val autoStart = p.getProperty("cftpfsm.autoStart").toBoolean
    val interRouter = Routing.loadBalancerActor(CyclicIterator(multi)).start()
    val routerId = p.getProperty("cftpfsm.routerId")
    val router = actorOf(new ActorForwarder(routerId, interRouter)).start()

    if (autoStart) router ! Broadcast(Open)
    router
  }

  def managerFromConf(p: java.util.Properties) = {
    val mvAfterSucc = p.getProperty("cftpmgr.mvAfterSucc").toBoolean
    val bakPath = p.getProperty("cftpmgr.bakPath")
    val routerId = p.getProperty("cftpfsm.routerId")
    val initDelay = p.getProperty("cftpmgr.initDelay").toLong
    val delay = p.getProperty("cftpmgr.delay").toLong
    val host = p.getProperty("cftpmgr.host")
    val port = p.getProperty("cftpmgr.port").toInt
    val serviceId = p.getProperty("cftpmgr.serviceId")
    val maxRetry = p.getProperty("cftpmgr.maxRetry").toInt
    val dumpDir = p.getProperty("cftpmgr.dumpDir")
    val mgr = actorOf(new FileUploadManager(mvAfterSucc, bakPath, routerId,
      host, port, serviceId, initDelay, delay, maxRetry, dumpDir)).start()
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
    // 处理在不合适的状态接收到的文件上传请求
    case Ev(UploadFile(local, remote)) =>
      uploadManager ! UploadFileFail(local, remote)
      stay
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
    case Ev(UploadFile(local, remote)) =>
      uploadManager ! UploadFileFail(local, remote)
      stay
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
    case Ev(Ping) =>
      uploadManager ! Pong(cftp.id, new java.util.Date())
      stay forMax (ACTIVE_TIMEOUT)
  }

  when(UnAvailable, ACTIVE_TIMEOUT) {
    case Ev(StateTimeout) =>
      setTimer("autoConn", Open, DISCON_TIMEOUT, false)
      goto(Disconnected)
    case Ev(UploadFile(local, remote)) =>
      uploadManager ! UploadFileFail(local, remote)
      stay
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
  start: Date, var sendFlag: Int, var failFlag: Int,
  var lastSend: Date, var lastFail: Option[Date], var retryReq: Int)

// TODO: 控制单线程dispatcher
class FileUploadManager(mvAfterSucc: Boolean, bakPath: String, routerId: String,
  host: String, port: Int, serviceId: String,
  initDelay: Long, delay: Long,
  maxRetry: Int,
  dumpDir: String) extends Actor {

  lazy val router: ActorRef = registry.actorsFor(routerId).head

  val bakDir = new File(bakPath)
  // TODO: 不满足require时退出
  require(bakDir.isDirectory(), "目录 %s 不存在".format(bakPath))

  private var st = new HashMap[String, FileStatus]

  def receive = {
    case m @ UploadFile(local, remote) =>
      val now = new Date()
      st += (local -> FileStatus(remote, now, 1, 0, now, None, 0))
      router ! m
    case m @ UploadFileSucc(local, remote) =>
      EventHandler.info(this, "FU_SUCC: %s".format(st(local)))
      st -= local
      if (mvAfterSucc) {
        val oldFile = new File(local)
        val oldName = oldFile.getName()
        var newFile = new File("%s%s%s".format(
          bakDir.getCanonicalPath, File.separator, oldName))
        while (newFile.exists())
          newFile = new File("%s%s%s.%s".format(
            bakDir.getCanonicalPath, File.separator, oldName, System.currentTimeMillis()))
        if (!oldFile.renameTo(newFile)) {
          // TODO: warn
        }
      }
    case m @ UploadFileFail(local, remote) =>
      val now = new Date()
      st(local).retryReq += 1
      st(local).lastFail = Some(now)
      st(local).failFlag = st(local).sendFlag + 1
    case RetryAll =>
      st.foreach {
        case (local, status @ FileStatus(remote, start, sendFlag, failFlag, lastSend,
          lastFail, retryReq)) if ((retryReq > 0) && (retryReq <= maxRetry) && lastFail.isDefined && (failFlag > sendFlag)) =>
          val now = new Date()
          router ! UploadFile(local, remote)
          status.lastSend = now
          status.sendFlag = status.failFlag + 1
        case _ =>
      }
    case Pure =>
      st filter {
        case (local, status @ FileStatus(remote, start, sendFlag, failFlag, lastSend,
          lastFail, retryReq)) =>
          // TODO: 把这个和上面重复的条件判断抽取出来
          if ((retryReq > 0) && (retryReq <= maxRetry) && lastFail.isDefined && (failFlag > sendFlag)) false
          else true
      }
    case Dump =>
      st.foreach {
        case (local, status) =>
          // TODO: 编写逻辑，Dump到文件
          println("%s: %s".format(local, status))
      }
    case DumpToFile =>
      // TODO: 当dumpDir不存在时报错
      val dumpFileName = "%s%sdump.%s".format(
        new java.io.File(dumpDir).getCanonicalPath(),
        java.io.File.separator,
        System.currentTimeMillis())
      val f = new java.io.File(dumpFileName)
      printToFile(f)(p => {
        st.foreach {
          case (local, status) =>
            p.println("%s: %s".format(local, status))
        }
      })
    case Clear =>
      st.clear()
    case Ping =>
      router ! Broadcast(Ping)
    case Pong(id, time) =>
      EventHandler.info(this, "cftp: %s is active at %s".format(id, time))
  }

  //  override def postRestart(reason: Throwable) {
  //    st = new HashMap[String, FileStatus]
  //  }

  override def preStart() {
    remote.start(host, port)
    remote.register(serviceId, self)
    akka.actor.Scheduler.schedule(self, RetryAll, initDelay, delay, java.util.concurrent.TimeUnit.SECONDS)
  }
}


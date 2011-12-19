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
case class UploadFile(file: String) extends FtpMessage

object CFtpFSM {
  implicit lazy val manager = actorOf[FileStatusManager].start()
  lazy val DEFAULT = singleFromConf("secret.properties")
  lazy val DEFAULTROUTER = fsmRouter(3, "secret.properties")

  private def loadProps(propsFile: String) = {
    // TODO: 处理获取资源是出现空指针的情况
    val is = classOf[CFtpFSM].getClassLoader.getResourceAsStream(propsFile)
    val p = new java.util.Properties
    p.load(is)
    p
  }
  
  private def fromProps(p: java.util.Properties)(implicit m: ActorRef) = {
    val host = p.getProperty("test.cftp.host")
    val port = Integer.parseInt(p.getProperty("test.cftp.port"))
    val serverEncoding = p.getProperty("test.cftp.serverEncoding")
    val user = p.getProperty("test.cftp.user")
    val pass = p.getProperty("test.cftp.pass")
    val ddir = p.getProperty("test.cftp.ddir")
    actorOf(new CFtpFSM(host, port, serverEncoding, user, pass, ddir) {
      protected val manager = m
    })
  }
  
  def singleFromConf(propsFile: String) = {
    val p = loadProps(propsFile)
    fromProps(p).start()
  }
  
  def multiFromConf(fsmCnt: Int, propsFile: String) = {
    val p = loadProps(propsFile)
    Vector.fill(fsmCnt)(fromProps(p).start())
  }
  
  def fsmRouter(fsmCnt: Int, propsFile: String, autoStart: Boolean = true) = {
    val multi = multiFromConf(fsmCnt, propsFile)
    val router = Routing.loadBalancerActor(CyclicIterator(multi)).start()
    if (autoStart) router ! Broadcast(Open)
    router
  }
}

abstract class CFtpFSM(
  host: String,
  port: Int,
  serverEncoding: String,
  user: String,
  pass: String,
  ddir: String) extends Actor with FSM[FtpClientState, Unit] {
  import FSM._

  // defined else where
  protected val manager: ActorRef
  
  private var cftp: CFtp = _
  private val ACTIVE_TIMEOUT = 5 seconds
  private val DISCON_TIMEOUT = 10 seconds

  startWith(Disconnected, Unit)

  when(Disconnected) {
    case Ev(Open) =>
      cancelTimer("autoConn")
      cftp = new CFtp(host, port, serverEncoding) {
        override def fileUploadEvent(status: String, local: String, remote: String) {
          manager ! FileUploadMsg(status, local)
        }
      }
      cftp.open()
      if (cftp.isConnected()) {
        setTimer("autoLogin", Login, ACTIVE_TIMEOUT, false)
        goto(Connected)
      } else {
        EventHandler.error(this, "无法连接 %s:%s".format(host, port))
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
        EventHandler.error(this, "登录失败 user:%s".format(user))
        goto(UnAvailable)
      }
  }

  when(LoggedIn) {
    case Ev(StateTimeout) =>
      if (cftp.activeTest())
        stay forMax (ACTIVE_TIMEOUT)
      else {
        //活动测试不成功时定时重连
        EventHandler.error(this, "活动测试不成功")
        cftp.quit()
        goto(UnAvailable)
      }
    case Ev(UploadFile(file)) =>
      if (cftp.upload(file))
        stay forMax (ACTIVE_TIMEOUT)
      else {
        EventHandler.error(this, "上传失败 file:%s".format(file))
        cftp.quit()
        goto(UnAvailable)
      }
  }

  when(UnAvailable, ACTIVE_TIMEOUT) {
    case Ev(StateTimeout) =>
      setTimer("autoConn", Open, DISCON_TIMEOUT, false)
      goto(Disconnected)
  }

  onTransition {
    case a -> UnAvailable => EventHandler.info(this,  "%s -> UnAvailable".format(a))
  }

  initialize
}

sealed trait FileMessage
/**
 * status: begin, success, failed
 */
case class FileUploadMsg(file: String, status: String) extends FileMessage

class FileStatusManager extends Actor {
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

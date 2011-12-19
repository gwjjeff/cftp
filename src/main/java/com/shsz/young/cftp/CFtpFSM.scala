package com.shsz.young.cftp

import akka.actor.{ Actor, FSM }
import akka.event.EventHandler
import akka.util.duration._
import Actor._

sealed trait FtpClientState
case object Disconnected extends FtpClientState
case object Connected extends FtpClientState
case object LoggedIn extends FtpClientState
case object Busy extends FtpClientState

sealed trait FtpMessage
case class Open(host: String, port: Int, serverEncoding: String) extends FtpMessage
case class Login(user: String, pass: String)
case class UploadFile(file: String)

class CFtpFSM extends CFtp with Actor with FSM[FtpClientState, Unit] {
  import FSM._

  private var cftp: CFtp = _

  startWith(Disconnected, Unit)

  when(Disconnected) {
    case Ev(Open(host, port, serverEncoding)) =>
      cftp = new CFtp(host, port, serverEncoding)
      cftp.open()
      if (cftp.isConnected()) goto(Connected)
      else {
        EventHandler.error(this, "无法连接 " + host + ":" + port)
        stop
      }
  }

  when(Connected) {
    case Ev(Login(user, pass)) =>
      cftp.login(user, pass)
      if (cftp.isLoggedIn()) goto(LoggedIn) forMax (5 seconds)
      else {
        EventHandler.error(this, "登录失败 user:" + user)
        stop
      }
  }

  when(LoggedIn) {
    case Ev(StateTimeout) =>
      if (cftp.activeTest())
        stay forMax (5 seconds)
      else {
        //TODO: 处理活动测试不成功
        goto(Disconnected)
      }
  }
}
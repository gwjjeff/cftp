package com.shsz.young.cftp.sender

import akka.actor.{ Actor, ActorRef }
import akka.camel.{ Message, Consumer, CamelServiceFactory }
import Actor._
import akka.event.EventHandler
import com.shsz.young.cftp.utils._
import com.shsz.young.cftp._

object CamelSender {
  private val service = CamelServiceFactory.createCamelService
  def start() {
    actorOf[CamelSender].start
    service.start
  }

  def stop() {
    service.stop
  }
}

class CamelSender extends Actor with Consumer {
  var manager: ActorRef = _
  var uri: String = _
  def endpointUri = {
    val opts = "noop=true"
    "%s?%s".format(uri, opts)
  }
  def receive = {
    case msg: Message =>
      // println("received %s" format msg.getHeaders.get("CamelFileName"))
      val local = msg.getHeaders.get("CamelFileAbsolutePath").toString()
      val remote = (new java.io.File(local)).getName
      manager ! UploadFile(local, remote)
  }
  override def preStart() {
    val p = loadProps("secret.properties")
    val mgrHost = p.getProperty("test.cftpmgr.host")
    val mgrPort = p.getProperty("test.cftpmgr.port").toInt
    val mgrServiceId = p.getProperty("test.cftpmgr.serviceId")

    manager = remote.actorFor(mgrServiceId, mgrHost, mgrPort)

    val localDir = p.getProperty("test.cftpcamel.localPath")
    val local = new java.io.File(localDir)
    if (local.isDirectory()) {
      uri = local.getCanonicalFile().toURI().toString()
    } else {
      EventHandler.info(this, "%s 不存在或者不是目录".format(localDir))
    }
  }
}
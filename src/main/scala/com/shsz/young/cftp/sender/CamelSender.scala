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
    registry.shutdownAll()
    remote.shutdown()
  }
}

class CamelSender extends Actor with Consumer {
  private var manager: ActorRef = _
  private var uri: String = _
  private var moveMode: Boolean = _
  private var moveDir: String = _
  // 除 noop 和 move 以外其他的 uri 选项
  // ref: http://camel.apache.org/file2.html
  private var uriOpts: String = _

  def endpointUri = {
    var opts = "noop=true"
    if (moveMode)
      opts = "move=%s".format(moveDir)
    if (uriOpts.length() > 0)
      "%s?%s&".format(uri, opts, uriOpts)
    else
      "%s?%s".format(uri, opts)
  }

  def receive = {
    case msg: Message =>
      // println("received %s" format msg.getHeaders.get("CamelFileName"))
      var localFile = msg.getHeaders.get("CamelFileAbsolutePath").toString()
      val fileNameOnly = msg.getHeaders.get("CamelFileNameOnly").toString()
      if (moveMode) {
        val parentPath = msg.getHeaders.get("CamelFileParent").toString()
        localFile = "%s%s%s%s%s".format(parentPath, java.io.File.separator,
          moveDir, java.io.File.separator, fileNameOnly)
      }
      manager ! UploadFile(localFile, fileNameOnly)
  }

  override def preStart() {
    val p = loadProps("secret.properties")
    val mgrHost = p.getProperty("cftpmgr.host")
    val mgrPort = p.getProperty("cftpmgr.port").toInt
    val mgrServiceId = p.getProperty("cftpmgr.serviceId")
    moveMode = p.getProperty("cftpcamel.moveMode", "true").toBoolean
    moveDir = p.getProperty("cftpcamel.moveDir", ".camel")
    uriOpts = p.getProperty("cftpcamel.uriOpts", "")
    manager = remote.actorFor(mgrServiceId, mgrHost, mgrPort)

    val localDir = p.getProperty("cftpcamel.localPath")
    val local = new java.io.File(localDir)
    if (local.isDirectory()) {
      uri = local.getCanonicalFile().toURI().toString()
    } else {
      EventHandler.info(this, "%s 不存在或者不是目录".format(localDir))
    }
  }
}
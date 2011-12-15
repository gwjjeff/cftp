package com.shsz.young.cftp

import org.junit.runner.RunWith
import org.scalatest.WordSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll

object CFtpSpec {

}

@RunWith(classOf[JUnitRunner])
class CFtpSpec extends WordSpec with ShouldMatchers with BeforeAndAfterAll {
  import CFtpSpec._
  // 必须是正确的host，user，pass
  val host = "xx"
  val user = "xx"
  val pass = "xx"
  val cftp = new CFtp(host)
  "CFtp" should {
    "连接和断开远程ftp服务器" in {
      cftp.isConnected() should be(false)
      cftp.connect();
      cftp.isConnected() should be(true)
      cftp.disconnect();
      cftp.isConnected() should be(false)
    }
    "登录和登出远程ftp服务器" in {
      cftp.isConnected() should be(false)
      cftp.connect();
      cftp.isConnected() should be(true)
      cftp.isLoggedIn() should be(false)
      cftp.login(user, pass);
      cftp.isLoggedIn() should be(true)
      cftp.logout()
      cftp.isLoggedIn() should be(false)
    }
  }
  override def afterAll = {
    if (cftp.isLoggedIn()) cftp.logout()
    if (cftp.isConnected()) cftp.disconnect()
    ()
  }
}
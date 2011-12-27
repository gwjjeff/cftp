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
  val is = classOf[CFtpSpec].getClassLoader.getResourceAsStream("secret.properties")
  val p = new java.util.Properties
  p.load(is)
  // 必须是正确的host，user，pass，ddir
  val host = p.getProperty("cftp.host")
  val port = Integer.parseInt(p.getProperty("test.cftp.port"))
  val user = p.getProperty("cftp.user")
  val pass = p.getProperty("cftp.pass")
  val ddir = p.getProperty("cftp.ddir")

  val localFile = p.getProperty("test.cftp.localFile")
  val remoteFile = p.getProperty("test.cftp.remoteFile")
  val localFile2 = p.getProperty("test.cftp.localFile2")
  
  val serverEncoding = p.getProperty("test.cftp.serverEncoding")
  
  val cftp = new CFtp(host, port, serverEncoding)
  "CFtp" should {
    "连接远程ftp服务器" in {
      cftp.isConnected() should be(true)
    }
    "登录和登出远程ftp服务器，活动测试" in {
      cftp.isLoggedIn() should be(true)
      cftp.activeTest() should be(true)
    }
    "上传、下载和删除文件" in {
      cftp.cd(ddir) should be(true)
      cftp.upload(localFile) should be(true)
      cftp.download(remoteFile, localFile2) should be(true)
      cftp.delete(remoteFile) should be(true)
    }
  }
  override def beforeAll = {
    cftp.isConnected() should be(false)
    cftp.open()
    cftp.isConnected() should be(true)
    cftp.isLoggedIn() should be(false)
    cftp.login(user, pass)
  }
  override def afterAll = {
    if (cftp.isConnected()) cftp.quit()
    cftp.isConnected() should be(false)
  }
}
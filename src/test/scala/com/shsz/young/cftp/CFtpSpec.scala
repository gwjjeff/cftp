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
  val port = Integer.parseInt(p.getProperty("cftp.port"))
  val user = p.getProperty("cftp.user")
  val pass = p.getProperty("cftp.pass")
  val ddir = p.getProperty("cftp.ddir")

  val localFileEnName = p.getProperty("test.cftp.localFileEnName")
  val remoteFileEnName = p.getProperty("test.cftp.remoteFileEnName")
  val localFile2EnName = p.getProperty("test.cftp.localFile2EnName")
  val localFileCnName = p.getProperty("test.cftp.localFileCnName")
  val remoteFileCnName = p.getProperty("test.cftp.remoteFileCnName")
  val localFile2CnName = p.getProperty("test.cftp.localFile2CnName")

  val serverEncoding = p.getProperty("cftp.serverEncoding")

  val cftp = new CFtp(host, port, serverEncoding)
  "CFtp" should {
    "连接远程ftp服务器" in {
      cftp.isConnected() should be(true)
    }
    "登录和登出远程ftp服务器，活动测试" in {
      cftp.isLoggedIn() should be(true)
      cftp.activeTest() should be(true)
    }
    "上传、下载和删除文件 - 英文文件名" in {
      cftp.upload(localFileEnName) should be(true)
      cftp.download(remoteFileEnName, localFile2EnName) should be(true)
      cftp.delete(remoteFileEnName) should be(true)
    }
    "上传、下载和删除文件 - 中文文件名" in {
      cftp.upload(localFileCnName) should be(true)
      cftp.download(remoteFileCnName, localFile2CnName) should be(true)
      cftp.delete(remoteFileCnName) should be(true)
    }
  }
  override def beforeAll = {
    cftp.isConnected() should be(false)
    cftp.open()
    cftp.isConnected() should be(true)
    cftp.isLoggedIn() should be(false)
    cftp.login(user, pass)
    cftp.cd(ddir) should be(true)
  }
  override def afterAll = {
    if (cftp.isConnected()) cftp.quit()
    cftp.isConnected() should be(false)
  }
}
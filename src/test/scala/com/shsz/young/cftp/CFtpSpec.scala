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
  // 必须是正确的host，user，pass，ddir
  val host = "xx"
  val user = "xx"
  val pass = "xx"
  val ddir = "xx"

  val localFile = "e:/temp1/_mylogfile.log"
  val remoteFile = "_mylogfile.log"
  val localFile2 = "e:/temp1/_mylogfile.log2"
  val cftp = new CFtp(host)
  "CFtp" should {
    "连接远程ftp服务器" in {
      cftp.isConnected() should be(true)
    }
    "登录和登出远程ftp服务器，活动测试" in {
      cftp.isLoggedIn() should be(true)
      cftp.activeTest() should be(true)
      cftp.cd(ddir) should be(true)
      cftp.upload(localFile) should be(true)
      cftp.download(remoteFile, localFile2) should be(true)
      cftp.delete(remoteFile) should be(true)
    }
    "上传和下载文件" in {
      pending

    }
  }
  override def beforeAll = {
    cftp.isConnected() should be(false)
    cftp.open();
    cftp.isConnected() should be(true)
    cftp.isLoggedIn() should be(false)
    cftp.login(user, pass);
  }
  override def afterAll = {
    if (cftp.isConnected()) cftp.quit()
    cftp.isConnected() should be(false)
  }
}
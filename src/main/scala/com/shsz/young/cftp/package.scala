package com.shsz.young.cftp

package object utils {
  def loadProps(propsFile: String) = {
    // TODO: 处理获取资源是出现空指针的情况
    val is = classOf[CFtpFSM].getClassLoader.getResourceAsStream(propsFile)
    val p = new java.util.Properties
    p.load(is)
    is.close()
    p
  }

}
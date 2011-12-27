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

  // ref: http://stackoverflow.com/questions/4604237/how-to-write-to-a-file-in-scala
  def printToFile(f: java.io.File)(op: java.io.PrintWriter => Unit) {
    val p = new java.io.PrintWriter(f)
    try { op(p) } finally { p.close() }
  }

}
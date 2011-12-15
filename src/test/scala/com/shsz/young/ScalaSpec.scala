package com.shsz.young

import org.junit.runner.RunWith
import org.scalatest.WordSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

object ScalaSpec {
  // ----------------
  case class A(name: String) {
    def me = this
  }
  implicit def stringToA(s: String) = A(s)
  // ----------------
  sealed trait Drawer {
    def draw: String
  }
  class Displayer extends Drawer {
    override def draw = "a displayer"
  }
  class Printer extends Drawer {
    override def draw = "a printer"
  }
  def drawerOf[T <: Drawer: Manifest]: Drawer = manifest[T].erasure.asInstanceOf[Class[_ <: Drawer]].newInstance()
  // ----------------
  def argsLength(args: Int*) = args.length
  // ----------------
  def hntower[T](tower: List[T] = (1 to 4).toList.reverse) {
    def move(sub: List[T], from: Symbol, to: Symbol, use: Symbol) {
      sub match {
        case base :: upper => {
          move(upper, from, use, to)
          println("move %s from %s to %s".format(base, from, to))
          move(upper, use, to, from)
        }
        case Nil => {}
      }
    }
    move(tower, 'a, 'c, 'b)
  }
}

@RunWith(classOf[JUnitRunner])
class ScalaSpec extends WordSpec with ShouldMatchers {
  import ScalaSpec._
  val v = A("a")
  "scala" should {
    "变量名 空格 方法名 的方式调用" in {
      "a" should be(v name)
      (v name) should be("a")
      (v me) should be(v)
    }
    "隐式声明的转换" in {
      ("a" me) should be(v)
    }
    "使用Manifest进行隐式类型创建" in {
      drawerOf[Printer].draw should be("a printer")
      drawerOf[Displayer].draw should be("a displayer")
    }
    "定义多参数函数" in {
      argsLength(12, 3, 2, 5, 6) should be(5)
      val arr = Array(12, 34, 5)
      argsLength(arr: _*) should be(3)
    }
    "使用内嵌递归函数" in {
      hntower(('A' to 'D').toList.reverse)
    }
  }
}

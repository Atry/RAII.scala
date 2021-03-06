package com.thoughtworks.raii

import java.io.StringWriter

import com.thoughtworks.raii.asynchronous.Do

import scalaz.syntax.all._
import org.scalatest.{Assertion, FreeSpec, Matchers}

import scala.concurrent.Promise
import org.scalatest.{FreeSpec, Matchers}
import com.thoughtworks.raii.asynchronous.Do._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
final class asynchronousSpec extends FreeSpec with Matchers {

  "Given a scoped resource" - {
    var isSourceClosed = false
    val source = Do.scoped(new AutoCloseable {
      isSourceClosed should be(false)
      override def close(): Unit = {
        isSourceClosed should be(false)
        isSourceClosed = true
      }
    })
    "And flatMap the resource to an new autoReleaseDependencies resource" - {
      var isResultClosed = false
      val result = Do.releaseFlatMap(source) { sourceCloseable =>
        Do.scoped(new AutoCloseable {
          isResultClosed should be(false)
          override def close(): Unit = {
            isResultClosed should be(false)
            isResultClosed = true
          }
        })
      }
      "When map the new resource" - {
        "Then dependency resource should have been released" in {
          val p = Promise[Assertion]
          Do.run(result.map { r =>
              isSourceClosed should be(true)
              isResultClosed should be(false)
            })
            .unsafePerformAsync { either =>
              isSourceClosed should be(true)
              isResultClosed should be(true)
              p.complete(scalaz.std.`try`.fromDisjunction(either))
            }
          p.future
        }
      }
    }
  }
}

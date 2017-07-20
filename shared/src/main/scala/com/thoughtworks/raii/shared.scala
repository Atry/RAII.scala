package com.thoughtworks.raii

import java.util.concurrent.atomic.AtomicReference

import com.thoughtworks.future.continuation.Continuation
import com.thoughtworks.raii.covariant.{Releasable, ResourceT}

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scalaz.Free.Trampoline
import scalaz.Trampoline
import scalaz.syntax.all._
import scalaz.std.iterable._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object shared {

  private[shared] sealed trait State[A]
  private[shared] final case class Closed[A]() extends State[A]
  private[shared] final case class Opening[A](handlers: Queue[Releasable[Continuation, A] => Trampoline[Unit]])
      extends State[A]
  private[shared] final case class Open[A](data: Releasable[Continuation, A], count: Int) extends State[A]

  implicit final class SharedOps[A](raii: ResourceT[Continuation, A]) {

    def shared: ResourceT[Continuation, A] = {
      val sharedReference = new SharedStateMachine(raii)
      ResourceT[Continuation, A](
        Continuation.shift { (handler: Releasable[Continuation, A] => Trampoline[Unit]) =>
          sharedReference.acquire(handler)
        }
      )
    }

  }

  private[shared] final class SharedStateMachine[A](underlying: ResourceT[Continuation, A])
      extends AtomicReference[State[A]](Closed())
      with Releasable[Continuation, A] {
    private def sharedCloseable = this
    override def value: A = state.get().asInstanceOf[Open[A]].data.value

    override def release(): Continuation[Unit] = {
      @tailrec
      def retry(): Continuation[Unit] = {
        state.get() match {
          case oldState @ Open(data, count) =>
            if (count == 1) {
              if (state.compareAndSet(oldState, Closed())) {
                data.release()
              } else {
                retry()
              }
            } else {
              if (state.compareAndSet(oldState, oldState.copy(count = count - 1))) {
                Continuation.now(())
              } else {
                retry()
              }
            }
          case Opening(_) | Closed() =>
            throw new IllegalStateException("Cannot release more than once")

        }
      }
      retry()
    }

    private def state = this

    @tailrec
    private def complete(data: Releasable[Continuation, A]): Trampoline[Unit] = {
      state.get() match {
        case oldState @ Opening(handlers) =>
          val newState = Open(data, handlers.length)
          if (state.compareAndSet(oldState, newState)) {
            handlers.traverse_ { f: (Releasable[Continuation, A] => Trampoline[Unit]) =>
              f(sharedCloseable)
            }
          } else {
            complete(data)
          }
        case Open(_, _) | Closed() =>
          throw new IllegalStateException("Cannot trigger handler more than once")
      }
    }

    @tailrec
    private[shared] def acquire(handler: Releasable[Continuation, A] => Trampoline[Unit]): Trampoline[Unit] = {
      state.get() match {
        case oldState @ Closed() =>
          if (state.compareAndSet(oldState, Opening(Queue(handler)))) {
            val ResourceT(continuation) = underlying
            Continuation.run(continuation)(complete)
          } else {
            acquire(handler)
          }
        case oldState @ Opening(handlers: Queue[Releasable[Continuation, A] => Trampoline[Unit]]) =>
          if (state.compareAndSet(oldState, Opening(handlers.enqueue(handler)))) {
            Trampoline.done(())
          } else {
            acquire(handler)
          }
        case oldState @ Open(data, count) =>
          if (state.compareAndSet(oldState, oldState.copy(count = count + 1))) {
            handler(sharedCloseable)
          } else {
            acquire(handler)
          }
      }

    }
  }

}

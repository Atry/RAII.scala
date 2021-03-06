package com.thoughtworks.raii

import com.thoughtworks.raii.covariant.ResourceT

import scala.language.higherKinds
import scalaz.Tags.Parallel
import scalaz.{\/, _}
import scalaz.syntax.all._

object covariant {

  val opacityTypes: OpacityTypes = new OpacityTypes {
    override type ResourceT[F[+ _], +A] = F[Releasable[F, A]]

    override def apply[F[+ _], A](run: F[Releasable[F, A]]): ResourceT[F, A] = run

    override def unwrap[F[+ _], A](resourceT: ResourceT[F, A]): F[Releasable[F, A]] =
      resourceT
  }

  /** @template */
  type ResourceT[F[+ _], +A] = opacityTypes.ResourceT[F, A]
  import opacityTypes._
  trait Releasable[F[+ _], +A] {
    def value: A

    /** Releases [[value]] and all resource dependencies during creating [[value]].
      *
      * @note After [[release]], [[value]] should not be used if:
      *  - [[value]] is a scoped native resource,
      *    e.g. a [[com.thoughtworks.raii.asynchronous.Do.scoped[A<:AutoCloseable](a:=>A)* scoped]] `AutoCloseable`,
      *  - or, [[value]] internally uses some scoped native resources.
      */
    def release(): F[Unit]
  }

  private[raii] object Releasable {
    @inline
    def now[F[+ _]: Applicative, A](value: A): Releasable[F, A] = {
      val value0 = value
      val pointUnit = Applicative[F].point(())
      new Releasable[F, A] {
        override def value: A = value0

        override def release: F[Unit] = pointUnit
      }
    }
  }

  private[raii] trait OpacityTypes {
    type ResourceT[F[+ _], +A]

    private[raii] def apply[F[+ _], A](run: F[Releasable[F, A]]): ResourceT[F, A]

    private[raii] def unwrap[F[+ _], A](resourceT: ResourceT[F, A]): F[Releasable[F, A]]

  }

  object ResourceT extends ResourceFactoryTInstances0 {

    def apply[F[+ _], A](run: F[Releasable[F, A]]): ResourceT[F, A] = opacityTypes.apply(run)

    def unapply[F[+ _], A](resourceT: ResourceT[F, A]): Some[F[Releasable[F, A]]] =
      Some(unwrap(resourceT))

    private[raii] final def using[F[+ _], A, B](resourceT: ResourceT[F, A], f: A => F[B])(
        implicit monad: Bind[F]): F[B] = {
      unwrap(resourceT).flatMap { fa =>
        f(fa.value).flatMap { a: B =>
          fa.release().map { _ =>
            a
          }
        }
      }
    }

    /**
      * Return a [Future] which contains content of [Releasable] and [Releasable] will be closed,
      * NOTE: the content of [Releasable] must be JVM resource cause the content will not be closed.
      */
    final def run[F[+ _], A](resourceT: ResourceT[F, A])(implicit monad: Bind[F]): F[A] = {
      unwrap(resourceT).flatMap { resource: Releasable[F, A] =>
        resource.release.map { _ =>
          resource.value
        }
      }
    }

    def releaseMap[F[+ _]: Monad, A, B](fa: ResourceT[F, A])(f: A => B): ResourceT[F, B] = {
      opacityTypes.apply(
        unwrap(fa).flatMap { releasableA =>
          val b = f(releasableA.value)
          releasableA.release().map { _ =>
            new Releasable[F, B] {
              override def value: B = b

              override def release(): F[Unit] = {
                ().point[F]
              }
            }
          }
        }
      )
    }

    def releaseFlatMap[F[+ _]: Bind, A, B](fa: ResourceT[F, A])(f: A => ResourceT[F, B]): ResourceT[F, B] = {
      opacityTypes.apply(
        for {
          releasableA <- unwrap(fa)
          releasableB <- unwrap(f(releasableA.value))
          _ <- releasableA.release()
        } yield {
          new Releasable[F, B] {
            override def value: B = releasableB.value

            override def release(): F[Unit] = {
              releasableB.release()
            }
          }
        }
      )
    }
    private[raii] final def foreach[F[+ _], A](resourceT: ResourceT[F, A],
                                               f: A => Unit)(implicit monad: Bind[F], foldable: Foldable[F]): Unit = {
      unwrap(resourceT)
        .flatMap { fa =>
          f(fa.value)
          fa.release()
        }
        .sequence_[Id.Id, Unit]
    }

    private[raii] def catchError[F[+ _]: MonadError[?[_], S], S, A](fa: F[A]): F[S \/ A] = {
      fa.map(_.right[S]).handleError(_.left[A].point[F])
    }

    implicit def resourceTParallelApplicative[F[+ _]](
        implicit F0: Applicative[
          Lambda[A => @@[F[A], Parallel]]
        ]
    ): Applicative[Lambda[A => ResourceT[F, A] @@ Parallel]] = {
      new ResourceFactoryTParallelApplicative[F] {
        override private[raii] implicit def typeClass = F0
      }
    }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances3 {

    implicit def resourceTApplicative[F[+ _]: Applicative]: Applicative[ResourceT[F, ?]] =
      new ResourceFactoryTApplicative[F] {
        override private[raii] def typeClass = implicitly
      }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances2 extends ResourceFactoryTInstances3 {

    implicit def resourceTMonad[F[+ _]: Monad]: Monad[ResourceT[F, ?]] = new ResourceFactoryTMonad[F] {
      private[raii] override def typeClass = implicitly
    }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances1 extends ResourceFactoryTInstances2 {

    implicit def resourceTNondeterminism[F[+ _]](implicit F0: Nondeterminism[F]): Nondeterminism[ResourceT[F, ?]] =
      new ResourceFactoryTNondeterminism[F] {
        private[raii] override def typeClass = implicitly
      }
  }

  private[raii] sealed abstract class ResourceFactoryTInstances0 extends ResourceFactoryTInstances1 {

    implicit def resourceTMonadError[F[+ _], S](implicit F0: MonadError[F, S]): MonadError[ResourceT[F, ?], S] =
      new ResourceFactoryTMonadError[F, S] {
        private[raii] override def typeClass = implicitly
      }
  }

  private[raii] trait ResourceFactoryTPoint[F[+ _]] extends Applicative[ResourceT[F, ?]] {
    private[raii] implicit def typeClass: Applicative[F]

    override def point[A](a: => A): ResourceT[F, A] =
      opacityTypes.apply(Applicative[F].point(Releasable.now(a)))
  }

  import com.thoughtworks.raii.covariant.opacityTypes.unwrap

  private[raii] trait ResourceFactoryTApplicative[F[+ _]]
      extends Applicative[ResourceT[F, ?]]
      with ResourceFactoryTPoint[F] {

    override def ap[A, B](fa: => ResourceT[F, A])(f: => ResourceT[F, (A) => B]): ResourceT[F, B] = {
      opacityTypes.apply(
        Applicative[F].apply2(unwrap(fa), unwrap(f)) { (releasableA, releasableF) =>
          new Releasable[F, B] {
            override val value: B = releasableF.value(releasableA.value)

            override def release(): F[Unit] = {
              Applicative[F].apply2(releasableA.release(), releasableF.release()) { (_: Unit, _: Unit) =>
                ()
              }
            }
          }
        }
      )
    }
  }

  private[raii] trait ResourceFactoryTParallelApplicative[F[+ _]]
      extends Applicative[Lambda[A => ResourceT[F, A] @@ Parallel]] {
    private[raii] implicit def typeClass: Applicative[Lambda[A => F[A] @@ Parallel]]

    override def point[A](a: => A): ResourceT[F, A] @@ Parallel = {

      Parallel({
        val fa: F[Releasable[F, A]] = Parallel.unwrap[F[Releasable[F, A]]](
          typeClass.point(
            new Releasable[F, A] {
              override def value: A = a

              override def release(): F[Unit] = Parallel.unwrap(typeClass.point(()))
            }
          ))
        opacityTypes.apply(fa)
      }: ResourceT[F, A])
    }

    override def ap[A, B](fa: => ResourceT[F, A] @@ Parallel)(
        f: => ResourceT[F, A => B] @@ Parallel): ResourceT[F, B] @@ Parallel = {
      Parallel {
        opacityTypes.apply(
          Parallel.unwrap[F[Releasable[F, B]]](
            typeClass.apply2(
              Parallel(unwrap(Parallel.unwrap(fa))),
              Parallel(unwrap(Parallel.unwrap(f)))
            ) { (resourceA, resourceF) =>
              new Releasable[F, B] {
                override val value: B = resourceF.value(resourceA.value)

                override def release(): F[Unit] = {
                  Parallel.unwrap[F[Unit]](
                    typeClass.apply2(Parallel(resourceA.release()), Parallel(resourceF.release())) {
                      (_: Unit, _: Unit) =>
                        ()
                    })
                }
              }
            }
          )
        )
      }
    }
  }

  private[raii] trait ResourceFactoryTMonad[F[+ _]]
      extends ResourceFactoryTApplicative[F]
      with Monad[ResourceT[F, ?]] {
    private[raii] implicit override def typeClass: Monad[F]

    override def bind[A, B](fa: ResourceT[F, A])(f: (A) => ResourceT[F, B]): ResourceT[F, B] = {
      opacityTypes.apply(
        for {
          releasableA <- unwrap(fa)
          releasableB <- unwrap(f(releasableA.value))
        } yield {
          new Releasable[F, B] {
            override def value: B = releasableB.value

            override def release(): F[Unit] = {
              releasableB.release() >> releasableA.release()
            }
          }
        }
      )
    }

  }

  private[raii] trait ResourceFactoryTMonadError[F[+ _], S]
      extends MonadError[ResourceT[F, ?], S]
      with ResourceFactoryTPoint[F] {
    private[raii] implicit def typeClass: MonadError[F, S]

    override def raiseError[A](e: S): ResourceT[F, A] =
      opacityTypes.apply(typeClass.raiseError[Releasable[F, A]](e))

    override def handleError[A](fa: ResourceT[F, A])(f: (S) => ResourceT[F, A]): ResourceT[F, A] = {
      opacityTypes.apply(
        unwrap(fa).handleError { s =>
          unwrap(f(s))
        }
      )
    }

    import com.thoughtworks.raii.covariant.ResourceT.catchError

    override def bind[A, B](fa: ResourceT[F, A])(f: A => ResourceT[F, B]): ResourceT[F, B] = {
      opacityTypes.apply(
        catchError(unwrap(fa)).flatMap {
          case \/-(releasableA) =>
            catchError(unwrap(f(releasableA.value))).flatMap[Releasable[F, B]] {
              case \/-(releasableB) =>
                new Releasable[F, B] {
                  override def value: B = releasableB.value

                  override def release(): F[Unit] = {
                    catchError(releasableB.release()).flatMap {
                      case \/-(()) =>
                        releasableA.release()
                      case -\/(s) =>
                        releasableA.release().flatMap { _ =>
                          typeClass.raiseError[Unit](s)
                        }
                    }
                  }
                }.point[F]
              case -\/(s) =>
                releasableA.release().flatMap { _ =>
                  typeClass.raiseError[Releasable[F, B]](s)
                }
            }
          case either @ -\/(s) =>
            typeClass.raiseError[Releasable[F, B]](s)
        }
      )
    }
  }

  private[raii] trait ResourceFactoryTNondeterminism[F[+ _]]
      extends ResourceFactoryTMonad[F]
      with Nondeterminism[ResourceT[F, ?]] {
    private[raii] implicit override def typeClass: Nondeterminism[F]

    override def chooseAny[A](head: ResourceT[F, A],
                              tail: Seq[ResourceT[F, A]]): ResourceT[F, (A, Seq[ResourceT[F, A]])] = {
      opacityTypes.apply(
        typeClass.chooseAny(unwrap(head), tail.map(unwrap)).map {
          case (fa, residuals) =>
            new Releasable[F, (A, Seq[ResourceT[F, A]])] {
              override val value: (A, Seq[ResourceT[F, A]]) =
                (fa.value, residuals.map { residual: F[Releasable[F, A]] =>
                  opacityTypes.apply[F, A](residual)
                })

              override def release(): F[Unit] = fa.release()
            }

        }
      )

    }
  }
}

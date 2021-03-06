package cats
package data

import cats.arrow.{Arrow, Category, Compose, Split}
import cats.functor.{Contravariant, Profunctor}
import cats.{CoflatMap, Comonad, Functor, Monad}
import scala.annotation.tailrec

/**
 * Represents a function `F[A] => B`.
 */
final case class Cokleisli[F[_], A, B](run: F[A] => B) { self =>

  def dimap[C, D](f: C => A)(g: B => D)(implicit F: Functor[F]): Cokleisli[F, C, D] =
    Cokleisli(fc => g(run(F.map(fc)(f))))

  def lmap[C](f: C => A)(implicit F: Functor[F]): Cokleisli[F, C, B] =
    Cokleisli(fc => run(F.map(fc)(f)))

  def map[C](f: B => C): Cokleisli[F, A, C] =
    Cokleisli(f compose run)

  def contramapValue[C](f: F[C] => F[A]): Cokleisli[F, C, B] =
    Cokleisli(run compose f)

  def flatMap[C](f: B => Cokleisli[F, A, C]): Cokleisli[F, A, C] =
    Cokleisli(fa => f(self.run(fa)).run(fa))

  def compose[C](c: Cokleisli[F, C, A])(implicit F: CoflatMap[F]): Cokleisli[F, C, B] =
    Cokleisli(fc => run(F.coflatMap(fc)(c.run)))

  def andThen[C](c: Cokleisli[F, B, C])(implicit F: CoflatMap[F]): Cokleisli[F, A, C] =
    c compose this

  def first[C](implicit F: Comonad[F]): Cokleisli[F, (A, C), (B, C)] =
    Cokleisli(fac => run(F.map(fac)(_._1)) -> F.extract(F.map(fac)(_._2)))

  def second[C](implicit F: Comonad[F]): Cokleisli[F, (C, A), (C, B)] =
    Cokleisli(fca => F.extract(F.map(fca)(_._1)) -> run(F.map(fca)(_._2)))
}

object Cokleisli extends CokleisliInstances {
  def pure[F[_], A, B](x: B): Cokleisli[F, A, B] =
    Cokleisli(_ => x)
}

private[data] sealed abstract class CokleisliInstances extends CokleisliInstances0 {
  implicit def catsDataArrowForCokleisli[F[_]](implicit ev: Comonad[F]): Arrow[Cokleisli[F, ?, ?]] =
    new CokleisliArrow[F] { def F: Comonad[F] = ev }

  implicit def catsDataMonadForCokleisli[F[_], A]: Monad[Cokleisli[F, A, ?]] = new Monad[Cokleisli[F, A, ?]] {
    def pure[B](x: B): Cokleisli[F, A, B] =
      Cokleisli.pure(x)

    def flatMap[B, C](fa: Cokleisli[F, A, B])(f: B => Cokleisli[F, A, C]): Cokleisli[F, A, C] =
      fa.flatMap(f)

    override def map[B, C](fa: Cokleisli[F, A, B])(f: B => C): Cokleisli[F, A, C] =
      fa.map(f)

    def tailRecM[B, C](b: B)(fn: B => Cokleisli[F, A, Either[B, C]]): Cokleisli[F, A, C] =
      Cokleisli({ (fa: F[A]) =>
        @tailrec
        def loop(c: Cokleisli[F, A, Either[B, C]]): C = c.run(fa) match {
          case Right(c) => c
          case Left(bb) => loop(fn(bb))
        }
        loop(fn(b))
      })
  }

  implicit def catsDataMonoidKForCokleisli[F[_]](implicit ev: Comonad[F]): MonoidK[λ[α => Cokleisli[F, α, α]]] =
    Category[Cokleisli[F, ?, ?]].algebraK
}

private[data] sealed abstract class CokleisliInstances0 {
  implicit def catsDataSplitForCokleisli[F[_]](implicit ev: CoflatMap[F]): Split[Cokleisli[F, ?, ?]] =
    new CokleisliSplit[F] { def F: CoflatMap[F] = ev }

  implicit def catsDataProfunctorForCokleisli[F[_]](implicit ev: Functor[F]): Profunctor[Cokleisli[F, ?, ?]] =
    new CokleisliProfunctor[F] { def F: Functor[F] = ev }

  implicit def catsDataSemigroupKForCokleisli[F[_]](implicit ev: CoflatMap[F]): SemigroupK[λ[α => Cokleisli[F, α, α]]] =
    Compose[Cokleisli[F, ?, ?]].algebraK

  implicit def catsDataContravariantForCokleisli[F[_]: Functor, A]: Contravariant[Cokleisli[F, ?, A]] =
    new Contravariant[Cokleisli[F, ?, A]] {
      def contramap[B, C](fbc: Cokleisli[F, B, A])(f: C => B): Cokleisli[F, C, A] = fbc.lmap(f)
    }
}

private trait CokleisliArrow[F[_]] extends Arrow[Cokleisli[F, ?, ?]] with CokleisliSplit[F] with CokleisliProfunctor[F] {
  implicit def F: Comonad[F]

  def lift[A, B](f: A => B): Cokleisli[F, A, B] =
    Cokleisli(fa => f(F.extract(fa)))

  def id[A]: Cokleisli[F, A, A] =
    Cokleisli(fa => F.extract(fa))

  def first[A, B, C](fa: Cokleisli[F, A, B]): Cokleisli[F, (A, C), (B, C)] =
    fa.first[C]

  override def second[A, B, C](fa: Cokleisli[F, A, B]): Cokleisli[F, (C, A), (C, B)] =
    fa.second[C]

  override def dimap[A, B, C, D](fab: Cokleisli[F, A, B])(f: C => A)(g: B => D): Cokleisli[F, C, D] =
    super[CokleisliProfunctor].dimap(fab)(f)(g)

  override def split[A, B, C, D](f: Cokleisli[F, A, B], g: Cokleisli[F, C, D]): Cokleisli[F, (A, C), (B, D)] =
    super[CokleisliSplit].split(f, g)
}

private trait CokleisliSplit[F[_]] extends Split[Cokleisli[F, ?, ?]] {
  implicit def F: CoflatMap[F]

  def compose[A, B, C](f: Cokleisli[F, B, C], g: Cokleisli[F, A, B]): Cokleisli[F, A, C] =
    f.compose(g)

  def split[A, B, C, D](f: Cokleisli[F, A, B], g: Cokleisli[F, C, D]): Cokleisli[F, (A, C), (B, D)] =
    Cokleisli(fac => f.run(F.map(fac)(_._1)) -> g.run(F.map(fac)(_._2)))
}

private trait CokleisliProfunctor[F[_]] extends Profunctor[Cokleisli[F, ?, ?]] {
  implicit def F: Functor[F]

  def dimap[A, B, C, D](fab: Cokleisli[F, A, B])(f: C => A)(g: B => D): Cokleisli[F, C, D] =
    fab.dimap(f)(g)

  override def lmap[A, B, C](fab: Cokleisli[F, A, B])(f: C => A): Cokleisli[F, C, B] =
    fab.lmap(f)

  override def rmap[A, B, C](fab: Cokleisli[F, A, B])(f: B => C): Cokleisli[F, A, C] =
    fab.map(f)
}

package manatki.data
import cats.syntax.either._
import cats.{Monad, StackSafeMonad}
import manatki.free.FunK
import tofu.syntax.monadic._

sealed trait Freer[+F[_], A] {
  def flatMap[G[x] >: F[x], B](f: A => Freer[G, B]): Freer[G, B]
  def foldMap[G[_]: Monad](f: FunK[F, G]): G[A] = this.tailRecM {
    case Freer.Pure(a)               => a.asRight.pure[G]
    case bind: Freer.Bind[F, pin, A] => f(bind.head).map(a => bind.cont(a).asLeft)
  }
  // fold map for stack safe monads
  def foldMapL[G[_]: Monad](f: FunK[F, G]): G[A] = this match {
    case Freer.Pure(a)               => a.pure[G]
    case bind: Freer.Bind[F, pin, A] => f(bind.head).flatMap(pin => bind.cont(pin).foldMapL(f))
  }
  def mapK[G[_]](f: FunK[F, G]): Freer[G, A]
}

object Freer {
  def pure[A](a: A): Freer[Nothing, A] = Pure(a)
  def lift[F[_], A](fa: F[A]): Freer[F, A] = new Bind[F, A, A](fa) {
    def cont(a: A): Freer[F, A] = pure(a)
  }

  implicit class FreerOps[F[_], A](private val self: Freer[F, A]) extends AnyVal {
    def runTailRec(implicit F: Monad[F]) = self.foldMap(FunK.id)
  }

  final case class Pure[A](a: A) extends Freer[Nothing, A] {
    def flatMap[G[_], B](f: A => Freer[G, B])                    = f(a)
    def mapK[G[_]](f: FunK[Nothing, G]): Freer[G, A]             = this
    override def foldMap[G[_]: Monad](f: FunK[Nothing, G]): G[A] = a.pure[G]
  }

  abstract class Bind[+F[_], Pin, A](val head: F[Pin]) extends Freer[F, A] { self =>
    def cont(a: Pin): Freer[F, A]

    def flatMap[G[x] >: F[x], B](f: A => Freer[G, B]): Bind[G, Pin, B] = new Bind[G, Pin, B](self.head) {
      def cont(a: Pin)                                                            = self.cont(a).flatMap[G, B](f)
      override def flatMap[H[x] >: G[x], C](g: B => Freer[H, C]): Bind[H, Pin, C] = self.flatMap(a => f(a).flatMap(g))
    }

    def mapK[G[_]](f: FunK[F, G]): Freer[G, A] = new Bind[G, Pin, A](f(self.head)) {
      def cont(a: Pin): Freer[G, A] = self.cont(a).mapK(f)
    }
  }

  implicit def monadInstance[F[_]]: Monad[Freer[F, *]] = monadInstanceAny.asInstanceOf[MonadInstance[F]]

  private class MonadInstance[F[_]] extends StackSafeMonad[Freer[F, *]] {
    override def flatMap[A, B](fa: Freer[F, A])(f: A => Freer[F, B]): Freer[F, B] = fa.flatMap(f)
    override def pure[A](x: A): Freer[F, A]                                       = Freer.Pure(x)
  }

  private val monadInstanceAny: MonadInstance[Any] = new MonadInstance

}

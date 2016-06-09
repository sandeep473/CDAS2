package nasa.nccs.caching


import akka.actor.Status.Success

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success
import scala.util.control.NonFatal

/**
  * General interface implemented by all spray cache implementations.
  */
trait Cache[K,V] { cache ⇒

  /**
    * Selects the (potentially non-existing) cache entry with the given key.
    */
  def apply(key: K) = new Keyed(key)

  def put( key: K, value: V )

  def getEntries: Seq[(K,V)]

  class Keyed(key: K) {
    /**
      * Returns either the cached Future for the key or evaluates the given call-by-name argument
      * which produces either a value instance of type `V` or a `Future[V]`.
      */
    def apply(magnet: ⇒ ValueMagnet[V])(implicit ec: ExecutionContext): Future[V] =
      cache.apply(key, () ⇒ try magnet.future catch { case NonFatal(e) ⇒ Future.failed(e) })

    /**
      * Returns either the cached Future for the key or evaluates the given function which
      * should lead to eventual completion of the promise.
      */
    def apply[U](f: Promise[V] ⇒ U)(implicit ec: ExecutionContext): Future[V] =
      cache.apply(key, () ⇒ { val p = Promise[V](); f(p); p.future })
  }

  /**
    * Returns either the cached Future for the given key or evaluates the given value generating
    * function producing a `Future[V]`.
    */
  def apply(key: K, genValue: () ⇒ Future[V])(implicit ec: ExecutionContext): Future[V]

  /**
    * Retrieves the future instance that is currently in the cache for the given key.
    * Returns None if the key has no corresponding cache entry.
    */
  def get(key: K): Option[Future[V]]

  /**
    * Removes the cache item for the given key. Returns the removed item if it was found (and removed).
    */
  def remove(key: K): Option[Future[V]]

  /**
    * Clears the cache by removing all entries.
    */
  def clear()

  /**
    * Returns the set of keys in the cache, in no particular order
    * Should return in roughly constant time.
    * Note that this number might not reflect the exact keys of active, unexpired
    * cache entries, since expired entries are only evicted upon next access
    * (or by being thrown out by a capacity constraint).
    */
  def keys: Set[K]

  def values: Iterable[Future[V]]

  /**
    * Returns a snapshot view of the keys as an iterator, traversing the keys from the least likely
    * to be retained to the most likely.  Note that this is not constant time.
    * @param limit No more than limit keys will be returned
    */
  def ascendingKeys(limit: Option[Int] = None): Iterator[K]

  /**
    * Returns the upper bound for the number of currently cached entries.
    * Note that this number might not reflect the exact number of active, unexpired
    * cache entries, since expired entries are only evicted upon next access
    * (or by being thrown out by a capacity constraint).
    */
  def size: Int
}

class ValueMagnet[V](val future: Future[V])
object ValueMagnet {
  implicit def fromAny[V](block: V): ValueMagnet[V] = fromFuture(Future.successful(block))
  implicit def fromFuture[V](future: Future[V]): ValueMagnet[V] = new ValueMagnet(future)
}
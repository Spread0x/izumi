package izumi.fundamentals.reflection

import izumi.fundamentals.reflection.ReflectionUtil.{Kind, kindOf}
import izumi.fundamentals.reflection.Tags.hktagFormat
import izumi.fundamentals.reflection.macrortti.{LTag, LightTypeTag, LightTypeTagImpl}

import scala.annotation.implicitNotFound
import scala.language.experimental.macros
import scala.reflect.api
import scala.reflect.api.{TypeCreator, Universe}

trait Tags extends UniverseGeneric { self =>

  import ReflectionUtil.WeakTypeTagMigrate
  import u._

  /**
  * Like [[scala.reflect.api.TypeTags.TypeTag]], but supports higher-kinded type tags via `TagK` type class.
  *
  * In context of DI this lets you define modules parameterized by higher-kinded type parameters.
  * This is especially helpful for applying [[https://www.beyondthelines.net/programming/introduction-to-tagless-final/ `tagless final` style]]
  *
  * Example:
  * {{{
  * class MyModule[F[_]: Monad: TagK] {
  *   make[MyService[F]]
  *   make[F[Int]].named("lucky-number").from(Monad[F].pure(7))
  * }
  * }}}
  *
  * Without a `TagK` constraint above, this example would fail with `no TypeTag available for MyService[F]` error
  *
  * Currently some limitations apply as to when a `Tag` will be correctly constructed:
  *   * Type Parameters do not yet resolve inside structural refinements, e.g. T in {{{ Tag[{ def x: T}] }}}
  *   * Type Parameters do not yet resolve inside higher-kinded type lambdas, e.g. T in {{{ TagK[Either[T, ?]] }}}
  *   * TagK* does not resolve for constructors with bounded parameters, e.g. S in {{{ class Abc[S <: String]; TagK[Abc] }}}
  *     (You can still have a bound in partial application: e.g. {{{ class Abc[S <: String, A]; TagK[Abc["hi", ?]] }}}
  *   * Further details at [[https://github.com/7mind/izumi/pull/369]]
  */
  @implicitNotFound("could not find implicit value for Tag[${T}]. Did you forget to put on a Tag, TagK or TagKK context bound on one of the parameters in ${T}? e.g. def x[T: Tag, F[_]: TagK] = ...")
  trait Tag[T] {
    def tag: TypeTag[T]

    def fullLightTypeTag: LightTypeTag

    override def toString: String = s"Tag[${tag.tpe}]@@[$fullLightTypeTag]"
  }

  object Tag extends LowPriorityTagInstances {

    /**
      * Use `Tag.auto.T[TYPE_PARAM]` syntax to summon a `Tag` for a type parameter of any kind:
      *
      * {{{
      *   def module1[F[_]: Tag.auto.T] = new ModuleDef {
      *     ...
      *   }
      *
      *   def module2[F[_, _]: Tag.auto.T] = new ModuleDef {
      *     ...
      *   }
      * }}}
      *
      * {{{
      *   def y[K[_[_, _], _[_], _[_[_], _, _, _]](implicit ev: Tag.auto.T[K]): Tag.auto.T[K] = ev
      * }}}
      *
      * {{{
      *   def x[K[_[_, _], _[_], _[_[_], _, _, _]: Tag.auto.T]: Tag.auto.T[K] = implicitly[Tag.auto.T[K]]
      * }}}
      *
      * */
    def auto: Any = macro TagLambdaMacro.lambdaImpl

    def apply[T: Tag]: Tag[T] = implicitly

    def apply[T](t: TypeTag[T], FLTT: LightTypeTag): Tag[T] =
      new Tag[T] {
        override val tag: TypeTag[T] = t
        override def fullLightTypeTag: LightTypeTag = FLTT
      }

    /**
      * Resulting [Tag] will not have the ability to migrate into a different universe
      * (which is not usually a problem, but still worth naming it 'unsafe')
      */
    def unsafeFromSafeType[T](tpe: SafeType0[u.type]): Tag[T] =
      Tag(ReflectionUtil.typeToTypeTag[T](u: u.type)(tpe.tpe, u.rootMirror), tpe.tag)

    /**
      * Create a Tag of a type formed by applying the type in `tag` to `args`.
      *
      * Example:
      * {{{
      * implicit def tagFromTagTAKA[T[_, _[_], _], K[_]: TagK, A0: Tag, A1: Tag](implicit t: TypeTag[T[Nothing, Nothing, Nothing]): Tag[T[A0, K, A1]] =
      *   Tag.appliedTag(t, List(Tag[A0].tag, TagK[K].tag, Tag[A1].tag))
      * }}}
      **/
    def appliedTag[R](tag: WeakTypeTag[_], args: List[TypeTag[_]]): Tag[R] = {
      val appliedTypeCreator = new TypeCreator {
        override def apply[U <: SingletonUniverse](m: api.Mirror[U]): U#Type = {
          m.universe.appliedType(tag.migrate(m).tpe.typeConstructor, args.map(_.migrate(m).tpe))
        }
      }
      val newTypeTag = TypeTag[R](tag.mirror, appliedTypeCreator)
      Tag(newTypeTag, LightTypeTagImpl.makeFLTT(u)(newTypeTag.tpe))
    }

    /**
      * Create a Tag of a type formed from an `intersection` of types (A with B) with a structural refinement taken from `structType`
      *
      * `structType` is assumed to be a weak type of final result type, e.g.
      * {{{
      * Tag[A with B {def abc: Unit}] == refinedTag(List(typeTag[A], typeTag[B]), weakTypeTag[A with B { def abc: Unit }])
      * }}}
      **/
    def refinedTag[R](intersection: List[TypeTag[_]], structType: WeakTypeTag[_]): Tag[R] = {
      val refinedTypeCreator = new TypeCreator {
        override def apply[U <: SingletonUniverse](m: api.Mirror[U]): U#Type = {
          val parents = intersection.map(_.migrate(m).tpe)
          val struct = structType.migrate(m).tpe
          m.universe.internal.reificationSupport.setInfo(struct.typeSymbol, m.universe.internal.refinedType(parents, struct.decls))
          m.universe.internal.refinedType(parents, struct.decls, struct.typeSymbol)
        }
      }
      val newTypeTag = TypeTag[R](intersection.headOption.fold(u.rootMirror)(_.mirror), refinedTypeCreator)
      Tag(newTypeTag, LightTypeTagImpl.makeFLTT(u)(newTypeTag.tpe))
    }

    /** For construction from [[TagLambdaMacro]] */
    type HKTagRef[T] = HKTag[T]

    // FIXME: report scalac bug - `Nothing` type is lost when two implicits for it are summoned as in
    //    implicit final def tagFromTypeTag[T](implicit t: TypeTag[T], l: LTag[T]): Tag[T] = Tag(t, l.fullLightTypeTag)
    //  [info] /Users/kai/src/izumi-r2/distage/distage-core/src/test/scala/izumi/distage/impl/TagTest.scala:55:17: universe.this.RuntimeDIUniverse.Tag.tagFromTypeTag is not a valid implicit value for izumi.distage.model.reflection.universe.RuntimeDIUniverse.Tag[Nothing] because:
    //  [info] hasMatchingSymbol reported error: type mismatch;
    //  [info]  found   : izumi.fundamentals.reflection.macrortti.LTag.Weak[Nothing]
    //  [info]  required: izumi.fundamentals.reflection.macrortti.LWeakTag[T]
    //  [info]     (which expands to)  izumi.fundamentals.reflection.macrortti.LTag.Weak[T]
    //  [info] Note: Nothing <: T, but class Weak is invariant in type T.
    //  [info] You may wish to define T as +T instead. (SLS 4.5)
    //  [info]       assert(Tag[Nothing].tpe == safe[Nothing])
    implicit final def tagFromTypeTag[T](implicit t: TypeTag[T]): Tag[T] = macro TagMacro.FIXMEgetLTagAlso[self.type, T]
  }

  trait LowPriorityTagInstances {
    implicit final def tagFromTagMaterializer[T](implicit t: TagMaterializer[self.type, T]): Tag[T] = t.value
  }

  /**
    * Internal unsafe API representing a poly-kinded, higher-kinded type tag.
    *
    * To create a Tag* implicit for an arbitrary kind use the following syntax:
    *
    * {{{
    *   type TagK5[K[_, _, _, _, _]] = HKTag[ { type Arg[A, B, C, D, E] = K[A, B, C, D, E] } ]
    * }}}
    *
    * As an argument to HKTag, you should specify the type variables your type parameter will take and apply them to it, in order.
    *
    * {{{
    *   type TagFGC[K[_[_, _], _[_], _[_[_], _, _, _]] = HKTag[ { type Arg[A[_, _], B[_], C[_[_], _, _, _]] = K[A, B, C] } ]
    * }}}
    *
    * A convenience macro `Tag.auto.T` is available to automatically create a type lambda from a type of any kind:
    *
    * {{{
    *   def x[K[_[_, _], _[_], _[_[_], _, _, _]: Tag.auto.T]: Tag.auto.T[K] = implicitly[Tag.auto.T[K]]
    * }}}
    *
    */
  trait HKTag[T] {

    /**
     * Internal `TypeTag` holding the `typeConstructor` of type `T`
     **/
    def tag: TypeTag[_]
    def fullLightTypeTag: LightTypeTag

    override def toString: String =
      s"${hktagFormat(tag.tpe)}@@[$fullLightTypeTag]"
  }

  object HKTag extends LowPriorityHKTagInstances {
    def apply[T](k: TypeTag[T], l: LTag.WeakHK[T]): HKTag[T] = {
      new HKTag[T] {
        override val tag: TypeTag[_] = {
          val ctorCreator = new TypeCreator {
            override def apply[U <: SingletonUniverse](m: api.Mirror[U]): U#Type = {
              val t = k.migrate(m).tpe.decls.head.info
              t.typeConstructor
            }
          }

          TypeTag(k.mirror, ctorCreator)
        }
        override val fullLightTypeTag: LightTypeTag = l.fullLightTypeTag
      }
    }

    implicit def hktagFromTypeTag[T](implicit k: TypeTag[T], l: LTag.WeakHK[T]): HKTag[T] = {
      new HKTag[T] {
        override val tag: TypeTag[_] = {
          val ctorCreator = new TypeCreator {
            override def apply[U <: SingletonUniverse](m: api.Mirror[U]): U#Type = {
              val t = k.migrate(m).tpe.decls.head.info
              t.typeConstructor
            }
          }

          TypeTag(k.mirror, ctorCreator)
        }
        override val fullLightTypeTag: LightTypeTag = l.fullLightTypeTag
      }
    }

  }

  trait LowPriorityHKTagInstances {
    /**
      * Workaround for a scalac bug whereby it loses the correct type of HKTag argument
      * Here, if implicit resolution fails because scalac thinks that `ArgStruct` is a WeakType,
      * we just inspect it and recreate HKTag Arg again.
      *
      * See: TagTest, "scalac bug: can't find HKTag when obscured by type lambda"
      *
      * TODO: report scalac bug
      */
    implicit def hktagFixupArgStruct[T]: HKTag[T] = macro TagMacro.fixupHKTagArgStruct[self.type, T]
  }

  /**
    * `TagK` is a [[scala.reflect.api.TypeTags.TypeTag]] for higher-kinded types.
    *
    * Example:
    * {{{
    * def containerTypesEqual[F[_]: TagK, K[_]: TagK]): Boolean = TagK[F].tag.tpe =:= TagK[K].tag.tpe
    *
    * containerTypesEqual[Set, collection.immutable.Set] == true
    * containerTypesEqual[Array, List] == false
    * }}}
    */
  type TagK[K[_]] = HKTag[{ type Arg[A] = K[A] }]
  type TagKK[K[_, _]] = HKTag[{ type Arg[A, B] = K[A, B] }]
  type TagK3[K[_, _, _]] = HKTag[{ type Arg[A, B, C] = K[A, B, C]}]

  type TagT[K[_[_]]] = HKTag[{ type Arg[A[_]] = K[A]}]
  type TagTK[K[_[_], _]] = HKTag[{ type Arg[A[_], B] = K[A, B] }]
  type TagTKK[K[_[_], _, _]] = HKTag[{ type  Arg[A[_], B, C] = K[A, B, C] }]
  type TagTK3[K[_[_], _, _, _]] = HKTag[{ type Arg[A[_], B, C, D] = K[A, B, C, D] }]

  object TagK {
    /**
    * Construct a type tag for a higher-kinded type `K[_]`
    *
    * Example:
    * {{{
    *     TagK[Option]
    * }}}
    **/
    def apply[K[_]: TagK]: TagK[K] = implicitly
  }

  object TagKK {
    def apply[K[_, _]: TagKK]: TagKK[K] = implicitly
  }

  object TagK3 {
    def apply[K[_, _, _]: TagK3]: TagK3[K] = implicitly
  }

  object TagT {
    def apply[K[_[_]]: TagT]: TagT[K] = implicitly
  }

  object TagTK {
    def apply[K[_[_], _]: TagTK]: TagTK[K] = implicitly
  }

  object TagTKK {
    def apply[K[_[_], _, _]: TagTKK]: TagTKK[K] = implicitly
  }

  object TagTK3 {
    def apply[K[_[_], _, _, _]: TagTK3]: TagTK3[K] = implicitly
  }

// TODO
//  type TagKUBound[U, K[_ <: U]] = HKTag[{ type Arg[A <: U] = K[A] }]
//
//  object TagKUBound {
//    def apply[U, K[_ <: U]](implicit ev: TagKUBound[U, K]): TagKUBound[U, K] = implicitly
//  }

  // Workaround needed specifically to support generic methods in factories, see `GenericAssistedFactory` and related tests
  //
  // We need to construct a SafeType signature for a generic method, but generic parameters have no type tags
  // So we resort to weak type parameters and pointer equality
  trait WeakTag[T] {
    def tag: WeakTypeTag[T]
    def fullLightTypeTag: LightTypeTag

    override final def toString: String = s"WeakTag[${tag.tpe}]"
  }

  object WeakTag extends WeakTagInstances0 {
    def apply[T: WeakTag]: WeakTag[T] = implicitly

    def apply[T](t: WeakTypeTag[T], l: LightTypeTag): WeakTag[T] =
      new WeakTag[T] {
        override val tag: WeakTypeTag[T] = t
        override def fullLightTypeTag: LightTypeTag = l
      }
  }

  trait WeakTagInstances0 extends WeakTagInstances1 {
    implicit def weakTagFromTag[T: Tag]: WeakTag[T] = WeakTag(Tag[T].tag, Tag[T].fullLightTypeTag)
  }

  trait WeakTagInstances1 {
    implicit def weakTagFromWeakTypeTag[T](implicit t: WeakTypeTag[T], l: LTag.Weak[T]): WeakTag[T] = WeakTag(t, l.fullLightTypeTag)
  }

  // workaround for a strange (prefix?) equality issue when splicing calls to `implicitly[RuntimeDIUniverse.u.TypeTag[X[Y]]`
  type ScalaReflectTypeTag[T] = u.TypeTag[T]
  type ScalaReflectWeakTypeTag[T] = u.WeakTypeTag[T]

  // workaround for being unable to refer to Tag object's type from a type projection (?)
  type TagObject = Tag.type
  type HKTagObject = HKTag.type
}

object Tags extends Tags {
  override final val u: scala.reflect.runtime.universe.type = scala.reflect.runtime.universe

  final val defaultTagImplicitError: String =
    "could not find implicit value for Tag[${T}]. Did you forget to put on a Tag, TagK or TagKK context bound on one of the parameters in ${T}? e.g. def x[T: Tag, F[_]: TagK] = ..."

  final def hktagFormatMap: Map[Kind, String] = Map(
    Kind(Nil) -> "Tag"
    , Kind(Kind(Nil) :: Nil) -> "TagK"
    , Kind(Kind(Nil) :: Kind(Nil) :: Nil) -> "TagKK"
    , Kind(Kind(Nil) :: Kind(Nil) :: Kind(Nil) :: Nil) -> "TagK3"
    , Kind(Kind(Kind(Nil) :: Nil) :: Nil) -> "TagT"
    , Kind(Kind(Kind(Nil) :: Nil) :: Kind(Nil) :: Nil) -> "TagTK"
    , Kind(Kind(Kind(Nil) :: Nil) :: Kind(Nil) :: Kind(Nil) :: Nil) -> "TagTKK"
    , Kind(Kind(Kind(Nil) :: Nil) :: Kind(Nil) :: Kind(Nil) :: Kind(Nil) :: Nil) -> "TagTK3"
  )

  final def hktagFormat(tpe: Universe#Type): String = {
    val kind = kindOf(tpe)
    hktagFormatMap.get(kind) match {
      case Some(t) => s"$t[$tpe]"
      case _ => s"HKTag for $tpe of kind $kind"
    }
  }
}

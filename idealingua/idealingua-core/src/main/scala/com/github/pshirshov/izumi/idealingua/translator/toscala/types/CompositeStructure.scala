package com.github.pshirshov.izumi.idealingua.translator.toscala.types

import com.github.pshirshov.izumi.idealingua.model.common.TypeId.InterfaceId
import com.github.pshirshov.izumi.idealingua.model.il.ast.typed.Interfaces
import com.github.pshirshov.izumi.idealingua.translator.toscala.STContext

import scala.meta._


class CompositeStructure(ctx: STContext, val fields: ScalaStruct) {
  val t: ScalaType = ctx.conv.toScala(fields.id)

  import ScalaField._

  val composite: Interfaces = fields.fields.superclasses.interfaces

  val explodedSignature: List[Term.Param] = fields.all.toParams

  val constructorSignature: List[Term.Param] = {

    val embedded = fields.fields.all
      .map(_.definedBy)
      .collect({ case i: InterfaceId => i })
      .filterNot(composite.contains)
    // TODO: contradictions

    val interfaceParams = (composite ++ embedded)
      .distinct
      .map {
        d =>
          (ctx.tools.idToParaName(d), ctx.conv.toScala(d).typeFull)
      }.toParams


    val fieldParams = fields.localOrAmbigious.toParams

    interfaceParams ++
      fieldParams
  }

  def instantiator: Term.Apply = {
    val local = fields.localOrAmbigious
    val localNames = local.map(_.field.field.name).toSet

    val constructorCode = fields.fields.all
      .filterNot(f => localNames.contains(f.field.name))
      .map {
        f =>
          q""" ${Term.Name(f.field.name)} = ${ctx.tools.idToParaName(f.definedBy)}.${Term.Name(f.field.name)}  """
      }

    val constructorCodeNonUnique = local.distinct.map {
      f =>
        q""" ${f.name} = ${f.name}  """
    }

    q"""
         ${t.termFull}(..${constructorCode ++ constructorCodeNonUnique})
         """

  }

  val constructors: List[Defn.Def] = {

    List(
      q"""def apply(..$constructorSignature): ${t.typeFull} = {
         $instantiator
         }"""
    )

  }

  val decls: List[Term.Param] = fields.all.toParams

  val names: List[Term.Name] = fields.all.toNames
}

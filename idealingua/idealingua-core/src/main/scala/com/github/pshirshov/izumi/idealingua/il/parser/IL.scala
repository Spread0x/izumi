package com.github.pshirshov.izumi.idealingua.il.parser

import com.github.pshirshov.izumi.idealingua.model.common.DomainId
import com.github.pshirshov.izumi.idealingua.model.il.ast.raw.{RawTypeDef, _}

object IL {

  sealed trait Val

  case class ILDomainId(id: DomainId) extends Val

  case class ILService(v: Service) extends Val

  case class ILInclude(i: String) extends Val

  case class ILDef(v: RawTypeDef) extends Val

}

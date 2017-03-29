package model

case class Owner(id: String, ssas: Set[SSA]) {
  def hasSSA(ssa: SSA): Boolean = ssas.contains(ssa)
}


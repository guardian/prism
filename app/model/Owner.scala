package model

case class Owner(id: String, ssas: Set[SSA] = Set.empty) {
  def hasSSA(ssa: SSA): Boolean = ssas.contains(ssa)
}

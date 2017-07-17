package model

case class Owner(id: String, ssas: Set[SSA] = Set.empty, accounts: Set[String] = Set()) {
  def hasSSA(ssa: SSA): Boolean = ssas.contains(ssa)
  def email = s"$id@theguardian.com"
}


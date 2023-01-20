package model

case class SSA(
    stack: String,
    stage: Option[String] = None,
    app: Option[String] = None
)

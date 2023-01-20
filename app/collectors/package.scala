package collectors

object `package` {
  implicit class seq2wrap[T](seq: Seq[T]) {
    def wrap: Option[Seq[T]] = {
      if (seq.isEmpty) None else Some(seq)
    }
  }
}

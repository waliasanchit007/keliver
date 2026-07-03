import androidx.compose.runtime.Composable

/** Hand-written side of the round-trip boundary — codegen never touches this. */
class MockBoundBindings : BoundScreenBindings {
  override val title: String = "Mock title"
  override val subtitle: String = "Mock subtitle"
  override fun buyTapped() { println("buyTapped") }
}

@Composable
fun BoundScreenPreview() {
  BoundScreen(MockBoundBindings())
}

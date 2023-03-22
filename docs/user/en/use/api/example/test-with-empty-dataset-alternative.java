@ExtendWith(DbInstanceParameterResolver.class)
@Slf4j
public class EmptyDataSetAlternativeTest implements TestFileSupport, TestConstants {

	@Test
	void shouldWriteTest(Evita evita) {
		// here comes your test logic
		assertNotNull(evita);
	}

}
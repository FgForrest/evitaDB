@ExtendWith(DbInstanceParameterResolver.class)
public class EmptyDataSetAlternativeTest {

	@Test
	void exampleTestCaseWithAssertions(
		EvitaContract evita,
		EvitaSessionContract session
	) {
		// here comes your test logic
		assertNotNull(evita);
		assertNotNull(session);
	}

}
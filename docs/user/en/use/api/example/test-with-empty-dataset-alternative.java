@ExtendWith(DbInstanceParameterResolver.class)
public class EmptyDataSetAlternativeTest implements EvitaTestSupport {

	@Test
	void shouldWriteTest(EvitaContract evita, EvitaSessionContract session) {
		// here comes your test logic
		assertNotNull(evita);
		assertNotNull(session);
	}

}
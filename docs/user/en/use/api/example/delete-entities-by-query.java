evita.updateCatalog(
	"testCatalog", session -> {
		return session.deleteEntities(
			query(
				collection("Brand"),
				filterBy(
					and(
						attributeStartsWith("name", "A"),
						entityLocaleEquals(Locale.ENGLISH)
					)
				),
				require(
					page(1, 20)
				)
			)
		);
	}
);
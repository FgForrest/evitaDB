evita.updateCatalog(
	catalogName, session -> {
		return session.deleteEntities(
			query(
				collection("brand"),
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
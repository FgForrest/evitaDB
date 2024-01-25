evita.UpdateCatalog(
	"evita", session => {
		return session.DeleteEntities(
			Query(
				Collection("Brand"),
				FilterBy(
					And(
						AttributeStartsWith("name", "A"),
						EntityLocaleEquals(new CultureInfo("en"))
					)
				),
				Require(
					Page(1, 20)
				)
			)
		);
	}
);
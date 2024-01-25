string brandNameInEnglish = evita.QueryCatalog(
	"evita",
	session => {
		return session.GetEntity("brand", 1)
			.GetAttribute("name");
	}
);
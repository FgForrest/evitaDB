final String brandNameInEnglish = evita.queryCatalog(
	"evita",
	session -> {
		return session.getEntity("Brand", 1, attributeContent("name"), dataInLocales(Locale.ENGLISH))
			.orElseThrow()
			.getAttribute("name");
	}
);
final String brandNameInEnglish = evita.queryCatalog(
	"evita",
	session -> {
		return session.getEntity("Brand", 1, attributeContent("name"))
			.orElseThrow()
			.getAttribute("name");
	}
);

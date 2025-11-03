evita.updateCatalog(
	"evita",
	session -> {

		/* first create stubs of the entity schemas that the product will reference */
		session.defineEntitySchema("Brand");
		session.defineEntitySchema("Category");

		session.defineEntitySchema("Product")
			
			/* all is strictly verified but associated data
			   and references can be added on the fly */
			.verifySchemaButAllow(
				EvolutionMode.ADDING_ASSOCIATED_DATA,
				EvolutionMode.ADDING_REFERENCES
			)
			/* products are not organized in the tree */
			.withoutHierarchy()
			/* prices are referencing another entity stored in Evita */
			.withPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute(
				"code", String.class,
				whichIs -> whichIs.unique()
			)
			.withAttribute(
				"url", String.class,
				whichIs -> whichIs.unique().localized()
			)
			.withAttribute(
				"oldEntityUrls", String[].class,
				whichIs -> whichIs.filterable().localized()
			)
			.withAttribute(
				"name", String.class,
				whichIs -> whichIs.filterable().sortable()
			)
			.withAttribute(
				"ean", String.class,
				whichIs -> whichIs.filterable()
			)
			.withAttribute(
				"priority", Long.class,
				whichIs -> whichIs.sortable()
			)
			.withAttribute(
				"validity", DateTimeRange.class,
				whichIs -> whichIs.filterable()
			)
			.withAttribute(
				"quantity", BigDecimal.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(2)
			)
			.withAttribute(
				"alias", Boolean.class,
				whichIs -> whichIs.filterable()
			)
			/* here we define set of associated data,
			   that can be stored along with entity */
			.withAssociatedData("referencedFiles", ReferencedFileSet.class)
			.withAssociatedData(
				"labels", Labels.class,
				whichIs -> whichIs.localized()
			)
			/* here we define references that relate to
			   another entities stored in Evita */
			.withReferenceToEntity(
				"categories",
				"Category",
				Cardinality.ZERO_OR_MORE,
				whichIs ->
					/* we can specify special attributes on relation */
					whichIs.indexed().withAttribute(
						"categoryPriority", Long.class,
						thatIs -> thatIs.sortable()
					)
			)
			/* for faceted references we can compute "counts" */
			.withReferenceToEntity(
				"brand",
				"Brand",
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.faceted())
			/* references may be also represented be
			   entities unknown to Evita */
			.withReferenceTo(
				"stock",
				"stock",
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.faceted()
			)
			/* finally apply schema changes */
			.updateVia(session);
	}
);

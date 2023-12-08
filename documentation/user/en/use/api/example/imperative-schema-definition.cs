evita.UpdateCatalog(
	"evita",
	session => {
		session.DefineEntitySchema("Product")
			/* all is strictly verified but associated data
			   and references can be added on the fly */
			.VerifySchemaButAllow(
				EvolutionMode.AddingAssociatedData,
				EvolutionMode.AddingReferences
			)
			/* products are not organized in the tree */
			.WithoutHierarchy()
			/* prices are referencing another entity stored in Evita */
			.WithPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.WithLocale(new CultureInfo("en"), new CultureInfo("cs-CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.WithAttribute<string>(
				"code",
				whichIs => whichIs.Unique()
			)
			.WithAttribute<string>(
				"url",
				whichIs => whichIs.Unique().Localized()
			)
			.WithAttribute<string[]>(
				"oldEntityUrls",
				whichIs => whichIs.Filterable().Localized()
			)
			.WithAttribute<string>(
				"name",
				whichIs => whichIs.Filterable().Sortable()
			)
			.WithAttribute<string>(
				"ean",
				whichIs => whichIs.Filterable()
			)
			.WithAttribute<long>(
				"priority",
				whichIs => whichIs.Sortable()
			)
			.WithAttribute<DateTimeRange>(
				"validity",
				whichIs => whichIs.filterable()
			)
			.WithAttribute<decimal>(
				"quantity",
				whichIs => whichIs.Filterable().IndexDecimalPlaces(2)
			)
			.WithAttribute(
				"alias", Boolean.class,
				whichIs => whichIs.Filterable()
			)
			/* here we define set of associated data,
			   that can be stored along with entity */
			.WithAssociatedData<ReferencedFileSet>("referencedFiles")
			.WithAssociatedData<Labels>(
				"labels",
				whichIs => whichIs.Localized()
			)
			/* here we define references that relate to
			   another entities stored in Evita */
			.WithReferenceToEntity(
				"categories",
				"Category",
				Cardinality.ZeroOrMore,
				whichIs =>
					/* we can specify special attributes on relation */
					whichIs.Indexed().WithAttribute<long>(
						"categoryPriority",
						thatIs => thatIs.Sortable()
					)
			)
			/* for faceted references we can compute "counts" */
			.WithReferenceToEntity(
				"brand",
				"Brand",
				Cardinality.ZeroOrMore,
				whichIs => whichIs.Faceted())
			/* references may be also represented be
			   entities unknown to Evita */
			.WithReferenceTo(
				"stock",
				"stock",
				Cardinality.ZeroOrMore,
				whichIs => whichIs.Faceted()
			)
			/* finally apply schema changes */
			.UpdateVia(session);
	}
);
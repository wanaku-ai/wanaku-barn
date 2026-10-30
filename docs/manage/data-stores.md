# Inspect stored records

The admin UI's **Data Stores** page lists records from the backend data store. Each row shows the record name, the exact `wanaku.type` label, additional labels, creation and update dates, and its persisted revision. Missing metadata on older records appears as **Not recorded**; records without a type appear as **Unspecified**. Dates use your browser's local time zone.

Search by name, ID, label key or value, or audit actor. Combine search with the stored type filter and use pagination to browse the results. **View** shows the ID, all labels, audit actors, and a text preview with formatted JSON where possible. Binary content remains available through **Download**. Semantic records download as JSON; immutable semantic catalogs download as ZIP files; uploaded files retain their names.

Deleting an ordinary upload requires confirmation and sends its recorded revision to avoid deleting a changed record. Managed catalogs, templates, Kamelets and immutable records have deletion disabled; manage those through their corresponding feature pages.

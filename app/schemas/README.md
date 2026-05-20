# Room schemas

This directory holds the exported Room schema JSON for `NeuronDatabase`.

## How it works

`app/build.gradle.kts` sets `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`,
so Room's annotation processor writes a schema file **on every build** to:

```
app/schemas/com.tryptz.neuron.data.local.NeuronDatabase/<version>.json
```

For the current schema (`version = 3`) that is:

```
app/schemas/com.tryptz.neuron.data.local.NeuronDatabase/3.json
```

The directory is empty until the project is built once with an Android SDK present.
That is expected — the JSON cannot be hand-written or fabricated; it is generated.

## What you must do

1. After the first successful build, **commit the generated `3.json`**
   (and `2.json` if any build still emits it) so `MIGRATION_2_3` has a schema to verify against.
2. Whenever you change an `@Entity` (new column, table, index, etc.):
   - bump `version` in `NeuronDatabase`,
   - add a `Migration` to `NeuronDatabase.MIGRATIONS`,
   - build (Room exports the new `<version>.json` and validates the migration
     against the previously committed schema),
   - commit the new schema JSON alongside the migration.

Keeping these JSON files in version control is what lets Room verify that each
migration actually produces the expected schema.

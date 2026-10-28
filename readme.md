# Config4J
A config generator based on [NightConfig by TheElectronWill](https://github.com/TheElectronWill/night-config)
Usage :
 - https://github.com/DenisD3D/Config4J/tree/master/src/test/java/ (Example)
 - https://github.com/DenisD3D/Mc2Discord (Main usage of the library, view generated config: https://github.com/DenisD3D/Mc2Discord/wiki/Default-configuration-file)

## Migrating from 1.x
Config4J 2 uses the NightConfig serde annotations instead of the deprecated `conversion` package:
 - `@Path` → `@SerdeKey`
 - `@PreserveNotNull` → `@SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)`, also required on fields without a default value, NightConfig fails on missing entries otherwise
 - `fr.denisd3d.config4j.Comment` → `@SerdeComment`, its value still goes through the translator
 - `@Conversion` / `Converter` → a `ValueSerializer` and a `ValueDeserializer`, registered by overriding `registerSerializers` and `registerDeserializers`

`@DefaultValue` and `@OnlyIf` are unchanged. Only TOML files are supported.

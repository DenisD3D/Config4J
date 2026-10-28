package config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.serde.DeserializerContext;
import com.electronwill.nightconfig.core.serde.ObjectDeserializerBuilder;
import com.electronwill.nightconfig.core.serde.ObjectSerializerBuilder;
import com.electronwill.nightconfig.core.serde.SerializerContext;
import com.electronwill.nightconfig.core.serde.TypeConstraint;
import com.electronwill.nightconfig.core.serde.ValueDeserializer;
import com.electronwill.nightconfig.core.serde.ValueSerializer;
import com.electronwill.nightconfig.core.serde.annotations.SerdeComment;
import com.electronwill.nightconfig.core.serde.annotations.SerdeKey;
import com.electronwill.nightconfig.core.serde.annotations.SerdeSkipDeserializingIf;
import com.electronwill.nightconfig.core.serde.annotations.SerdeSkipDeserializingIf.SkipDeIf;
import fr.denisd3d.config4j.Config4J;
import fr.denisd3d.config4j.DefaultValue;
import fr.denisd3d.config4j.OnlyIf;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class ExampleConfig extends Config4J {
    @SerdeKey("name")
    @SerdeComment("The name of the example")
    @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
    public String name = "example";

    @SerdeKey("translated")
    @SerdeComment("A value whose default is given by the translator")
    @DefaultValue("translated.value")
    @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
    public String translated;

    @SerdeKey("custom_integer")
    @SerdeComment("A value converted to a custom type")
    @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
    public CustomInteger customInteger = CustomInteger.of(42);

    @SerdeKey("Category")
    @SerdeComment("A category broken down in its own table")
    @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
    public Category category = new Category();

    @SerdeKey("enable_optional")
    @SerdeComment("Generate the Optional category")
    @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
    public boolean enableOptional = false;

    @SerdeKey("Optional")
    @SerdeComment("Only generated if enable_optional is true")
    @OnlyIf("enable_optional")
    @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
    public Category optional = new Category();

    @SerdeKey("Entry")
    @SerdeComment("A list of tables")
    @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
    public List<Entry> entries = new ArrayList<>();

    @SerdeKey("Group")
    @SerdeComment("A category that only contains a list of tables")
    @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
    public Group group = new Group();

    @SerdeKey("Map")
    @SerdeComment("A free-form table")
    @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
    public Config map = Config.inMemory();

    public ExampleConfig(File file) {
        super(file);
    }

    public ExampleConfig(File file, Function<String, String> translator) {
        super(file, translator);
    }

    @Override
    protected void registerSerializers(ObjectSerializerBuilder builder) {
        builder.withSerializerForClass(CustomInteger.class, new CustomIntegerConverter());
    }

    @Override
    protected void registerDeserializers(ObjectDeserializerBuilder builder) {
        CustomIntegerConverter converter = new CustomIntegerConverter();
        builder.withDeserializerProvider((valueClass, resultType) ->
                resultType.getSatisfyingRawType().filter(CustomInteger.class::equals).isPresent() ? converter : null);
    }

    @Override
    public void betweenLoadAndSave() {
        if (entries.isEmpty()) {
            entries.add(new Entry());
        }
        if (group.entries.isEmpty()) {
            group.entries.add(new Entry());
        }
        if (map.isEmpty()) {
            map.set("key", "value");
        }
    }

    public static class Entry {
        @SerdeKey("id")
        @SerdeComment("The id of the entry")
        @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
        public CustomInteger id = CustomInteger.of(0);

        @SerdeKey("tags")
        @SerdeComment("The tags of the entry")
        @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
        public List<String> tags = new ArrayList<>();

        @SuppressWarnings("unused")
        public Entry() { // Allow NightConfig to create new instances
        }
    }

    public static class Group {
        @SerdeKey("Entry")
        @SerdeComment("A list of tables")
        @SerdeSkipDeserializingIf(SkipDeIf.IS_MISSING)
        public List<Entry> entries = new ArrayList<>();
    }

    public static class CustomIntegerConverter implements ValueSerializer<CustomInteger, Object>, ValueDeserializer<Object, CustomInteger> {
        @Override
        public CustomInteger deserialize(Object value, Optional<TypeConstraint> resultType, DeserializerContext ctx) {
            if (value instanceof Number number) return CustomInteger.of(number.intValue());
            if (value instanceof String string) return CustomInteger.of(string);
            return CustomInteger.of(0);
        }

        @Override
        public Object serialize(CustomInteger value, SerializerContext ctx) {
            return value.asInt();
        }
    }
}

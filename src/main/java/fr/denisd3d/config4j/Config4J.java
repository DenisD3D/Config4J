package fr.denisd3d.config4j;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.core.serde.ObjectDeserializer;
import com.electronwill.nightconfig.core.serde.ObjectDeserializerBuilder;
import com.electronwill.nightconfig.core.serde.ObjectSerializer;
import com.electronwill.nightconfig.core.serde.ObjectSerializerBuilder;
import com.electronwill.nightconfig.core.serde.ValueSerializer;
import com.electronwill.nightconfig.core.serde.annotations.SerdeComment;
import com.electronwill.nightconfig.core.serde.annotations.SerdeKey;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlWriter;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;

/**
 * A TOML configuration file mapped to the fields of the subclass, using NightConfig serde annotations
 * ({@link SerdeKey}, {@link SerdeComment}, {@link com.electronwill.nightconfig.core.serde.annotations.SerdeSkipDeserializingIf}, ...).
 * <p>
 * In addition to NightConfig serde, comments and {@link DefaultValue} go through the translator, and {@link OnlyIf}
 * allows to only generate a part of the config when a condition is met.
 */
public abstract class Config4J {

    private static final ValueSerializer<Object, Object> NULL_SERIALIZER = (value, ctx) -> null;

    private final transient File file;
    private final transient CommentedConfig config;
    private final transient Function<String, String> translator;
    private final transient Map<String, String> valuesToRemove = new HashMap<>();
    private transient ObjectSerializer serializer;
    private transient ObjectDeserializer deserializer;

    /**
     * @param file The configuration file
     */
    public Config4J(File file) {
        this(file, null);
    }

    /**
     * @param file       The configuration file
     * @param translator A function to convert comments and default values between languages
     */
    public Config4J(File file, Function<String, String> translator) {
        this.translator = translator != null ? translator : s -> s; // Default translator

        Config.setInsertionOrderPreserved(true);

        this.file = file;
        this.config = TomlFormat.newConfig(); // After setInsertionOrderPreserved, the map type is chosen on creation
    }

    /**
     * Called after the config is loaded and before it is saved. Used to fill array with default values
     */
    public abstract void betweenLoadAndSave();

    /**
     * Override to register serializers for custom types, called once before the first save
     *
     * @param builder The builder of the serializer, already containing the standard serializers
     */
    protected void registerSerializers(ObjectSerializerBuilder builder) {
    }

    /**
     * Override to register deserializers for custom types, called once before the first load
     *
     * @param builder The builder of the deserializer, already containing the standard deserializers
     */
    protected void registerDeserializers(ObjectDeserializerBuilder builder) {
    }

    /**
     * Loads the config, corrects it and saves it
     */
    public void loadAndCorrect() {
        load();
        betweenLoadAndSave();
        save();
    }

    /**
     * Loads the config
     */
    public void load() {
        TomlFormat.instance().createParser().parse(file, config, ParsingMode.REPLACE, FileNotFoundAction.CREATE_EMPTY); // Read the config file
        prepareLoadedConfig(config, "", getClass());
        getDeserializer().deserializeFields(config, this); // Map the config to the object
    }

    /**
     * Saves the config
     */
    public void save() {
        config.clear();
        getSerializer().serializeFields(this, config);

        try {
            finishConvertFieldToConfig(config, this);
        } catch (IllegalAccessException e) {
            throw new ParsingException(e.getMessage());
        }

        removeOnlyIfValues(config);

        TomlWriter writer = TomlFormat.instance().createWriter();
        writer.setHideRedundantLevels(false); // Keep the header of tables only containing arrays of tables
        writer.setWriteStringMultilinePredicate(s -> false); // Keep strings on a single line
        writer.write(config, file, WritingMode.REPLACE);
    }

    private ObjectSerializer getSerializer() {
        if (serializer == null) {
            ObjectSerializerBuilder builder = ObjectSerializer.builder();
            registerSerializers(builder);
            // Null fields are kept as null, they are then replaced by their default value or removed by finishConvertFieldToConfig
            builder.<Object, Object>withSerializerProvider((valueClass, ctx) -> valueClass == null ? NULL_SERIALIZER : null);
            serializer = builder.build();
        }
        return serializer;
    }

    private ObjectDeserializer getDeserializer() {
        if (deserializer == null) {
            ObjectDeserializerBuilder builder = ObjectDeserializer.builder();
            registerDeserializers(builder);
            deserializer = builder.build();
        }
        return deserializer;
    }

    /**
     * Prepares the loaded config before mapping it to the object:
     * <ul>
     * <li>Registers the {@link OnlyIf} values absent from the config, to not generate them if the condition is false</li>
     * <li>Replaces enum values by the exact name of the matching constant, ignoring case (NightConfig serde only
     * accepts exact names, the former ObjectConverter ignored case)</li>
     * </ul>
     *
     * @param config        The loaded config
     * @param previous_path The path of the config, null inside lists where OnlyIf is not supported
     * @param type          The class the config is mapped to
     */
    private void prepareLoadedConfig(Config config, String previous_path, Class<?> type) {
        for (Field field : getSerializedFields(type)) {
            List<String> path = Collections.singletonList(getKey(field));
            String fieldPath = previous_path == null ? null : previous_path + getKey(field);

            if (fieldPath != null && hasOnlyIf(field) && !config.contains(path)) {
                valuesToRemove.put(field.getAnnotation(OnlyIf.class).value(), fieldPath);
            }

            Object value = config.get(path);
            Object prepared = prepareLoadedValue(value, field.getGenericType(), fieldPath == null ? null : fieldPath + ".");
            if (prepared != value) {
                config.set(path, prepared);
            }
        }
    }

    /**
     * @param value The config value
     * @param type  The type the value is mapped to
     * @param path  The path of the value if it is a sub config, null inside lists
     * @return The prepared value, or the value itself if nothing changed
     */
    private Object prepareLoadedValue(Object value, Type type, String path) {
        Class<?> rawType = type instanceof ParameterizedType parameterizedType ? (Class<?>) parameterizedType.getRawType()
                : type instanceof Class<?> cls ? cls : null;
        if (rawType == null || value == null) {
            return value;
        }

        if (rawType.isEnum() && value instanceof String string) {
            for (Object constant : rawType.getEnumConstants()) {
                String name = ((Enum<?>) constant).name();
                if (name.equalsIgnoreCase(string)) {
                    return name;
                }
            }
        } else if (value instanceof Config subconfig && isObjectMappedToConfig(rawType)) {
            prepareLoadedConfig(subconfig, path, rawType);
        } else if (value instanceof List<?> list && Collection.class.isAssignableFrom(rawType)
                && type instanceof ParameterizedType parameterizedType) {
            Type elementType = parameterizedType.getActualTypeArguments()[0];
            return list.stream().map(element -> prepareLoadedValue(element, elementType, null)).toList();
        }
        return value;
    }

    /**
     * Sets the default values, translated comments and removes null values
     *
     * @param config The config to map to
     * @param value  The object that was serialized to the config
     * @throws IllegalAccessException If a field cannot be accessed
     */
    private void finishConvertFieldToConfig(CommentedConfig config, Object value) throws IllegalAccessException {
        for (Field field : getSerializedFields(value.getClass())) {
            List<String> path = Collections.singletonList(getKey(field));

            if (config.get(path) == null) {
                if (hasDefaultValue(field)) {
                    // If the field is null, set the default value
                    String defaultValue = getDefaultValue(field);
                    config.set(path, defaultValue);
                    field.set(value, deserializeValue(defaultValue, field.getType())); // Deserialize the default value to the type of the field
                } else {
                    // If the field is null and has no default value, remove it
                    config.remove(path);
                }
            }

            Object configValue = config.get(path);
            Object fieldValue = field.get(value);
            if (configValue instanceof Collection<?> configs && fieldValue instanceof Collection<?> objects) {
                Iterator<?> iterator_configs = configs.iterator();
                Iterator<?> iterator_objects = objects.iterator();
                while (iterator_configs.hasNext() && iterator_objects.hasNext()) {
                    if (iterator_configs.next() instanceof CommentedConfig subconfig) {
                        finishConvertFieldToConfig(subconfig, iterator_objects.next());
                    }
                }
            } else if (configValue instanceof CommentedConfig subconfig && fieldValue != null && isObjectMappedToConfig(fieldValue.getClass())) {
                finishConvertFieldToConfig(subconfig, fieldValue);
            } else if (configValue instanceof UnmodifiableConfig subconfig && !(subconfig instanceof CommentedConfig)) {
                config.set(path, toCommentedConfig(subconfig)); // The TOML writer writes configs without comments inline
            }

            if (hasComment(field)) { // If the field has a comment, set its translation
                config.setComment(path, getComment(field));
            }
        }
    }

    /**
     * @param config The config to copy
     * @return A copy of the config where the config and its sub configs support comments
     */
    private static CommentedConfig toCommentedConfig(UnmodifiableConfig config) {
        CommentedConfig copy = TomlFormat.newConfig();
        for (UnmodifiableConfig.Entry entry : config.entrySet()) {
            Object value = entry.getValue();
            copy.set(Collections.singletonList(entry.getKey()), value instanceof UnmodifiableConfig subconfig ? toCommentedConfig(subconfig) : value);
        }
        return copy;
    }

    /**
     * @param value The value to deserialize
     * @param type  The type to deserialize to
     * @return The deserialized value
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object deserializeValue(Object value, Class<?> type) {
        List<?> list = getDeserializer().deserializeToCollection(Collections.singletonList(value), ArrayList.class, (Class) type);
        return list.get(0);
    }

    /**
     * Removes values that should only be present if a condition is met
     *
     * @param config The config to remove values from
     */
    private void removeOnlyIfValues(CommentedConfig config) {
        for (Map.Entry<String, String> entry : valuesToRemove.entrySet()) {
            if (!config.<Boolean>get(entry.getKey())) {
                config.remove(entry.getValue());
            }
        }
    }

    /**
     * @param type The class to get the fields of
     * @return The fields (de)serialized by NightConfig, in the same order
     */
    private static List<Field> getSerializedFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> cls = type; cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic())
                    continue; // Skip fields ignored by NightConfig

                if (Modifier.isFinal(modifiers) || !Modifier.isPublic(modifiers)) {
                    field.setAccessible(true); // Enforces field access if needed, same as NightConfig
                }
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * @param type The class to check
     * @return true if objects of this class are serialized to a sub config field by field
     */
    private static boolean isObjectMappedToConfig(Class<?> type) {
        return !UnmodifiableConfig.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type)
                && !Collection.class.isAssignableFrom(type);
    }

    /**
     * @param field The field to get the key for
     * @return The config key of the field
     */
    private static String getKey(Field field) {
        SerdeKey key = field.getAnnotation(SerdeKey.class);
        return key == null ? field.getName() : key.value();
    }

    /**
     * @param field The field to check
     * @return true if the field has a default value
     */
    private boolean hasDefaultValue(Field field) {
        return field.isAnnotationPresent(DefaultValue.class);
    }

    /**
     * @param field The field to get the default value
     * @return The default value of the field
     */
    private String getDefaultValue(Field field) {
        return translator.apply(field.getAnnotation(DefaultValue.class).value());
    }

    /**
     * @param field The field to check
     * @return true if the field has a comment
     */
    private boolean hasComment(Field field) {
        return field.getDeclaredAnnotationsByType(SerdeComment.class).length > 0;
    }

    /**
     * @param field The field to get the comment
     * @return The translated comment of the field, one line per annotation
     */
    private String getComment(Field field) {
        StringJoiner comment = new StringJoiner("\n");
        for (SerdeComment line : field.getDeclaredAnnotationsByType(SerdeComment.class)) {
            comment.add(translator.apply(line.value()));
        }
        return comment.toString();
    }

    /**
     * @param field The field to check
     * @return true if the field has an OnlyIf annotation
     */
    private boolean hasOnlyIf(Field field) {
        return field.isAnnotationPresent(OnlyIf.class);
    }
}

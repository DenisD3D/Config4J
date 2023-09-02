package fr.denisd3d.config4j;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.conversion.*;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.ParsingException;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;

public abstract class Config4J {

    private final transient CommentedFileConfig config;
    private final transient ObjectConverter converter;
    private final transient Function<String, String> translator;
    private final transient Map<String, String> valuesToRemove = new HashMap<>();

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

        converter = new ObjectConverter();
        config = CommentedFileConfig.of(file);
    }

    /**
     * Called after the config is loaded and before it is saved. Used to fill array with default values
     */
    public abstract void betweenLoadAndSave();

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
        config.load(); // Read the config file
        try {
            convertConfigToObject(config, "", this); // Map the config to the object
        } catch (IllegalAccessException e) {
            throw new ParsingException(e.getMessage());
        }
    }

    /**
     * Maps a config to an object
     *
     * @param config        The config to map
     * @param previous_path The path of the parent config
     * @param value         The object to map to
     * @throws IllegalAccessException If the config cannot be mapped
     */
    private void convertConfigToObject(Config config, String previous_path, Object value) throws IllegalAccessException {
        converter.toObject(config, value);
        for (Field field : value.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(Path.class))
                continue;  // Skip if current field not part of the config

            if (hasOnlyIf(field)) {
                if (!config.contains(getPartialPath(field)))
                    valuesToRemove.put(field.getAnnotation(OnlyIf.class).value(), previous_path + getPartialPath(field));
            }

            if (!shouldBreakDown(field))
                continue;

            enforceFieldAccess(field, value);
            if (config.get(getPartialPath(field)) instanceof Config subconfig) {
                convertConfigToObject(subconfig, previous_path + getPartialPath(field) + ".", field.get(value));
            }
        }
    }

    /**
     * Saves the config
     */
    public void save() {
        config.clear();
        converter.toConfig(this, config);

        try {
            finishConvertFieldToConfig(config, this);
        } catch (IllegalAccessException e) {
            throw new ParsingException(e.getMessage());
        }

        removeOnlyIfValues(config);

        config.save();
    }

    /**
     * Maps an object to a config
     *
     * @param config The config to map to
     * @param value  The object to be mapped
     * @throws IllegalAccessException If the object cannot be mapped
     */
    private void finishConvertFieldToConfig(CommentedConfig config, Object value) throws IllegalAccessException {
        for (Field field : value.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(Path.class))
                continue; // Skip if current field not part of the config

            if (hasDefaultValue(field)) {
                if (config.get(getPartialPath(field)) == null) {
                    // If the field does not exist, set the default value
                    String defaultValue = getDefaultValue(field);
                    config.set(getPartialPath(field), defaultValue);
                    Converter<Object, Object> converter = getConverter(field); // If the field has a converter, use it to get correct default value
                    field.set(value, converter != null ? converter.convertToField(defaultValue) : defaultValue); // Set the field value
                }
            } else if (config.get(getPartialPath(field)) instanceof Collection<?>) {
                Collection<?> configs = config.get(getPartialPath(field));
                Collection<?> objects = (Collection<?>) field.get(value);

                Iterator<?> iterator_configs = configs.iterator();
                Iterator<?> iterator_objects = objects.iterator();
                while (iterator_configs.hasNext() && iterator_objects.hasNext()) {
                    if (iterator_configs.next() instanceof CommentedConfig subconfig) {
                        finishConvertFieldToConfig(subconfig, iterator_objects.next());
                    }
                }
            } else if (shouldBreakDown(field)) { // If the field should be break down, process the subfields
                finishConvertFieldToConfig(config.get(getPartialPath(field)), field.get(value));
            }

            if (hasComment(field)) { // If the field has a comment, set it
                config.setComment(getPartialPath(field), getComment(field));
            }
        }
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
     * @param field The field to find partial path for
     * @return The partial path for the field
     */
    private String getPartialPath(Field field) {
        return field.getAnnotation(Path.class).value();
    }

    /**
     * Enforces field access if needed
     *
     * @param field The field to enforce access
     * @param value The instance of the object containing the field
     */
    private static void enforceFieldAccess(Field field, Object value) {
        if (!field.canAccess(value)) {
            field.setAccessible(true); // Enforces field access if needed
        }
    }

    /**
     * @param field The field to check
     * @return true if the field should be split into a subfields
     */
    private boolean shouldBreakDown(Field field) {
        if (field.isAnnotationPresent(ForceBreakdown.class)) return true;
        if (field.isAnnotationPresent(Conversion.class))
            return false; // If the field is annotated with Conversion, it is supported by the config format
        return !config.configFormat().supportsType(field.getType()); // If the field is not supported by the config format, it should be break down
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
        return field.isAnnotationPresent(Comment.class);
    }

    /**
     * @param field The field to get the comment
     * @return The comment of the field
     */
    private String getComment(Field field) {
        return translator.apply(field.getAnnotation(Comment.class).value());
    }

    /**
     * @param field The field to check
     * @return true if the field has an OnlyIf annotation
     */
    private boolean hasOnlyIf(Field field) {
        return field.isAnnotationPresent(OnlyIf.class);
    }

    /**
     * @param field The field to get the converter
     * @return The converter of the field
     */
    private static Converter<Object, Object> getConverter(Field field) {
        Conversion conversion = field.getAnnotation(Conversion.class);
        if (conversion == null)
            return null;

        try {

            Constructor<? extends Converter<?, ?>> constructor = conversion.value().getDeclaredConstructor();
            if (!constructor.canAccess(null)) {
                field.setAccessible(true); // Enforces field access if needed
            }
            @SuppressWarnings("unchecked")
            Converter<Object, Object> objectObjectConverter = (Converter<Object, Object>) constructor.newInstance();
            return objectObjectConverter;
        } catch (ReflectiveOperationException ex) {
            throw new ReflectionException("Cannot create a converter for field " + field, ex);
        }
    }
}

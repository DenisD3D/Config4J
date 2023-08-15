package ml.denisd3d.config4j;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.conversion.Conversion;
import com.electronwill.nightconfig.core.conversion.ForceBreakdown;
import com.electronwill.nightconfig.core.conversion.ObjectConverter;
import com.electronwill.nightconfig.core.conversion.Path;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public abstract class Config4J {

    private final transient CommentedFileConfig config;
    private final transient ObjectConverter converter;
    private final transient Function<String, String> translator;
    private final transient List<String> toRemovePaths = new ArrayList<>();
    private transient boolean save_need_reload = false;

    public Config4J(File file, Function<String, String> translator) {
        this.translator = translator != null ? translator : s -> s;

        Config.setInsertionOrderPreserved(true);

        converter = new ObjectConverter();
        config = CommentedFileConfig.of(file);
    }

    @SuppressWarnings("unchecked")
    public <T extends Config4J> T loadAndCorrect() {
        load();
        betweenLoadAndSave();
        save();
        return (T) this;
    }

    public void load() {
        config.load();
        converter.toObject(config, this);

        try {
            mapConfig("", this);
            toRemovePaths.clear();
            checkToRemovePath("", this);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    public abstract void betweenLoadAndSave();

    private void mapConfig(String path, Object value) throws IllegalAccessException {
        for (Field field : value.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Path.class) && (field.isAnnotationPresent(ForceBreakdown.class) || !config.configFormat().supportsType(field.getType()))) {
                if (!field.isAccessible()) {
                    field.setAccessible(true); // Enforces field access if needed
                }
                Config subconfig = config.get(path + field.getAnnotation(Path.class).value());
                if (subconfig != null) {
                    converter.toObject(subconfig, field.get(value));
                    mapConfig(path + field.getAnnotation(Path.class).value() + ".", field.get(value));
                }
            }
        }
    }

    private void checkToRemovePath(String path, Object value) throws IllegalAccessException {
        for (Field field : value.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Path.class)) {
                if (field.isAnnotationPresent(OnlyIf.class) && !config.contains(path + field.getAnnotation(Path.class).value())) {
                    toRemovePaths.add(path + field.getAnnotation(Path.class).value());
                }
                if ((field.isAnnotationPresent(ForceBreakdown.class) || (!config.configFormat().supportsType(field.getType())) && !field.isAnnotationPresent(Conversion.class))) {
                    if (!field.isAccessible()) {
                        field.setAccessible(true);// Enforces field access if needed
                    }
                    checkToRemovePath(path + field.getAnnotation(Path.class).value() + ".", field.get(value));
                }
            }
        }
    }

    public void save() {
        save_need_reload = false;
        config.clear();
        converter.toConfig(this, config);

        try {
            mapField(config, "", this);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        if (save_need_reload) {
            converter.toObject(config, this); // Allow DefaultValue to be loaded in instance
        }

        config.save();
    }

    private void mapField(CommentedConfig config, String path, Object value) throws IllegalAccessException {
        for (Field field : value.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Path.class)) {
                if (field.isAnnotationPresent(ForceBreakdown.class) || !config.configFormat().supportsType(field.getType())) {
                    if (field.isAnnotationPresent(OnlyIf.class) && !config.<Boolean>get(field.getAnnotation(OnlyIf.class).value())) {
                        if (toRemovePaths.contains(path + field.getAnnotation(Path.class).value())) {
                            config.remove(path + field.getAnnotation(Path.class).value()); // If Section wasn't present at load, don't add it
                        }
                        continue; // OnlyIf target is false
                    }
                    if (!field.isAccessible()) {
                        field.setAccessible(true);// Enforces field access if needed
                    }
                    mapField(config, path + field.getAnnotation(Path.class).value() + ".", field.get(value));
                } else if (field.isAnnotationPresent(DefaultValue.class) && config.get(path + field.getAnnotation(Path.class).value()) == null) {
                    config.set(path + field.getAnnotation(Path.class).value(), translator.apply(field.getAnnotation(DefaultValue.class).value()));
                    save_need_reload = true;
                }

                if (field.isAnnotationPresent(Comment.class)) {
                    config.setComment(path + field.getAnnotation(Path.class).value(), translator.apply(field.getAnnotation(Comment.class).value()));
                }

                if (config.get(path + field.getAnnotation(Path.class).value()) instanceof List<?>) {
                    List<?> configs = config.get(path + field.getAnnotation(Path.class).value());
                    for (int i = 0; i < configs.size(); i++) {
                        Object o = configs.get(i);
                        if (o instanceof CommentedConfig) {
                            mapField((CommentedConfig) o, "", ((ArrayList<?>) field.get(value)).get(i));
                        }
                    }
                }
            }
        }
    }
}

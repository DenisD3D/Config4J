import config.ExampleConfig;

import java.io.File;

public class Main {
    public static final File CONFIG_FILE = new File("config", "example-config.toml"); // config file location

    public static ExampleConfig config = new ExampleConfig(CONFIG_FILE);

    public static void main(String[] args) {
        if (new File("config").mkdir()) {
            System.out.println("Created config folder");
        }
        config.loadAndCorrect();
        System.out.println("Loaded config");
    }
}

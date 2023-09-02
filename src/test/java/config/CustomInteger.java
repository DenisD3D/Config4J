package config;

public class CustomInteger {
    public int value;

    public CustomInteger(int value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "config.CustomInteger{" +
                "value=" + value +
                '}';
    }

    public static CustomInteger of(String value) {
        return new CustomInteger(Integer.parseInt(value));
    }

    public static CustomInteger of(int value) {
        return new CustomInteger(value);
    }

    public int asInt() {
        return this.value;
    }
}

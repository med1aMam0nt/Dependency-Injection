import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class Main {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface AutoInjectable {}

    interface SomeInterface {
        void doSomething();
    }

    interface SomeOtherInterface {
        void doSomeOther();
    }

    static class SomeImpl implements SomeInterface {
        public void doSomething() { System.out.print("A"); }
    }

    static class OtherImpl implements SomeInterface {
        public void doSomething() { System.out.print("B"); }
    }

    static class SODoer implements SomeOtherInterface {
        public void doSomeOther() { System.out.print("C"); }
    }

    static class SomeBean {
        @AutoInjectable
        private SomeInterface field1;

        @AutoInjectable
        private SomeOtherInterface field2;

        public void foo() {
            // если не проинжектить — будет NullPointerException
            field1.doSomething();
            field2.doSomeOther();
            System.out.println();
        }
    }

    static class Injector {
        private final Properties props = new Properties();

        public Injector(String propertiesPath) {
            try (InputStream in = new FileInputStream(propertiesPath)) {
                props.load(in);
            } catch (IOException e) {
                throw new RuntimeException("Cannot load properties file: " + propertiesPath, e);
            }
        }

        public <T> T inject(T obj) {
            Class<?> cls = obj.getClass();

            for (Field field : cls.getDeclaredFields()) {
                if (!field.isAnnotationPresent(AutoInjectable.class)) continue;

                if (Modifier.isStatic(field.getModifiers())) continue;

                if (Modifier.isFinal(field.getModifiers())) {
                    throw new RuntimeException("Cannot inject into final field: " + field.getName());
                }

                Class<?> fieldType = field.getType();
                String key = fieldType.getName(); // например "Main$SomeInterface"
                String implClassName = props.getProperty(key);

                if (implClassName == null || implClassName.isBlank()) {
                    throw new RuntimeException("No implementation mapping for: " + key
                            + " (field: " + field.getName() + ")");
                }

                try {
                    Class<?> implClass = Class.forName(implClassName);

                    if (!fieldType.isAssignableFrom(implClass)) {
                        throw new RuntimeException("Class " + implClassName + " is not assignable to " + key);
                    }

                    Object instance = implClass.getDeclaredConstructor().newInstance();

                    field.setAccessible(true);
                    field.set(obj, instance);

                    System.out.printf(
                            "[Injector] injected %s.%s : %s -> %s%n",
                            cls.getSimpleName(),
                            field.getName(),
                            fieldType.getName(),
                            instance.getClass().getName()
                    );

                } catch (Exception e) {
                    throw new RuntimeException("Failed to inject field: " + field.getName()
                            + " with impl: " + implClassName, e);
                }
            }

            return obj;
        }
    }

    public static void main(String[] args) throws Exception {
        String propsPath = "injector.properties";
        ensurePropertiesFileExists(propsPath);

        System.out.println("=== 1) Without injection (should fail) ===");
        try {
            new SomeBean().foo();
        } catch (NullPointerException e) {
            System.out.println("[Expected] NullPointerException because fields are not injected yet.");
        }

        System.out.println();
        System.out.println("=== 2) With injection ===");
        printActiveConfig(propsPath);

        SomeBean sb = new Injector(propsPath).inject(new SomeBean());
        printInjectedFields(sb);

        System.out.print("[Result] foo() output: ");
        sb.foo(); // в зависимости от properties будет AC или BC
    }

    private static void ensurePropertiesFileExists(String path) throws IOException {
        File f = new File(path);
        if (f.exists()) return;

        String content = ""
                + "# mapping: interface = implementation\n"
                + "Main$SomeInterface=Main$SomeImpl\n"
                + "Main$SomeOtherInterface=Main$SODoer\n";

        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        }

        System.out.println("Created default " + path);
        System.out.println("Edit it to switch implementations (A->B).");
    }

    private static void printActiveConfig(String propsPath) throws IOException {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(propsPath)) {
            p.load(in);
        }

        System.out.println("[Config] Active mappings:");
        System.out.println("  " + SomeInterface.class.getName() + " = " + p.getProperty(SomeInterface.class.getName()));
        System.out.println("  " + SomeOtherInterface.class.getName() + " = " + p.getProperty(SomeOtherInterface.class.getName()));
    }

    private static void printInjectedFields(Object obj) throws IllegalAccessException {
        System.out.println("[Debug] Injected fields of " + obj.getClass().getSimpleName() + ":");

        for (Field f : obj.getClass().getDeclaredFields()) {
            if (!f.isAnnotationPresent(AutoInjectable.class)) continue;

            f.setAccessible(true);
            Object val = f.get(obj);

            System.out.printf(
                    "  %s (%s) = %s%n",
                    f.getName(),
                    f.getType().getName(),
                    (val == null ? "null" : val.getClass().getName())
            );
        }
    }
}
package com.rakesh.chat;

import com.rakesh.chat.server.ServerConfig;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 10: the deliverables, and whether the documentation still tells the truth.
 *
 * <p>Every other test file in this project tests <i>behaviour</i>. This one tests the things
 * a stranger actually meets first — the README, the runnable jars, the run scripts and the
 * example settings file — because those are the parts nothing else notices when they rot.
 * The server does not care that {@code server.properties.example} forgot a setting; the
 * person who clones the repository on a Sunday afternoon very much does.
 *
 * <p>Two of these earn their place on their own:
 *
 * <ul>
 *   <li>{@link Documentation#everyTextFileWeShipIsUtf8} — Phase 9 discovered that
 *       {@code README.md} had been written as UTF-16 by PowerShell, which makes git treat
 *       the most-read file in the repository as a <b>binary blob</b>: no rendering on
 *       GitHub, no diffs, no line history. It opened fine in every editor, so nothing but a
 *       byte-level check was ever going to catch it. This is that check.</li>
 *   <li>{@link RunnableJar#theClientLauncherDoesNotExtendApplication} — the fat client jar
 *       only starts because its {@code Main-Class} is not a JavaFX {@code Application}.
 *       "Simplify" that class into {@code ChatWindow} and {@code java -jar} dies with
 *       "JavaFX runtime components are missing" while {@code mvn javafx:run} keeps working,
 *       which is a miserable afternoon.</li>
 * </ul>
 *
 * <p>This class lives in the root {@code com.rakesh.chat} package on purpose: it is about
 * the project as a whole, not about the server or the client.
 *
 * <p>These tests read files from the folder the build was started in, which is what Maven
 * gives them. {@link #weAreLookingAtTheProjectFolder()} checks that first, so a wrong
 * working directory fails with one clear message instead of nine confusing ones.
 */
class Phase10Test {

    /** The project folder. Maven runs tests from here. */
    private static final Path ROOT = Path.of("");

    /** Everything Phase 10 promises a stranger will find in the repository. */
    private static final List<String> DELIVERABLES = List.of(
            "README.md",
            "pom.xml",
            "server.properties.example",
            "run-server.sh",
            "run-client.sh",
            "run-server.bat",
            "run-client.bat");

    private static String read(String name) throws IOException {
        return Files.readString(ROOT.resolve(name), StandardCharsets.UTF_8);
    }

    @BeforeAll
    static void weAreLookingAtTheProjectFolder() {
        assertTrue(Files.exists(ROOT.resolve("pom.xml")),
                "These tests read files from the working directory, and there is no pom.xml "
                        + "here. Run them with `mvn test` from the project folder.");
    }

    // =================================================================== the runnable jar

    @Nested
    @DisplayName("Runnable jar")
    class RunnableJar {

        /**
         * A jar's manifest names one class to start at. If that class is renamed or moved,
         * nothing fails to compile — the jar just refuses to start, and only at runtime.
         */
        @Test
        @DisplayName("both Main-Class entries exist and are startable")
        void bothMainClassesExist() throws Exception {
            for (String className : List.of("com.rakesh.chat.server.Main",
                    "com.rakesh.chat.client.Main")) {
                Class<?> launcher = Class.forName(className);
                Method main = launcher.getMethod("main", String[].class);

                assertTrue(Modifier.isPublic(main.getModifiers()), className + ".main must be public");
                assertTrue(Modifier.isStatic(main.getModifiers()), className + ".main must be static");
                assertEquals(void.class, main.getReturnType(), className + ".main must return void");
            }
        }

        /**
         * The single most fragile line in the whole packaging story, so it gets a test.
         * See the javadoc on {@code client/Main.java}.
         */
        @Test
        @DisplayName("the client launcher does NOT extend Application")
        void theClientLauncherDoesNotExtendApplication() throws Exception {
            Class<?> launcher = Class.forName("com.rakesh.chat.client.Main");
            Class<?> application = Class.forName("javafx.application.Application");

            assertFalse(application.isAssignableFrom(launcher),
                    "client.Main must not extend Application. If it does, the JavaFX launcher "
                            + "insists on the module path and `java -jar chat-client.jar` fails "
                            + "with 'JavaFX runtime components are missing'.");
        }

        @Test
        @DisplayName("the pom builds both fat jars, and keeps JavaFX out of the server one")
        void thePomBuildsBothJars() throws IOException {
            String pom = read("pom.xml");

            assertTrue(pom.contains("maven-shade-plugin"), "no shade plugin — no runnable jar");
            assertTrue(pom.contains("chat-server.jar"), "pom does not build target/chat-server.jar");
            assertTrue(pom.contains("chat-client.jar"), "pom does not build target/chat-client.jar");
            assertTrue(pom.contains("com.rakesh.chat.server.Main"), "server jar has no Main-Class");
            assertTrue(pom.contains("com.rakesh.chat.client.Main"), "client jar has no Main-Class");

            // Without this exclusion the server jar carries ~9 MB of JavaFX it never draws
            // a single pixel with.
            assertTrue(pom.contains("<exclude>org.openjfx:*</exclude>"),
                    "the server jar should not bundle JavaFX");
        }
    }

    // =================================================================== the run scripts

    @Nested
    @DisplayName("Run scripts")
    class RunScripts {

        @Test
        @DisplayName("there is a script for Windows and one for everyone else")
        void bothPlatformsAreCovered() {
            for (String script : List.of("run-server.sh", "run-client.sh",
                    "run-server.bat", "run-client.bat")) {
                assertTrue(Files.exists(ROOT.resolve(script)), "missing " + script);
            }
        }

        /**
         * The drift this catches is real: rename the jar in the pom, forget the scripts,
         * and the only symptom is a stranger's first command printing "no such file".
         */
        @Test
        @DisplayName("each script runs the jar the pom actually builds")
        void theScriptsPointAtTheRightJars() throws IOException {
            assertTrue(read("run-server.sh").contains("target/chat-server.jar"));
            assertTrue(read("run-client.sh").contains("target/chat-client.jar"));
            assertTrue(read("run-server.bat").contains("target\\chat-server.jar"));
            assertTrue(read("run-client.bat").contains("target\\chat-client.jar"));
        }
    }

    // =================================================================== the docs

    @Nested
    @DisplayName("Documentation")
    class Documentation {

        /**
         * Phase 9's bug B10, as an assertion. A UTF-16 file is still perfectly readable in
         * an editor, so this has to look at the bytes: no byte-order mark, no stray NUL
         * bytes (the giveaway of UTF-16 in a mostly-ASCII file), and a strict UTF-8 decode
         * that refuses rather than substituting question marks.
         */
        @Test
        @DisplayName("every text file we ship is UTF-8")
        void everyTextFileWeShipIsUtf8() throws IOException {
            for (String name : DELIVERABLES) {
                byte[] bytes = Files.readAllBytes(ROOT.resolve(name));

                assertFalse(bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE,
                        name + " starts with a UTF-16LE byte-order mark");
                assertFalse(bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF,
                        name + " starts with a UTF-16BE byte-order mark");

                for (byte b : bytes) {
                    assertNotEquals(0, b, name + " contains a NUL byte — it is probably UTF-16. "
                            + "git will treat it as binary: no diffs, no history, no rendering "
                            + "on GitHub. On Windows, `>` and Out-File write UTF-16 by default.");
                }

                // REPORT rather than the default REPLACE, so bad bytes throw instead of
                // quietly turning into the replacement character.
                CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                try {
                    strict.decode(ByteBuffer.wrap(bytes));
                } catch (CharacterCodingException e) {
                    fail(name + " is not valid UTF-8: " + e);
                }
            }
        }

        /**
         * Reflection over the settings class, so the example file cannot fall behind a
         * setting somebody added. A setting nobody can discover may as well not exist.
         */
        @Test
        @DisplayName("every setting appears in server.properties.example")
        void everySettingIsDocumented() throws IOException {
            String example = read("server.properties.example");

            for (String setting : settingNames()) {
                // Either "port=5000" or, for the secret, "#passphrase=change me".
                assertTrue(example.contains("\n" + setting + "=")
                                || example.contains("\n#" + setting + "="),
                        "server.properties.example never mentions '" + setting + "'. Add it, "
                                + "with a comment saying what it does — ServerConfig.load reads it.");
            }
        }

        /** The other direction: a key in the file that the server would silently ignore. */
        @Test
        @DisplayName("the example file invents no settings that do not exist")
        void theExampleFileInventsNothing() throws IOException {
            List<String> known = settingNames();

            for (String line : read("server.properties.example").lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.contains("=")) {
                    continue;
                }
                // Commented-out keys count: they are still an instruction to the reader.
                boolean commented = trimmed.startsWith("#");
                String key = commented ? trimmed.substring(1) : trimmed;
                key = key.substring(0, key.indexOf('=')).trim();

                // Ordinary prose in a comment often contains an "=" too. A real key never
                // has a space in it, so that is how we tell the two apart.
                if (commented && (key.isEmpty() || key.contains(" "))) {
                    continue;
                }

                assertTrue(known.contains(key),
                        "server.properties.example offers '" + key + "', which ServerConfig does "
                                + "not have. Someone setting it would see no effect and no warning.");
            }
        }

        /**
         * The example file says {@code port=5000}. This proves 5000 is really the default,
         * rather than a number that was true once.
         */
        @Test
        @DisplayName("the example file shows the real defaults")
        void theExampleFileShowsTheRealDefaults() throws Exception {
            ServerConfig fromFile = ServerConfig.load(ROOT.resolve("server.properties.example"));
            ServerConfig defaults = ServerConfig.defaults();

            for (Field field : settingFields()) {
                assertEquals(field.get(defaults), field.get(fromFile),
                        "server.properties.example gives '" + field.getName() + "' a value that "
                                + "is not the default. Either the file is stale or the default "
                                + "moved — a documented default that lies is worse than none.");
            }
        }

        /**
         * The example file is <b>tracked by git</b>, unlike server.properties. A real
         * passphrase in it is public the moment the repository is.
         */
        @Test
        @DisplayName("the example file carries no live secret")
        void theExampleFileCarriesNoSecret() throws IOException {
            assertNull(ServerConfig.load(ROOT.resolve("server.properties.example")).passphrase,
                    "server.properties.example has an active passphrase= line. Comment it out. "
                            + "This file is committed; anything in it is published.");
        }

        /**
         * The README is the deliverable Phase 10 is mostly about, and the section headings
         * are what the build guide actually asks for.
         */
        @Test
        @DisplayName("the README has the sections Phase 10 asks for")
        void theReadmeHasItsSections() throws IOException {
            String readme = read("README.md");

            for (String heading : List.of("Design decisions", "Known limitations",
                    "Architecture", "Build and run", "Screenshots")) {
                assertTrue(readme.toLowerCase().contains(heading.toLowerCase()),
                        "README.md has no '" + heading + "' section");
            }
        }

        @Test
        @DisplayName("the README tells people to use the run scripts that exist")
        void theReadmeMentionsTheScripts() throws IOException {
            String readme = read("README.md");

            assertTrue(readme.contains("run-server"), "README never mentions run-server");
            assertTrue(readme.contains("run-client"), "README never mentions run-client");
        }

        /**
         * The build guide asks for screenshots. They cannot be generated, so this only
         * checks that the folder and its capture instructions are there — the honest limit
         * of what a test can say about a picture.
         */
        @Test
        @DisplayName("the screenshots folder exists, with instructions")
        void theScreenshotsFolderExists() {
            Path folder = ROOT.resolve("docs").resolve("screenshots");

            assertTrue(Files.isDirectory(folder), "docs/screenshots is missing");
            assertTrue(Files.exists(folder.resolve("README.md")),
                    "docs/screenshots/README.md should say what to capture and how");
        }
    }

    // =================================================================== helpers

    /** The public, non-static fields of {@link ServerConfig} — i.e. the settings. */
    private static List<Field> settingFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : ServerConfig.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static List<String> settingNames() {
        List<String> names = new ArrayList<>();
        for (Field field : settingFields()) {
            names.add(field.getName());
        }
        // Sanity check on the reflection itself: if this ever comes back empty, every test
        // that uses it would pass by doing nothing at all.
        assertFalse(names.isEmpty(), "ServerConfig has no public settings — check the reflection");
        assertTrue(names.contains("port"), "expected 'port' among the settings");
        return names;
    }
}

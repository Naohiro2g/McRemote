package club.code2create.mcremote;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ConstructionPermissionSnapshotArchitectureTest {
    private static final List<Class<?>> CONSTRUCTION_HANDLERS = List.of(
            PlayerCommands.class,
            BlockQueryCommands.class,
            BlockEditCommands.class,
            SignCommands.class,
            WorldB5Commands.class,
            DirectionCommands.class,
            LightningCommands.class
    );

    @Test
    void handlersDependOnlyOnSessionSnapshotRatherThanPermissionProvider() throws IOException {
        for (Class<?> handler : CONSTRUCTION_HANDLERS) {
            String resource = "/" + handler.getName().replace('.', '/') + ".class";
            byte[] bytes;
            try (var input = handler.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("missing handler bytecode " + resource);
                }
                bytes = input.readAllBytes();
            }
            String constants = new String(bytes, StandardCharsets.ISO_8859_1);
            assertFalse(constants.contains("getPermissionManager"), handler.getSimpleName());
            assertFalse(constants.contains("IPermissionManager"), handler.getSimpleName());
            assertFalse(constants.contains("LuckPermsPermissionManager"), handler.getSimpleName());
        }
    }

    @Test
    void dedicatedLightningPermissionIsAbsentFromProductFixtureAndReadme() throws IOException {
        List<Path> roots = List.of(
                Path.of("src/main"),
                Path.of("src/test/resources/fixtures/direction-lightning-v23.1.json"),
                Path.of("README.md")
        );
        for (Path root : roots) {
            try (Stream<Path> paths = Files.isDirectory(root) ? Files.walk(root) : Stream.of(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    assertFalse(content.contains("mcr.lightning"), path.toString());
                }
            }
        }
    }
}

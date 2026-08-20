package club.code2create.mcremote;

import com.google.gson.JsonElement;

/** A command that receives the original JSON params without String coercion. */
@FunctionalInterface
public interface StructuredRemoteCommand {
    void execute(JsonElement params);
}

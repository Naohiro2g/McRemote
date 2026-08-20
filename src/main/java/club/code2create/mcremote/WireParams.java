package club.code2create.mcremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Strict JSON parameter validation shared by b5 handlers. */
final class WireParams {
    private WireParams() {
    }

    static JsonArray positional(JsonElement params, int... allowedSizes) {
        if (params == null || !params.isJsonArray()) {
            throw new IllegalArgumentException("params must be an array");
        }
        JsonArray array = params.getAsJsonArray();
        for (int size : allowedSizes) {
            if (array.size() == size) {
                return array;
            }
        }
        throw new IllegalArgumentException("unexpected parameter count");
    }

    static String string(JsonArray params, int index) {
        JsonPrimitive value = primitive(params, index);
        if (!value.isString()) {
            throw new IllegalArgumentException("parameter must be a string");
        }
        return value.getAsString();
    }

    static boolean bool(JsonArray params, int index) {
        JsonPrimitive value = primitive(params, index);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("parameter must be a boolean");
        }
        return value.getAsBoolean();
    }

    static double finiteDouble(JsonArray params, int index) {
        JsonPrimitive value = primitive(params, index);
        if (!value.isNumber()) {
            throw new IllegalArgumentException("parameter must be a number");
        }
        double parsed = value.getAsDouble();
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("parameter must be finite");
        }
        return parsed;
    }

    /** Accepts mathematically integral JSON numbers (including 1.0), never truncates fractions. */
    static int integer(JsonArray params, int index) {
        try {
            return Math.toIntExact(longInteger(params, index));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("parameter is outside integer range", e);
        }
    }

    static long longInteger(JsonArray params, int index) {
        JsonPrimitive value = primitive(params, index);
        if (!value.isNumber()) {
            throw new IllegalArgumentException("parameter must be a number");
        }
        try {
            BigInteger integer = new BigDecimal(value.getAsString()).toBigIntegerExact();
            return integer.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("parameter must be an integer", e);
        }
    }

    private static JsonPrimitive primitive(JsonArray params, int index) {
        if (index < 0 || index >= params.size()) {
            throw new IllegalArgumentException("missing parameter");
        }
        JsonElement value = params.get(index);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("parameter must be primitive");
        }
        return value.getAsJsonPrimitive();
    }
}
